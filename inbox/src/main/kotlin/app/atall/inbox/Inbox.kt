package app.atall.inbox

import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InboxConfig(
  val zone: ZoneId = ZoneId.systemDefault(),
  /** How far apart two messages can be and still fold into one item. */
  val dedupeWindowMs: Long = 48 * 3600_000L,
  /** A deadline closer than this makes a message Needs you; further out is FYI. */
  val needsYouHorizonMs: Long = 72 * 3600_000L,
  /** Codes stop needing the user (and leave memory) after this. */
  val codeTtlMs: Long = 10 * 60_000L,
  /** Ask the LLM when the heuristics are unsure. Off = unsure messages land in FYI. */
  val llmEnabled: Boolean = true,
  /** Per item. After this many failed LLM tries the item keeps its provisional lane (FYI) as DEFAULT. */
  val maxLlmAttempts: Int = 2,
  val llmBatch: Int = 20,
  /** Clear the agent's own Messages notification for duplicates too, not only for Noise. Off by default
   *  (innovation.md #5: suppress only Noise, with undo). */
  val suppressDuplicateOriginals: Boolean = false,
  val digest: DigestSchedule = DigestSchedule(zone = zone),
  /** Retention: closed items, and Noise, are dropped after these ages (by last activity). */
  val keepClosedMs: Long = 14 * 24 * 3600_000L,
  val keepNoiseMs: Long = 3 * 24 * 3600_000L,
)

/**
 * The Agent Inbox. Feed it every agent message ([ingest]); it ignores replies to Juno's requests, redacts codes,
 * folds near-duplicates across agents, triages, persists, and exposes UI state, the waiting-on-you list and the
 * digest. Thread-safe: state changes happen under one lock; the LLM is called outside it.
 */
class AgentInbox(
  private val store: InboxStore,
  private val classifier: InboxClassifier? = null,
  private val clock: Clock = SystemClock,
  val config: InboxConfig = InboxConfig(),
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val lock = Any()
  private val llmMutex = Mutex()
  private val items = LinkedHashMap<String, InboxItem>()
  private val feats = HashMap<String, List<Features>>()
  private val seen = LinkedHashMap<String, Long>()
  private val meta = HashMap<String, Long>()
  private val triage = HeuristicTriage(config.needsYouHorizonMs)
  private val deduper = Deduper(config.dedupeWindowMs)
  private val digests = DigestBuilder(config.zone)
  private val codes = CodeShelf(clock, config.codeTtlMs)
  private val _state = MutableStateFlow(InboxUiState())

  /** Live UI state for the inbox screen. */
  val state: StateFlow<InboxUiState> = _state.asStateFlow()

  init {
    val snap = store.load()
    snap.items.forEach { items[it.id] = it; feats[it.id] = featuresOf(it) }
    seen.putAll(snap.seen)
    meta.putAll(snap.meta)
    // Codes never survive a restart (they lived only in memory), so code items are past needing the user.
    publish()
  }

  // ---------------------------------------------------------------- ingest

  suspend fun ingest(msg: IncomingMessage): IngestResult {
    if (msg.inReplyTo != null) return IngestResult.IgnoredReply
    val first = synchronized(lock) { ingestLocked(msg) }
    if (first !is Pending) return first.result
    classifyPending()
    return synchronized(lock) {
      val item = items[first.itemId] ?: return@synchronized first.result
      val n = notifyFor(item, first.wasNeedsYou, msg.historical, first.isNew)
      val suppress = item.lane == Lane.NOISE || (!first.isNew && config.suppressDuplicateOriginals)
      if (first.isNew) IngestResult.Added(item, n, suppress) else IngestResult.Merged(item, first.crossAgent, n, suppress)
    }
  }

  suspend fun ingestAll(msgs: List<IncomingMessage>): List<IngestResult> = msgs.sortedBy { it.sentAt }.map { ingest(it) }

  private sealed interface Step { val result: IngestResult }
  private data class Done(override val result: IngestResult) : Step
  private data class Pending(
    override val result: IngestResult, val itemId: String, val isNew: Boolean, val crossAgent: Boolean, val wasNeedsYou: Boolean,
  ) : Step

  private fun ingestLocked(msg: IncomingMessage): Step {
    if (msg.id in seen) return Done(IngestResult.AlreadySeen)
    val now = clock.now()
    val red = Redactor.redact(msg.text)
    val f = TextFeatures.extract(red.text, msg.sentAt, config.zone)
    val v = triage.evaluate(red.text, red.hasCode, f, msg.sentAt)
    val src = Source(msg.id, msg.agentId, msg.agentName, red.text, msg.sentAt)
    val wantLlm = v.needsLlm && !red.hasCode && config.llmEnabled && classifier != null

    seen[msg.id] = now
    while (seen.size > InboxStore.SEEN_MAX) seen.remove(seen.keys.first())
    store.markSeen(msg.id, now)

    val match = deduper.find(f, msg.sentAt, items.values.map { it to (feats[it.id] ?: emptyList()) })
    if (match != null) {
      val old = match.item
      val crossAgent = old.agentIds.none { it == msg.agentId }
      val wasNeedsYou = old.lane == Lane.NEEDS_YOU && old.isOpenAt(msg.sentAt)
      var lane = old.lane
      var by = old.triagedBy
      var conf = old.confidence
      var reason = old.reason
      var awaiting = old.awaitingLlm
      if (old.triagedBy != TriagedBy.USER && v.confidence >= Confidence.MEDIUM && rank(v.lane) > rank(old.lane)) {
        lane = v.lane; by = TriagedBy.HEURISTIC; conf = v.confidence; reason = v.reason; awaiting = false
      }
      // Reopen a Done item when a new message says it needs the user again; a Dismissed one stays dismissed.
      val reopen = old.state == ItemState.DONE && lane == Lane.NEEDS_YOU && v.lane == Lane.NEEDS_YOU && v.confidence == Confidence.HIGH
      val item = old.copy(
        sources = old.sources + src,
        lastAt = maxOf(old.lastAt, msg.sentAt),
        firstAt = minOf(old.firstAt, msg.sentAt),
        lane = lane, triagedBy = by, confidence = conf, reason = reason, awaitingLlm = awaiting,
        signals = old.signals + v.signals,
        dueAt = listOfNotNull(old.dueAt, f.whenAt).minOrNull(),
        hasCode = old.hasCode || red.hasCode,
        expiresAt = if (red.hasCode) msg.sentAt + config.codeTtlMs else old.expiresAt,
        state = if (reopen) ItemState.OPEN else old.state,
        stateAt = if (reopen) now else old.stateAt,
        snoozedUntil = if (reopen) 0 else old.snoozedUntil,
      )
      put(item, feats[old.id].orEmpty() + f)
      if (red.hasCode) codes.put(item.id, red.codes)
      publish()
      val n = notifyFor(item, wasNeedsYou && !reopen, msg.historical, isNew = false)
      val suppress = item.lane == Lane.NOISE || config.suppressDuplicateOriginals
      val res = IngestResult.Merged(item, crossAgent, n, suppress)
      return if (item.awaitingLlm && wantLlm) Pending(res, item.id, false, crossAgent, wasNeedsYou) else Done(res)
    }

    val item = InboxItem(
      id = newId(),
      title = titleOf(red.text),
      lane = v.lane,
      triagedBy = TriagedBy.HEURISTIC,
      confidence = v.confidence,
      reason = v.reason,
      signals = v.signals,
      sources = listOf(src),
      firstAt = msg.sentAt,
      lastAt = msg.sentAt,
      dueAt = f.whenAt,
      hasCode = red.hasCode,
      expiresAt = if (red.hasCode) msg.sentAt + config.codeTtlMs else null,
      awaitingLlm = wantLlm,
    )
    put(item, listOf(f))
    if (red.hasCode) codes.put(item.id, red.codes)
    publish()
    val res = IngestResult.Added(item, notifyFor(item, false, msg.historical, isNew = true), item.lane == Lane.NOISE)
    return if (wantLlm) Pending(res, item.id, true, false, false) else Done(res)
  }

  private fun notifyFor(item: InboxItem, wasNeedsYou: Boolean, historical: Boolean, isNew: Boolean): Notify = when {
    historical -> Notify.NONE
    !item.isOpenAt(clock.now()) -> Notify.NONE
    item.lane == Lane.NEEDS_YOU && !wasNeedsYou -> Notify.BUZZ
    !isNew -> Notify.NONE // a duplicate of a card already shown
    item.lane == Lane.FYI -> Notify.QUIET
    else -> Notify.NONE
  }

  private fun rank(l: Lane) = when (l) { Lane.NOISE -> 0; Lane.FYI -> 1; Lane.NEEDS_YOU -> 2 }

  // ---------------------------------------------------------------- LLM fallback

  /**
   * Classifies items the heuristics were unsure about, in one batched call. Code items never go out, and every
   * text passes [LlmGate]. Returns how many items got an LLM lane. Safe to call any time (e.g. on sign-in).
   */
  suspend fun classifyPending(): Int {
    val c = classifier ?: return 0
    if (!config.llmEnabled) return 0
    return llmMutex.withLock {
      val batch = synchronized(lock) {
        items.values.filter { it.awaitingLlm && !it.hasCode && it.triagedBy != TriagedBy.USER && it.llmAttempts < config.maxLlmAttempts }
          .sortedBy { it.firstAt }.take(config.llmBatch)
          .map { i ->
            ClassifyItem(i.id, i.agentNames.joinToString(", "), i.sources.joinToString("\n") { it.text }.take(600), i.signals.map { it.name.lowercase() })
          }
          .filter { !Redactor.looksLikeCode(it.text) } // belt and braces; such an item stays on its heuristic lane
      }
      if (batch.isEmpty()) return@withLock 0
      val answers: Map<String, ClassifyAnswer>? = try {
        LlmGate.check(batch)
        c.classify(batch).associateBy { it.ref }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      synchronized(lock) {
        var applied = 0
        for (b in batch) {
          val cur = items[b.ref] ?: continue
          if (!cur.awaitingLlm || cur.triagedBy == TriagedBy.USER) continue
          val a = answers?.get(b.ref)
          val lane = a?.let { ChatGptJsonClassifier.laneOf(it.lane) }
          val next = if (lane != null) {
            applied++
            cur.copy(lane = lane, triagedBy = TriagedBy.LLM, confidence = Confidence.MEDIUM,
              reason = a.reason.take(80).ifBlank { "sorted by Juno" }, awaitingLlm = false, llmAttempts = cur.llmAttempts + 1)
          } else {
            val tries = cur.llmAttempts + 1
            if (tries >= config.maxLlmAttempts) cur.copy(triagedBy = TriagedBy.DEFAULT, awaitingLlm = false, llmAttempts = tries)
            else cur.copy(llmAttempts = tries)
          }
          put(next, feats[next.id].orEmpty())
        }
        publish()
        applied
      }
    }
  }

  // ---------------------------------------------------------------- reads

  fun item(id: String): InboxItem? = synchronized(lock) { items[id] }

  /** Items, newest activity first. [lane] null = all lanes. Closed and snoozed items only when [includeClosed]. */
  fun items(lane: Lane? = null, includeClosed: Boolean = false): List<InboxItem> = synchronized(lock) {
    val now = clock.now()
    items.values.filter { (lane == null || it.lane == lane) && (includeClosed || it.isOpenAt(now)) }.sortedByDescending { it.lastAt }
  }

  /** For the widget. Open Needs-you items (snoozed and expired codes excluded), soonest deadline first. */
  fun waitingOnYou(limit: Int = 5): WaitingOnYou = synchronized(lock) {
    val now = clock.now()
    val open = waitingLocked(now)
    WaitingOnYou(open.size, open.take(limit).map {
      WaitingItemUi(it.id, it.title, it.sources.distinctBy { s -> s.agentId }.map { s -> AgentBadge(s.agentId, s.agentName) },
        Labels.due(it.dueAt, now, config.zone), it.dueAt != null && it.dueAt < now)
    }, now)
  }

  private fun waitingLocked(now: Long) = items.values.filter { it.lane == Lane.NEEDS_YOU && it.isOpenAt(now) }
    .sortedWith(compareBy<InboxItem>({ it.dueAt ?: Long.MAX_VALUE }, { it.firstAt }))

  /** The code for a code item while it is fresh (memory only). Null once expired or after a restart. */
  fun revealCode(itemId: String): String? = codes.reveal(itemId)

  // ---------------------------------------------------------------- user actions

  fun markDone(id: String) = setState(id, ItemState.DONE)
  fun dismiss(id: String) = setState(id, ItemState.DISMISSED)
  fun reopen(id: String) = setState(id, ItemState.OPEN)

  fun snooze(id: String, until: Long): Boolean = update(id) { it.copy(snoozedUntil = until) }

  /** The user's lane wins over heuristics and the LLM from now on, including for later duplicates. */
  fun moveTo(id: String, lane: Lane): Boolean =
    update(id) { it.copy(lane = lane, triagedBy = TriagedBy.USER, confidence = Confidence.HIGH, awaitingLlm = false, reason = "moved by you") }

  private fun setState(id: String, s: ItemState): Boolean = update(id) {
    if (s != ItemState.OPEN) codes.forget(id)
    it.copy(state = s, stateAt = clock.now(), snoozedUntil = if (s == ItemState.OPEN) 0 else it.snoozedUntil)
  }

  private fun update(id: String, f: (InboxItem) -> InboxItem): Boolean = synchronized(lock) {
    val cur = items[id] ?: return false
    put(f(cur), feats[id].orEmpty())
    publish()
    true
  }

  // ---------------------------------------------------------------- digest

  /** The digest for now: since the last [markDigestSent] (or the last 24 h). Does not mark it sent. */
  fun digest(now: Long = clock.now()): Digest = synchronized(lock) {
    val since = meta[META_LAST_DIGEST] ?: (now - 24 * 3600_000L)
    digests.build(items.values.toList(), now, since)
  }

  /** Call once Juno has posted the digest; the next window starts at its [Digest.generatedAt]. */
  fun markDigestSent(d: Digest) = synchronized(lock) {
    meta[META_LAST_DIGEST] = d.generatedAt
    store.putMeta(META_LAST_DIGEST, d.generatedAt)
    publish()
  }

  fun nextDigestAt(): Long = config.digest.nextAfter(clock.now())

  // ---------------------------------------------------------------- housekeeping

  /** Drops old closed items and old Noise, purges stale codes. Run daily (e.g. with the digest job). */
  fun sweep(): Int = synchronized(lock) {
    val now = clock.now()
    codes.purge()
    val drop = items.values.filter {
      (it.state != ItemState.OPEN && now - maxOf(it.lastAt, it.stateAt) > config.keepClosedMs) ||
        (it.lane == Lane.NOISE && now - it.lastAt > config.keepNoiseMs) ||
        (it.hasCode && it.expiresAt != null && now - it.expiresAt > config.keepNoiseMs)
    }
    drop.forEach { items.remove(it.id); feats.remove(it.id); store.remove(it.id) }
    if (drop.isNotEmpty()) publish()
    drop.size
  }

  /** Recomputes [state] (e.g. on a minute tick so due labels and code expiry stay current). */
  fun refresh() = synchronized(lock) { publish() }

  // ---------------------------------------------------------------- internals

  private fun put(item: InboxItem, f: List<Features>) {
    items[item.id] = item
    feats[item.id] = f
    store.upsert(item)
  }

  private fun featuresOf(i: InboxItem) = i.sources.map { TextFeatures.extract(it.text, it.at, config.zone) }

  private fun publish() {
    val now = clock.now()
    val open = items.values.filter { it.isOpenAt(now) }.sortedByDescending { it.lastAt }
    fun cards(l: Lane) = open.filter { it.lane == l }.map { it.toCard(now, config.zone, codes.has(it.id)) }
    val needs = waitingLocked(now).map { it.toCard(now, config.zone, codes.has(it.id)) }
    val noise = cards(Lane.NOISE)
    _state.value = InboxUiState(
      needsYou = needs,
      fyi = cards(Lane.FYI),
      noise = noise,
      waitingCount = needs.size,
      noiseLabel = if (noise.isEmpty()) null else "${noise.size} muted",
      nextDigestAt = config.digest.nextAfter(now),
    )
  }

  companion object {
    const val META_LAST_DIGEST = "lastDigestAt"

    private val lead = Regex("(?i)^\\s*(heads[- ]up|reminder|quick reminder|fyi|note|psa|hey|hi|just a reminder|friendly reminder)[,:!.\\-–—\\s]+")

    /** First sentence of the (redacted) text, without filler, at most 80 chars. */
    fun titleOf(text: String): String {
      val t = lead.replace(text.trim(), "").replaceFirstChar { it.uppercase() }
      val firstLine = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: t
      val m = Regex("^(.+?[.!?])(\\s|$)").find(firstLine)
      val s = (m?.groupValues?.get(1) ?: firstLine).trim()
      return if (s.length <= 80) s else s.take(77).trimEnd() + "…"
    }
  }
}
