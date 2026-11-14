package app.atall.core

import app.atall.agents.text.AgentMessage
import app.atall.agents.text.Direction
import app.atall.agents.text.FailReason
import app.atall.agents.text.LogEntry
import app.atall.agents.text.Reaction
import app.atall.agents.text.Reactions
import app.atall.agents.text.RequestClosed
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import app.atall.api.Coordinator
import app.atall.api.Inbox
import app.atall.api.InboxDecision
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
 * - No mention, or `@Juno`: Juno coordinates ([juno]: plan, ask agents, wait, merge). With no agent to ask, or when
 *   the bridge isn't bound, the plain streaming chat ([coordinator]) answers.
 * - `@all`: every agent gets the same question, blind; Juno builds the Agreement Map.
 * - Only peer mentions: each peer gets the text directly, with compact context ([ContextPack]); replies show as
 *   "replied directly".
 * - Peer messages: tapbacks become reaction chips; replies to Juno's asks go under that Juno turn; the rest show as
 *   direct replies or unprompted messages (the [inbox] can hold unprompted ones).
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
    private val juno: () -> JunoBridge? = { null },
    /** The user's first name for the agents' context header ("From Atall (Kush's thread):"). */
    private val userName: () -> String = { "" },
    /** Whether Juno can call the model (a ChatGPT sign-in exists). Defaults to [responses] being present. */
    private val signedIn: () -> Boolean = { responses() != null },
) {
    /**
     * Called for each user message. Suspends until direct sends are handed off and the main agent has answered.
     * [talk]: the "Talk it over" chip was on (the agents answer, then see each other's answers and reply).
     */
    suspend fun send(raw: String, talk: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val msg = ThreadMessage(ThreadMessage.newId(), now(), ThreadMessage.USER, text, status = SendStatus.SENDING)
        thread.upsert(msg)
        dispatch(msg, talk = talk)
    }

    /** Retry a NOT_SENT user message. */
    suspend fun retry(messageId: String) {
        val m = thread.get(messageId)?.takeIf { it.author == ThreadMessage.USER && it.status == SendStatus.NOT_SENT } ?: return
        thread.upsert(m.copy(status = SendStatus.SENDING, statusNote = null))
        dispatch(m, attempt = now().toString(36))
    }

    private suspend fun dispatch(msg: ThreadMessage, attempt: String = "0", talk: Boolean = false) {
        val agents = registry.all().filter { it.channel.implemented }
        when (val route = Mentions.route(msg.text, registry.all(), talk && agents.count { !it.paused } >= 2)) {
            is Route.Direct -> direct(msg, route, attempt)
            is Route.All -> coordinate(msg, route)
            is Route.Talk -> coordinate(msg, route)
            is Route.ToMain -> coordinate(msg, route)
        }
    }

    /** Juno's turn: the coordinator when there are agents to ask, the plain chat otherwise. */
    private suspend fun coordinate(msg: ThreadMessage, route: Route) {
        val bridge = juno()
        if (bridge == null || !bridge.hasActivePeers()) {
            val text = when (route) { is Route.ToMain -> route.text; is Route.All -> route.text; is Route.Talk -> route.text; else -> msg.text }
            val mentioned = (route as? Route.ToMain)?.mentionedPeers.orEmpty()
            thread.upsert(msg.copy(status = SendStatus.SENT))
            runMain(MainTurn(msg.id, text, msg.at, mentioned))
            return
        }
        if (!signedIn()) {
            thread.upsert(msg.copy(status = SendStatus.SENT))
            thread.upsert(ThreadMessage(ThreadMessage.newId(), now(), Main.AUTHOR, PlainJuno.SIGN_IN_COPY, kind = MessageKind.NEEDS_SIGN_IN))
            return
        }
        // A plain follow-up while Juno is mid-run answers its question or joins the run.
        if (route is Route.ToMain && route.mentionedPeers.isEmpty() && bridge.takeFollowUp(msg) != null) return
        try {
            bridge.startRun(msg, route)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            thread.upsert(msg.copy(status = SendStatus.SENT))
            notice("${Main.NAME} couldn't answer just now.")
        }
    }

    private suspend fun direct(msg: ThreadMessage, route: Route.Direct, attempt: String) {
        var failure: String? = null
        val chrono = thread.chronological()
        val recent = recentBefore(chrono, msg)
        val names = registry.all().associate { it.id to it.name }
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
            val others = route.peerIds.filter { it != p.id }.mapNotNull { names[it] }
            val packed = ContextPack.packed(ContextPack.Input(
                kind = ContextPack.Kind.DIRECT, userName = userName(), recipientId = p.id, recipientName = p.name,
                request = msg.text, recent = recent, names = names, others = others,
            ))
            // The send gate, at the transport: the user's own words go; nothing else may say buy / book / go ahead.
            if (app.atall.coordinator.SendGate.check(packed.segments, userName()).gated) { failure = failure ?: "Needs your OK first."; continue }
            val text = packed.text
            when (val r = agent.send(text, cid, SendOptions(allowDraft = false))) {
                is SendResult.Sent, is SendResult.Duplicate, is SendResult.Drafted -> {}
                is SendResult.NeedsUser -> thread.upsert(
                    ThreadMessage(
                        id = "tap:$cid", at = now(), author = Main.AUTHOR, text = "Tap to send to ${p.name}",
                        kind = MessageKind.NEEDS_TAP, peerId = p.id, draftText = text, correlationId = cid,
                    ),
                )
                is SendResult.RateLimited -> failure = failure ?: "Wait a moment, then retry."
                is SendResult.Failed -> failure = failure ?: failCopy(r.why, p.name)
            }
        }
        thread.upsert(msg.copy(
            status = if (failure == null) SendStatus.SENT else SendStatus.NOT_SENT, statusNote = failure,
            sentTo = (msg.sentTo + route.peerIds).distinct(),
        ))
    }

    /** The user tapped "Tap to send to X": send through a reply action if one came back, else open the prefilled draft. */
    suspend fun tapToSend(messageId: String): Boolean {
        val m = thread.get(messageId)?.takeIf { it.kind == MessageKind.NEEDS_TAP } ?: return false
        val p = m.peerId?.let(registry::get) ?: return false
        val agent = peers.agent(p.id) ?: return false
        val cid = m.correlationId ?: return false
        val r = agent.send(m.draftText ?: return false, cid, SendOptions(allowDraft = true))
        val (status, note) = when (r) {
            is SendResult.Drafted -> SendStatus.DRAFTED to null
            is SendResult.Sent, is SendResult.Duplicate -> SendStatus.SENT to null
            is SendResult.NeedsUser -> null to null
            is SendResult.RateLimited -> null to "Wait a moment, then tap again."
            is SendResult.Failed -> SendStatus.NOT_SENT to failCopy(r.why, p.name)
        }
        thread.upsert(m.copy(status = status ?: m.status, statusNote = note))
        val ok = status == SendStatus.DRAFTED || status == SendStatus.SENT
        if (ok && PeerLines.isJunoCid(cid)) juno()?.userSent(cid)
        return ok
    }

    /** Yes / No on "Okay to have X do this?". */
    suspend fun approve(messageId: String, yes: Boolean) {
        val m = thread.get(messageId)?.takeIf { it.kind == MessageKind.NEEDS_APPROVAL && it.status == null } ?: return
        juno()?.approve(m.askId ?: return, yes)
    }

    /** A live message from a peer (any channel). */
    suspend fun onPeerMessage(peerId: String, m: AgentMessage) {
        val p = registry.get(peerId) ?: return
        if (m.historical || thread.hasSource(m.id)) return
        // A tapback (including one the transport didn't recognise) is a chip, never a reply.
        (m.reaction ?: Reactions.parse(m.text))?.let { react(p.id, it); return }
        if (runCatching { juno()?.claim(p.id, m) }.getOrNull() == true) { dropStaleHints(p.id); return }
        val cid = m.correlation?.correlationId
        val attribution = PeerLines.attribution(p.id, cid, m.sentAt, thread.chronological())
        if (attribution == Attribution.UNPROMPTED) {
            if (runCatching { coordinator().onPeerMessage(p.toPeer(), m) }.getOrDefault(false)) return
            if (inbox().onUnsolicited(p.toPeer(), m) == InboxDecision.HOLD) return
        }
        showPeer(p.id, m.id, m.text, m.sentAt, attribution)
    }

    /** Catch up from a peer's durable log (the live flow doesn't replay). Lines from before the agent was added are skipped. */
    suspend fun backfill(peerId: String, entries: List<LogEntry>) {
        val p = registry.get(peerId) ?: return
        for (e in entries) {
            if (e.dir != Direction.IN || e.at < p.createdAt || thread.hasSource(e.id)) continue
            onPeerMessage(p.id, AgentMessage(e.id, e.agentId, e.text, e.at, e.at, e.correlation, false, e.reaction))
        }
    }

    /** A request closed without an answer. */
    suspend fun onRequestClosed(event: RequestClosed) {
        if (runCatching { juno()?.onRequestClosed(event) }.getOrNull() == true) return
        val cid = event.request.correlationId
        if (!cid.startsWith(DIRECT)) return
        val p = registry.all().firstOrNull { it.id == event.request.agentId.value } ?: return
        // The reply already reached the thread (e.g. uncorrelated, then attributed by us): no hint needed.
        if (PeerLines.answeredSince(p.id, event.request.sentAt, thread.chronological())) return
        val text = when (event.outcome) {
            RequestClosed.Outcome.EXPIRED -> "${p.name} didn't answer"
            RequestClosed.Outcome.LIKELY_MISSED -> "${p.name} may have answered in Messages"
            else -> return
        }
        thread.upsert(ThreadMessage(
            "closed:$cid", now(), Main.AUTHOR, text, kind = MessageKind.NOTICE, peerId = p.id,
            sourceId = PeerLines.MISSED_PREFIX + cid, detail = event.request.sentAt.toString(),
        ))
    }

    /** A tapback: a chip on the Juno sub-task or the user's message it reacts to; never agent text, never an answer. */
    private suspend fun react(peerId: String, r: Reaction) {
        if (runCatching { juno()?.react(peerId, r) }.getOrNull() == true) return
        val target = PeerLines.reactionTarget(peerId, r, thread.chronological()) ?: return
        val next = PeerLines.applyReaction(target, peerId, r)
        if (next !== target) thread.upsert(next)
    }

    private fun showPeer(peerId: String, sourceId: String, text: String, sentAt: Long, attribution: Attribution) {
        val direct = attribution == Attribution.DIRECT
        // A direct answer never shows above the question it answers, even with a skewed timestamp.
        val floor = if (direct) (thread.chronological().lastOrNull { it.author == ThreadMessage.USER }?.at ?: 0) + 1 else 0
        thread.upsert(ThreadMessage("in:$sourceId", maxOf(sentAt, floor), peerId, text, direct = direct, sourceId = sourceId, attribution = attribution,
            suggest = PeerLines.suggestions(text, peerId, registry.all())))
        dropStaleHints(peerId)
    }

    /** "X may have answered in Messages" goes away once a line from X arrives after that send. */
    private fun dropStaleHints(peerId: String) {
        for (h in PeerLines.staleMissedHints(peerId, thread.chronological())) thread.upsert(h.copy(hidden = true))
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
        thread.chronological().filter { it.kind == MessageKind.TEXT && it.text.isNotBlank() }.takeLast(limit)
            .map { ThreadLine(it.id, it.at, it.author, it.text) }

    private fun notice(text: String) =
        thread.upsert(ThreadMessage(ThreadMessage.newId(), now(), Main.AUTHOR, text, kind = MessageKind.NOTICE))

    private fun now() = maxOf(clock(), (thread.chronological().lastOrNull()?.at ?: 0) + 1)

    companion object {
        const val DIRECT = "d:"
        const val DEFAULT_MODEL = "gpt-6.1-sol"

        fun directCid(messageId: String, peerId: String, attempt: String) =
            "$DIRECT${messageId.take(13)}:$peerId:$attempt"

        /** Text turns before [msg] (oldest first), for agent context: no notices, strips or sub-tasks. */
        fun recentBefore(chrono: List<ThreadMessage>, msg: ThreadMessage, limit: Int = ContextPack.MAX_RECENT): List<ThreadLine> {
            val i = chrono.indexOfFirst { it.id == msg.id }
            val before = if (i >= 0) chrono.subList(0, i) else chrono.filter { it.at < msg.at }
            return before.filter { it.kind == MessageKind.TEXT && it.text.isNotBlank() }.takeLast(limit)
                .map { ThreadLine(it.id, it.at, it.author, it.text) }
        }

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
