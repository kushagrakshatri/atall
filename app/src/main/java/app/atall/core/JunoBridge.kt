package app.atall.core

import app.atall.agents.text.AgentMessage
import app.atall.agents.text.Confidence
import app.atall.agents.text.NonAnswers
import app.atall.agents.text.Reaction
import app.atall.agents.text.Reactions
import app.atall.agents.text.RequestClosed
import app.atall.api.PeerDirectory
import app.atall.coordinator.AgentKind
import app.atall.coordinator.AgreementMap
import app.atall.coordinator.AskPurpose
import app.atall.coordinator.AskState
import app.atall.coordinator.AskStatus
import app.atall.coordinator.Certainty
import app.atall.coordinator.CloseOutcome
import app.atall.coordinator.DelegationStrip
import app.atall.coordinator.InMemoryScoreLog
import app.atall.coordinator.Juno
import app.atall.coordinator.JunoConfig
import app.atall.coordinator.Llm
import app.atall.coordinator.LlmException
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.NeedsUserKind
import app.atall.coordinator.Peer
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.Phase
import app.atall.coordinator.PendingKind
import app.atall.coordinator.Roster
import app.atall.coordinator.RunMode
import app.atall.coordinator.RunState
import app.atall.coordinator.RunStore
import app.atall.coordinator.RunUpdate
import app.atall.coordinator.ScoreLog
import app.atall.coordinator.StripPhase
import app.atall.coordinator.RoomStage
import app.atall.coordinator.ScoreEvent
import app.atall.coordinator.SendGate
import app.atall.coordinator.TalkTrigger
import app.atall.coordinator.TalkView
import app.atall.coordinator.Views
import app.atall.coordinator.adapters.ConnectorAgent
import app.atall.coordinator.adapters.ConnectorText
import app.atall.coordinator.adapters.TextAgentPeer
import app.atall.api.ChannelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import app.atall.api.ThreadLine as Line
import app.atall.coordinator.AgentProfile as JunoAgent
import app.atall.coordinator.ThreadLine as JunoLine

/**
 * Juno's real brain in the app: `app.atall.coordinator`'s [Juno] (plan → ask_agent → wait → merge, the approval gate,
 * `@all` and its Agreement Map), wired to the thread and the texting agents.
 *
 * - [start] begins a run for an unaddressed / `@Juno` message or an `@all`, and drives it in [scope].
 * - Every message an agent sends to one of Juno's asks comes in through [claim]; every transport closure through
 *   [onRequestClosed]. Juno's state is persisted ([RunStore]) and re-read; [project] turns it into thread lines:
 *   the request, a strip (RUN), one SUBTASK per ask with the agent's replies under it, needs-you lines, the answer
 *   (or the Agreement Map), all with the run's id so the thread keeps them together.
 * - Agents receive [ContextPack]ed text: the header and Juno's self-contained sub-task (plain text), plus who else
 *   is asked. @all questions (the user's raw line) also carry the user's words and a few recent turns.
 * - A tapback on one of Juno's asks is a chip on that sub-task ("Reacted 🧳 · waiting"); it never settles the ask.
 *
 * Pure Kotlin (no Android): tested with fakes in `JunoBridgeTest`.
 */
class JunoBridge(
    private val thread: ThreadStore,
    private val registry: AgentRegistry,
    private val peers: PeerDirectory,
    llm: () -> Llm?,
    store: RunStore,
    private val scope: CoroutineScope,
    private val scores: ScoreLog = InMemoryScoreLog(),
    private val userName: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    // Juno starts a talk by itself when @all's first answers clash on the thing being decided (plan.md "Triggers").
    config: JunoConfig = JunoConfig(autoTalk = true),
    /** Off in tests that step the run by hand. */
    private val autoDrive: Boolean = true,
    /** A talk ended with Juno's wrap-up (once per talk): the app's one notification for it. */
    private val onTalkDone: (runId: String, text: String) -> Unit = { _, _ -> },
    /** The app's debug log ("Copy debug log"); every run step lands here. */
    val log: DebugLog = DebugLog(null, clock),
    private val telemetry: Telemetry = Telemetry.NONE,
    /** A run is reported as stuck when nothing changed for this long while every reply is in. */
    private val stuckAfterMs: Long = 60_000L,
    /** C2: the Atall connector. Agents it can reach get Juno's asks as signed task cards; the rest go by text. */
    private val connector: ConnectorAccess = ConnectorAccess.NONE,
) {
    /** Called when a run starts for a user message, before anything is sent (the debug Story 1 marks its run here). */
    var onRunStarted: (runId: String, msg: ThreadMessage) -> Unit = { _, _ -> }
    val juno = Juno({ roster() }, ::peerFor, SignedInLlm(llm), store, scores, clock, config, userName = userName, trace = { runId, name, detail, text ->
        log.add("juno", name, runId, detail, text)
        when {
            name == "model.error" -> telemetry.capture("Model call failed: ${detail.substringBefore(' ')}", Telemetry.Level.WARNING, runId, mapOf("detail" to Redact.text(detail)))
            name == "run.phase" && (detail.endsWith("-> DONE") || detail.contains("-> FAILED")) ->
                telemetry.capture("Run ${detail.substringAfter("-> ").substringBefore(' ')}", if (detail.contains("FAILED")) Telemetry.Level.WARNING else Telemetry.Level.INFO, runId, mapOf("transition" to detail))
        }
    })
    /** runId -> (state version, when it was first seen at that version): the stuck check. */
    private val lastSeen = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val stuckReported = ConcurrentHashMap.newKeySet<String>()
    private val projectLock = Mutex()
    private val liveRuns = ConcurrentHashMap.newKeySet<String>()
    private var jobs: List<Job> = emptyList()

    /** Collect Juno's updates, resume runs left over from a previous process and sweep periodically. Call once. */
    fun start(sweepEveryMs: Long = 30_000L) {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { juno.updates.collect { u ->
                if (u is RunUpdate.TalkDone) runCatching { onTalkDone(u.runId, u.text) }
                // A projection that throws must never end this collector (the thread would freeze for every later run).
                runIdOf(u)?.let { projectSafely(it) }
            } },
            scope.launch {
                for (id in safe { juno.recover() }.orEmpty()) { liveRuns += id; projectSafely(id); if (autoDrive) launch { drive(id) } }
                while (isActive) {
                    delay(sweepEveryMs)
                    safe { juno.sweep() }
                    for (id in liveRuns.toList()) {
                        projectSafely(id)
                        checkStuck(id)
                        // A talk's round can end on the clock while nobody drives it (12-minute cap, deadlines).
                        val ph = safe { juno.state(id) }?.phase
                        if (autoDrive && (ph == Phase.PLANNING || ph == Phase.MERGING || ph == Phase.WAITING)) launch { drive(id) }
                    }
                }
            },
        )
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs = emptyList() }

    // ------------------------------------------------------------------ roster and peers

    /**
     * Juno plus every agent; paused or not-yet-reachable agents are in it, marked paused. An agent the connector can
     * reach gets its per-agent connector timeouts (C2); a connector-only agent with no live connection is unreachable.
     */
    fun roster(): Roster = Roster(listOf(JunoAgent(Main.AUTHOR, Main.NAME, AgentKind.MAIN)) + registry.all().map { p ->
        val relayId = connector.relayIdFor(p)
        val t = relayId?.let { ConnectorRouting.timeouts(it) }
        JunoAgent(p.id, p.name, AgentKind.TEXT, aliases = p.aliases, paused = p.paused || (!textRoute(p) && relayId == null),
            sensitive = p.sharesPrivately, softTimeoutMs = t?.softMs, hardTimeoutMs = t?.hardMs)
    })

    private fun textRoute(p: AgentProfile) = p.channel.implemented && p.channel != ChannelType.CONNECTOR

    /** Whether there is anyone to coordinate (otherwise the app's streaming plain chat answers). */
    fun hasActivePeers(): Boolean = registry.all().any { !it.paused && ((textRoute(it) && peers.agent(it.id) != null) || connector.relayIdFor(it) != null) }

    /**
     * The peer Juno sends [id]'s asks to. Over the connector when the phone is paired and the agent is connected (a
     * signed task card: a short title and the same packed text a text would carry); by text otherwise. When the relay
     * can't be reached or refuses this phone at send time, the ask goes by text instead, except in a connector-only run.
     */
    private fun peerFor(id: String): Peer? {
        val profile = registry.get(id) ?: return null
        val agent = if (textRoute(profile)) peers.agent(id) else null
        // No draft from Juno's side: with no live reply action the ask becomes "Tap to send to X" in the thread
        // (two drafts opened back to back would replace each other in Messages).
        val inner = agent?.let { TextAgentPeer(it, replyWindowMs = 30 * 60_000L, allowDraft = false) }
        val conn = connector.peer(profile)
        if (inner == null && conn == null) return null
        return object : Peer {
            override val id: String = id
            override suspend fun send(text: String, correlationId: String): PeerSend {
                val p = packed(id, text, correlationId)
                // The send gate, at the transport: only the user's own words may carry buy / book / "go ahead".
                if (SendGate.check(p.segments, userName()).gated) return PeerSend.Failed("needs_your_ok")
                val runId = PeerLines.runIdOf(correlationId)
                val only = connector.connectorOnly(runId)
                if (conn != null) {
                    val r = conn.sendCard(correlationId, ConnectorText.title(text), p.text)
                    val byText = r is PeerSend.Failed && r.reason in ConnectorAgent.UNAVAILABLE && inner != null && !only
                    log.add("connector", "send", runId, "${correlationId.substringAfter('~')} to ${conn.relayAgentId}: " + when (r) {
                        is PeerSend.Sent -> "posted"; is PeerSend.Duplicate -> "already posted"; is PeerSend.Failed -> r.reason + if (byText) " (sending by text)" else ""
                        else -> r.javaClass.simpleName
                    })
                    if (!byText) return r
                } else if (only) return PeerSend.Failed("connector_unavailable")
                return inner!!.send(p.text, correlationId)
            }
            override suspend fun close(correlationId: String) { conn?.close(correlationId); inner?.close(correlationId) }
        }
    }

    /**
     * C3 fallback ladder (§8), text rung: the ask [cid] that went over the connector goes by text instead (no claim or
     * progress by the agent's soft timeout, or the relay down a minute). Same packed text, same ask id, tagged so the
     * reply correlates. False when the agent has no live text route, the send gate holds it, or it needs a tap.
     */
    suspend fun sendAskByText(cid: String, reason: String): Boolean {
        val runId = PeerLines.runIdOf(cid)
        val ask = safe { juno.state(runId) }?.ask(cid) ?: return false
        if (ask.status.settled) return true
        val profile = registry.get(ask.agentId) ?: return false
        val agent = (if (textRoute(profile) && !profile.paused) peers.agent(profile.id) else null) ?: return false
        val p = packed(ask.agentId, ask.text, cid)
        if (SendGate.check(p.segments, userName()).gated) return false
        val r = TextAgentPeer(agent, replyWindowMs = 30 * 60_000L, tag = true, allowDraft = false).send(p.text, cid)
        log.add("connector", "fallback", runId, "${cid.substringAfter('~')} to ${profile.name} by text ($reason): ${r.javaClass.simpleName}")
        return r is PeerSend.Sent || r is PeerSend.Duplicate
    }

    /** The whole text an agent would get for ask [cid] (the tap rung shares it), or null when the ask is gone or done. */
    suspend fun fullAskText(cid: String): String? {
        val ask = safe { juno.state(PeerLines.runIdOf(cid)) }?.ask(cid)?.takeIf { !it.status.settled } ?: return null
        val p = packed(ask.agentId, ask.text, cid)
        return p.text.takeIf { !SendGate.check(p.segments, userName()).gated }
    }

    /** The text that actually goes to agent [agentId] for ask [askId]: Juno's sub-task wrapped in compact context. */
    suspend fun pack(agentId: String, need: String, askId: String): String = packed(agentId, need, askId).text

    suspend fun packed(agentId: String, need: String, askId: String): ContextPack.Packed {
        val runId = PeerLines.runIdOf(askId)
        val st = safe { juno.state(runId) }
        val ask = st?.ask(askId)
        val r = roster()
        val kind = when (ask?.purpose) {
            AskPurpose.BLIND -> ContextPack.Kind.BLIND
            AskPurpose.CHALLENGE -> ContextPack.Kind.CHALLENGE
            AskPurpose.ROOM -> ContextPack.Kind.ROOM
            else -> ContextPack.Kind.DELEGATED
        }
        val others = st?.asks.orEmpty()
            .filter { it.agentId != agentId && it.agentId != Main.AUTHOR && it.purpose == (ask?.purpose ?: AskPurpose.TASK) && it.status != AskStatus.CANCELLED }
            .map { r.nameOf(it.agentId) }.distinct()
        val chrono = thread.chronological()
        val request = chrono.firstOrNull { it.runId == runId && it.author == ThreadMessage.USER }
        val recent = if (request == null) emptyList() else Router.recentBefore(chrono, request)
        // A bare follow-up ("try to bring in @all") carries what it refers to: the intent, Juno's plan, its question.
        val follow = if (request == null || kind == ContextPack.Kind.ROOM || kind == ContextPack.Kind.CHALLENGE) null
            else FollowUp.expand(request.text, recent, userName(), registry.all().map { it.name })
        return ContextPack.packed(ContextPack.Input(
            kind = kind, userName = userName(), recipientId = agentId, recipientName = r.nameOf(agentId),
            request = request?.text ?: st?.userText.orEmpty(),
            need = if (follow != null && kind == ContextPack.Kind.BLIND) follow.intent else need,
            recent = recent, context = follow?.context,
            names = registry.all().associate { it.id to it.name }, others = if (kind == ContextPack.Kind.ROOM) emptyList() else others,
            approved = ask?.approved == true, quoted = ask?.quoted.orEmpty(),
        ))
    }

    // ------------------------------------------------------------------ user side

    /**
     * Start a run for [msg]. [route] is [Route.ToMain] (Juno plans and delegates) or [Route.All] (blind fan-out and
     * the Agreement Map). Returns the run id; the run drives itself in [scope].
     */
    suspend fun startRun(msg: ThreadMessage, route: Route): String {
        val chrono = thread.chronological()
        val recent = Router.recentBefore(chrono, msg, limit = JunoConfig().maxContextLines)
        val r = roster()
        // A bare follow-up becomes the question it stands for (v0.1.6 bug: "try to bring in @all" went out as just
        // "try to bring in"). Juno's own model gets the whole expansion; each agent's text gets it through [packed].
        val follow = FollowUp.expand(msg.text, recent, userName(), registry.all().map { it.name })
        fun asked(text: String) = follow?.full ?: text
        val runId = when (route) {
            is Route.All -> juno.startAll(asked(route.text), r.activePeers)
            is Route.Talk -> juno.startTalk(asked(route.text), route.peerIds.mapNotNull { r.byId(it) }.filter { !it.paused }.ifEmpty { r.activePeers })
            is Route.ToMain -> juno.startCoordinated(
                if (route.mentionedPeers.isNotEmpty()) asked(route.text) else route.text,
                mustAsk = route.mentionedPeers.mapNotNull { r.byId(it) }.filter { !it.paused },
                context = recent.map { JunoLine(displayName(it.author), it.text) },
            )
            is Route.Direct -> error("direct messages don't start runs")
        }
        liveRuns += runId
        runCatching { onRunStarted(runId, msg) }
        thread.update(msg.id) { it.copy(runId = runId, status = SendStatus.SENT, statusNote = null) }
        project(runId)
        if (autoDrive) scope.launch { drive(runId) }
        return runId
    }

    /**
     * An unaddressed message while one of Juno's runs is open: it answers Juno's question, or (while Juno waits on
     * agents) it is folded into the run ("the user added …"). Returns the run id, or null when no run takes it.
     */
    suspend fun takeFollowUp(msg: ThreadMessage): String? {
        val now = clock()
        val runs = liveRuns.mapNotNull { safe { juno.state(it) } }
        // A talk that's still going: what the user writes joins it (every agent in it sees it next round).
        runs.filter { it.mode == RunMode.ROOM && !it.phase.terminal && it.room?.stage != RoomStage.WRAPPING }
            .maxByOrNull { it.createdAt }?.let { talk ->
                juno.interject(talk.runId, msg.text)
                thread.update(msg.id) { it.copy(runId = talk.runId, status = SendStatus.SENT, statusNote = null) }
                project(talk.runId)
                if (autoDrive) scope.launch { drive(talk.runId) }
                return talk.runId
            }
        val candidates = runs
            .filter { it.mode == RunMode.COORDINATE && !it.phase.terminal && now - it.createdAt <= FOLLOW_UP_WINDOW_MS }
            .sortedByDescending { it.createdAt }
        val run = candidates.firstOrNull { s ->
            s.pending.any { it.kind == PendingKind.ASK_USER && it.output == null } ||
                s.phase == Phase.WAITING || s.phase == Phase.PLANNING
        } ?: return null
        val asking = run.pending.any { it.kind == PendingKind.ASK_USER && it.output == null }
        if (asking) juno.answer(run.runId, msg.text) else juno.interject(run.runId, msg.text)
        thread.update(msg.id) { it.copy(runId = run.runId, status = SendStatus.SENT, statusNote = null) }
        project(run.runId)
        if (asking && autoDrive) scope.launch { drive(run.runId) }
        return run.runId
    }

    suspend fun approve(askId: String, yes: Boolean) {
        val runId = PeerLines.runIdOf(askId)
        juno.approve(runId, askId, yes)
        project(runId)
        if (autoDrive) scope.launch { drive(runId) }
    }

    /** The user tapped "Tap to send" for one of Juno's asks and the send (or draft) went through. */
    suspend fun userSent(askId: String) {
        juno.userSentDraft(askId)
        project(PeerLines.runIdOf(askId))
    }

    suspend fun stopWaiting(runId: String) { juno.cancel(runId); project(runId) }

    suspend fun challenge(runId: String, disputeId: String) {
        if (juno.challenge(runId, disputeId)) { liveRuns += runId; project(runId); if (autoDrive) scope.launch { drive(runId) } }
    }

    /** "Talk it over" on a disagreement in the Agreement Map (what "Check this" used to do). */
    suspend fun talkOver(runId: String, disputeId: String) {
        if (juno.talkOver(runId, disputeId)) { liveRuns += runId; project(runId); if (autoDrive) scope.launch { drive(runId) } }
    }

    /** "Wrap up now" on a talk that's waiting on a slow agent. */
    suspend fun wrapUp(runId: String) { if (juno.wrapUpNow(runId)) { project(runId); if (autoDrive) scope.launch { drive(runId) } } }

    /** "Leave X out" on a talk. */
    suspend fun leaveOut(runId: String, agentId: String) { if (juno.leaveOut(runId, agentId)) { project(runId); if (autoDrive) scope.launch { drive(runId) } } }

    /** The user tapped one of Juno's choices after a talk: it goes out as their own message, and it's logged. */
    suspend fun choiceTapped(runId: String, choice: String) {
        val st = safe { juno.state(runId) } ?: return
        val topic = st.room?.focusTopic ?: st.userText.take(80)
        runCatching { scores.append(ScoreEvent.TalkChoice(Main.AUTHOR, clock(), runId, topic, choice)) }
    }

    suspend fun drive(runId: String) {
        try { juno.drive(runId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.add("bridge", "drive.error", runId, "${e.javaClass.simpleName}: ${e.message}")
            telemetry.capture("Run drive failed: ${e.javaClass.simpleName}", Telemetry.Level.ERROR, runId, mapOf("message" to Redact.text(e.message ?: "")))
        }
        projectSafely(runId)
    }

    private suspend fun projectSafely(runId: String) {
        try { project(runId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.add("bridge", "project.error", runId, "${e.javaClass.simpleName}: ${e.message}")
            telemetry.capture("Thread projection failed: ${e.javaClass.simpleName}", Telemetry.Level.ERROR, runId, mapOf("message" to Redact.text(e.message ?: "")))
        }
    }

    /**
     * A run is stuck when nothing changed for [stuckAfterMs] while every reply it waits on is in and it isn't waiting
     * on the user. Reported once per run (log + telemetry event) with what it was doing.
     */
    suspend fun checkStuck(runId: String): Boolean {
        val st = safe { juno.state(runId) } ?: return false
        val now = clock()
        val seen = lastSeen[runId]
        if (seen == null || seen.first != st.version) { lastSeen[runId] = st.version to now; return false }
        if (st.phase.terminal || st.phase == Phase.AWAITING_USER || runId in stuckReported) return false
        val asks = st.asks.filter { it.agentId != Main.AUTHOR && it.status != AskStatus.CANCELLED && it.status != AskStatus.DECLINED }
        val allIn = asks.isNotEmpty() && asks.all { it.replies.isNotEmpty() || it.status.settled }
        if (!allIn || now - seen.second < stuckAfterMs) return false
        stuckReported += runId
        val talkStage = st.room?.stage
        val detail = "phase=${st.phase} mode=${st.mode} for ${(now - seen.second) / 1000}s; asks=" + asks.joinToString { "${it.id.substringAfter('~')}:${it.status}:${it.replies.size}" } +
            " pending=" + st.pending.joinToString { "${it.kind}:${if (it.output == null) "open" else "done"}" } + " map=${st.agreement?.status} talk=$talkStage"
        log.add("bridge", "run.stuck", runId, detail)
        telemetry.capture("Run stuck: no transition for ${stuckAfterMs / 1000}s with every reply in", Telemetry.Level.ERROR, runId, mapOf("state" to detail))
        return true
    }

    // ------------------------------------------------------------------ agent side

    /**
     * A message from agent [peerId]. Returns true when it belongs to one of Juno's runs (it is then shown under that
     * run), false when the app should show it on its own. A tapback is never an answer: it becomes a chip on the
     * sub-task it reacts to ([react]); it is claimed either way so it can never become reply text.
     */
    suspend fun claim(peerId: String, m: AgentMessage): Boolean {
        // The Router turns tapbacks into chips before calling this; a tapback that still gets here is never shown as text.
        (m.reaction ?: Reactions.parse(m.text))?.let { react(peerId, it); return true }
        val cid = m.correlation?.correlationId
        val ask: AskState? = when {
            PeerLines.isJunoCid(cid) -> safe { juno.state(PeerLines.runIdOf(cid!!)) }?.ask(cid!!)?.takeIf { it.agentId == peerId }
            cid == null -> openAskFor(peerId, m.sentAt)
            else -> null
        }
        if (ask == null) return false
        // An uncorrelated line we attach ourselves is "probably" the answer: Juno is told it is uncertain.
        val certainty = if (cid == null) Certainty.LOW else when (m.correlation?.confidence) {
            Confidence.MEDIUM -> Certainty.MEDIUM; Confidence.LOW -> Certainty.LOW; else -> Certainty.HIGH
        }
        juno.ingest(PeerSignal.Message(
            agentId = peerId, messageId = m.id, text = m.text, at = m.sentAt, correlationId = ask.id, certainty = certainty,
            part = m.correlation?.part ?: 0, late = m.correlation?.late == true, userInterjected = m.correlation?.userInterjected == true,
            // The agent's own status text ("signal dropped", "let me check") is never its answer (also for lines
            // replayed from the log, which carry no flag: classified again from the text).
            status = (m.nonAnswer ?: NonAnswers.classify(m.text))?.name?.lowercase(),
        ))
        project(PeerLines.runIdOf(ask.id))
        return true
    }

    /**
     * A tapback from [peerId]. When it reacts to one of Juno's asks to that agent, it becomes (or, "Removed …",
     * stops being) a chip on that sub-task and the ask keeps waiting for the real reply. Returns whether it was taken.
     */
    suspend fun react(peerId: String, r: Reaction): Boolean {
        val ask = reactionAsk(peerId, r) ?: return false
        val runId = PeerLines.runIdOf(ask.id)
        projectLock.withLock {
            thread.get("ask:${ask.id}")?.let { sub ->
                val next = PeerLines.applyReaction(sub, peerId, r)
                if (next !== sub) thread.upsert(next)
            }
        }
        project(runId)
        return true
    }

    /**
     * The ask a tapback from [peerId] reacts to: the one the transport matched by its quote; else the newest ask to
     * that agent in a live run whose sent text (or sub-task) the quote matches, or whose quote starts with our header.
     */
    private suspend fun reactionAsk(peerId: String, r: Reaction): AskState? {
        r.targetCorrelationId?.takeIf { PeerLines.isJunoCid(it) }?.let { cid ->
            return safe { juno.state(PeerLines.runIdOf(cid)) }?.ask(cid)?.takeIf { it.agentId == peerId }
        }
        if (r.targetCorrelationId != null) return null // a direct send's text: the app puts the chip on that message
        val asks = liveRuns.mapNotNull { safe { juno.state(it) } }
            .flatMap { s -> s.asks.filter { it.agentId == peerId && it.status != AskStatus.CANCELLED } }
            .sortedByDescending { it.sentAt ?: it.createdAt }
        asks.firstOrNull { a ->
            Reactions.quotes(r.quoted, a.draftText ?: "") || Reactions.quotes(r.quoted, a.text) ||
                Reactions.quotes(r.quoted, Markdown.plain(a.text))
        }?.let { return it }
        return if (r.quoted.trim().startsWith(ContextPack.HEADER_PREFIX, ignoreCase = true)) asks.firstOrNull() else null
    }

    /** A transport closure for one of Juno's asks (timeouts, "may have answered in Messages"). */
    suspend fun onRequestClosed(e: RequestClosed): Boolean {
        val cid = e.request.correlationId
        if (!PeerLines.isJunoCid(cid)) return false
        val outcome = when (e.outcome) {
            RequestClosed.Outcome.COMPLETED -> CloseOutcome.COMPLETED
            RequestClosed.Outcome.EXPIRED -> CloseOutcome.EXPIRED
            RequestClosed.Outcome.LIKELY_MISSED -> CloseOutcome.LIKELY_MISSED
            RequestClosed.Outcome.CLOSED_BY_CALLER -> CloseOutcome.CLOSED_BY_CALLER
        }
        juno.ingest(PeerSignal.Closed(e.agentId.value, cid, outcome, clock()))
        project(PeerLines.runIdOf(cid))
        return true
    }

    /** The newest unsettled ask to [peerId] in a live run that was sent before [at]: where a stray reply belongs. */
    private suspend fun openAskFor(peerId: String, at: Long): AskState? = liveRuns.mapNotNull { safe { juno.state(it) } }
        .flatMap { s -> s.asks.filter { a ->
            a.agentId == peerId && a.status in OPEN && (a.sentAt ?: a.createdAt) <= at + 5_000 && (a.hardDeadline == null || at <= a.hardDeadline!!)
        } }
        .maxByOrNull { it.sentAt ?: it.createdAt }

    // ------------------------------------------------------------------ projection into the thread

    /** Re-read run [runId] and make the thread match it. Idempotent; lines only change when their content does. */
    suspend fun project(runId: String) = projectLock.withLock {
        val st = safe { juno.state(runId) } ?: return@withLock
        val view = safe { juno.view(runId) } ?: return@withLock
        if (!st.live) liveRuns -= runId
        val r = roster()
        val chrono = thread.chronological()
        val request = chrono.firstOrNull { it.runId == runId && it.author == ThreadMessage.USER }
        val base = request?.at ?: st.createdAt
        var last = base

        fun put(m: ThreadMessage) { thread.upsert(m.copy(runId = runId)); last = maxOf(last, m.at) }
        fun at(t: Long?) = maxOf(t ?: clock(), last + 1)

        // The strip: who Juno is asking, then who answered.
        val strip = view.strip
        val headId = "run:$runId"
        if (strip != null || !st.phase.terminal) {
            val label = strip?.labelText ?: if (st.mode == RunMode.ALL) "Asking everyone" else "Thinking"
            val existing = thread.get(headId)
            put(ThreadMessage(headId, existing?.at ?: (base + 1), Main.AUTHOR, label, kind = MessageKind.RUN,
                detail = stripDetail(strip), streaming = strip?.phase == StripPhase.LIVE || (strip == null && !st.phase.terminal)))
        } else thread.get(headId)?.let { if (!it.hidden) thread.upsert(it.copy(hidden = true)) }

        val talk = view.talk
        if (talk != null) {
            val id = "talk:$runId"
            put(ThreadMessage(id, thread.get(id)?.at ?: at(st.room?.startedAt), Main.AUTHOR, talk.label, kind = MessageKind.TALK,
                detail = Json.encodeToString(TalkView.serializer(), talk), streaming = talk.live))
        }
        for (a in st.asks.sortedBy { it.createdAt }) {
            if (talk != null) {
                // A talk shows its rounds inside its card; lines an @all projected before it became a talk fold away.
                for (old in listOf("ask:${a.id}", "blind:${a.id}") + a.replies.map { "in:${it.messageId}" }) {
                    thread.get(old)?.let { if (!it.hidden) thread.upsert(it.copy(hidden = true)) }
                }
                if (a.agentId != Main.AUTHOR) projectNeeds(a, ::put, ::at)
                continue
            }
            if (a.agentId == Main.AUTHOR) {
                // @all: Juno's own blind answer is folded into the merged answer (and under "Show every answer"),
                // never shown on its own. Lines an older build projected are hidden.
                thread.get("blind:${a.id}")?.let { if (!it.hidden) thread.upsert(it.copy(hidden = true)) }
                continue
            }
            val subId = "ask:${a.id}"
            val sub = thread.get(subId)
            // A tapback stored as a reply by an older build: a chip, never a reply line (and never "Replied").
            val (tapbacks, replies) = a.replies.partition { Reactions.parse(it.text) != null }
            var chips = sub?.reactions.orEmpty()
            for (p in tapbacks) {
                Reactions.parse(p.text)?.let { chips = PeerLines.applyReaction(ThreadMessage("", 0, "", "", reactions = chips), a.agentId, it).reactions }
                thread.get("in:${p.messageId}")?.let { if (!it.hidden) thread.upsert(it.copy(hidden = true)) }
            }
            put(ThreadMessage(subId, sub?.at ?: at(a.createdAt), Main.AUTHOR, a.text, kind = MessageKind.SUBTASK,
                peerId = a.agentId, askId = a.id, correlationId = a.id, reactions = chips,
                detail = statusWord(a.copy(replies = replies), st, chips)))
            projectNeeds(a, ::put, ::at)
            // The agent's status texts: one small muted line each, under its sub-task, never a reply.
            for (p in a.statusNotes) {
                val id = "status:${p.messageId}"
                put(ThreadMessage(id, thread.get(id)?.at ?: at(p.at), Main.AUTHOR, PeerLines.statusLine(roster().nameOf(a.agentId), p.text),
                    kind = MessageKind.NOTICE, peerId = a.agentId, askId = a.id, sourceId = p.messageId))
            }
            for (p in replies) {
                val id = "in:${p.messageId}"
                put(ThreadMessage(id, thread.get(id)?.at ?: at(p.at), a.agentId, p.text, attribution = Attribution.JUNO,
                    askId = a.id, sourceId = p.messageId, detail = replyNote(p.certainty, p.late), via = p.provenance.takeIf { it == app.atall.coordinator.Via.CONNECTOR },
                    suggest = PeerLines.suggestions(p.text, a.agentId, registry.all())))
            }
        }

        st.pending.filter { it.kind == PendingKind.ASK_USER }.forEach { q ->
            val id = "q:$runId:${q.callId}"
            put(ThreadMessage(id, thread.get(id)?.at ?: at(q.startedAt), Main.AUTHOR, q.question.orEmpty()))
        }

        val map = view.agreement
        val mapShown = map != null && (st.mode == RunMode.ALL || (st.mode == RunMode.ROOM && st.room?.trigger != TalkTrigger.ASKED))
        if (mapShown) {
            val id = "map:$runId"
            put(ThreadMessage(id, thread.get(id)?.at ?: at(null), Main.AUTHOR, map!!.verdict?.line ?: "Waiting for answers",
                kind = MessageKind.AGREEMENT, detail = Json.encodeToString(AgreementMap.serializer(), map)))
        }
        // The answer: Juno's merge (@all), its reply, or a talk's wrap-up. An @all from an older build stored only the
        // map's verdict line as its answer; the map already shows that.
        val oldStyleAll = st.mode == RunMode.ALL && st.answer == map?.verdict?.line
        if (st.answer != null && !oldStyleAll) {
            val id = "answer:$runId"
            val room = st.room
            val prev = thread.get(id)
            // A new answer (a talk's wrap-up replacing the merge, a re-merge) moves below what came before it.
            val keepAt = prev != null && prev.text == st.answer
            put(ThreadMessage(id, if (keepAt) prev!!.at else at(st.answeredAt), Main.AUTHOR, st.answer!!,
                choices = room?.choices.orEmpty(), yourCall = room?.yourCall == true))
        }
        st.followUps.forEachIndexed { i, f ->
            val id = "follow:$runId:$i"
            put(ThreadMessage(id, thread.get(id)?.at ?: at(f.at), Main.AUTHOR, f.text))
        }
        if (st.phase == Phase.FAILED || st.phase == Phase.CANCELLED) {
            val id = "end:$runId"
            val (kind, text) = when {
                st.phase == Phase.CANCELLED -> MessageKind.NOTICE to "Stopped waiting."
                st.errorKind == LlmException.Kind.SIGN_IN -> MessageKind.NEEDS_SIGN_IN to "Your ChatGPT sign-in ended. Sign in again to talk to ${Main.NAME}."
                st.errorKind == LlmException.Kind.LIMIT -> MessageKind.NOTICE to "You've hit your ChatGPT limit for Atall. Manage usage at chatgpt.com/settings/usage."
                else -> MessageKind.NOTICE to "${Main.NAME} couldn't answer just now."
            }
            put(ThreadMessage(id, thread.get(id)?.at ?: at(null), Main.AUTHOR, text, kind = kind))
        }
    }

    private fun projectNeeds(a: AskState, put: (ThreadMessage) -> Unit, at: (Long?) -> Long) {
        val name = roster().nameOf(a.agentId)
        val tapId = "tap:${a.id}"
        val tap = thread.get(tapId)
        if (a.status == AskStatus.NEEDS_TAP) {
            put(ThreadMessage(tapId, tap?.at ?: at(null), Main.AUTHOR, "Tap to send to $name", kind = MessageKind.NEEDS_TAP,
                peerId = a.agentId, askId = a.id, draftText = a.draftText ?: a.text, correlationId = a.id,
                status = tap?.status, statusNote = tap?.statusNote))
        } else if (tap != null) {
            // Sent (by the tap or the user in Messages), or the ask settled without it.
            val status = when {
                tap.status == SendStatus.DRAFTED || tap.status == SendStatus.SENT -> tap.status
                a.sentAt != null && a.status != AskStatus.TIMED_OUT -> SendStatus.SENT
                else -> null
            }
            put(if (status == null) tap.copy(hidden = true) else tap.copy(status = status, statusNote = null))
        }

        val okId = "approve:${a.id}"
        val ok = thread.get(okId)
        if (a.status == AskStatus.AWAITING_APPROVAL || ok != null) {
            val status = when (a.status) {
                AskStatus.AWAITING_APPROVAL -> null
                AskStatus.DECLINED -> SendStatus.NOT_SENT
                else -> SendStatus.SENT
            }
            put(ThreadMessage(okId, ok?.at ?: at(null), Main.AUTHOR, Views.approvalLine(a) { roster().nameOf(it) }, kind = MessageKind.NEEDS_APPROVAL,
                peerId = a.agentId, askId = a.id, detail = a.text, status = status))
        }

        val missId = "missed:${a.id}"
        val miss = thread.get(missId)
        if (a.status == AskStatus.LIKELY_MISSED && a.replies.isEmpty()) {
            put(ThreadMessage(missId, miss?.at ?: at(null), Main.AUTHOR, "$name may have answered in Messages", kind = MessageKind.NOTICE,
                peerId = a.agentId, askId = a.id, sourceId = PeerLines.MISSED_PREFIX + a.id, detail = (a.sentAt ?: a.createdAt).toString()))
        } else if (miss != null && !miss.hidden) put(miss.copy(hidden = true))
    }

    private fun displayName(author: String) = when (author) {
        ThreadMessage.USER -> userName().ifBlank { "User" }
        Main.AUTHOR -> Main.NAME
        else -> registry.get(author)?.name ?: "Agent"
    }

    private fun runIdOf(u: RunUpdate): String? = when (u) {
        is RunUpdate.Changed -> u.runId
        is RunUpdate.Answer -> u.runId
        is RunUpdate.FollowUpPosted -> u.runId
        is RunUpdate.NeedsUser -> u.runId
        is RunUpdate.MapUpdated -> u.runId
        is RunUpdate.Failed -> u.runId
        is RunUpdate.TalkDone -> u.runId
        is RunUpdate.Unsolicited, is RunUpdate.DirectReply -> null
    }

    private suspend fun <T> safe(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** The model, or a sign-in error when there is no usable ChatGPT sign-in (the run then shows "Sign in again"). */
    private class SignedInLlm(private val llm: () -> Llm?) : Llm {
        override suspend fun respond(request: LlmRequest): LlmResponse =
            (llm() ?: throw LlmException(LlmException.Kind.SIGN_IN, "signed_out")).respond(request)
    }

    companion object {
        /** An unaddressed message joins an open run only this soon after the run started. */
        const val FOLLOW_UP_WINDOW_MS = 15 * 60_000L
        private val OPEN = setOf(AskStatus.PENDING, AskStatus.DRAFTED, AskStatus.NEEDS_TAP, AskStatus.REPLYING, AskStatus.LIKELY_MISSED, AskStatus.TIMED_OUT)

        fun stripDetail(s: DelegationStrip?): String? = when {
            s == null -> null
            s.phase == StripPhase.LIVE -> s.slowHint
            else -> (listOf(s.timer) + s.notes).filter { it.isNotBlank() }.joinToString(" · ")
        }

        /**
         * The sub-task's status. A tapback alone never reads as "Replied": with [reactions] and no real reply it is
         * "Reacted 🧳 · waiting" (or "· no reply yet" / "· didn't answer" once the run or the ask is over).
         */
        fun statusWord(a: AskState, st: RunState, reactions: List<ReactionChip> = emptyList()): String {
            val emoji = reactions.lastOrNull { it.by == a.agentId }?.emoji
            if (emoji != null && a.replies.isEmpty()) {
                val tail = when (a.status) {
                    AskStatus.PENDING, AskStatus.REPLYING, AskStatus.ANSWERED, AskStatus.DRAFTED, AskStatus.LIKELY_MISSED ->
                        if (st.phase.terminal) "no reply yet" else "waiting"
                    AskStatus.TIMED_OUT -> "didn't answer"
                    else -> null
                }
                if (tail != null) return "Reacted $emoji · $tail"
            }
            return baseStatus(a, st)
        }

        private fun baseStatus(a: AskState, st: RunState): String = when (a.status) {
            AskStatus.QUEUED -> "Queued"
            AskStatus.SENDING -> "Sending"
            AskStatus.PENDING -> if (st.phase.terminal) "Didn't answer yet" else "Waiting"
            AskStatus.DRAFTED -> "Draft open in Messages"
            AskStatus.NEEDS_TAP -> "Needs a tap"
            AskStatus.AWAITING_APPROVAL -> "Waiting for your OK"
            AskStatus.REPLYING -> "Replying"
            AskStatus.ANSWERED -> if (a.replies.isEmpty()) (if (st.phase.terminal) "Didn't answer yet" else "Waiting")
                else a.sentAt?.let { s -> "Replied in " + short(a.replies.first().at - s) } ?: "Replied"
            AskStatus.TIMED_OUT -> "Didn't answer"
            AskStatus.LIKELY_MISSED -> "May have answered in Messages"
            AskStatus.DECLINED -> "You said no"
            AskStatus.FAILED -> "Couldn't send"
            AskStatus.CANCELLED -> "Stopped"
        }

        private fun replyNote(c: Certainty, late: Boolean): String? = listOfNotNull(
            if (c == Certainty.LOW) "Might not be an answer to this" else null,
            if (late) "Arrived late" else null,
        ).joinToString(" · ").ifEmpty { null }

        private fun short(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s" }
    }
}
