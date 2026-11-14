package app.atall.core

import app.atall.agents.text.AgentMessage
import app.atall.agents.text.FailReason
import app.atall.agents.text.LogEntry
import app.atall.agents.text.Direction
import app.atall.agents.text.RequestClosed
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import app.atall.api.Coordinator
import app.atall.api.InboxDecision
import app.atall.api.Inbox
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.NeedsUserKind
import app.atall.api.PeerDirectory
import app.atall.api.ShowEverything
import app.atall.api.ThreadLine
import app.atall.api.ThreadSink
import app.atall.chatgpt.ResponsesClient

/**
 * Turns what the user types into sends, and what agents send into thread lines.
 *
 * - No mention, or `@Juno`: the [coordinator] handles it (the app's fallback is a plain ChatGPT chat).
 * - Only peer mentions: each peer gets the text directly; replies show as "replied directly".
 * - Peer messages: direct replies show; others go to the coordinator first, then the [inbox], then the thread.
 *
 * Pure Kotlin (no Android); [peers] hides the channel adapters.
 */
class Router(
    private val thread: ThreadStore,
    private val registry: AgentRegistry,
    private val peers: PeerDirectory,
    private val coordinator: () -> Coordinator,
    private val responses: () -> ResponsesClient?,
    private val model: () -> String = { DEFAULT_MODEL },
    private val inbox: () -> Inbox = { ShowEverything },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called for each user message. Suspends until direct sends are handed off and the main agent has answered. */
    suspend fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val msg = ThreadMessage(ThreadMessage.newId(), now(), ThreadMessage.USER, text, status = SendStatus.SENDING)
        thread.upsert(msg)
        dispatch(msg)
    }

    /** Retry a NOT_SENT user message. */
    suspend fun retry(messageId: String) {
        val m = thread.get(messageId)?.takeIf { it.author == ThreadMessage.USER && it.status == SendStatus.NOT_SENT } ?: return
        thread.upsert(m.copy(status = SendStatus.SENDING, statusNote = null))
        dispatch(m, attempt = now().toString(36))
    }

    private suspend fun dispatch(msg: ThreadMessage, attempt: String = "0") {
        when (val route = Mentions.route(msg.text, registry.all())) {
            is Route.Direct -> direct(msg, route, attempt)
            is Route.ToMain -> {
                thread.upsert(msg.copy(status = SendStatus.SENT))
                runMain(MainTurn(msg.id, route.text, msg.at, route.mentionedPeers))
            }
        }
    }

    private suspend fun direct(msg: ThreadMessage, route: Route.Direct, attempt: String) {
        var failure: String? = null
        for (peerId in route.peerIds) {
            val p = registry.get(peerId) ?: continue
            val problem = when {
                p.paused -> "${p.name} is paused."
                !p.channel.implemented -> "${p.name} can't be reached from Atall yet."
                else -> null
            }
            val agent = if (problem == null) peers.agent(p.id) else null
            if (agent == null) { failure = failure ?: (problem ?: "${p.name} can't be reached right now."); continue }
            val cid = directCid(msg.id, p.id, attempt)
            when (val r = agent.send(route.text, cid, SendOptions(allowDraft = false))) {
                is SendResult.Sent, is SendResult.Duplicate, is SendResult.Drafted -> {}
                is SendResult.NeedsUser -> thread.upsert(
                    ThreadMessage(
                        id = "tap:$cid", at = now(), author = Main.AUTHOR, text = "Tap to send to ${p.name}",
                        kind = MessageKind.NEEDS_TAP, peerId = p.id, draftText = route.text, correlationId = cid,
                    ),
                )
                is SendResult.RateLimited -> failure = failure ?: "Wait a moment, then retry."
                is SendResult.Failed -> failure = failure ?: failCopy(r.why, p.name)
            }
        }
        thread.upsert(msg.copy(status = if (failure == null) SendStatus.SENT else SendStatus.NOT_SENT, statusNote = failure))
    }

    /** The user tapped "Tap to send to X": open the prefilled draft (or send silently if a reply action came back). */
    suspend fun tapToSend(messageId: String): Boolean {
        val m = thread.get(messageId)?.takeIf { it.kind == MessageKind.NEEDS_TAP } ?: return false
        val p = m.peerId?.let(registry::get) ?: return false
        val agent = peers.agent(p.id) ?: return false
        val r = agent.send(m.draftText ?: return false, m.correlationId ?: return false, SendOptions(allowDraft = true))
        val (status, note) = when (r) {
            is SendResult.Drafted -> SendStatus.DRAFTED to null
            is SendResult.Sent, is SendResult.Duplicate -> SendStatus.SENT to null
            is SendResult.NeedsUser -> null to null
            is SendResult.RateLimited -> null to "Wait a moment, then tap again."
            is SendResult.Failed -> SendStatus.NOT_SENT to failCopy(r.why, p.name)
        }
        thread.upsert(m.copy(status = status ?: m.status, statusNote = note))
        return status == SendStatus.DRAFTED || status == SendStatus.SENT
    }

    /** A live message from a peer (any channel). */
    suspend fun onPeerMessage(peerId: String, m: AgentMessage) {
        val p = registry.get(peerId) ?: return
        if (m.historical || thread.hasSource(m.id)) return
        val isDirect = m.correlation?.correlationId?.startsWith(DIRECT) == true
        if (!isDirect) {
            if (runCatching { coordinator().onPeerMessage(p.toPeer(), m) }.getOrDefault(false)) return
            if (inbox().onUnsolicited(p.toPeer(), m) == InboxDecision.HOLD) return
        }
        showPeer(p.id, m.id, m.text, m.sentAt, isDirect)
    }

    /** Catch up from a peer's durable log (the live flow doesn't replay). Lines from before the agent was added are skipped. */
    fun backfill(peerId: String, entries: List<LogEntry>) {
        val p = registry.get(peerId) ?: return
        for (e in entries) {
            if (e.dir != Direction.IN || e.at < p.createdAt || thread.hasSource(e.id)) continue
            showPeer(p.id, e.id, e.text, e.at, e.correlation?.correlationId?.startsWith(DIRECT) == true)
        }
    }

    /** A direct ask closed without an answer. */
    fun onRequestClosed(event: RequestClosed) {
        val cid = event.request.correlationId
        if (!cid.startsWith(DIRECT)) return
        val p = registry.all().firstOrNull { it.id == event.request.agentId.value } ?: return
        val text = when (event.outcome) {
            RequestClosed.Outcome.EXPIRED -> "${p.name} didn't answer"
            RequestClosed.Outcome.LIKELY_MISSED -> "${p.name} may have answered in Messages"
            else -> return
        }
        thread.upsert(ThreadMessage("closed:$cid", now(), Main.AUTHOR, text, kind = MessageKind.NOTICE, peerId = p.id, sourceId = "closed:$cid"))
    }

    private fun showPeer(peerId: String, sourceId: String, text: String, sentAt: Long, direct: Boolean) {
        // A direct answer never shows above the question it answers, even with a skewed timestamp.
        val floor = if (direct) (thread.all().lastOrNull { it.author == ThreadMessage.USER }?.at ?: 0) + 1 else 0
        thread.upsert(ThreadMessage("in:$sourceId", maxOf(sentAt, floor), peerId, text, direct = direct, sourceId = sourceId))
    }

    private suspend fun runMain(turn: MainTurn) {
        val started = mutableListOf<String>()
        val sink = object : ThreadSink {
            override suspend fun start(text: String): String {
                val id = ThreadMessage.newId()
                started += id
                thread.upsert(ThreadMessage(id, now(), Main.AUTHOR, text, streaming = true))
                return id
            }
            override suspend fun update(id: String, text: String) { thread.update(id) { it.copy(text = text) } }
            override suspend fun needsUser(kind: NeedsUserKind, text: String) {
                if (kind == NeedsUserKind.SIGN_IN) {
                    thread.upsert(ThreadMessage(ThreadMessage.newId(), now(), Main.AUTHOR, text, kind = MessageKind.NEEDS_SIGN_IN))
                } else notice(text)
            }
        }
        val context = MainContext(history(), peers, sink, responses(), model(), Main.NAME)
        try {
            coordinator().onTurn(turn, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (started.isEmpty()) notice("${Main.NAME} couldn't answer just now.")
        } finally {
            for (id in started) thread.update(id) { m -> m.copy(streaming = false) }
            // Drop a started-but-empty answer (the brain failed before writing anything).
            for (id in started) thread.get(id)?.takeIf { it.text.isBlank() }?.let { e -> thread.upsert(e.copy(kind = MessageKind.NOTICE, text = "${Main.NAME} couldn't answer just now.")) }
        }
    }

    /** The recent thread for the main agent (last 30 text lines, oldest first). */
    fun history(limit: Int = 30): List<ThreadLine> =
        thread.all().filter { it.kind == MessageKind.TEXT && it.text.isNotBlank() }.takeLast(limit)
            .map { ThreadLine(it.id, it.at, it.author, it.text) }

    private fun notice(text: String) =
        thread.upsert(ThreadMessage(ThreadMessage.newId(), now(), Main.AUTHOR, text, kind = MessageKind.NOTICE))

    private fun now() = maxOf(clock(), (thread.all().lastOrNull()?.at ?: 0) + 1)

    companion object {
        const val DIRECT = "d:"
        const val DEFAULT_MODEL = "gpt-6.1-sol"

        fun directCid(messageId: String, peerId: String, attempt: String) =
            "$DIRECT${messageId.take(13)}:$peerId:$attempt"

        fun failCopy(why: FailReason, name: String): String = when (why) {
            FailReason.NO_NUMBER -> "Add $name's number first."
            FailReason.NO_MESSAGES_APP -> "Google Messages isn't installed."
            FailReason.TOO_MANY_OUTSTANDING -> "$name still has questions waiting."
            FailReason.TOO_LONG -> "That's too long for one text."
            FailReason.UNKNOWN_AGENT -> "$name can't be reached right now."
            else -> "Couldn't send to $name."
        }
    }
}
