package app.atall.agents.text

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

data class TextAgentConfig(
  /** How long a request waits for its first reply. RCS delivery can lag minutes. */
  val replyWindowMs: Long = 30 * 60_000L,
  /** Use a captured reply action this long after its notification went away (proven ~1 min; spike used 15). */
  val actionGraceMs: Long = 15 * 60_000L,
  val maxTextLength: Int = 2_000,
  val maxOutstandingPerAgent: Int = 4,
  val correlation: Correlator.Tuning = Correlator.Tuning(),
  val rateLimits: RateLimiter.Limits = RateLimiter.Limits(),
  /** Plain-text (non-MessagingStyle) notifications have no stable time: same text within this is a re-post. */
  val fallbackDedupeMs: Long = 10 * 60_000L,
  /** A self line equal to something we sent within this is our echo, not the user typing. */
  val echoWindowMs: Long = 10 * 60_000L,
  /** Lines older than the agent's newest seen line by more than this are old history (after log trims). */
  val staleHistoryMs: Long = 24 * 3_600_000L,
  /** Closed requests stay matchable this long for replies whose timestamp is in-window but arrive late. */
  val lateMatchMs: Long = 60 * 60_000L,
  /** Messages may cancel the notification right after our own action reply; that is not the user opening it. */
  val ownReplyCancelGuardMs: Long = 15_000L,
  /** Buffer of the hot flows; a slow collector loses the oldest (the log keeps everything). */
  val flowBuffer: Int = 256,
)

/** Whether the agent can be answered silently right now (for UI). */
data class LiveStatus(val hasAction: Boolean, val usable: Boolean, val capturedAt: Long, val removedAt: Long)

/**
 * Route C core: owns the registry, the durable log, outstanding requests, live reply actions and correlation.
 * The Android listener feeds it [onPosted]/[onRemoved]/[onRescan]; the coordinator talks to [agent]s.
 * Thread-safe: every entry point takes one lock; file writes are small. Flows are emitted under the lock with
 * tryEmit (never blocks), so order is preserved.
 */
class TextAgentHub(
  private val store: TextAgentStore,
  private val drafts: DraftLauncher,
  private val clock: Clock = SystemClock,
  val config: TextAgentConfig = TextAgentConfig(),
  private val random: java.util.Random = java.security.SecureRandom(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /**
   * Debug diagnostics (on-device log only). Called with one line per capture/fallback decision. Lines carry agent
   * ids, reasons and ages, never message text or numbers.
   */
  private val diagnostics: (String) -> Unit = {},
) {
  private class Live(val handle: ReplyHandle, val key: String, var capturedAt: Long, val source: String?, var removedAt: Long = 0,
                     var removedReason: RemovalReason? = null)
  /** What the listener last saw for an agent's conversation; diagnostics only. */
  private class Seen(var at: Long = 0, var hadReply: Boolean = false, var actions: Int = 0, var source: String? = null)
  private data class Recent(val text: String, val at: Long)

  private val lock = Any()
  private var agents: List<AgentConfig> = store.agents().map { it.normalized() }
  private var requests: MutableList<OutstandingRequest> = store.requests().toMutableList()
  private val keys: LinkedHashMap<String, Long> = store.keys()
  private val meta: MutableMap<String, Long> = store.meta().toMutableMap()
  /** Captured reply actions per agent conversation, newest first. Older ones are kept as fallbacks (a re-post may
   *  carry a fresh PendingIntent while the old one still works, or the new one may be cancelled). */
  private val live = HashMap<AgentId, ArrayDeque<Live>>()
  private val seen = HashMap<AgentId, Seen>()
  private val lastOwnReplyAt = HashMap<AgentId, Long>()
  private val recentOut = HashMap<AgentId, ArrayDeque<Recent>>()
  private val recentIn = HashMap<AgentId, ArrayDeque<Recent>>()
  private val limiter = RateLimiter(config.rateLimits)
  private val handles = HashMap<AgentId, TextAgent>()
  private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = config.flowBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Request closures, thread-opened and user-typed signals. Hot, not replayed. */
  val events: SharedFlow<HubEvent> = _events.asSharedFlow()

  init {
    for (e in store.log(null, 400)) when (e.dir) {
      Direction.OUT -> remember(recentOut, e.agentId, e.text, e.at)
      Direction.IN -> remember(recentIn, e.agentId, e.text, e.at)
      Direction.USER -> {}
    }
  }

  // ================================================================== registry

  fun agents(): List<AgentConfig> = synchronized(lock) { agents.toList() }

  /** Add or replace by id. Rejects a name/alias that another agent already answers to (routing would be ambiguous). */
  fun register(agent: AgentConfig): TextAgent = synchronized(lock) {
    val a = agent.normalized()
    val titles = (listOf(a.name) + a.aliases).map(Rules::fold)
    for (o in agents) {
      if (o.id == a.id) continue
      val clash = titles.any { t -> Rules.matches(o, t) } || (listOf(o.name) + o.aliases).any { Rules.matches(a, it) } ||
        (a.number.isNotEmpty() && o.number.isNotEmpty() && Rules.matches(o, a.number))
      require(!clash) { "agent ${a.id} overlaps ${o.id}" }
    }
    agents = agents.filter { it.id != a.id } + a
    store.saveAgents(agents)
    if (meta["reg:${a.id}"] == null) { meta["reg:${a.id}"] = clock.now(); store.saveMeta(meta) }
    handleFor(a.id)
  }

  fun unregister(id: AgentId) = synchronized(lock) {
    agents = agents.filter { it.id != id }
    store.saveAgents(agents)
    live.remove(id)
    seen.remove(id)
    if (requests.removeAll { it.agentId == id }) store.saveRequests(requests)
    handles.remove(id)
    meta.keys.removeAll { it.endsWith(":$id") }
    store.saveMeta(meta)
  }

  fun agent(id: AgentId): TextAgent? = synchronized(lock) { if (agents.any { it.id == id }) handleFor(id) else null }

  private fun handleFor(id: AgentId) = handles.getOrPut(id) { TextAgent(id, this) }

  fun liveStatus(id: AgentId): LiveStatus = synchronized(lock) {
    val all = live[id]?.takeIf { it.isNotEmpty() } ?: return LiveStatus(false, false, 0, 0)
    val now = clock.now()
    val l = all.firstOrNull { usable(it, now) } ?: all.first()
    LiveStatus(true, usable(l, now), l.capturedAt, l.removedAt)
  }

  private fun capture(id: AgentId, s: NotificationSnapshot, now: Long) {
    val sn = seen.getOrPut(id) { Seen() }
    sn.at = now; sn.hadReply = s.reply != null; sn.actions = s.actionCount; sn.source = s.replySource
    val handle = s.reply
    if (handle == null) {
      // A MessagingStyle update without an action (e.g. Messages' own "sending…" re-post) never drops what we hold.
      diag("capture agent=$id reply=absent actions=${s.actionCount} kept=${live[id]?.size ?: 0}")
      return
    }
    val q = live.getOrPut(id) { ArrayDeque() }
    val ident = handle.identity
    val same = if (ident != null) q.firstOrNull { it.handle.identity == ident } else null
    if (same != null) {
      q.remove(same)
      // Same PendingIntent re-posted: it is showing again, so its age restarts.
      q.addFirst(Live(handle, s.key, now, s.replySource))
    } else {
      q.addFirst(Live(handle, s.key, now, s.replySource))
    }
    while (q.size > MAX_HANDLES) q.removeLast()
    diag("capture agent=$id reply=${s.replySource ?: "yes"} actions=${s.actionCount} held=${q.size}")
  }

  private fun diag(line: String) { runCatching { diagnostics(line) } }

  /**
   * Whether a held reply action may be fired. A notification Messages cancelled itself (APP_CANCEL: the chat was
   * opened or read) does not take its reply action with it (Route C: the PendingIntent keeps working after the
   * notification clears), so APP_CANCEL never starts the grace clock; a real failure shows up as CANCELED/ERROR from
   * fire() and falls back to an older action or a draft. Other removals (swiped away, ...) get [TextAgentConfig.actionGraceMs].
   */
  private fun usable(l: Live, now: Long) =
    l.removedAt == 0L || l.removedReason == RemovalReason.APP_CANCEL || now - l.removedAt <= config.actionGraceMs

  private companion object { const val MAX_HANDLES = 3 }

  // ================================================================== listener side

  /** A notification was posted or updated. Non-agent notifications are ignored untouched. */
  fun onPosted(s: NotificationSnapshot) = synchronized(lock) {
    val agent = Parsing.route(s, agents) ?: return@synchronized
    val now = clock.now()
    capture(agent.id, s, now)
    var changed = false
    for (line in Parsing.lines(s, agent.id)) {
      changed = (if (line.self) selfLine(agent.id, line, now) else agentLine(agent.id, line, now)) || changed
    }
    sweepLocked(now, changed)
  }

  /** The listener (re)connected: re-read everything still showing. Recovers reply actions after a restart. */
  fun onRescan(active: List<NotificationSnapshot>) { for (s in active) onPosted(s) }

  fun onRemoved(key: String, reason: RemovalReason) = synchronized(lock) {
    val now = clock.now()
    var id: AgentId? = null
    for ((agent, q) in live) for (l in q) if (l.key == key && l.removedAt == 0L) { l.removedAt = now; l.removedReason = reason; id = agent }
    if (id == null) return@synchronized
    // m05: APP_CANCEL is not "agent gone". The open requests stay open until their own reply window, and the
    // held reply action stays usable (see [usable]); the only effect is the opened-chat hint below.
    diag("removed agent=$id reason=$reason (requests stay open, action kept)")
    val opened = reason == RemovalReason.APP_CANCEL || reason == RemovalReason.CLICK
    val ownReply = lastOwnReplyAt[id]?.let { now - it <= config.ownReplyCancelGuardMs } ?: false
    if (opened && !ownReply) {
      var marked = false
      requests.replaceAll { r ->
        if (r.agentId == id && r.closedAt == 0L) { marked = true; r.copy(threadOpenedAt = now) } else r
      }
      if (marked) { store.saveRequests(requests); _events.tryEmit(ThreadOpened(id, now)) }
    }
  }

  /** The user's own line (or our echo). Returns whether requests changed. */
  private fun selfLine(id: AgentId, line: ParsedLine, now: Long): Boolean {
    if (!fresh(id, line)) return false
    // The user's own tapback in Messages is neither our echo nor the user typing to the agent.
    if (Reactions.parse(line.text) != null) return false
    val skew = config.correlation.clockSkewMs
    // Our own request coming back: confirms it reached the thread; for a draft, that the user tapped Send.
    val idx = requests.indexOfFirst {
      it.agentId == id && it.echoedAt == 0L && line.time >= it.sentAt - skew && Rules.sameText(it.sentText, line.text)
    }
    if (idx >= 0) {
      val r = requests[idx]
      requests[idx] = if (r.state == RequestState.AWAITING_USER_SEND)
        r.copy(state = RequestState.WAITING, sentAt = line.time, echoedAt = line.time, replyDeadline = r.replyDeadline - r.sentAt + line.time)
      else r.copy(echoedAt = line.time)
      return true
    }
    if (recentOut[id]?.any { Rules.sameText(it.text, line.text) && line.time >= it.at - skew && line.time - it.at <= config.echoWindowMs } == true) return false
    store.append(LogEntry(line.id, id, Direction.USER, line.text, line.time))
    if (line.time < (meta["reg:$id"] ?: Long.MIN_VALUE)) return false // history from before registration
    if (line.time > (meta["user:$id"] ?: 0)) { meta["user:$id"] = line.time; store.saveMeta(meta) }
    _events.tryEmit(UserLine(id, line.text, line.time))
    return false
  }

  /** A line from the agent. Returns whether requests changed. */
  private fun agentLine(id: AgentId, line: ParsedLine, now: Long): Boolean {
    if (!line.timeReliable) {
      // No MessagingStyle: post time changes on every re-post, so dedupe by text, and drop our own echo.
      if (recentOut[id]?.any { Rules.sameText(it.text, line.text) && now - it.at <= config.echoWindowMs } == true) return false
      if (recentIn[id]?.any { Rules.sameText(it.text, line.text) && now - it.at <= config.fallbackDedupeMs } == true) return false
    }
    if (!fresh(id, line)) return false
    val historical = line.time < (meta["reg:$id"] ?: Long.MIN_VALUE)
    // A quoted tapback, or "🧳 to <one of our texts>" with the quote marks lost.
    val parsed = Reactions.parse(line.text) ?: Reactions.parseUnquoted(line.text)?.takeIf { reactionTarget(id, it) != null }
    parsed?.let { r ->
      // A tapback is not an answer: never attributed to a request (it would swallow the real reply that follows).
      val reaction = r.copy(targetCorrelationId = reactionTarget(id, r))
      store.append(LogEntry(line.id, id, Direction.IN, line.text, line.time, reaction = reaction))
      handleFor(id).emit(AgentMessage(line.id, id, line.text, line.time, now, null, historical, reaction))
      return false
    }
    var correlation: Correlation? = null
    var changed = false
    // The agent's own status text ("Looks like your signal dropped", "Let me check"): tied to the request it is about,
    // but not an answer, so the request stays unanswered and keeps its reply window.
    val nonAnswer = NonAnswers.classify(line.text)
    if (!historical) {
      val candidates = requests.filter {
        it.agentId == id && (it.closedAt == 0L || now - it.closedAt <= config.lateMatchMs)
      }
      Correlator.decide(candidates, line.text, line.time, meta["user:$id"] ?: 0, config.correlation)?.let { d ->
        correlation = d.correlation.copy(late = d.request.closedAt > 0)
        if (nonAnswer == null) {
          val i = requests.indexOfFirst { it.correlationId == d.request.correlationId }
          requests[i] = Correlator.attributed(requests[i], line.time)
          changed = true
        } else diag("status agent=$id kind=$nonAnswer request stays open")
      }
    }
    store.append(LogEntry(line.id, id, Direction.IN, line.text, line.time, correlation = correlation))
    remember(recentIn, id, line.text, if (line.timeReliable) line.time else now)
    handleFor(id).emit(AgentMessage(line.id, id, line.text, line.time, now, correlation, historical, nonAnswer = nonAnswer))
    return changed
  }

  /** The request whose sent text the reaction quotes, newest first (open, recently closed, then the log). */
  private fun reactionTarget(id: AgentId, r: Reaction): String? {
    requests.filter { it.agentId == id }.sortedByDescending { it.sentAt }
      .firstOrNull { Reactions.quotes(r.quoted, it.sentText) }?.let { return it.correlationId }
    return store.log(id, 100).asReversed()
      .firstOrNull { it.dir == Direction.OUT && it.correlationId != null && Reactions.quotes(r.quoted, it.text) }?.correlationId
  }

  /** Not seen before and not ancient history; records the key and watermark. */
  private fun fresh(id: AgentId, line: ParsedLine): Boolean {
    if (keys.containsKey(line.id)) return false
    val wmKey = "wm:$id"
    val wm = meta[wmKey]
    addKey(line.id, line.time)
    if (wm != null && line.time < wm - config.staleHistoryMs) return false
    if (wm == null || line.time > wm) { meta[wmKey] = line.time; store.saveMeta(meta) }
    return true
  }

  private fun addKey(k: String, at: Long) {
    keys[k] = at
    store.addKey(k, at)
    while (keys.size > TextAgentStore.KEYS_MAX) keys.remove(keys.keys.first())
  }

  private fun remember(map: HashMap<AgentId, ArrayDeque<Recent>>, id: AgentId, text: String, at: Long) {
    val q = map.getOrPut(id) { ArrayDeque() }
    q.addLast(Recent(text, at))
    while (q.size > 50) q.removeFirst()
  }

  // ================================================================== sweeping

  /** Close requests whose windows passed; emit [RequestClosed]. Cheap; also runs on every event. */
  fun sweep() = synchronized(lock) { sweepLocked(clock.now(), false) }

  private fun sweepLocked(now: Long, dirty: Boolean) {
    var changed = dirty
    val it = requests.listIterator()
    while (it.hasNext()) {
      val r = it.next()
      if (r.closedAt > 0) {
        if (now - r.closedAt > config.lateMatchMs) { it.remove(); changed = true }
        continue
      }
      val outcome = Correlator.closure(r, now, config.correlation) ?: continue
      val closed = r.copy(closedAt = now)
      it.set(closed)
      changed = true
      _events.tryEmit(RequestClosed(closed, outcome))
    }
    if (changed) store.saveRequests(requests)
  }

  // ================================================================== agent side (called by TextAgent)

  internal suspend fun send(id: AgentId, rawText: String, cid: String, options: SendOptions): SendResult = withContext(io) {
    synchronized(lock) { sendLocked(id, rawText, cid, options) }
  }

  private fun sendLocked(id: AgentId, rawText: String, cid: String, options: SendOptions): SendResult {
    val text = rawText.trim()
    if (cid.isBlank() || cid.length > 128 || cid.any { it == '\n' || it == '\t' || it == '\r' }) return SendResult.Failed(cid, FailReason.BAD_CORRELATION_ID)
    keys["cid|$cid"]?.let { return SendResult.Duplicate(cid, it) }
    if (text.isEmpty()) return SendResult.Failed(cid, FailReason.EMPTY)
    if (text.length > config.maxTextLength) return SendResult.Failed(cid, FailReason.TOO_LONG)
    val agent = agents.firstOrNull { it.id == id } ?: return SendResult.Failed(cid, FailReason.UNKNOWN_AGENT)
    val now = clock.now()
    sweepLocked(now, false)
    if (options.expectReply && requests.count { it.agentId == id && it.closedAt == 0L } >= config.maxOutstandingPerAgent) {
      return SendResult.Failed(cid, FailReason.TOO_MANY_OUTSTANDING)
    }
    limiter.check(id, now)?.let { (scope, wait) -> return SendResult.RateLimited(cid, wait, scope) }

    val tag = if (options.tag && options.expectReply) Correlator.newTag(requests.mapNotNull { it.tag }.toSet(), random) else null
    val sentText = if (tag != null) Correlator.withTag(text, tag) else text

    var draftReason = DraftReason.NO_LIVE_ACTION
    val held = live[id]
    if (held != null && held.isNotEmpty()) {
      draftReason = DraftReason.ACTION_EXPIRED
      for (l in held.toList()) {
        if (!usable(l, now)) continue
        // Record the request and the echo before firing: Messages may re-post (and we may hear our own line)
        // before fire() returns, re-entering this hub on the same thread.
        val provisional = newRequest(id, cid, text, sentText, now, Via.ACTION, tag, options)
        provisional?.let { requests.add(it) }
        remember(recentOut, id, sentText, now)
        lastOwnReplyAt[id] = now
        val fired = runCatching { l.handle.fire(sentText) }.getOrDefault(FireResult.ERROR)
        if (fired == FireResult.OK) {
          accept(id, cid, sentText, now, Via.ACTION, null)
          diag("send agent=$id via=action age=${(now - l.capturedAt) / 1000}s removed=${if (l.removedAt == 0L) "no" else "${(now - l.removedAt) / 1000}s"} source=${l.source}")
          return SendResult.Sent(cid, now, now - l.capturedAt, sentText)
        }
        draftReason = if (fired == FireResult.CANCELED) DraftReason.ACTION_CANCELED else DraftReason.ACTION_FAILED
        diag("action unusable agent=$id result=$fired age=${(now - l.capturedAt) / 1000}s; trying older")
        held.remove(l)
        lastOwnReplyAt.remove(id)
        provisional?.let { p -> requests.removeAll { it.correlationId == p.correlationId } }
        recentOut[id]?.removeLastOrNull()
      }
    }
    if (draftReason == DraftReason.ACTION_EXPIRED || draftReason == DraftReason.NO_LIVE_ACTION || held.isNullOrEmpty()) {
      val sn = seen[id]
      val newest = held?.firstOrNull()
      diag("fallback agent=$id reason=$draftReason allowDraft=${options.allowDraft} held=${held?.size ?: 0}" +
        (newest?.let { " newestAge=${(now - it.capturedAt) / 1000}s removedFor=${if (it.removedAt == 0L) 0 else (now - it.removedAt) / 1000}s" } ?: "") +
        (sn?.let { " lastNotif=${(now - it.at) / 1000}s ago hadReply=${it.hadReply} actions=${it.actions}" } ?: " lastNotif=never"))
    } else {
      diag("fallback agent=$id reason=$draftReason allowDraft=${options.allowDraft}")
    }
    if (agent.number.isEmpty()) return SendResult.Failed(cid, FailReason.NO_NUMBER)
    val draft = DraftRequest(agent.number, sentText)
    if (!options.allowDraft) return SendResult.NeedsUser(cid, NeedsUserReason.DRAFTS_DISABLED, draft)
    return when (runCatching { drafts.open(draft) }.getOrDefault(DraftOutcome.NO_APP)) {
      DraftOutcome.OPENED -> {
        remember(recentOut, id, sentText, now)
        accept(id, cid, sentText, now, Via.DRAFT, newRequest(id, cid, text, sentText, now, Via.DRAFT, tag, options))
        SendResult.Drafted(cid, now, draftReason, sentText)
      }
      DraftOutcome.IN_BACKGROUND -> SendResult.NeedsUser(cid, NeedsUserReason.APP_IN_BACKGROUND, draft)
      DraftOutcome.NO_APP -> SendResult.Failed(cid, FailReason.NO_MESSAGES_APP)
    }
  }

  /** Bookkeeping for an accepted send; [request] (if any) is added, else it was added provisionally. */
  private fun accept(id: AgentId, cid: String, sentText: String, now: Long, via: Via, request: OutstandingRequest?) {
    limiter.record(id, now)
    addKey("cid|$cid", now)
    store.append(LogEntry("out|$cid", id, Direction.OUT, sentText, now, via, correlationId = cid))
    request?.let { requests.add(it) }
    store.saveRequests(requests)
  }

  private fun newRequest(id: AgentId, cid: String, text: String, sentText: String, now: Long, via: Via, tag: String?, options: SendOptions): OutstandingRequest? {
    if (!options.expectReply) return null
    val window = (options.replyWindowMs ?: config.replyWindowMs).coerceAtLeast(1_000)
    return OutstandingRequest(
      correlationId = cid, agentId = id, text = text, sentText = sentText, sentAt = now, via = via,
      state = if (via == Via.DRAFT) RequestState.AWAITING_USER_SEND else RequestState.WAITING,
      replyDeadline = now + window, tag = tag,
      // A draft opens the chat in Messages: a reply arriving while it stays open gets no notification.
      threadOpenedAt = if (via == Via.DRAFT) now else 0,
    )
  }

  internal fun history(id: AgentId, limit: Int): List<LogEntry> = synchronized(lock) { store.log(id, limit.coerceIn(1, 1000)) }

  internal fun outstanding(id: AgentId): List<OutstandingRequest> = synchronized(lock) {
    sweepLocked(clock.now(), false)
    requests.filter { it.agentId == id && it.closedAt == 0L }.sortedBy { it.sentAt }
  }

  internal fun close(id: AgentId, cid: String) = synchronized(lock) {
    val r = requests.firstOrNull { it.agentId == id && it.correlationId == cid } ?: return@synchronized
    requests.remove(r)
    store.saveRequests(requests)
    if (r.closedAt == 0L) _events.tryEmit(RequestClosed(r.copy(closedAt = clock.now()), RequestClosed.Outcome.CLOSED_BY_CALLER))
  }
}

/** The [Agent] for one registered texting agent. Obtain from [TextAgentHub.register] / [TextAgentHub.agent]. */
class TextAgent internal constructor(override val id: AgentId, private val hub: TextAgentHub) : Agent {
  private val flow = MutableSharedFlow<AgentMessage>(extraBufferCapacity = hub.config.flowBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  override val displayName: String get() = hub.agents().firstOrNull { it.id == id }?.name ?: id.value
  override val incoming: Flow<AgentMessage> = flow.asSharedFlow()

  internal fun emit(m: AgentMessage) { flow.tryEmit(m) }

  override suspend fun send(text: String, correlationId: String, options: SendOptions) = hub.send(id, text, correlationId, options)
  override suspend fun history(limit: Int) = hub.history(id, limit)
  override suspend fun outstanding() = hub.outstanding(id)
  override suspend fun close(correlationId: String) = hub.close(id, correlationId)
}
