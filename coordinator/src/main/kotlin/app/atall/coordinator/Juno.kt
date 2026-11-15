package app.atall.coordinator

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class JunoConfig(
  val maxRounds: Int = 3,
  val maxAsks: Int = 4,
  /** Model calls per run before Juno answers with what it has. */
  val maxModelCalls: Int = 8,
  /** A reply counts as finished this long after its last fragment (dots send several texts in a row). */
  val quietWindowMs: Long = 20_000,
  val textSoftTimeoutMs: Long = 180_000,
  val textHardTimeoutMs: Long = 30 * 60_000,
  val apiSoftTimeoutMs: Long = 60_000,
  val apiHardTimeoutMs: Long = 5 * 60_000,
  /** The strip turns "slow" after this. */
  val slowAfterMs: Long = 45_000,
  val minWaitSeconds: Int = 5,
  /** Ceiling for one wait() call; defaults to the text soft timeout. */
  val maxWaitSeconds: Int = 180,
  /** @all: how long to collect before comparing what came back. Late answers re-merge the map. */
  val allWaitMs: Long = 180_000,
  val includeMainInAll: Boolean = true,
  /** @all: automatically challenge the first disputed claim once (otherwise only on the user's tap). */
  val autoChallenge: Boolean = false,
  /** Safety valve for [Juno.drive]'s loop. */
  val maxDriveSteps: Int = 64,
  /** Longest single sleep while waiting (re-checks deadlines at least this often). */
  val maxSleepMs: Long = 60_000,
  val maxContextLines: Int = 30,
  /** "Talk it over": rounds, relay caps, the 12-minute limit, round waits. */
  val talk: TalkLimits = TalkLimits(),
  /** @all: when the first answers clash, Juno starts a talk on the first disagreement by itself (once per message). */
  val autoTalk: Boolean = false,
  /**
   * Longest any one model call may take. Before M0.4 there was none: a stalled merge-step call (the claims call with
   * strict json_schema streams until OkHttp's 10-minute read timeout) left an @all at "Collecting answers".
   */
  val modelTimeoutMs: Long = 90_000,
  /** @all: after the map, Juno writes one merged answer from every answer (its own folded in). */
  val mergeAnswer: Boolean = true,
)

/**
 * Juno's own log: state transitions, sends and transport fallbacks, replies, merge/map/talk steps, model call errors.
 * [text] carries message text (the app drops or redacts it); [detail] never does.
 */
fun interface JunoTrace {
  fun event(runId: String?, name: String, detail: String, text: String?)
  companion object { val NONE = JunoTrace { _, _, _, _ -> } }
}

/** The run a coroutine works for, so model-call errors are logged with it. */
internal class RunTag(val runId: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<RunTag> }

/** A recent thread line given to Juno as context (author display name, text). */
data class ThreadLine(val author: String, val text: String)

/** Things the UI or the notification layer reacts to. The thread itself is always re-read from [Juno.view]. */
sealed interface RunUpdate {
  data class Changed(val runId: String) : RunUpdate
  data class Answer(val runId: String, val text: String) : RunUpdate
  data class FollowUpPosted(val runId: String, val agentId: String, val text: String) : RunUpdate
  data class NeedsUser(val runId: String, val line: NeedsUserLine) : RunUpdate
  data class MapUpdated(val runId: String, val map: AgreementMap) : RunUpdate
  data class Failed(val runId: String, val kind: LlmException.Kind?, val message: String) : RunUpdate
  /** A talk ended with Juno's wrap-up. Emitted once per talk: the app's one notification for it. */
  data class TalkDone(val runId: String, val text: String) : RunUpdate
  /** A peer message no ask claims (story 3): show it as a plain agent message. */
  data class Unsolicited(val agentId: String, val text: String, val at: Long) : RunUpdate
  /** A reply to a direct @mention: show it attributed, "replied directly". */
  data class DirectReply(val agentId: String, val text: String, val at: Long, val correlationId: String) : RunUpdate
}

sealed interface DriveResult {
  val view: RunView?
  data class Done(override val view: RunView) : DriveResult
  /** Waiting on the user (approval or a question). Call [Juno.approve] / [Juno.answer], then drive again. */
  data class WaitingOnUser(override val view: RunView) : DriveResult
  /** Only with inline=false: waiting on agents; drive again at [wakeAt] or on the next [Juno.ingest]. */
  data class Suspended(override val view: RunView, val wakeAt: Long) : DriveResult
  data class Failed(override val view: RunView?) : DriveResult
  /** Another coroutine is already driving this run. */
  data object Busy : DriveResult { override val view: RunView? = null }
}

/**
 * Juno, the main agent's brain. An event-driven state machine over persisted [RunState]s:
 *  - [startCoordinated] / [startAll] create a run; [drive] advances it (model calls, sends, waits) until it is done
 *    or needs the user. Every step is saved to [RunStore] before its effects, so after process death [recover] +
 *    [drive] continue where it stopped (sends are idempotent by ask id).
 *  - The app's event pump feeds every peer message and transport event to [ingest]. Waiting drivers wake on it.
 *  - [sweep] applies timeouts, quiet windows, queued sends and late follow-ups; the app calls it from a periodic
 *    job (WorkManager) as a backstop. [drive] sweeps by itself while it runs.
 */
class Juno(
  private val rosterProvider: () -> Roster,
  private val peerFor: (String) -> Peer?,
  private val llm: Llm,
  private val store: RunStore,
  private val scores: ScoreLog = InMemoryScoreLog(),
  private val clock: () -> Long = System::currentTimeMillis,
  val config: JunoConfig = JunoConfig(),
  private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
  /** What the user wants to be called ("Kush"); blank when unknown. Read on every model call, so a change applies at once. */
  private val userName: () -> String = { "" },
  private val trace: JunoTrace = JunoTrace.NONE,
) {
  constructor(roster: Roster, peers: Map<String, Peer>, llm: Llm, store: RunStore, scores: ScoreLog = InMemoryScoreLog(),
              clock: () -> Long = System::currentTimeMillis, config: JunoConfig = JunoConfig(), newId: () -> String = { UUID.randomUUID().toString().take(8) }) :
    this({ roster }, { peers[it] }, llm, store, scores, clock, config, newId)

  private val roster: Roster get() = rosterProvider()
  private val mutex = Mutex()
  private val cache = HashMap<String, RunState>()
  private val driving = HashSet<String>()
  private val version = MutableStateFlow(0L)
  private val _updates = MutableSharedFlow<RunUpdate>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val updates: SharedFlow<RunUpdate> = _updates.asSharedFlow()

  // ============================================================== public API

  /** An unaddressed message or @Juno (with must-ask peers when the user named some). Returns the run id; then [drive]. */
  suspend fun startCoordinated(text: String, mustAsk: List<AgentProfile> = emptyList(), context: List<ThreadLine> = emptyList(), summary: String? = null): String {
    val now = clock()
    val runId = "r" + newId()
    val items = buildList {
      if (summary != null || context.isNotEmpty()) {
        val ctx = buildString {
          summary?.let { append("Earlier in this thread (summary): ").append(it).append("\n\n") }
          if (context.isNotEmpty()) {
            append("Recent thread, oldest first:\n")
            context.takeLast(config.maxContextLines).forEach { append(it.author).append(": ").append(it.text.take(600)).append("\n") }
          }
        }
        add(Items.developer(ctx.trim()))
      }
      add(Items.developer(Planner.hint(text, roster, mustAsk)))
      add(Items.user(text))
    }
    val s = RunState(runId, RunMode.COORDINATE, text, mustAsk.map { it.id }, now, Phase.PLANNING, items)
    mutex.withLock { putL(s) }
    return runId
  }

  /** `@all`: the same [question], blind, to every agent in [agents] (+ Juno itself). Returns the run id; then [drive]. */
  suspend fun startAll(question: String, agents: List<AgentProfile> = roster.activePeers): String {
    val now = clock()
    val runId = "r" + newId()
    mutex.withLock {
      val busy = busyAgentsL()
      var n = 0
      val asks = buildList {
        if (config.includeMainInAll) add(AskState("$runId~a${n++}", roster.main.id, question, AskPurpose.BLIND, status = AskStatus.PENDING, createdAt = now, sentAt = now))
        agents.filter { it.kind != AgentKind.MAIN && !it.paused }.forEach { p ->
          val st = if (p.kind == AgentKind.TEXT && p.id in busy) AskStatus.QUEUED else AskStatus.SENDING
          add(AskState("$runId~a${n++}", p.id, question, AskPurpose.BLIND, status = st, createdAt = now))
        }
      }
      val wait = PendingCall("all-wait", PendingKind.WAIT, asks.map { it.id }, until = now + config.allWaitMs, startedAt = now)
      putL(RunState(runId, RunMode.ALL, question, emptyList(), now, Phase.WAITING, asks = asks, pending = listOf(wait)))
    }
    return runId
  }

  /** A direct @mention: no model, no run. Replies come back as [RunUpdate.DirectReply]. */
  suspend fun direct(agent: AgentProfile, text: String): PeerSend {
    val cid = "direct-" + newId()
    val peer = peerFor(agent.id) ?: return PeerSend.Failed("agent_unavailable")
    val r = try { peer.send(text, cid) } catch (e: CancellationException) { throw e } catch (e: Exception) { PeerSend.Failed("send_error") }
    val now = clock()
    scores.append(ScoreEvent.DirectSent(agent.id, now, viaOf(r)))
    if (r is PeerSend.NeedsUser) scores.append(ScoreEvent.NeededUser(agent.id, now, cid, "tap_to_send"))
    return r
  }

  /** Route a composer message: direct sends happen here; Juno and @all runs are started and returned for [drive]. */
  suspend fun submit(route: Route, context: List<ThreadLine> = emptyList()): Submitted = when (route) {
    is Route.Direct -> Submitted.DirectSent(route.agent, direct(route.agent, route.text))
    is Route.ToJuno -> Submitted.RunStarted(startCoordinated(route.text, route.mustAsk, context))
    is Route.FanOut -> Submitted.RunStarted(startAll(route.text, route.agents))
    is Route.Nothing -> Submitted.NotSent(route.reason)
  }

  sealed interface Submitted {
    data class DirectSent(val agent: AgentProfile, val result: PeerSend) : Submitted
    data class RunStarted(val runId: String) : Submitted
    data class NotSent(val reason: String) : Submitted
  }

  /**
   * Advance a run until it is done or needs the user. With [inline] (default) it also waits on agents, suspending on
   * events and deadlines (never polling the model). With inline=false it returns [DriveResult.Suspended] instead of
   * waiting: use that from a WorkManager job, which reschedules itself at wakeAt.
   */
  suspend fun drive(runId: String, inline: Boolean = true): DriveResult = withContext(RunTag(runId)) { driveIn(runId, inline) }

  private suspend fun driveIn(runId: String, inline: Boolean): DriveResult {
    mutex.withLock {
      if (getL(runId) == null) return DriveResult.Failed(null)
      if (!driving.add(runId)) return DriveResult.Busy
    }
    try {
      var steps = 0
      while (true) {
        val v0 = version.value
        if (steps > config.maxDriveSteps) {
          mutex.withLock { getL(runId)?.let { putL(finishFallbackL(it, "too_many_steps")) } }
        }
        flushSends(runId)
        val closes = mutex.withLock { sweepRunL(runId) }
        closePeers(closes)
        val s = mutex.withLock { getL(runId) } ?: return DriveResult.Failed(null)
        when (s.phase) {
          Phase.DONE -> return DriveResult.Done(viewOf(s))
          Phase.FAILED, Phase.CANCELLED -> return DriveResult.Failed(viewOf(s))
          Phase.AWAITING_USER -> return DriveResult.WaitingOnUser(viewOf(s))
          Phase.PLANNING -> { steps++; if (s.mode == RunMode.ROOM) roomPlan(runId) else if (s.unprocessed.isNotEmpty()) processCalls(runId) else modelTurn(runId) }
          Phase.MERGING -> { steps++; if (s.mode == RunMode.ROOM) roomMerge(runId) else merge(runId) }
          Phase.WAITING -> {
            val mainAsk = s.asks.firstOrNull { it.agentId == roster.main.id && it.status == AskStatus.PENDING }
            if (mainAsk != null) { answerAsMain(runId, mainAsk); continue }
            val wake = nextWake(s)
            if (!inline) return DriveResult.Suspended(viewOf(s), wake)
            val ms = (wake - clock()).coerceIn(1, config.maxSleepMs)
            withTimeoutOrNull(ms) { version.first { it != v0 } }
          }
        }
      }
    } finally {
      mutex.withLock { driving.remove(runId) }
    }
  }

  /** Feed one transport event. Safe from any thread/coroutine; cheap; never calls the model. */
  suspend fun ingest(signal: PeerSignal) {
    val events = mutableListOf<ScoreEvent>()
    val updates = mutableListOf<RunUpdate>()
    mutex.withLock {
      val now = clock()
      when (signal) {
        is PeerSignal.Message -> {
          val cid = signal.correlationId
          if (cid != null && cid.startsWith("direct-")) { updates += RunUpdate.DirectReply(signal.agentId, signal.text, signal.at, cid); return@withLock }
          val s = cid?.let { getL(runIdOf(it)) }
          val ask = s?.ask(cid)
          if (s == null || ask == null || ask.agentId != signal.agentId || ask.status == AskStatus.CANCELLED || ask.status == AskStatus.DECLINED) {
            trace.event(s?.runId, "ingest.unclaimed", "${roster.nameOf(signal.agentId)} cid=${cid ?: "none"}", signal.text)
            events += ScoreEvent.Unsolicited(signal.agentId, now)
            updates += RunUpdate.Unsolicited(signal.agentId, signal.text, signal.at)
            return@withLock
          }
          if (ask.replies.any { it.messageId == signal.messageId } || ask.statusNotes.any { it.messageId == signal.messageId }) return@withLock
          if (signal.status != null) {
            // m05: an agent's status text (Hark's "Looks like your signal dropped…" on the 2026-11-14 run was merged as
            // its answer) is a note on the ask, never a reply: the slot stays open until the soft timeout.
            trace.event(s.runId, "reply.status", "${ask.id} ${roster.nameOf(ask.agentId)} ${signal.status} (not an answer; ask stays ${ask.status})", signal.text)
            val note = ReplyPart(signal.messageId, signal.text, signal.at, signal.certainty, signal.late || ask.status.settled, signal.userInterjected)
            putL(s.replaceAsk(ask.copy(statusNotes = ask.statusNotes + note, sentAt = ask.sentAt ?: now)))
            updates += RunUpdate.Changed(s.runId)
            return@withLock
          }
          val late = signal.late || ask.status.settled
          val part = ReplyPart(signal.messageId, signal.text, signal.at, signal.certainty, late, signal.userInterjected,
            via = signal.via, verified = signal.verified, claims = signal.claims)
          if (ask.replies.isEmpty()) events += ScoreEvent.FirstReply(ask.agentId, now, ask.id, now - (ask.sentAt ?: ask.createdAt), signal.certainty, late)
          if (signal.via == Via.CONNECTOR) {
            events += ScoreEvent.ClaimsIn(ask.agentId, now, ask.id, signal.claims.size, signal.claims.sumOf { it.sources.size }, signal.verified)
            trace.event(s.runId, "reply.connector", "${ask.id} ${roster.nameOf(ask.agentId)} claims=${signal.claims.size} verified=${signal.verified} final=${signal.final}", null)
          }
          val replying = ask.copy(
            replies = ask.replies + part, status = AskStatus.REPLYING, lastReplyAt = now, settledAt = null,
            sentAt = ask.sentAt ?: now, afterMerge = ask.afterMerge || s.phase.terminal, followedUp = false,
          )
          // A connector result is the whole answer: no quiet window for more fragments.
          val updated = if (signal.final) settle(replying, AskStatus.ANSWERED, now, events) else replying
          putL(s.replaceAsk(updated))
          updates += RunUpdate.Changed(s.runId)
        }
        is PeerSignal.Closed -> {
          val s = getL(runIdOf(signal.correlationId)) ?: return@withLock
          val ask = s.ask(signal.correlationId) ?: return@withLock
          trace.event(s.runId, "transport.closed", "${ask.id} ${roster.nameOf(ask.agentId)} ${signal.outcome} while ${ask.status}", null)
          val next = when (signal.outcome) {
            CloseOutcome.COMPLETED -> if (ask.status == AskStatus.REPLYING) AskStatus.ANSWERED else null
            CloseOutcome.EXPIRED -> if (!ask.status.settled && ask.replies.isEmpty() && ask.status != AskStatus.AWAITING_APPROVAL) AskStatus.TIMED_OUT else null
            CloseOutcome.LIKELY_MISSED -> if (!ask.status.settled && ask.replies.isEmpty()) AskStatus.LIKELY_MISSED else null
            CloseOutcome.CLOSED_BY_CALLER -> null
          } ?: return@withLock
          val a2 = settle(ask, next, now, events)
          if (next == AskStatus.LIKELY_MISSED) {
            events += ScoreEvent.NeededUser(ask.agentId, now, ask.id, "look_in_messages")
            updates += RunUpdate.NeedsUser(s.runId, NeedsUserLine(NeedsUserKind.LOOK_IN_MESSAGES, "${roster.nameOf(ask.agentId)} may have answered in Messages", ask.agentId, ask.id, null))
          }
          putL(s.replaceAsk(a2))
          updates += RunUpdate.Changed(s.runId)
        }
        is PeerSignal.DraftSent -> markSentL(signal.correlationId, now)?.let { updates += RunUpdate.Changed(it) }
      }
    }
    events.forEach { scores.append(it) }
    updates.forEach { _updates.tryEmit(it) }
  }

  /** The user tapped "Tap to send" and sent the draft in Messages (when the transport can't see the echo itself). */
  suspend fun userSentDraft(askId: String) { mutex.withLock { markSentL(askId, clock()) } }

  /** The user's answer to a money/account ask. Then call [drive]. */
  suspend fun approve(runId: String, askId: String, approved: Boolean): Boolean {
    val ok = mutex.withLock {
      val s = getL(runId) ?: return@withLock false
      val a = s.ask(askId)?.takeIf { it.status == AskStatus.AWAITING_APPROVAL } ?: return@withLock false
      if (approved) {
        // The user took a while: give the waits that include this ask a fresh window.
        val until = clock() + (if (a.purpose == AskPurpose.ROOM) config.talk.roundWaitMs else config.maxWaitSeconds * 1000L)
        val room = s.room?.let { r -> if (a.shareFrom.isEmpty()) r else r.copy(shared = (r.shared + a.shareFrom.map { "$it>${a.agentId}" }).distinct()) }
        putL(recompute(s.replaceAsk(a.copy(status = AskStatus.SENDING, approved = true)).copy(room = room, pending = s.pending.map {
          if ((it.kind == PendingKind.WAIT || it.kind == PendingKind.ROOM_ROUND) && askId in it.askIds && it.output == null) it.copy(until = maxOf(it.until ?: 0, until)) else it
        })))
      }
      else if (a.purpose == AskPurpose.ROOM) {
        // "Don't share" / "No": drop the private (or gated) part. What's left still goes, if anything is left and it
        // passes the gate on its own.
        val events = mutableListOf<ScoreEvent>()
        val room = s.room?.let { r -> r.copy(notShared = (r.notShared + a.shareFrom.map { "$it>${a.agentId}" }).distinct()) }
        val rest = a.textWithoutShared?.takeIf { a.shareFrom.isNotEmpty() && !SendGate.check(a.quotedWithoutShared.map { Segment(it, Provenance.PEER_RELAY) }, userNameNow()).gated }
        val next = if (rest != null) a.copy(status = AskStatus.SENDING, text = rest, quoted = a.quotedWithoutShared, shareFrom = emptyList(), textWithoutShared = null, quotedWithoutShared = emptyList())
          else settle(a, AskStatus.DECLINED, clock(), events)
        putL(recompute(s.replaceAsk(next).copy(room = room)))
        events.forEach { scores.append(it) }
      }
      else {
        val events = mutableListOf<ScoreEvent>()
        val a2 = settle(a, AskStatus.DECLINED, clock(), events)
        putL(resolvePending(s.replaceAsk(a2), a.id,
          obj("ask_id" to JsonPrimitive(a.id), "status" to JsonPrimitive("declined"), "note" to JsonPrimitive("The user said no. Don't ask again; tell them briefly what you'd do instead, or stop.")).toString()))
      }
      true
    }
    if (!ok) return false
    if (mutex.withLock { getL(runId)?.mode } == RunMode.ROOM) { flushSends(runId); return true }
    if (approved) {
      flushSends(runId)
      mutex.withLock {
        val s = getL(runId) ?: return@withLock
        val a = s.ask(askId) ?: return@withLock
        putL(resolvePending(s, askId, askOutput(a, "Approved by the user and sent.")))
      }
    }
    return true
  }

  /** The user's answer to Juno's ask_user question (or, with none open, a mid-run message). Then call [drive]. */
  suspend fun answer(runId: String, text: String) {
    mutex.withLock {
      val s = getL(runId) ?: return
      if (s.mode == RunMode.ROOM) { roomSteerL(s, text); return }
      val q = s.pending.firstOrNull { it.kind == PendingKind.ASK_USER && it.output == null }
      if (q == null) { putL(s.copy(interjections = s.interjections + text)); return }
      val out = obj("answer" to JsonPrimitive(text)).toString()
      putL(recompute(s.copy(pending = s.pending.map { if (it.callId == q.callId) it.copy(output = out) else it })))
    }
  }

  /** The user wrote again while the run is open: the next wait ends early and Juno sees it. */
  suspend fun interject(runId: String, text: String) { answer(runId, text) }

  /** "Stop waiting": cancel the run's open asks and close their correlations. */
  suspend fun cancel(runId: String) {
    val closes = mutex.withLock {
      val s = getL(runId) ?: return
      val events = mutableListOf<ScoreEvent>()
      val open = s.asks.filter { !it.status.settled }
      val room = s.room?.takeIf { !s.phase.terminal }?.let { it.copy(stage = RoomStage.CLOSED, endReason = EndReason.USER_STOP) } ?: s.room
      putL(s.copy(phase = if (s.phase.terminal) s.phase else Phase.CANCELLED, room = room, pending = if (s.phase.terminal) s.pending else emptyList(),
        asks = s.asks.map { if (!it.status.settled) settle(it, AskStatus.CANCELLED, clock(), events) else it }))
      events.forEach { scores.append(it) }
      open.map { it.agentId to it.id }
    }
    closePeers(closes)
  }

  /** @all: send one disputed claim (only that claim and the other side's evidence) back to the agents involved, once. */
  suspend fun challenge(runId: String, disputeId: String): Boolean {
    val ok = mutex.withLock {
      val s = getL(runId) ?: return@withLock false
      val map = s.agreement ?: return@withLock false
      if (s.mode != RunMode.ALL || s.phase != Phase.DONE) return@withLock false
      val d = map.disputed.firstOrNull { it.id == disputeId && it.challenge == ChallengeState.NONE } ?: return@withLock false
      val now = clock()
      val busy = busyAgentsL()
      var n = s.asks.size
      val asks = d.sides.flatMapIndexed { i, side ->
        val other = d.sides.filterIndexed { j, _ -> j != i }.maxBy { it.agentIds.size }
        val ev = other.evidence.values.firstOrNull()?.let { "\nTheir reason: $it" } ?: ""
        val text = Prompts.render("challenge_ask", mapOf("other" to other.statement, "evidence" to ev, "mine" to side.statement))
        side.agentIds.map { agent ->
          val isMain = agent == roster.main.id
          val st = when { isMain -> AskStatus.PENDING; roster.byId(agent)?.kind == AgentKind.TEXT && agent in busy -> AskStatus.QUEUED; else -> AskStatus.SENDING }
          AskState("$runId~a${n++}", agent, text, AskPurpose.CHALLENGE, status = st, createdAt = now, sentAt = if (isMain) now else null, disputeId = d.id)
        }
      }
      val m2 = map.copy(status = MapStatus.CHALLENGING, disputed = map.disputed.map { if (it.id == d.id) it.copy(challenge = ChallengeState.ASKED) else it })
      val wait = PendingCall("challenge-${d.id}", PendingKind.CHALLENGE_WAIT, asks.map { it.id }, until = now + config.allWaitMs, startedAt = now)
      putL(s.copy(phase = Phase.WAITING, asks = s.asks + asks, pending = listOf(wait), agreement = m2))
      true
    }
    return ok
  }

  /** Load every live run after a restart. Drive each returned id (RunService/WorkManager). */
  suspend fun recover(): List<String> = mutex.withLock {
    store.live().forEach { cache[it.runId] = it }
    cache.values.filter { it.live }.sortedBy { it.createdAt }.map { it.runId }
  }

  /** Timeouts, quiet windows, queued sends, late follow-ups and @all re-merges for every live run in memory. */
  suspend fun sweep() {
    val ids = mutex.withLock { cache.values.filter { it.live }.map { it.runId } }
    for (id in ids) {
      flushSends(id)
      closePeers(mutex.withLock { sweepRunL(id) })
      val s = mutex.withLock { getL(id) } ?: continue
      if (s.phase == Phase.DONE && id !in mutex.withLock { driving.toSet() }) withContext(RunTag(id)) { lateWork(s) }
    }
  }

  suspend fun view(runId: String): RunView? = mutex.withLock { getL(runId) }?.let(::viewOf)
  suspend fun state(runId: String): RunState? = mutex.withLock { getL(runId) }
  suspend fun scoreboard(windowMs: Long = Scoreboard.WEEK_MS): List<AgentScore> = Scoreboard.compute(scores.since(clock() - windowMs), clock(), windowMs)

  // ============================================================== tools

  fun tools(): List<ToolSpec> {
    val names = roster.activePeers.map { it.name }
    return buildList {
      if (names.isNotEmpty()) {
        add(ToolSpec("ask_agent", "Send one agent a message written for it. Returns an ask_id. Replies come back through wait.",
          Schema.obj(
            "agent" to Schema.string("Which agent", names),
            "text" to Schema.string("The message for that agent: self-contained, short, only what it needs"),
            "why" to Schema.string("One short phrase: why this agent"),
            "money_or_account" to Schema.boolean("True if it buys, books, pays, orders, signs up, cancels, changes an account, or confirms one of those"),
          )))
        add(ToolSpec("wait", "Wait for replies to your asks. You are not called again until they are in or max_seconds pass.",
          Schema.obj(
            "ask_ids" to Schema.stringArray("Ask ids to wait for; empty means all open asks"),
            "max_seconds" to Schema.integer("Longest to wait, ${config.minWaitSeconds}-${config.maxWaitSeconds}"),
          )))
      }
      add(ToolSpec("ask_user", "Ask the user one short question when you can't go on without it. Offer your best guess.",
        Schema.obj("question" to Schema.string("The question, one line"))))
      add(ToolSpec("reply", "Post your answer to the user. Ends your turn.", Schema.obj("text" to Schema.string("Your answer, in your voice"))))
    }
  }

  fun instructions(): String = Prompts.render("juno_system", mapOf(
    "main" to roster.main.name, "roster" to Prompts.rosterBlock(roster),
    "maxAsks" to config.maxAsks.toString(), "maxRounds" to config.maxRounds.toString(),
    "userLine" to Prompts.userLine(runCatching { userName() }.getOrDefault("")),
  ))

  // ============================================================== model turn + calls

  private suspend fun modelTurn(runId: String) {
    val s = mutex.withLock {
      val s0 = getL(runId) ?: return
      if (s0.modelCalls >= config.maxModelCalls) { putL(finishFallbackL(s0, "model_call_limit")); return }
      val s1 = if (s0.interjections.isEmpty()) s0 else s0.copy(transcript = s0.transcript + s0.interjections.map { Items.user(it) }, interjections = emptyList())
      if (s1 !== s0) putL(s1)
      s1
    }
    val resp = callModel(runId, LlmRequest("juno", instructions(), s.transcript, tools())) ?: return
    mutex.withLock {
      val cur = getL(runId) ?: return
      val withOut = cur.copy(transcript = cur.transcript + Items.outputsOf(resp), modelCalls = cur.modelCalls + 1)
      if (resp.toolCalls.isEmpty()) {
        // Plain text instead of reply(): treat it as the answer.
        if (resp.text.isBlank()) putL(finishFallbackL(withOut, "empty_answer")) else putL(finishL(withOut, resp.text.trim(), false))
      } else putL(withOut.copy(unprocessed = resp.toolCalls.map { CallRecord(it.callId, it.name, it.arguments) }))
    }
  }

  /** Every model call: a time limit (a timeout is a TRANSIENT failure, never a silent hang) and errors logged. */
  private suspend fun model(req: LlmRequest): LlmResponse {
    val runId = coroutineContext[RunTag]?.runId
    val t0 = clock()
    try {
      return withTimeout(config.modelTimeoutMs) { llm.respond(req) }
    } catch (e: TimeoutCancellationException) {
      trace.event(runId, "model.error", "${req.purpose} timeout after ${config.modelTimeoutMs / 1000}s", null)
      throw LlmException(LlmException.Kind.TRANSIENT, "timeout")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val kind = (e as? LlmException)?.kind ?: LlmException.Kind.OTHER
      trace.event(runId, "model.error", "${req.purpose} $kind ${e.message ?: e.javaClass.simpleName} after ${(clock() - t0) / 1000}s", null)
      throw e
    }
  }

  /** One model call with one retry on a transient error. Null when it failed (the run is finished or failed). */
  private suspend fun callModel(runId: String, req: LlmRequest): LlmResponse? {
    var attempt = 0
    while (true) {
      try {
        return model(req)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val kind = (e as? LlmException)?.kind ?: LlmException.Kind.OTHER
        if (kind == LlmException.Kind.TRANSIENT && attempt++ == 0) { delay(2_000); continue }
        mutex.withLock {
          val s = getL(runId) ?: return null
          val failed = s.copy(error = e.message ?: kind.name, errorKind = kind)
          putL(if (failed.asks.any { it.replies.isNotEmpty() }) finishFallbackL(failed, "model_failed") else failed.copy(phase = Phase.FAILED))
        }
        _updates.tryEmit(RunUpdate.Failed(runId, kind, e.message ?: kind.name))
        return null
      }
    }
  }

  private suspend fun processCalls(runId: String) {
    // Step 1 (persisted): create the asks these calls ask for. Reprocessing after a crash reuses them by callId.
    mutex.withLock {
      val s = getL(runId) ?: return
      val now = clock()
      var asks = s.asks
      val busy = busyAgentsL()
      val events = mutableListOf<ScoreEvent>()
      val notes = mutableListOf<RunUpdate>()
      val roundAdds = if (s.unprocessed.any { it.name == "ask_agent" } && s.unprocessed.none { c -> s.asks.any { it.callId == c.callId } }) 1 else 0
      for (c in s.unprocessed.filter { it.name == "ask_agent" }) {
        if (asks.any { it.callId == c.callId }) continue
        val args = parseJsonObject(c.arguments) ?: continue
        val agent = args.str("agent")?.let { roster.resolve(it) } ?: continue
        val text = args.str("text")?.trim().orEmpty()
        if (askError(s.copy(asks = asks), agent, text, s.rounds + roundAdds) != null) continue
        val gate = ApprovalGate.check(text, args.bool("money_or_account") == true)
        val status = when {
          gate.gated -> AskStatus.AWAITING_APPROVAL
          agent.kind == AgentKind.TEXT && agent.id in busy -> AskStatus.QUEUED
          else -> AskStatus.SENDING
        }
        val a = AskState("$runId~a${asks.size}", agent.id, text, AskPurpose.TASK, c.callId, args.str("why"), status, now, gateReason = gate.reason)
        asks = asks + a
        if (gate.gated) {
          events += ScoreEvent.NeededUser(agent.id, now, a.id, "approval")
          notes += RunUpdate.NeedsUser(runId, NeedsUserLine(NeedsUserKind.APPROVE, "Okay to have ${agent.name} do this?", agent.id, a.id, null, text))
        }
      }
      putL(s.copy(asks = asks))
      events.forEach { scores.append(it) }
      notes.forEach { _updates.tryEmit(it) }
    }
    // Step 2: send (concurrently, idempotent by ask id).
    flushSends(runId)
    // Step 3 (persisted atomically): outputs, pending calls, final answer.
    mutex.withLock {
      val s = getL(runId) ?: return
      val now = clock()
      val outputs = mutableListOf<JsonObject>()
      val pending = s.pending.toMutableList()
      var final: String? = null
      var nudged = s.nudgedMustAsk
      val createdNow = s.asks.filter { a -> a.callId != null && s.unprocessed.any { it.callId == a.callId } }
      val roundAdds = if (s.unprocessed.any { it.name == "ask_agent" }) 1 else 0
      for (c in s.unprocessed) {
        val args = parseJsonObject(c.arguments)
        fun out(text: String) { outputs += Items.functionCallOutput(c.callId, text) }
        fun err(code: String, detail: String) = out(obj("error" to JsonPrimitive(code), "detail" to JsonPrimitive(detail)).toString())
        if (args == null) { err("invalid_arguments", "arguments were not a JSON object"); continue }
        when (c.name) {
          "ask_agent" -> {
            val a = s.asks.firstOrNull { it.callId == c.callId }
            if (a == null) {
              val agent = args.str("agent")?.let { roster.resolve(it) }
              val text = args.str("text")?.trim().orEmpty()
              val e = if (agent == null) "unknown_agent" to "Ask one of: ${roster.activePeers.joinToString { it.name }}."
                else askError(s, agent, text, s.rounds + roundAdds) ?: ("not_sent" to "Not sent.")
              err(e.first, e.second)
            } else if (a.status == AskStatus.AWAITING_APPROVAL) {
              pending += PendingCall(c.callId, PendingKind.APPROVAL, listOf(a.id), startedAt = now)
            } else out(askOutput(a, null))
          }
          "wait" -> {
            val wanted = args.strings("ask_ids").flatMap { ref ->
              s.asks.filter { it.id == ref }.ifEmpty { roster.resolve(ref)?.let { ag -> s.asks.filter { it.agentId == ag.id && !it.status.settled } }.orEmpty() }
            }.map { it.id }.distinct().ifEmpty { s.asks.filter { !it.status.settled || it.id in createdNow.map { c2 -> c2.id } }.map { it.id } }
            if (wanted.isEmpty()) err("nothing_to_wait_on", "No open asks. Reply to the user.")
            else {
              val secs = (args.int("max_seconds") ?: config.maxWaitSeconds).coerceIn(config.minWaitSeconds, config.maxWaitSeconds)
              pending += PendingCall(c.callId, PendingKind.WAIT, wanted, until = now + secs * 1000L, startedAt = now)
            }
          }
          "ask_user" -> {
            val q = args.str("question")?.trim().orEmpty()
            if (q.isEmpty()) err("invalid_arguments", "question is empty")
            else {
              pending += PendingCall(c.callId, PendingKind.ASK_USER, question = q, startedAt = now)
              _updates.tryEmit(RunUpdate.NeedsUser(runId, NeedsUserLine(NeedsUserKind.ANSWER_QUESTION, q, null, null, c.callId)))
            }
          }
          "reply" -> {
            val text = args.str("text")?.trim().orEmpty()
            val missing = s.mustAsk.filter { id -> s.asks.none { it.agentId == id } }
            when {
              text.isEmpty() -> err("invalid_arguments", "text is empty")
              createdNow.any { !it.status.settled && it.status != AskStatus.AWAITING_APPROVAL } ->
                err("wait_first", "You just asked ${Views.names(createdNow.map { roster.nameOf(it.agentId) }.distinct())}. Call wait before replying.")
              missing.isNotEmpty() && !nudged -> {
                nudged = true
                err("must_ask", "The user asked you to include ${Views.names(missing.map { roster.nameOf(it) })}. Ask them before replying.")
              }
              else -> { final = text; out(obj("status" to JsonPrimitive("posted")).toString()) }
            }
          }
          else -> err("unknown_tool", c.name)
        }
      }
      val base = s.copy(transcript = s.transcript + outputs, unprocessed = emptyList(), pending = pending, nudgedMustAsk = nudged, rounds = s.rounds + roundAdds)
      putL(if (final != null) finishL(base.copy(pending = emptyList()), final!!, false) else recompute(base))
    }
  }

  /** Why an ask can't go out, or null. */
  private fun askError(s: RunState, agent: AgentProfile, text: String, roundsAfter: Int): Pair<String, String>? = when {
    agent.kind == AgentKind.MAIN -> "unknown_agent" to "That's you. Ask one of: ${roster.activePeers.joinToString { it.name }}."
    agent.paused -> "agent_paused" to "${agent.name} is paused by the user."
    peerFor(agent.id) == null -> "agent_unavailable" to "${agent.name} can't be reached right now."
    text.isEmpty() -> "invalid_arguments" to "text is empty"
    s.asks.count { it.purpose == AskPurpose.TASK } >= config.maxAsks -> "ask_limit" to "That's ${config.maxAsks} asks. Reply with what you have."
    roundsAfter > config.maxRounds -> "round_limit" to "That's ${config.maxRounds} rounds. Reply with what you have."
    s.asks.any { it.agentId == agent.id && (it.status.inFlight || it.status == AskStatus.QUEUED) } ->
      "agent_busy" to "${agent.name} is still on your earlier ask (${s.asks.last { it.agentId == agent.id }.id}). Wait for it first."
    else -> null
  }

  private fun askOutput(a: AskState, note: String?): String {
    val status = when (a.status) {
      AskStatus.PENDING, AskStatus.REPLYING, AskStatus.ANSWERED -> "sent"
      AskStatus.DRAFTED -> "draft_opened"
      AskStatus.NEEDS_TAP -> "needs_user_tap"
      AskStatus.QUEUED -> "queued"
      AskStatus.FAILED -> "failed"
      else -> a.status.name.lowercase()
    }
    val n = note ?: when (a.status) {
      AskStatus.NEEDS_TAP -> "The user has to tap Send for ${roster.nameOf(a.agentId)}; it may take a while."
      AskStatus.DRAFTED -> "Messages is open with the text; it goes when the user taps Send."
      AskStatus.QUEUED -> "${roster.nameOf(a.agentId)} is busy with another request; this goes out right after."
      AskStatus.FAILED -> "Couldn't send (${a.failReason ?: "error"}). Don't retry; answer without it."
      else -> null
    }
    return buildJsonObject {
      put("ask_id", a.id); put("agent", roster.nameOf(a.agentId)); put("status", status)
      n?.let { put("note", it) }
    }.toString()
  }

  private fun waitOutput(s: RunState, ids: List<String>, now: Long, startedAt: Long): String = buildJsonObject {
    put("asks", buildJsonArray {
      ids.mapNotNull { s.ask(it) }.forEach { a ->
        add(buildJsonObject {
          put("ask_id", a.id); put("agent", roster.nameOf(a.agentId))
          put("status", when (a.status) {
            AskStatus.ANSWERED -> "answered"; AskStatus.REPLYING -> "partial"
            AskStatus.TIMED_OUT, AskStatus.FAILED -> "no_answer"; AskStatus.LIKELY_MISSED -> "maybe_answered_in_messages"
            AskStatus.NEEDS_TAP, AskStatus.DRAFTED -> "waiting_for_user_tap"; AskStatus.DECLINED -> "declined_by_user"
            AskStatus.AWAITING_APPROVAL -> "waiting_for_user_approval"; AskStatus.CANCELLED -> "cancelled"
            else -> "still_working"
          })
          if (a.replies.isNotEmpty()) put("reply", a.replyText)
          // C2: an answer over the connector carries its claims with confidence and sources; use them in the merge.
          if (a.claims.isNotEmpty()) put("claims", Claims.json(a.claims))
          if (a.replies.isNotEmpty()) put("via", if (a.verified) "connector_verified" else "text")
          val notes = buildList {
            if (a.uncertain) add("Uncertain: the user also typed in that chat, so this may not answer your message.")
            if (a.late) add("Arrived late.")
            if (a.status == AskStatus.REPLYING) add("May still be sending more.")
            if (a.status == AskStatus.LIKELY_MISSED) add("The reply was probably shown inside Messages where Atall can't read it.")
            a.statusNotes.lastOrNull()?.let { add("Only sent a status text so far, not an answer: \"${it.text.take(160)}\".") }
          }
          if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
        })
      }
    })
    if (s.interjections.isNotEmpty()) put("user_added", JsonArray(s.interjections.map { JsonPrimitive(it) }))
    put("waited_seconds", (now - startedAt) / 1000)
  }.toString()

  // ============================================================== sends, sweeping, waking

  private suspend fun flushSends(runId: String) {
    val gated = mutableListOf<RunUpdate>()
    val toSend = mutex.withLock {
      val s = getL(runId) ?: return@withLock emptyList()
      val sending = s.asks.filter { it.status == AskStatus.SENDING && it.agentId != roster.main.id }
      // The send gate (backstop): only the user's own words may carry buy / book / "go ahead" to an agent.
      val blocked = sending.filter { SendGate.check(segmentsOf(it), userNameNow()).gated }
      if (blocked.isNotEmpty()) {
        val events = mutableListOf<ScoreEvent>()
        putL(blocked.fold(s) { acc, a -> acc.replaceAsk(settle(a.copy(failReason = "needs_your_ok"), AskStatus.FAILED, clock(), events)) })
        events.forEach { scores.append(it) }
        gated += RunUpdate.Changed(runId)
      }
      sending - blocked.toSet()
    }
    gated.forEach { _updates.tryEmit(it) }
    if (toSend.isEmpty()) return
    val results = coroutineScope {
      toSend.map { a ->
        async {
          val peer = peerFor(a.agentId)
          a to (if (peer == null) PeerSend.Failed("agent_unavailable") else try { peer.send(a.text, a.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { PeerSend.Failed("send_error") })
        }
      }.awaitAll()
    }
    val events = mutableListOf<ScoreEvent>()
    val notes = mutableListOf<RunUpdate>()
    mutex.withLock {
      var s = getL(runId) ?: return
      val now = clock()
      for ((a0, r) in results) {
        val cur = s.ask(a0.id) ?: continue
        // A reply can land before the send call returns (it moved the ask on); still record the transport it went by.
        if (cur.status != AskStatus.SENDING) { if (cur.via == null && r is PeerSend.Sent) s = s.replaceAsk(cur.copy(via = r.via)); continue }
        val a = cur
        val prof = roster.byId(a.agentId)
        fun sentAt(at: Long) = a.copy(sentAt = a.sentAt ?: at, softDeadline = at + soft(prof), hardDeadline = at + hard(prof))
        val next = when (r) {
          is PeerSend.Sent -> sentAt(r.at).copy(status = AskStatus.PENDING, via = r.via)
          is PeerSend.Duplicate -> sentAt(r.firstAt).copy(status = AskStatus.PENDING)
          is PeerSend.Drafted -> sentAt(r.at).copy(status = AskStatus.DRAFTED)
          is PeerSend.NeedsUser -> a.copy(status = AskStatus.NEEDS_TAP, draftText = r.draftText, draftNumber = r.number)
          is PeerSend.RateLimited -> settle(a.copy(failReason = "rate_limited"), AskStatus.FAILED, now, events)
          is PeerSend.Failed -> settle(a.copy(failReason = r.reason), AskStatus.FAILED, now, events)
        }
        trace.event(runId, "send", "${a.id} ${roster.nameOf(a.agentId)} " + when (r) {
          is PeerSend.Failed -> "failed:${r.reason}"; is PeerSend.RateLimited -> "rate_limited"; is PeerSend.NeedsUser -> "needs_tap (no live reply action)"
          is PeerSend.Drafted -> "drafted"; is PeerSend.Duplicate -> "duplicate"; is PeerSend.Sent -> if (r.via == Via.CONNECTOR) "sent (connector)" else "sent"
        }, null)
        if (r !is PeerSend.Duplicate) events += ScoreEvent.AskSent(a.agentId, now, a.id, viaOf(r), a.purpose)
        if (r is PeerSend.NeedsUser) {
          events += ScoreEvent.NeededUser(a.agentId, now, a.id, "tap_to_send")
          notes += RunUpdate.NeedsUser(runId, NeedsUserLine(NeedsUserKind.TAP_TO_SEND, "Tap to send to ${roster.nameOf(a.agentId)}", a.agentId, a.id, null, r.draftText))
        }
        s = s.replaceAsk(next)
      }
      putL(s)
    }
    events.forEach { scores.append(it) }
    notes.forEach { _updates.tryEmit(it) }
  }

  /** Time-based transitions for one run. Returns (agentId, askId) correlations to close at the transport. */
  private suspend fun sweepRunL(runId: String): List<Pair<String, String>> {
    val s0 = getL(runId) ?: return emptyList()
    val now = clock()
    val events = mutableListOf<ScoreEvent>()
    val closes = mutableListOf<Pair<String, String>>()
    val busy = busyAgentsL(exceptRun = runId).toMutableSet()
    s0.asks.filter { it.status.inFlight && roster.byId(it.agentId)?.kind == AgentKind.TEXT }.forEach { busy += it.agentId }
    val asks = s0.asks.map { a ->
      when {
        a.status == AskStatus.REPLYING && now >= a.lastReplyAt + config.quietWindowMs -> settle(a, AskStatus.ANSWERED, now, events)
        a.status in setOf(AskStatus.PENDING, AskStatus.DRAFTED) && a.hardDeadline != null && now >= a.hardDeadline -> {
          closes += a.agentId to a.id; settle(a, AskStatus.TIMED_OUT, now, events)
        }
        a.status == AskStatus.NEEDS_TAP && now >= a.createdAt + hard(roster.byId(a.agentId)) -> settle(a, AskStatus.TIMED_OUT, now, events)
        a.status == AskStatus.QUEUED && a.agentId !in busy -> { busy += a.agentId; a.copy(status = AskStatus.SENDING) }
        else -> a
      }
    }
    var s = s0.copy(asks = asks)
    // Resolve waits whose asks are all settled, whose time is up, or that the user interrupted.
    val pend = s.pending.map { p ->
      if (p.output == null && p.kind == PendingKind.ROOM_ROUND) roomRoundDone(s, p, now)?.let { p.copy(output = it) } ?: p
      else if (p.output != null || (p.kind != PendingKind.WAIT && p.kind != PendingKind.CHALLENGE_WAIT)) p
      else {
        val all = p.askIds.mapNotNull { s.ask(it) }
        // m05: a wait's own time (the model's max_seconds) never ends it while an agent it waits on is still within
        // its soft timeout with nothing in yet: on 2026-11-14 a 120 s wait dropped kDot 60 s early.
        val timeUp = p.until != null && now >= p.until && (p.kind != PendingKind.WAIT || all.none { holdsSlot(it, now) })
        val done = all.all { it.status.settled } || timeUp || (s.mode == RunMode.COORDINATE && s.interjections.isNotEmpty())
        if (done) p.copy(output = if (s.mode == RunMode.COORDINATE) waitOutput(s, p.askIds, now, p.startedAt) else "done") else p
      }
    }
    val consumed = s.mode == RunMode.COORDINATE && pend.zip(s.pending).any { (a, b) -> a.output != null && b.output == null && a.kind == PendingKind.WAIT } && s.interjections.isNotEmpty()
    s = recompute(s.copy(pending = pend, interjections = if (consumed) emptyList() else s.interjections))
    if (s != s0) putL(s)
    events.forEach { scores.append(it) }
    return closes
  }

  /** When every pending call has its output: append them and move on; otherwise set the waiting phase. */
  private fun recompute(s: RunState): RunState {
    if (s.phase.terminal || s.pending.isEmpty()) return s
    if (s.pending.all { it.output != null }) {
      return when (s.mode) {
        RunMode.COORDINATE -> s.copy(transcript = s.transcript + s.pending.map { Items.functionCallOutput(it.callId, it.output!!) }, pending = emptyList(), phase = Phase.PLANNING)
        RunMode.ALL -> s.copy(pending = emptyList(), phase = Phase.MERGING)
        RunMode.ROOM -> {
          // Close the round now, with why (everyone in, its deadline, the user's words, a wrap-up, the 12 minutes).
          val why = s.pending.firstOrNull { it.kind == PendingKind.ROOM_ROUND }?.output
          val now = clock()
          val room = s.room?.let { r -> r.copy(rounds = r.rounds.map { if (it.closedAt == null) it.copy(closedAt = now, closeReason = why) else it }) }
          s.copy(pending = emptyList(), phase = Phase.MERGING, room = room)
        }
      }
    }
    val open = s.pending.filter { it.output == null }
    if (s.mode == RunMode.ROOM) {
      // A round waits on the user only when everything else in it is in and a relay is held for their tap.
      val held = open.flatMap { it.askIds }.mapNotNull { s.ask(it) }
      val waitingOnUser = held.any { it.status == AskStatus.AWAITING_APPROVAL } && held.all { it.status.settled || it.status == AskStatus.AWAITING_APPROVAL }
      return s.copy(phase = if (waitingOnUser) Phase.AWAITING_USER else Phase.WAITING)
    }
    return s.copy(phase = if (open.any { it.kind == PendingKind.APPROVAL || it.kind == PendingKind.ASK_USER }) Phase.AWAITING_USER else Phase.WAITING)
  }

  private fun resolvePending(s: RunState, askId: String, output: String): RunState =
    recompute(s.copy(pending = s.pending.map { if (it.kind == PendingKind.APPROVAL && askId in it.askIds && it.output == null) it.copy(output = output) else it }))

  /**
   * An ask that keeps its agent's slot open past a wait's own deadline: sent, nothing but status texts in, and still
   * within its soft timeout; or mid-answer (its quiet window settles it). A notification event (APP_CANCEL) or a
   * status text never shortens this.
   */
  private fun holdsSlot(a: AskState, now: Long): Boolean =
    a.status == AskStatus.REPLYING ||
      ((a.status == AskStatus.PENDING || a.status == AskStatus.DRAFTED) && a.replies.isEmpty() && a.softDeadline != null && now < a.softDeadline)

  private fun nextWake(s: RunState): Long {
    val now = clock()
    val times = buildList {
      // A WAIT whose own time passed but is held open by a slow agent wakes at that agent's soft timeout instead.
      s.pending.filter { it.output == null }.filter { it.kind != PendingKind.WAIT || (it.until ?: 0) > now }.mapNotNull { it.until }.forEach { add(it) }
      s.pending.filter { it.output == null && it.kind == PendingKind.WAIT }.flatMap { it.askIds }.mapNotNull { s.ask(it) }
        .filter { holdsSlot(it, now) }.forEach { add(it.softDeadline!!) }
      s.room?.takeIf { it.stage == RoomStage.ROUND_OPEN }?.let { add(it.startedAt + config.talk.maxMs) }
      s.asks.forEach { a ->
        if (a.status == AskStatus.REPLYING) add(a.lastReplyAt + config.quietWindowMs)
        if (!a.status.settled) a.hardDeadline?.let { add(it) }
      }
    }
    return (times.minOrNull() ?: (now + config.maxSleepMs)).coerceAtMost(now + config.maxSleepMs)
  }

  private suspend fun markSentL(askId: String, now: Long): String? {
    val s = getL(runIdOf(askId)) ?: return null
    val a = s.ask(askId)?.takeIf { it.status == AskStatus.NEEDS_TAP || it.status == AskStatus.DRAFTED } ?: return null
    val prof = roster.byId(a.agentId)
    val sentAt = if (a.status == AskStatus.DRAFTED) a.sentAt ?: now else now
    putL(s.replaceAsk(a.copy(status = if (a.replies.isEmpty()) AskStatus.PENDING else AskStatus.REPLYING, sentAt = sentAt, softDeadline = now + soft(prof), hardDeadline = now + hard(prof))))
    return s.runId
  }

  private suspend fun closePeers(list: List<Pair<String, String>>) {
    for ((agent, id) in list) try { peerFor(agent)?.close(id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
  }

  // ============================================================== finishing, follow-ups, @all

  private fun finishL(s: RunState, text: String, fallback: Boolean): RunState {
    val now = clock()
    _updates.tryEmit(RunUpdate.Answer(s.runId, text))
    return s.copy(phase = Phase.DONE, answer = text, answeredAt = now, fallback = fallback, unprocessed = emptyList(), pending = emptyList())
  }

  /** Answer with what came back, without the model (it failed, looped or hit a cap). */
  private fun finishFallbackL(s: RunState, why: String): RunState {
    val answered = s.asks.filter { it.replies.isNotEmpty() && it.agentId != roster.main.id }
    val missing = s.asks.filter { it.replies.isEmpty() && it.agentId != roster.main.id }.map { roster.nameOf(it.agentId) }.distinct()
    val text = if (answered.isEmpty()) "I couldn't finish that one. Want me to try again?"
    else buildString {
      append("Here's what came back:\n")
      answered.forEach { append("\n").append(roster.nameOf(it.agentId)).append(": ").append(it.replyText.trim()).append("\n") }
      if (missing.isNotEmpty()) append("\n").append(Views.names(missing)).append(if (missing.size == 1) " didn't answer yet." else " didn't answer yet.")
    }.trim()
    return finishL(s.copy(error = s.error ?: why), text, true)
  }

  private suspend fun lateWork(s: RunState) {
    val late = s.asks.filter { it.afterMerge && it.status == AskStatus.ANSWERED && !it.followedUp && it.replies.isNotEmpty() }
    if (late.isEmpty()) return
    if (s.mode == RunMode.ROOM) {
      // A talk is over once Juno wrapped up: a late reply stays in the talk's detail, with no new turn or notification.
      mutex.withLock { getL(s.runId)?.let { cur -> putL(cur.copy(asks = cur.asks.map { a -> if (late.any { it.id == a.id }) a.copy(followedUp = true) else a })) } }
      return
    }
    if (s.mode == RunMode.ALL) {
      mutex.withLock { getL(s.runId)?.let { cur -> putL(cur.copy(asks = cur.asks.map { a -> if (late.any { it.id == a.id }) a.copy(followedUp = true) else a }, phase = Phase.MERGING)) } }
      merge(s.runId)
      return
    }
    for (a in late) {
      val name = roster.nameOf(a.agentId)
      val req = LlmRequest("late", Prompts.render("late_followup", mapOf("main" to roster.main.name)), listOf(
        Items.developer("Your earlier answer:\n${s.answer.orEmpty()}"),
        Items.user("The user's message: ${s.userText}\n\n$name was asked: ${a.text}\n\n$name's late reply:\n${a.replyText}"),
      ))
      val text = try { model(req).text.trim().ifEmpty { null } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        ?: "$name's answer is in: ${Views.preview(a.replyText)}"
      mutex.withLock {
        val cur = getL(s.runId) ?: return
        putL(cur.copy(asks = cur.asks.map { if (it.id == a.id) it.copy(followedUp = true) else it }, followUps = cur.followUps + FollowUp(a.agentId, text, clock())))
      }
      _updates.tryEmit(RunUpdate.FollowUpPosted(s.runId, a.agentId, text))
    }
  }

  /** @all and challenge: Juno answers the question (or the challenge) itself, blind, with no tools. */
  private suspend fun answerAsMain(runId: String, ask: AskState) {
    val req = LlmRequest("blind", Prompts.render("juno_blind", mapOf("main" to roster.main.name)), listOf(Items.user(ask.text)))
    val text = try { model(req).text.trim() } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
    mutex.withLock {
      val s = getL(runId) ?: return
      val a = s.ask(ask.id) ?: return
      val now = clock()
      val events = mutableListOf<ScoreEvent>()
      val next = if (text.isEmpty()) settle(a, AskStatus.FAILED, now, events)
      else settle(a.copy(replies = listOf(ReplyPart("main-${a.id}", text, now)), lastReplyAt = now), AskStatus.ANSWERED, now, events)
      putL(s.replaceAsk(next))
    }
  }

  private suspend fun merge(runId: String) {
    val s = mutex.withLock { getL(runId) } ?: return
    val challenged = s.agreement?.disputed?.firstOrNull { it.challenge == ChallengeState.ASKED }
    val map0 = if (challenged != null) resolveChallenge(s, challenged) else compare(s)
    val map = if (challenged != null && map0.asked.isEmpty()) map0.copy(asked = s.agreement?.asked.orEmpty()) else map0
    // One merged answer from every answer (Juno's own blind answer is folded in, never shown on its own).
    val merged = if (config.mergeAnswer) mergedAnswer(s, map) else null
    mutex.withLock {
      val cur = getL(runId) ?: return
      putL(cur.copy(agreement = map, phase = Phase.DONE, answer = merged ?: map.verdict?.line, fallback = config.mergeAnswer && merged == null, answeredAt = clock()))
    }
    _updates.tryEmit(RunUpdate.MapUpdated(runId, map))
    if (challenged == null && config.autoTalk && s.mode == RunMode.ALL && s.room == null) {
      map.disputed.firstOrNull()?.let { if (talkOver(runId, it.id, TalkTrigger.CLASH)) return }
    }
    if (challenged == null && config.autoChallenge) map.disputed.firstOrNull()?.let { challenge(runId, it.id) }
  }

  /**
   * @all's final answer: the question, every answer word for word (Juno's own labelled as its first take), the
   * comparison, and the questions they asked the user. Juno keeps the agents' specifics, asks nothing an agent already
   * knows, and ends with at most one short combined question. Null when the model fails (the fallback is used).
   */
  private suspend fun mergedAnswer(s: RunState, map: AgreementMap): String? {
    val answers = s.asks.filter { (it.purpose == AskPurpose.BLIND || it.purpose == AskPurpose.CHALLENGE) && it.replies.isNotEmpty() }
    if (answers.isEmpty()) return null
    val input = buildString {
      append("Question: ").append(s.userText).append("\n")
      answers.groupBy { it.agentId }.forEach { (agent, asks) ->
        val who = if (agent == roster.main.id) "Your own first take (${roster.main.name}, written without the agents' context)" else roster.nameOf(agent)
        append("\n").append(who).append(":\n")
        asks.forEach { append(it.replyText.trim()).append("\n") }
        asks.flatMap { it.claims }.takeIf { it.isNotEmpty() }?.let { append("Its claims (with confidence and sources):\n").append(Claims.lines(it)).append("\n") }
      }
      val c = Agreement.Clusters(map.agreed, map.disputed, map.unique)
      append("\nComparison:\n").append(Agreement.describe(s.userText, c, roster::nameOf)).append("\n")
      if (map.asked.isNotEmpty()) {
        append("\nQuestions the agents asked Kush (combine the ones still open into one short question at the end; drop any an agent's answer already settles):\n")
        map.asked.forEach { (agent, qs) -> qs.forEach { q -> append("- ").append(roster.nameOf(agent)).append(": ").append(q).append("\n") } }
      }
    }
    trace.event(s.runId, "merge", "answers=${answers.size} asked=${map.asked.values.sumOf { it.size }}", null)
    return try {
      model(LlmRequest("merge", Prompts.render("juno_merge", mapOf("main" to roster.main.name, "userLine" to Prompts.userLine(userNameNow()))), listOf(Items.user(input))))
        .text.trim().ifEmpty { null }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
      ?: fallbackMerge(s, map, answers)
  }

  /** The merge without the model: the verdict line, each agent's opening line, and one combined question. */
  private fun fallbackMerge(s: RunState, map: AgreementMap, answers: List<AskState>): String {
    val peers = answers.filter { it.agentId != roster.main.id }.distinctBy { it.agentId }
    return buildString {
      map.verdict?.line?.let { append(it).append("\n\n") }
      peers.forEach { a -> append("**").append(roster.nameOf(a.agentId)).append(":** ").append(Relay.trim(firstSentence(a.replyText), 220)).append("\n") }
      combinedQuestion(map)?.let { append("\n").append(it) }
    }.trim().ifEmpty { "Here's what came back. Tap Show every answer to read them." }
  }

  private fun firstSentence(t: String): String {
    val one = t.trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()
    val m = Regex("^(.+?[.!])\\s").find(one + " ")
    return (m?.groupValues?.get(1) ?: one).trim()
  }

  /** Up to two still-open things the agents asked about, as one short question. Peers' questions first. */
  internal fun combinedQuestion(map: AgreementMap): String? =
    Questions.combine(map.asked.filterKeys { it != roster.main.id }.values.flatten() + map.asked[roster.main.id].orEmpty())

  private suspend fun compare(s: RunState): AgreementMap {
    val answers = s.asks.filter { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }
    return compareAnswers(s, answers.map { RawAnswer(it.agentId, roster.nameOf(it.agentId), it.replyText, it.uncertain, it.late) }, verdictCall = true)
  }

  /** Claims → clusters → verdict over [raw] (the blind answers, or in a talk each agent's latest position). */
  private suspend fun compareAnswers(s: RunState, raw: List<RawAnswer>, verdictCall: Boolean): AgreementMap {
    val base = AgreementMap(s.userText, MapStatus.READY, Views.participants(s, roster), raw = raw)
    if (raw.size < 2) {
      return base.copy(verdict = Verdict(VerdictLevel.TOO_FEW, Agreement.fallbackLine(VerdictLevel.TOO_FEW, null, roster::nameOf), 0, 0, 0))
    }
    val labels = raw.mapIndexed { i, a -> Agreement.label(i) to a.agentId }.toMap()
    val input = buildString {
      append("Question: ").append(s.userText).append("\n")
      raw.forEachIndexed { i, a ->
        append("\nAnswer ").append(Agreement.label(i)).append(":\n").append(a.text.trim()).append("\n")
        // C2: claims the agent posted with its answer (connector): extract from these first, keep their sources as evidence.
        claimsOf(s, a.agentId).takeIf { it.isNotEmpty() }?.let { append("Claims it posted (with confidence and sources):\n").append(Claims.lines(it)).append("\n") }
      }
    }
    val parsed = try {
      Agreement.parse(model(LlmRequest("claims", Prompts.render("claims_extract"), listOf(Items.user(input)), jsonSchema = Agreement.claimsSchema, schemaName = "claims")).text, labels)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    val clusters = parsed?.let { Agreement.clusterWithPicks(it) }
    if (parsed == null) trace.event(s.runId, "map.claims", "unusable: comparing raw answers only", null)
    else trace.event(s.runId, "map.claims", "decision='${parsed.decision}' picks=" + parsed.answers.joinToString { "${roster.nameOf(it.agentId)}:${it.pickKind}" } +
      " disputed=${clusters?.disputed?.size}", null)
    val asked = parsed?.answers.orEmpty().filter { it.asks.isNotEmpty() }.associate { it.agentId to it.asks }
    return withVerdict(base.copy(agreed = clusters?.agreed.orEmpty(), disputed = clusters?.disputed.orEmpty(), unique = clusters?.unique.orEmpty(), asked = asked), raw.size, clusters == null, verdictCall)
  }

  /** The structured claims [agentId] posted for this run's blind/talk asks (connector answers), newest ask last. */
  private fun claimsOf(s: RunState, agentId: String): List<SourcedClaim> =
    s.asks.filter { it.agentId == agentId && (it.purpose == AskPurpose.BLIND || it.purpose == AskPurpose.ROOM || it.purpose == AskPurpose.CHALLENGE) }
      .lastOrNull { it.claims.isNotEmpty() }?.claims.orEmpty()

  private suspend fun withVerdict(map: AgreementMap, answered: Int, unclear: Boolean, verdictCall: Boolean = true): AgreementMap {
    val c = if (unclear) null else Agreement.Clusters(map.agreed, map.disputed, map.unique)
    val level = Agreement.level(answered, c)
    val line = if (!verdictCall || c == null || level == VerdictLevel.TOO_FEW || level == VerdictLevel.UNCLEAR) Agreement.fallbackLine(level, c, roster::nameOf)
    else try {
      model(LlmRequest("verdict", Prompts.render("verdict", mapOf("main" to roster.main.name)), listOf(Items.user(Agreement.describe(map.question, c, roster::nameOf))))).text.trim()
        .ifEmpty { Agreement.fallbackLine(level, c, roster::nameOf) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Agreement.fallbackLine(level, c, roster::nameOf) }
    return map.copy(status = MapStatus.READY, verdict = Verdict(level, line, map.agreed.size, map.disputed.size, map.unique.size))
  }

  private suspend fun resolveChallenge(s: RunState, d: Dispute): AgreementMap {
    val map = s.agreement!!
    val asks = s.asks.filter { it.purpose == AskPurpose.CHALLENGE && it.disputeId == d.id }
    val answered = asks.filter { it.replies.isNotEmpty() }
    val agentsInDispute = d.sides.flatMap { it.agentIds }
    val labels = agentsInDispute.mapIndexed { i, a -> Agreement.label(i) to a }.toMap()
    val positions: Map<String, Pair<Boolean, String>> = if (answered.isEmpty()) emptyMap() else {
      val input = buildString {
        append("Disputed topic: ").append(d.topic).append("\nOriginal values: ").append(d.sides.joinToString("; ") { it.value }).append("\n")
        answered.forEach { a ->
          val label = labels.entries.first { it.value == a.agentId }.key
          val orig = d.sides.first { a.agentId in it.agentIds }.value
          append("\nAgent ").append(label).append(" originally said: ").append(orig).append("\nIts reply: ").append(a.replyText.trim()).append("\n")
        }
      }
      try {
        val o = parseJsonObject(model(LlmRequest("challenge", Prompts.render("challenge_resolve"), listOf(Items.user(input)), jsonSchema = Agreement.challengeSchema, schemaName = "positions")).text)
        (o?.get("positions") as? JsonArray).orEmpty().mapNotNull { el ->
          val p = el as? JsonObject ?: return@mapNotNull null
          val agent = labels[p.str("agent")?.trim()?.uppercase()] ?: return@mapNotNull null
          agent to ((p.bool("keeps") ?: true) to (p.str("value")?.trim().orEmpty()))
        }.toMap()
      } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
    }
    // Move agents that changed their answer.
    val sides = d.sides.map { it.copy(agentIds = it.agentIds.toMutableList()) }.toMutableList()
    val changed = mutableListOf<String>()
    for ((agent, pos) in positions) {
      val (keeps, value) = pos
      if (keeps || value.isEmpty()) continue
      val from = sides.indexOfFirst { agent in it.agentIds }
      val to = sides.indexOfFirst { Agreement.similarity(it.value, value) >= Agreement.VALUE_MATCH }
      if (from < 0 || to == from) continue
      sides[from] = sides[from].copy(agentIds = sides[from].agentIds - agent)
      if (to >= 0) sides[to] = sides[to].copy(agentIds = sides[to].agentIds + agent)
      else sides += DisputeSide(value, value, listOf(agent))
      changed += "${roster.nameOf(agent)} changed to ${if (to >= 0) sides[to].value else value}"
    }
    val live = sides.filter { it.agentIds.isNotEmpty() }.sortedByDescending { it.agentIds.size }
    val resolution = when {
      answered.isEmpty() -> "No one answered the check"
      changed.isEmpty() -> if (answered.size == agentsInDispute.size) "Everyone kept their answer" else "No one changed their answer"
      else -> changed.joinToString("; ")
    }
    val total = live.sumOf { it.agentIds.size }
    var agreed = map.agreed
    var disputed = map.disputed
    if (live.size == 1) {
      agreed = agreed + ClaimCluster("g-${d.id}", d.topic, live[0].statement, live[0].value, live[0].agentIds, live[0].evidence)
      disputed = disputed.filter { it.id != d.id }
    } else {
      val nd = d.copy(sides = live, majority = if (live[0].agentIds.size * 2 > total) 0 else null, challenge = ChallengeState.RESOLVED, resolution = resolution)
      disputed = disputed.map { if (it.id == d.id) nd else it }
    }
    val answeredCount = s.asks.count { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }
    return withVerdict(map.copy(agreed = agreed, disputed = disputed), answeredCount, false)
  }

  // ============================================================== "Talk it over" (room mode)

  /**
   * "Talk it over": [question] to every agent in [agents], blind (round 0, exactly like @all but without Juno's own
   * answer: Juno moderates). Then up to [TalkLimits.maxRounds] reveal rounds, then Juno's wrap-up. Returns the run id.
   */
  suspend fun startTalk(question: String, agents: List<AgentProfile> = roster.activePeers): String {
    val now = clock()
    val runId = "r" + newId()
    mutex.withLock {
      val busy = busyAgentsL()
      val who = agents.filter { it.kind != AgentKind.MAIN && !it.paused }.distinctBy { it.id }
      val asks = who.mapIndexed { n, p ->
        val st = if (p.kind == AgentKind.TEXT && p.id in busy) AskStatus.QUEUED else AskStatus.SENDING
        AskState("$runId~a$n", p.id, question, AskPurpose.BLIND, status = st, createdAt = now, roomRound = 0)
      }
      val until = now + config.allWaitMs
      val room = RoomState(RoomStage.ROUND_OPEN, TalkTrigger.ASKED, question, who.map { it.id }, startedAt = now,
        rounds = listOf(RoomRound(0, who.map { it.id }, asks.map { it.id }, now, until)))
      val wait = PendingCall("room-r0", PendingKind.ROOM_ROUND, asks.map { it.id }, until = until, startedAt = now)
      putL(RunState(runId, RunMode.ROOM, question, emptyList(), now, Phase.WAITING, asks = asks, pending = listOf(wait), room = room))
    }
    return runId
  }

  /**
   * "Talk it over" on one disagreement of a finished @all (what "Check this" used to be), or Juno starting one itself
   * when the first answers clash ([TalkTrigger.CLASH]). The blind answers become round 0; the reveal round goes to the
   * agents on each side. Once per message. Then [drive].
   */
  suspend fun talkOver(runId: String, disputeId: String, trigger: TalkTrigger = TalkTrigger.CHECK_THIS): Boolean = mutex.withLock {
    val s = getL(runId) ?: return@withLock false
    val map = s.agreement ?: return@withLock false
    if (s.mode != RunMode.ALL || s.room != null || s.phase != Phase.DONE) return@withLock false
    val d = map.disputed.firstOrNull { it.id == disputeId && it.challenge == ChallengeState.NONE } ?: return@withLock false
    val now = clock()
    val participants = s.asks.filter { it.purpose == AskPurpose.BLIND && it.agentId != roster.main.id }.map { it.agentId }.distinct()
    var room = RoomState(RoomStage.MODERATING, trigger, s.userText, participants, focusTopic = d.topic, focusDisputeId = d.id, startedAt = now)
    room = appendEntriesL(s, room, now, closingRound = 0).first
    room = room.copy(rounds = listOf(RoomRound(0, participants, s.asks.filter { it.purpose == AskPurpose.BLIND }.map { it.id }, s.createdAt, s.createdAt, s.answeredAt ?: now, "all_in")))
    val m2 = map.copy(status = MapStatus.CHALLENGING, disputed = map.disputed.map { it.copy(challenge = ChallengeState.ASKED) })
    // The merged answer stays up while they talk; the wrap-up replaces it.
    putL(s.copy(mode = RunMode.ROOM, phase = Phase.PLANNING, room = room, agreement = m2, answeredAt = null, pending = emptyList(), unprocessed = emptyList()))
    trace.event(runId, "talk.start", "$trigger on '${d.topic}' with ${Views.names(participants.map { roster.nameOf(it) })}", null)
    true
  }

  /** "Wrap up now": close the open round and let Juno answer with what's in. Then [drive]. */
  suspend fun wrapUpNow(runId: String): Boolean = mutex.withLock {
    val s = getL(runId) ?: return@withLock false
    val r = s.room ?: return@withLock false
    if (s.phase.terminal || r.stage == RoomStage.WRAPPING || r.stage == RoomStage.CLOSED) return@withLock false
    putL(recompute(s.copy(room = r.copy(wrapRequested = true), pending = s.pending.map { if (it.kind == PendingKind.ROOM_ROUND && it.output == null) it.copy(output = "wrap") else it })))
    true
  }

  /** "Leave X out": no more relays to [agentId]; its open turn in the talk is dropped. Its first answer still counts. */
  suspend fun leaveOut(runId: String, agentId: String): Boolean {
    val closes = mutex.withLock {
      val s = getL(runId) ?: return@withLock null
      val r = s.room ?: return@withLock null
      if (s.phase.terminal || agentId !in r.participants || agentId in r.leftOut) return@withLock null
      val events = mutableListOf<ScoreEvent>()
      val open = s.asks.filter { it.agentId == agentId && !it.status.settled && (it.purpose == AskPurpose.ROOM || it.purpose == AskPurpose.BLIND) }
      var next = s.copy(room = r.copy(leftOut = r.leftOut + agentId), asks = s.asks.map { a -> if (a in open) settle(a, AskStatus.CANCELLED, clock(), events) else a })
      next = next.copy(pending = next.pending.map { p -> if (p.kind == PendingKind.ROOM_ROUND && p.output == null) roomRoundDone(next, p, clock())?.let { p.copy(output = it) } ?: p else p })
      putL(recompute(next))
      events.forEach { scores.append(it) }
      open.map { it.agentId to it.id }
    } ?: return false
    closePeers(closes)
    return true
  }

  /** Whether a talk round is over, and why: everyone in, the round's deadline, a wrap-up, or the talk's 12 minutes. */
  private fun roomRoundDone(s: RunState, p: PendingCall, now: Long): String? {
    val room = s.room ?: return "done"
    val asks = p.askIds.mapNotNull { s.ask(it) }
    if (room.wrapRequested) return "wrap"
    if (now >= room.startedAt + config.talk.maxMs) return "wall_clock"
    if (asks.all { it.status.settled }) return "all_in"
    // A relay held for the user's tap pauses the round's clock; an agent mid-reply gets its quiet window.
    if (p.until != null && now >= p.until && asks.none { it.status == AskStatus.AWAITING_APPROVAL || it.status == AskStatus.REPLYING }) return "deadline"
    return null
  }

  /** The user wrote while a talk is open: their words join it (relayed to everyone next round) and end the round early. */
  private suspend fun roomSteerL(s: RunState, text: String) {
    val r = s.room ?: return
    if (s.phase.terminal || r.stage == RoomStage.WRAPPING || r.stage == RoomStage.CLOSED) { putL(s.copy(interjections = s.interjections + text)); return }
    val entry = RoomEntry(r.nextSeq, (r.currentRound?.n ?: 0), EntryKind.USER, "user", text.trim(), at = clock())
    putL(recompute(s.copy(room = r.copy(entries = r.entries + entry), pending = s.pending.map { if (it.kind == PendingKind.ROOM_ROUND && it.output == null) it.copy(output = "user") else it })))
  }

  /**
   * Settled answers become entries (with their @mentions resolved), and agents get their anonymous labels. Returns the
   * room and the ids of asks that now have entries.
   */
  private fun appendEntriesL(s: RunState, room0: RoomState, now: Long, closingRound: Int): Pair<RoomState, List<String>> {
    var room = room0
    val added = mutableListOf<String>()
    val talkAsks = s.asks.filter { (it.purpose == AskPurpose.BLIND || it.purpose == AskPurpose.ROOM) && it.replies.isNotEmpty() && it.status.settled }
      .sortedBy { a -> a.replies.firstOrNull()?.at ?: a.createdAt }
    for (a in talkAsks) {
      val covered = room.entries.filter { it.askId == a.id }.maxOfOrNull { it.parts } ?: 0
      val parts = a.replies
      if (parts.size <= covered) continue
      val text = parts.drop(covered).joinToString("\n") { it.text }.trim()
      if (text.isEmpty()) continue
      val round = a.roomRound ?: 0
      val mentions = if (a.agentId == roster.main.id) emptyList() else Relay.mentions(text, a.agentId, roster)
      val e = RoomEntry(room.nextSeq, round, EntryKind.AGENT, a.agentId, text, a.id, parts.size, mentions, Relay.isPass(text),
        late = covered > 0 || round < closingRound, at = parts.last().at)
      var labels = room.labels
      if (a.agentId !in labels) labels = labels + (a.agentId to Agreement.label(labels.size))
      var pending = room.pendingMentions
      var sugg = room.suggestions
      for (m in mentions) {
        val targets = if (m == "all") room.participants.filter { it != a.agentId } else listOf(m)
        for (t in targets) {
          if (t in room.participants && t != a.agentId) pending = pending + (t to (pending[t].orEmpty() + e.seq))
          else if (m != "all" && roster.byId(t)?.let { !it.paused && it.kind != AgentKind.MAIN } == true && sugg.none { it.toAgent == t && it.fromAgent == a.agentId })
            sugg = sugg + Suggestion(a.agentId, t, e.seq, Relay.quoteFor(text, roster.nameOf(t)))
        }
      }
      room = room.copy(entries = room.entries + e, labels = labels, pendingMentions = pending, suggestions = sugg)
      added += a.id
    }
    return room to added
  }

  /** The last relay each agent was sent covers every entry up to this seq. Derived, never stored (engine.md §1). */
  private fun cursorOf(s: RunState, agentId: String): Int = s.asks
    .filter { it.agentId == agentId && it.purpose == AskPurpose.ROOM && it.relayThrough != null && it.status !in setOf(AskStatus.FAILED, AskStatus.DECLINED, AskStatus.CANCELLED) }
    .maxOfOrNull { it.relayThrough!! } ?: -1

  /** Mentions of [agentId] its relays haven't carried yet. */
  private fun unconsumedMentions(s: RunState, room: RoomState, agentId: String): List<Int> {
    val c = cursorOf(s, agentId)
    return room.pendingMentions[agentId].orEmpty().filter { it > c }
  }

  /** The user's words (said mid-talk) that [agentId]'s relays haven't carried yet. */
  private fun unrelayedUser(s: RunState, room: RoomState, agentId: String): List<Int> {
    val c = cursorOf(s, agentId)
    return room.entries.filter { it.kind == EntryKind.USER && it.seq > c }.map { it.seq }
  }

  /** The latest position of each agent (its newest non-pass entry), for comparing. */
  private fun latestPositions(room: RoomState): List<RoomEntry> = room.entries.filter { it.kind == EntryKind.AGENT }
    .groupBy { it.authorId }.mapNotNull { (_, es) -> es.lastOrNull { !it.passed } ?: es.lastOrNull() }

  /** A round closed (or the user steered, or asked to wrap up): record entries, re-compare, pick what's next. */
  private suspend fun roomMerge(runId: String) {
    // Step 1 (persisted): entries and the closed round.
    val s1 = mutex.withLock {
      val s = getL(runId) ?: return
      val r = s.room ?: return
      val now = clock()
      val n = r.currentRound?.n ?: 0
      var (room, _) = appendEntriesL(s, r, now, n)
      room = room.copy(rounds = room.rounds.map { if (it.n == n && it.closedAt == null) it.copy(closedAt = now, closeReason = if (r.wrapRequested) "wrap" else "all_in") else it })
      val next = s.copy(room = room, pending = emptyList())
      putL(next)
      next
    }
    // Step 2: compare the latest positions (the Agreement Map updates every round).
    val room = s1.room!!
    val latest = latestPositions(room)
    val map = if (latest.size >= 2) compareAnswers(s1, latest.map { RawAnswer(it.authorId, roster.nameOf(it.authorId), it.text) }, verdictCall = false) else s1.agreement
    // Step 3 (persisted): what's next.
    mutex.withLock {
      val cur = getL(runId) ?: return
      val r = cur.room ?: return
      if (cur.phase.terminal) return
      val withMap = cur.copy(agreement = map?.let { m -> m.copy(status = MapStatus.CHALLENGING, disputed = m.disputed.map { it.copy(challenge = ChallengeState.ASKED) }) } ?: cur.agreement)
      val n = r.currentRound?.n ?: 0
      val end = roomEndReason(withMap, r, n)
      val nextRoom = if (end != null) r.copy(stage = RoomStage.WRAPPING, endReason = end) else r.copy(stage = RoomStage.MODERATING)
      putL(withMap.copy(room = nextRoom, phase = Phase.PLANNING))
    }
    map?.let { _updates.tryEmit(RunUpdate.MapUpdated(runId, it)) }
  }

  /** Why the talk ends after round [n], or null to go on (engine.md §5, in order). */
  private fun roomEndReason(s: RunState, r: RoomState, n: Int): EndReason? {
    val now = clock()
    if (r.wrapRequested) return EndReason.WRAPPED_EARLY
    if (now >= r.startedAt + config.talk.maxMs) return EndReason.WALL_CLOCK
    val answered = r.entries.filter { it.kind == EntryKind.AGENT }.map { it.authorId }.distinct()
    if (answered.size < 2) return EndReason.TOO_FEW
    if (n >= config.talk.maxRounds) return EndReason.ROUND_CAP
    if (s.asks.count { it.purpose == AskPurpose.ROOM } >= config.talk.maxRelays) return EndReason.RELAY_CAP
    // The user said something mid-talk that hasn't reached everyone: it goes out before the talk can end by itself.
    val steer = r.participants.any { r.eligible(it) && unrelayedUser(s, r, it).isNotEmpty() }
    if (n >= 1 && !steer) {
      val thisRound = r.entries.filter { it.kind == EntryKind.AGENT && it.round == n }
      if (thisRound.isEmpty() || thisRound.all { it.passed }) return EndReason.SILENCE
      val mentions = r.participants.filter { r.eligible(it) }.any { unconsumedMentions(s, r, it).isNotEmpty() }
      if (s.agreement?.disputed.isNullOrEmpty() && !mentions) return EndReason.CONSENSUS
    }
    if (defaultFloor(s, r, n + 1).isEmpty()) return if (n == 0) EndReason.TOO_FEW else EndReason.CONSENSUS
    return null
  }

  /** Can [agentId] be relayed to now? In the talk, not left out, reachable, nothing open in this talk, under its cap. */
  private fun canSpeak(s: RunState, r: RoomState, agentId: String): Boolean {
    val p = roster.byId(agentId) ?: return false
    // An agent still thinking isn't re-asked, except to pass on the user's own words (queued behind its open turn).
    val open = s.asks.filter { it.agentId == agentId && !it.status.settled }
    val steerOk = open.isNotEmpty() && unrelayedUser(s, r, agentId).isNotEmpty() && open.none { it.purpose == AskPurpose.ROOM && it.roomRound == (r.currentRound?.n ?: 0) + 1 }
    return r.eligible(agentId) && !p.paused && p.kind != AgentKind.MAIN && peerFor(agentId) != null &&
      (open.isEmpty() || steerOk) &&
      s.asks.count { it.agentId == agentId && it.purpose == AskPurpose.ROOM } < config.talk.maxPerAgent
  }

  /**
   * Who speaks in round [n] by default (engine.md §3): round 1 = everyone who answered (only the two sides of the
   * disagreement for "Talk it over" on one point); round 2 = agents an agent @mentioned, then agents on either side of
   * a disagreement that's still open. Capped by the relays left.
   */
  private fun defaultFloor(s: RunState, r: RoomState, n: Int): List<String> {
    val answered = r.entries.filter { it.kind == EntryKind.AGENT && it.authorId != roster.main.id }.map { it.authorId }.distinct()
    val mentioned = r.participants.filter { unconsumedMentions(s, r, it).isNotEmpty() }
    val sides = s.agreement?.disputed.orEmpty().flatMap { d -> d.sides.flatMap { it.agentIds } }.filter { it != roster.main.id }
    val focus = s.agreement?.disputed?.firstOrNull { it.id == r.focusDisputeId }?.sides?.flatMap { it.agentIds }
    val raw = when {
      n <= 1 && r.trigger != TalkTrigger.ASKED && focus != null -> mentioned + focus.filter { it in answered }
      n <= 1 -> answered
      else -> mentioned + sides
    } + r.participants.filter { unrelayedUser(s, r, it).isNotEmpty() && it in answered }
    val left = config.talk.maxRelays - s.asks.count { it.purpose == AskPurpose.ROOM }
    return raw.distinct().filter { canSpeak(s, r, it) }.take(left.coerceAtLeast(0))
  }

  /** Between rounds: Juno moderates (one model call), or wraps up. */
  private suspend fun roomPlan(runId: String) {
    val s = mutex.withLock { getL(runId) } ?: return
    val r = s.room ?: return
    when (r.stage) {
      RoomStage.WRAPPING -> { wrapUp(runId); return }
      RoomStage.MODERATING -> {}
      else -> { mutex.withLock { getL(runId)?.let { putL(recompute(it.copy(phase = Phase.WAITING))) } }; return }
    }
    if (s.unprocessed.isNotEmpty()) { processRoomCalls(runId); return }
    val n = (r.currentRound?.n ?: 0) + 1
    val floor = defaultFloor(s, r, n)
    val calls = try {
      if (s.modelCalls >= config.maxModelCalls) null
      else model(LlmRequest("room", Prompts.render("room_moderator", mapOf("main" to roster.main.name, "userLine" to Prompts.userLine(userNameNow()))),
        listOf(Items.user(moderatorBrief(s, r, n, floor))), roomTools(r))).toolCalls.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    // The model failed or said nothing useful: the deterministic floor, as if Juno had opened it.
    val records = calls?.map { CallRecord(it.callId, it.name, it.arguments) }
      ?: listOf(CallRecord("auto-r$n", "open_floor", buildJsonObject { put("agents", buildJsonArray { floor.forEach { add(JsonPrimitive(roster.nameOf(it))) } }) }.toString()))
    mutex.withLock {
      val cur = getL(runId) ?: return
      putL(cur.copy(unprocessed = records, modelCalls = cur.modelCalls + if (calls != null) 1 else 0))
    }
    processRoomCalls(runId)
  }

  private fun roomTools(r: RoomState): List<ToolSpec> {
    val names = r.participants.filter { r.eligible(it) }.map { roster.nameOf(it) }
    return listOf(
      ToolSpec("open_floor", "Start the next round: each listed agent gets one text with what it hasn't seen yet.",
        Schema.obj("agents" to Schema.stringArray("Who speaks next (from: ${names.joinToString()})"), "note" to Schema.string("Optional, one line: what to focus on. Never paraphrase an agent's answer."))),
      ToolSpec("relay", "Send one agent only the listed entries (it counts as its turn this round).",
        Schema.obj("to" to Schema.string("Which agent", names.ifEmpty { null }), "seqs" to Schema.stringArray("Entry numbers to send"), "note" to Schema.string("Optional, one line"))),
      ToolSpec("say", "Add one short moderator line that the next round carries to everyone. At most once per round.",
        Schema.obj("text" to Schema.string("One line, no instructions to book, buy or act"))),
      ToolSpec("close_room", "End the talk now and answer the user.", Schema.obj("reply" to Schema.string("Your answer to the user: the answer first, at most two sentences"))),
    )
  }

  private fun moderatorBrief(s: RunState, r: RoomState, n: Int, floor: List<String>): String = buildString {
    append("Question: ").append(r.question).append("\n")
    r.focusTopic?.let { append("The talk is about this disagreement: ").append(it).append("\n") }
    append("Round ").append(n - 1).append(" just closed. Next would be round ").append(n).append(" of at most ").append(config.talk.maxRounds).append(".\n\n")
    append("Entries so far (number, who, round):\n")
    r.entries.forEach { e ->
      val who = when (e.kind) { EntryKind.USER -> userNameNow().ifBlank { "The user" } + " (the user)"; EntryKind.JUNO -> roster.main.name; EntryKind.AGENT -> roster.nameOf(e.authorId) }
      append("[").append(e.seq).append("] ").append(who).append(" (round ").append(e.round).append(if (e.late) ", late" else "").append("): ")
        .append(Relay.trim(e.text.replace(Regex("\\s+"), " "), 500)).append("\n")
    }
    val d = s.agreement?.disputed.orEmpty()
    if (d.isNotEmpty()) {
      append("\nStill disagree on:\n")
      d.forEach { x -> append("- ").append(x.topic).append(": ").append(x.sides.joinToString(" vs ") { side -> "${side.value} (${side.agentIds.joinToString { roster.nameOf(it) }})" }).append("\n") }
    } else append("\nNo open disagreement on the compared points.\n")
    val waiting = r.participants.mapNotNull { a -> unconsumedMentions(s, r, a).takeIf { it.isNotEmpty() }?.let { "${roster.nameOf(a)} (entries ${it.joinToString()})" } }
    if (waiting.isNotEmpty()) append("Mentioned and not yet told: ").append(waiting.joinToString("; ")).append("\n")
    append("Default next speakers: ").append(floor.joinToString { roster.nameOf(it) }.ifEmpty { "nobody" }).append("\n")
    append("Relays left in this talk: ").append(config.talk.maxRelays - s.asks.count { it.purpose == AskPurpose.ROOM }).append("\n")
  }

  /** Turns the moderator's calls (or the default floor) into relays. Re-running it after a crash reuses asks by call id. */
  private suspend fun processRoomCalls(runId: String) {
    val notes = mutableListOf<RunUpdate>()
    val events = mutableListOf<ScoreEvent>()
    mutex.withLock {
      val s = getL(runId) ?: return
      var r = s.room ?: return
      val now = clock()
      val n = (r.currentRound?.n ?: 0) + 1
      val calls = s.unprocessed
      val floorDefault = defaultFloor(s, r, n)
      var floor: List<String>? = null
      var note: String? = null
      val targeted = mutableMapOf<String, Pair<Set<Int>, String?>>()
      var closeReply: String? = null
      for (c in calls) {
        val a = parseJsonObject(c.arguments) ?: continue
        when (c.name) {
          "say" -> {
            val t = a.str("text")?.trim().orEmpty().take(300)
            if (t.isNotEmpty() && r.entries.none { it.kind == EntryKind.JUNO && it.round == n } && !ApprovalGate.check(t, false).gated)
              r = r.copy(entries = r.entries + RoomEntry(r.nextSeq, n, EntryKind.JUNO, roster.main.id, t, at = now))
          }
          "open_floor" -> {
            val asked = a.strings("agents").mapNotNull { roster.resolve(it)?.id }.filter { canSpeak(s, r, it) }
            floor = (floor.orEmpty() + asked).distinct()
            note = a.str("note")?.trim()?.takeIf { it.isNotEmpty() && !ApprovalGate.check(it, false).gated } ?: note
          }
          "relay" -> {
            val to = a.str("to")?.let { roster.resolve(it)?.id }?.takeIf { canSpeak(s, r, it) } ?: continue
            val seqs = a.strings("seqs").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (seqs.isNotEmpty()) targeted[to] = seqs to a.str("note")?.trim()?.takeIf { it.isNotEmpty() && !ApprovalGate.check(it, false).gated }
          }
          "close_room" -> closeReply = a.str("reply")?.trim()?.takeIf { it.isNotEmpty() }
        }
      }
      if (closeReply != null) {
        putL(s.copy(unprocessed = emptyList(), room = r.copy(stage = RoomStage.WRAPPING, endReason = r.endReason ?: EndReason.CONSENSUS, closeReply = closeReply)))
        return@withLock
      }
      val focusNote = if (n == 1 && r.focusTopic != null) s.agreement?.disputed?.firstOrNull { it.id == r.focusDisputeId }?.let { d ->
        "The answers split on ${d.topic}: ${d.sides.joinToString(" or ") { it.value }}. Say which, and why."
      } else null
      val speakers = (floor ?: floorDefault).ifEmpty { if (targeted.isEmpty()) floorDefault else emptyList() }
      val left = config.talk.maxRelays - s.asks.count { it.purpose == AskPurpose.ROOM }
      val order = (targeted.keys.toList() + speakers).distinct().take(left.coerceAtLeast(0))
      val busy = busyAgentsL(exceptRun = runId)
      var asks = s.asks
      val created = mutableListOf<AskState>()
      for (agent in order) {
        val callId = calls.firstOrNull()?.callId
        asks.firstOrNull { it.callId == callId && it.agentId == agent && it.purpose == AskPurpose.ROOM && it.roomRound == n }?.let { created += it; continue }
        val (only, tNote) = targeted[agent] ?: (null to null)
        val ask = composeRelayL(s.copy(asks = asks), r, agent, n, tNote ?: note ?: focusNote, only, "$runId~a${asks.size}", callId, now, busy) ?: continue
        asks = asks + ask
        created += ask
        if (ask.status == AskStatus.AWAITING_APPROVAL) {
          events += ScoreEvent.NeededUser(agent, now, ask.id, if (ask.shareFrom.isNotEmpty()) "share" else "approval")
          notes += RunUpdate.NeedsUser(runId, NeedsUserLine(NeedsUserKind.APPROVE, approvalText(ask), agent, ask.id, null, ask.text))
        }
      }
      if (created.isEmpty()) {
        putL(s.copy(unprocessed = emptyList(), room = r.copy(stage = RoomStage.WRAPPING, endReason = r.endReason ?: if (n == 1) EndReason.TOO_FEW else EndReason.CONSENSUS)))
        return@withLock
      }
      val api = created.all { roster.byId(it.agentId)?.kind == AgentKind.API }
      val until = now + if (api) config.talk.apiRoundWaitMs else config.talk.roundWaitMs
      r = r.copy(stage = RoomStage.ROUND_OPEN, rounds = r.rounds + RoomRound(n, created.map { it.agentId }, created.map { it.id }, now, until))
      val wait = PendingCall("room-r$n", PendingKind.ROOM_ROUND, created.map { it.id }, until = until, startedAt = now)
      putL(recompute(s.copy(asks = asks, unprocessed = emptyList(), room = r, pending = listOf(wait))))
    }
    events.forEach { scores.append(it) }
    notes.forEach { _updates.tryEmit(it) }
    flushSends(runId)
  }

  /** "Share Hark's answer with kDot?" or, for a relay the gate held, "Okay to pass this on to kDot?". */
  private fun approvalText(a: AskState): String = Views.approvalLine(a, roster::nameOf)

  /**
   * One relay to [agent] for round [n]: the entries it hasn't seen (or only [only]), anonymised and quoted. Held for
   * the user when it would carry a private agent's answer they haven't shared with [agent], or when the quoted words
   * would read as a go-ahead or an order (the send gate). Null when there's nothing new for it.
   */
  private fun composeRelayL(s: RunState, r: RoomState, agent: String, n: Int, note: String?, only: Set<Int>?, id: String, callId: String?, now: Long, busy: Set<String>): AskState? {
    val budget = config.talk.maxRelayChars - RELAY_HEADER_RESERVE
    val user = userNameNow()
    val refused = { e: RoomEntry -> "${e.authorId}>$agent" in r.notShared }
    val c = Relay.compose(r, agent, cursorOf(s, agent), user, note, budget, refused, only, roster.main.name) ?: return null
    val private = c.authors.filter { a -> roster.byId(a)?.sensitive == true && "$a>$agent" !in r.shared }
    val without = if (private.isEmpty()) null else Relay.compose(r, agent, cursorOf(s, agent), user, note, budget, { e -> refused(e) || e.authorId in private }, only, roster.main.name)
    val gate = SendGate.check(c.quoted.map { Segment(it, Provenance.PEER_RELAY) }, user)
    val prof = roster.byId(agent)
    val status = when {
      private.isNotEmpty() || gate.gated -> AskStatus.AWAITING_APPROVAL
      prof?.kind == AgentKind.TEXT && (agent in busy || s.asks.any { it.agentId == agent && (it.status.inFlight || it.status == AskStatus.QUEUED) }) -> AskStatus.QUEUED
      else -> AskStatus.SENDING
    }
    return AskState(id, agent, c.text, AskPurpose.ROOM, callId, "talk", status, now,
      gateReason = gate.reason ?: if (private.isNotEmpty()) "share" else null, roomRound = n, relayThrough = c.relayThrough, quoted = c.quoted,
      shareFrom = private, textWithoutShared = without?.text, quotedWithoutShared = without?.quoted.orEmpty())
  }

  /** The talk is over: where each agent landed, Juno's wrap-up, the reliability log. */
  private suspend fun wrapUp(runId: String) {
    val s = mutex.withLock { getL(runId) } ?: return
    val r = s.room ?: return
    val agentEntries = r.entries.filter { it.kind == EntryKind.AGENT && it.authorId != roster.main.id }
    val byAgent = agentEntries.groupBy { it.authorId }
    // Where they landed (only meaningful when an agent spoke after seeing the others).
    val landed: List<Landed> = if (byAgent.values.none { es -> es.any { it.round >= 1 } }) emptyList() else {
      val labels = byAgent.keys.mapIndexed { i, a -> Agreement.label(i) to a }.toMap()
      val input = buildString {
        append("Question: ").append(r.question).append("\n")
        r.focusTopic?.let { append("Disputed point: ").append(it).append("\n") }
        labels.forEach { (l, a) ->
          val es = byAgent.getValue(a)
          append("\nAgent ").append(l).append(" first answer: ").append(es.first().text.trim()).append("\n")
          es.drop(1).forEach { e -> append("Agent ").append(l).append(" later (round ").append(e.round).append("): ").append(e.text.trim()).append("\n") }
        }
      }
      try {
        val o = parseJsonObject(model(LlmRequest("positions", Prompts.render("room_positions"), listOf(Items.user(input)), jsonSchema = positionsSchema, schemaName = "positions")).text)
        (o?.get("positions") as? JsonArray).orEmpty().mapNotNull { el ->
          val p = el as? JsonObject ?: return@mapNotNull null
          val agent = labels[p.str("agent")?.trim()?.uppercase()] ?: return@mapNotNull null
          val changed = p.bool("changed") == true
          val along = if (changed && p.bool("new_reason") == false) p.str("went_along_with")?.trim()?.uppercase()?.let { labels[it] } ?: labels.values.firstOrNull { it != agent } else null
          Landed(agent, p.str("first")?.trim().orEmpty(), p.str("final")?.trim().orEmpty(), changed, along, p.str("reason")?.trim().orEmpty())
        }.filter { it.final.isNotEmpty() }.sortedBy { r.participants.indexOf(it.agentId) }
      } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    var answer = r.closeReply
    var choices = emptyList<String>()
    var yourCall = false
    if (answer == null && agentEntries.isNotEmpty()) {
      val input = buildString {
        append("Question: ").append(r.question).append("\n")
        append("How the talk ended: ").append(endWords(r.endReason)).append("\n\n")
        append("Final positions:\n")
        latestPositions(r).filter { it.authorId != roster.main.id }.forEach { append("- ").append(roster.nameOf(it.authorId)).append(": ").append(Relay.trim(it.text.replace(Regex("\\s+"), " "), 600)).append("\n") }
        if (landed.isNotEmpty()) {
          append("\nChanges:\n")
          landed.forEach { l ->
            append("- ").append(roster.nameOf(l.agentId)).append(": ")
            append(when { !l.changed -> "kept ${l.final}"; l.wentAlongWith != null -> "went from ${l.first} to ${l.final} with no new reason (went along with ${roster.nameOf(l.wentAlongWith)}; don't count this as agreement)"; else -> "changed from ${l.first} to ${l.final}: ${l.reason}" })
            append("\n")
          }
        }
        val disputes = s.agreement?.disputed.orEmpty()
        if (disputes.isNotEmpty()) append("\nStill split on: ").append(disputes.joinToString("; ") { d -> d.topic + " (" + d.sides.joinToString(" vs ") { side -> side.value + ": " + side.agentIds.joinToString { roster.nameOf(it) } } + ")" }).append("\n")
        val user = r.entries.filter { it.kind == EntryKind.USER }
        if (user.isNotEmpty()) append("\nThe user added: ").append(user.joinToString(" / ") { it.text }).append("\n")
        s.agreement?.let { m -> combinedQuestion(m)?.let { q -> append("\nStill open for the user (end with this as one short question unless an agent already answered it): ").append(q).append("\n") } }
        val silent = r.participants.filter { it !in byAgent.keys }.map { roster.nameOf(it) }
        if (silent.isNotEmpty()) append("Didn't answer: ").append(silent.joinToString()).append("\n")
      }
      try {
        val o = parseJsonObject(model(LlmRequest("wrap", Prompts.render("room_wrap", mapOf("main" to roster.main.name, "userLine" to Prompts.userLine(userNameNow()))),
          listOf(Items.user(input)), jsonSchema = wrapSchema, schemaName = "wrap")).text)
        answer = o?.str("answer")?.trim()?.takeIf { it.isNotEmpty() }
        choices = o?.strings("choices").orEmpty().map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(2)
        yourCall = o?.bool("your_call") == true
      } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    val text = answer ?: fallbackWrap(s, r)
    val events = mutableListOf<ScoreEvent>()
    mutex.withLock {
      val cur = getL(runId) ?: return
      if (cur.phase.terminal) return
      val room = cur.room ?: return
      val now = clock()
      val map = cur.agreement?.let { m -> m.copy(status = MapStatus.READY, disputed = m.disputed.map { d ->
        d.copy(challenge = ChallengeState.RESOLVED, resolution = d.resolution ?: "They still disagree")
      }, verdict = m.verdict ?: Verdict(Agreement.level(latestPositions(room).size, Agreement.Clusters(m.agreed, m.disputed, m.unique)), text, m.agreed.size, m.disputed.size, m.unique.size)) }
      // Mentions the talk never relayed become "Ask X about this?".
      val extra = room.participants.flatMap { t -> unconsumedMentions(cur, room, t).mapNotNull { seq ->
        room.entries.firstOrNull { it.seq == seq }?.let { e -> Suggestion(e.authorId, t, seq, Relay.quoteFor(e.text, roster.nameOf(t))) }
      } }.filter { x -> room.suggestions.none { it.toAgent == x.toAgent && it.fromAgent == x.fromAgent } }.distinctBy { it.fromAgent to it.toAgent }
      val topic = room.focusTopic ?: Relay.trim(room.question, 80)
      if (!room.logged) {
        val firsts = room.entries.filter { it.kind == EntryKind.AGENT }.groupBy { it.authorId }
        for (a in room.participants) {
          val l = landed.firstOrNull { it.agentId == a }
          val es = firsts[a].orEmpty()
          events += ScoreEvent.TalkPosition(a, now, runId, topic, room.trigger, answeredFirst = es.any { it.round == 0 }, spokeAfter = es.any { it.round >= 1 },
            changed = l?.changed == true, wentAlong = l?.wentAlongWith != null, first = l?.first ?: "", final = l?.final ?: "", endReason = room.endReason ?: EndReason.CONSENSUS)
        }
      }
      val closed = room.copy(stage = RoomStage.CLOSED, landed = landed, choices = choices, yourCall = yourCall, suggestions = room.suggestions + extra, logged = true,
        endReason = room.endReason ?: EndReason.CONSENSUS)
      putL(finishL(cur.copy(room = closed, agreement = map, fallback = answer == null), text, answer == null))
    }
    events.forEach { scores.append(it) }
    _updates.tryEmit(RunUpdate.TalkDone(runId, text))
  }

  private fun endWords(e: EndReason?) = when (e) {
    EndReason.CONSENSUS -> "they agree"; EndReason.SILENCE -> "nobody had more to add"; EndReason.ROUND_CAP -> "the round limit"
    EndReason.WRAPPED_EARLY -> "the user asked to wrap up early"; EndReason.WALL_CLOCK -> "the time limit"; EndReason.RELAY_CAP -> "the relay limit"
    EndReason.TOO_FEW -> "too few answers to talk over"; else -> "it ended"
  }

  /** The wrap-up without the model: the map's line, or each agent's last position. Never retells the talk. */
  private fun fallbackWrap(s: RunState, r: RoomState): String {
    val latest = latestPositions(r).filter { it.authorId != roster.main.id }
    if (latest.isEmpty()) return "Nobody answered, so there was nothing to talk over."
    if (latest.size == 1) return "Only ${roster.nameOf(latest[0].authorId)} answered, so there was nothing to talk over: ${Views.preview(latest[0].text)}"
    val d = s.agreement?.disputed.orEmpty()
    return if (d.isEmpty()) "**They agree.** " + Views.preview(latest.first().text)
    else "**They didn't agree.** " + latest.joinToString(" ") { "${roster.nameOf(it.authorId)}: ${Relay.trim(Relay.sanitize(it.text), 120)}" }
  }

  private val positionsSchema: JsonObject = Schema.obj(
    "positions" to Schema.array("One per agent", Schema.obj(
      "agent" to Schema.string("The agent's label as given"),
      "first" to Schema.string("Its first position as a short noun phrase, 2-5 words"),
      "final" to Schema.string("Its final position, same style; reuse the first's wording if unchanged"),
      "changed" to Schema.boolean("True if its final position differs from its first"),
      "new_reason" to Schema.boolean("True if it named a new fact or reason for changing"),
      "went_along_with" to Schema.string("If it changed with no new reason: the label it went along with, else empty"),
      "reason" to Schema.string("One clause: why it changed or kept its answer, from its own words"),
    )),
  )

  private val wrapSchema: JsonObject = Schema.obj(
    "answer" to Schema.string("The answer in the main agent's voice: verdict first in bold, at most two sentences"),
    "choices" to Schema.stringArray("At most two short tap-to-say choices for the user (e.g. Go with Rooh), or empty"),
    "your_call" to Schema.boolean("True when it's a split, or acting on it would spend money: the user decides"),
  )

  /** What the send gate sees for an ask: who wrote which part of it. */
  private fun segmentsOf(a: AskState): List<Segment> = when (a.purpose) {
    AskPurpose.BLIND -> listOf(Segment(a.text, Provenance.USER_VERBATIM))
    AskPurpose.TASK -> listOf(Segment(a.text, if (a.approved) Provenance.USER_APPROVED else Provenance.JUNO_REWRITE))
    AskPurpose.CHALLENGE -> listOf(Segment(a.text, Provenance.PEER_RELAY))
    AskPurpose.ROOM -> listOf(Segment(a.text, Provenance.ATALL)) + a.quoted.map { Segment(it, if (a.approved) Provenance.USER_APPROVED else Provenance.PEER_RELAY) }
  }

  private fun userNameNow(): String = runCatching { userName() }.getOrDefault("")

  // ============================================================== helpers

  private fun settle(a: AskState, status: AskStatus, now: Long, events: MutableList<ScoreEvent>): AskState {
    if (a.status == status) return a
    if (a.agentId != roster.main.id) {
      val within = status == AskStatus.ANSWERED && a.replies.isNotEmpty() && a.softDeadline?.let { a.replies.first().at <= it } != false
      events += ScoreEvent.AskSettled(a.agentId, now, a.id, status, within)
    }
    return a.copy(status = status, settledAt = now)
  }

  private fun soft(p: AgentProfile?) = p?.softTimeoutMs ?: if (p?.kind == AgentKind.API) config.apiSoftTimeoutMs else config.textSoftTimeoutMs
  private fun hard(p: AgentProfile?) = p?.hardTimeoutMs ?: if (p?.kind == AgentKind.API) config.apiHardTimeoutMs else config.textHardTimeoutMs

  private fun viaOf(r: PeerSend) = when (r) {
    is PeerSend.Sent -> if (r.via == Via.CONNECTOR) "connector" else "sent"
    is PeerSend.Duplicate -> "sent"; is PeerSend.Drafted -> "drafted"; is PeerSend.NeedsUser -> "needs_tap"
    is PeerSend.RateLimited -> "rate_limited"; is PeerSend.Failed -> "failed"
  }

  /** Texting agents with an in-flight ask in any run in memory (one at a time per texting agent). */
  private fun busyAgentsL(exceptRun: String? = null): Set<String> = cache.values.filter { it.runId != exceptRun }
    .flatMap { s -> s.asks.filter { it.status.inFlight && roster.byId(it.agentId)?.kind == AgentKind.TEXT }.map { it.agentId } }.toSet()

  private fun runIdOf(askId: String) = askId.substringBefore("~a")

  private fun viewOf(s: RunState) = Views.build(s, roster, clock(), config)

  private suspend fun getL(runId: String): RunState? = cache[runId] ?: store.load(runId)?.also { cache[runId] = it }

  private suspend fun putL(s: RunState) {
    val old = cache[s.runId]
    val v = s.copy(version = (old?.version ?: s.version) + 1)
    store.save(v)
    cache[v.runId] = v
    runCatching { traceDiff(old, v) }
    version.value++
    _updates.tryEmit(RunUpdate.Changed(v.runId))
  }

  /** One log line per change that matters: phase, mode, each ask's status and new reply parts, the map, the talk's stage. */
  private fun traceDiff(old: RunState?, s: RunState) {
    val id = s.runId
    if (old == null) { trace.event(id, "run.start", "${s.mode} ${s.phase} asks=${s.asks.size}", s.userText); }
    else {
      if (old.mode != s.mode) trace.event(id, "run.mode", "${old.mode} -> ${s.mode}", null)
      if (old.phase != s.phase) trace.event(id, "run.phase", "${old.phase} -> ${s.phase}" + (s.error?.let { " error=$it" } ?: ""), null)
    }
    for (a in s.asks) {
      val o = old?.ask(a.id)
      val who = roster.nameOf(a.agentId)
      if (o == null) trace.event(id, "ask.new", "${a.id} $who ${a.purpose} ${a.status}", a.text)
      else if (o.status != a.status) trace.event(id, "ask.status", "${a.id} $who ${o.status} -> ${a.status}" + (a.failReason?.let { " ($it)" } ?: ""), null)
      for (p in a.replies.drop(o?.replies?.size ?: 0)) trace.event(id, "reply", "${a.id} $who ${p.certainty}" + (if (p.late) " late" else ""), p.text)
    }
    val om = old?.agreement; val m = s.agreement
    if (m != null && (om == null || om.status != m.status || om.disputed.size != m.disputed.size || om.verdict != m.verdict))
      trace.event(id, "map", "${m.status} agreed=${m.agreed.size} disputed=${m.disputed.size} unique=${m.unique.size} level=${m.verdict?.level}", null)
    val or = old?.room; val r = s.room
    if (r != null && (or == null || or.stage != r.stage || or.rounds.size != r.rounds.size))
      trace.event(id, "talk", "stage=${r.stage} rounds=${r.rounds.size} trigger=${r.trigger}" + (r.endReason?.let { " end=$it" } ?: ""), null)
    if (s.answer != null && s.answer != old?.answer) trace.event(id, "answer", if (s.fallback) "fallback" else "model", s.answer)
  }

  private fun RunState.replaceAsk(a: AskState) = copy(asks = asks.map { if (it.id == a.id) a else it })
}
