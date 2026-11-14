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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
)

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
  suspend fun drive(runId: String, inline: Boolean = true): DriveResult {
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
          Phase.PLANNING -> { steps++; if (s.unprocessed.isNotEmpty()) processCalls(runId) else modelTurn(runId) }
          Phase.MERGING -> { steps++; merge(runId) }
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
            events += ScoreEvent.Unsolicited(signal.agentId, now)
            updates += RunUpdate.Unsolicited(signal.agentId, signal.text, signal.at)
            return@withLock
          }
          if (ask.replies.any { it.messageId == signal.messageId }) return@withLock
          val late = signal.late || ask.status.settled
          val part = ReplyPart(signal.messageId, signal.text, signal.at, signal.certainty, late, signal.userInterjected)
          if (ask.replies.isEmpty()) events += ScoreEvent.FirstReply(ask.agentId, now, ask.id, now - (ask.sentAt ?: ask.createdAt), signal.certainty, late)
          val updated = ask.copy(
            replies = ask.replies + part, status = AskStatus.REPLYING, lastReplyAt = now, settledAt = null,
            sentAt = ask.sentAt ?: now, afterMerge = ask.afterMerge || s.phase.terminal, followedUp = false,
          )
          putL(s.replaceAsk(updated))
          updates += RunUpdate.Changed(s.runId)
        }
        is PeerSignal.Closed -> {
          val s = getL(runIdOf(signal.correlationId)) ?: return@withLock
          val ask = s.ask(signal.correlationId) ?: return@withLock
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
        val until = clock() + config.maxWaitSeconds * 1000L
        putL(s.replaceAsk(a.copy(status = AskStatus.SENDING)).copy(pending = s.pending.map {
          if (it.kind == PendingKind.WAIT && askId in it.askIds && it.output == null) it.copy(until = maxOf(it.until ?: 0, until)) else it
        }))
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
      putL(s.copy(phase = if (s.phase.terminal) s.phase else Phase.CANCELLED, asks = s.asks.map { if (!it.status.settled) settle(it, AskStatus.CANCELLED, clock(), events) else it }))
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
      if (s.phase == Phase.DONE && id !in mutex.withLock { driving.toSet() }) lateWork(s)
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

  /** One model call with one retry on a transient error. Null when it failed (the run is finished or failed). */
  private suspend fun callModel(runId: String, req: LlmRequest): LlmResponse? {
    var attempt = 0
    while (true) {
      try {
        return llm.respond(req)
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
          val notes = buildList {
            if (a.uncertain) add("Uncertain: the user also typed in that chat, so this may not answer your message.")
            if (a.late) add("Arrived late.")
            if (a.status == AskStatus.REPLYING) add("May still be sending more.")
            if (a.status == AskStatus.LIKELY_MISSED) add("The reply was probably shown inside Messages where Atall can't read it.")
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
    val toSend = mutex.withLock { getL(runId)?.asks?.filter { it.status == AskStatus.SENDING && it.agentId != roster.main.id }.orEmpty() }
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
        val a = s.ask(a0.id)?.takeIf { it.status == AskStatus.SENDING } ?: continue
        val prof = roster.byId(a.agentId)
        fun sentAt(at: Long) = a.copy(sentAt = a.sentAt ?: at, softDeadline = at + soft(prof), hardDeadline = at + hard(prof))
        val next = when (r) {
          is PeerSend.Sent -> sentAt(r.at).copy(status = AskStatus.PENDING)
          is PeerSend.Duplicate -> sentAt(r.firstAt).copy(status = AskStatus.PENDING)
          is PeerSend.Drafted -> sentAt(r.at).copy(status = AskStatus.DRAFTED)
          is PeerSend.NeedsUser -> a.copy(status = AskStatus.NEEDS_TAP, draftText = r.draftText, draftNumber = r.number)
          is PeerSend.RateLimited -> settle(a.copy(failReason = "rate_limited"), AskStatus.FAILED, now, events)
          is PeerSend.Failed -> settle(a.copy(failReason = r.reason), AskStatus.FAILED, now, events)
        }
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
      if (p.output != null || (p.kind != PendingKind.WAIT && p.kind != PendingKind.CHALLENGE_WAIT)) p
      else {
        val all = p.askIds.mapNotNull { s.ask(it) }
        val done = all.all { it.status.settled } || (p.until != null && now >= p.until) || (s.mode == RunMode.COORDINATE && s.interjections.isNotEmpty())
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
      }
    }
    val open = s.pending.filter { it.output == null }
    return s.copy(phase = if (open.any { it.kind == PendingKind.APPROVAL || it.kind == PendingKind.ASK_USER }) Phase.AWAITING_USER else Phase.WAITING)
  }

  private fun resolvePending(s: RunState, askId: String, output: String): RunState =
    recompute(s.copy(pending = s.pending.map { if (it.kind == PendingKind.APPROVAL && askId in it.askIds && it.output == null) it.copy(output = output) else it }))

  private fun nextWake(s: RunState): Long {
    val now = clock()
    val times = buildList {
      s.pending.filter { it.output == null }.mapNotNull { it.until }.forEach { add(it) }
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
      val text = try { llm.respond(req).text.trim().ifEmpty { null } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
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
    val text = try { llm.respond(req).text.trim() } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
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
    val map = if (challenged != null) resolveChallenge(s, challenged) else compare(s)
    mutex.withLock {
      val cur = getL(runId) ?: return
      putL(cur.copy(agreement = map, phase = Phase.DONE, answer = map.verdict?.line, answeredAt = cur.answeredAt ?: clock()))
    }
    _updates.tryEmit(RunUpdate.MapUpdated(runId, map))
    if (challenged == null && config.autoChallenge) map.disputed.firstOrNull()?.let { challenge(runId, it.id) }
  }

  private suspend fun compare(s: RunState): AgreementMap {
    val blind = s.asks.filter { it.purpose == AskPurpose.BLIND }
    val answers = blind.filter { it.replies.isNotEmpty() }
    val raw = answers.map { RawAnswer(it.agentId, roster.nameOf(it.agentId), it.replyText, it.uncertain, it.late) }
    val base = AgreementMap(s.userText, MapStatus.READY, Views.participants(s, roster), raw = raw)
    if (answers.size < 2) {
      return base.copy(verdict = Verdict(VerdictLevel.TOO_FEW, Agreement.fallbackLine(VerdictLevel.TOO_FEW, null, roster::nameOf), 0, 0, 0))
    }
    val labels = answers.mapIndexed { i, a -> Agreement.label(i) to a.agentId }.toMap()
    val input = buildString {
      append("Question: ").append(s.userText).append("\n")
      answers.forEachIndexed { i, a -> append("\nAnswer ").append(Agreement.label(i)).append(":\n").append(a.replyText.trim()).append("\n") }
    }
    val parsed = try {
      Agreement.parseClaims(llm.respond(LlmRequest("claims", Prompts.render("claims_extract"), listOf(Items.user(input)), jsonSchema = Agreement.claimsSchema, schemaName = "claims")).text, labels)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    val clusters = parsed?.let { Agreement.cluster(it) }
    return withVerdict(base.copy(agreed = clusters?.agreed.orEmpty(), disputed = clusters?.disputed.orEmpty(), unique = clusters?.unique.orEmpty()), answers.size, clusters == null)
  }

  private suspend fun withVerdict(map: AgreementMap, answered: Int, unclear: Boolean): AgreementMap {
    val c = if (unclear) null else Agreement.Clusters(map.agreed, map.disputed, map.unique)
    val level = Agreement.level(answered, c)
    val line = if (c == null || level == VerdictLevel.TOO_FEW || level == VerdictLevel.UNCLEAR) Agreement.fallbackLine(level, c, roster::nameOf)
    else try {
      llm.respond(LlmRequest("verdict", Prompts.render("verdict", mapOf("main" to roster.main.name)), listOf(Items.user(Agreement.describe(map.question, c, roster::nameOf))))).text.trim()
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
        val o = parseJsonObject(llm.respond(LlmRequest("challenge", Prompts.render("challenge_resolve"), listOf(Items.user(input)), jsonSchema = Agreement.challengeSchema, schemaName = "positions")).text)
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
    is PeerSend.Sent, is PeerSend.Duplicate -> "sent"; is PeerSend.Drafted -> "drafted"; is PeerSend.NeedsUser -> "needs_tap"
    is PeerSend.RateLimited -> "rate_limited"; is PeerSend.Failed -> "failed"
  }

  /** Texting agents with an in-flight ask in any run in memory (one at a time per texting agent). */
  private fun busyAgentsL(exceptRun: String? = null): Set<String> = cache.values.filter { it.runId != exceptRun }
    .flatMap { s -> s.asks.filter { it.status.inFlight && roster.byId(it.agentId)?.kind == AgentKind.TEXT }.map { it.agentId } }.toSet()

  private fun runIdOf(askId: String) = askId.substringBefore("~a")

  private fun viewOf(s: RunState) = Views.build(s, roster, clock(), config)

  private suspend fun getL(runId: String): RunState? = cache[runId] ?: store.load(runId)?.also { cache[runId] = it }

  private suspend fun putL(s: RunState) {
    val v = s.copy(version = (cache[s.runId]?.version ?: s.version) + 1)
    store.save(v)
    cache[v.runId] = v
    version.value++
    _updates.tryEmit(RunUpdate.Changed(v.runId))
  }

  private fun RunState.replaceAsk(a: AskState) = copy(asks = asks.map { if (it.id == a.id) a else it })
}
