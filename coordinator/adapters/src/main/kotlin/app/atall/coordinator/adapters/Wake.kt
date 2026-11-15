package app.atall.coordinator.adapters

// C3: wake-ups and the fallback ladder (protocol.md §6 and §8). MCP is pull-based: an agent only sees a task card when
// it next calls atall_inbox. After a card is posted, the phone wakes the agent by its best path, nudges once more if
// nothing happens, and when there is still no claim or progress by the agent's soft timeout (or the relay is down),
// the same ask goes by text. Pure Kotlin; the app supplies the sinks (texts, webhook POSTs, notifications).

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.Locale

/** How Atall wakes an agent for a new task card. A wake carries no content: only the task's ref. */
@Serializable
enum class WakeKind {
  /** An "Atall #ref" text over the agent's text route (text-agents). kDot's backup; Hark before C3.1. */
  TEXT,
  /**
   * C3.1 "Full text": no task card at all. The whole ask (the same packed text the ladder's text rung sends) goes by
   * text at once and the agent's text reply is its answer, as in v0.1.4. No relay task, no "Atall #ref" nudge, no
   * ladder. Hark's default (Hark won't pull tasks from an outside board); pickable for kDot.
   */
  FULL_TEXT,
  /** The phone POSTs to the agent's routine webhook (Grok Bot: a webhook-triggered routine). */
  WEBHOOK,
  /** The relay delivers an MCP event to the agent's subscription (kDot: MCP Events `task.assigned`). */
  EVENTS,
  /** A one-tap notification that opens the agent's app with "Check your Atall inbox for #ref" filled in. */
  TAP,
  /** Nothing is sent: the agent checks its Atall inbox on its own schedule (Duet's checks, a Muse recurring task). */
  SCHEDULE,
  /** No wake-ups. */
  OFF,
}

/** Per-agent wake settings (Settings → Agents → the agent → Wake-up). */
@Serializable
data class WakeConfig(
  val kind: WakeKind,
  /** Used for the nudge, and when [kind] can't be used right now (no webhook set, no text route, no subscription). */
  val backup: WakeKind? = null,
  /** [WakeKind.SCHEDULE]: how often the agent checks on its own (Duet's pickup window, a Muse recurring task). */
  val windowMs: Long? = null,
)

/** What a wake attempt did. */
enum class WakeOutcome {
  /** Handed off with no tap needed (text sent, webhook 200, event delivered). */
  SENT,
  /** Waiting on one tap from the user (a notification is up). */
  TAP,
  /** This path can't be used now (not configured, no route, no subscription): try the next one. */
  UNAVAILABLE,
  /** Tried and failed (network, refused). */
  FAILED,
}

/** The app's side of each wake path. */
interface WakeSinks {
  /** Sends [text] ("Atall #ref") over the agent's text route. TAP when it became a "Tap to send" draft. */
  suspend fun text(task: ConnectorTask, text: String): WakeOutcome
  /** POSTs the agent's routine webhook with {ref, task_id} (no content). */
  suspend fun webhook(task: ConnectorTask): WakeOutcome
  /** Asks the relay to deliver the agent's MCP event again (the first delivery happens when the card is posted). */
  suspend fun events(task: ConnectorTask, first: Boolean): WakeOutcome
  /** Posts a one-tap notification: [prompt] is what opens prefilled in the agent's app; [full] the whole ask for the last rung. */
  suspend fun tap(task: ConnectorTask, prompt: String, full: String? = null): WakeOutcome
}

object WakeCopy {
  /** The text a texting agent gets. Content travels over the connector, never in the nudge. */
  fun text(ref: String) = "Atall #$ref"
  /** What a tap opens prefilled in the agent's app. */
  fun prompt(ref: String) = "Check your Atall inbox for #$ref"
}

object WakeDefaults {
  private const val MIN = 60_000L

  /** Best real path per agent (new-product/c3/wake-paths.md); the user changes it in Settings → Agents. */
  fun forAgent(relayId: String): WakeConfig = when (relayId.lowercase(Locale.ROOT)) {
    // C3.1: Hark won't take tasks from the connector, so it gets the whole ask by text and its reply is the answer.
    "hark" -> WakeConfig(WakeKind.FULL_TEXT)
    "kdot" -> WakeConfig(WakeKind.EVENTS, backup = WakeKind.TEXT)
    "grok" -> WakeConfig(WakeKind.WEBHOOK, backup = WakeKind.TAP)
    // Duet checks its connected accounts "every few hours"; the pickup window is Kush's call (default 3 h).
    "duet", "claude" -> WakeConfig(WakeKind.SCHEDULE, backup = WakeKind.TAP, windowMs = 180 * MIN)
    // A Muse recurring task ("every 15 minutes, call atall_inbox") is the automatic path; WhatsApp/app tap is the nudge.
    "muse" -> WakeConfig(WakeKind.SCHEDULE, backup = WakeKind.TAP, windowMs = 15 * MIN)
    else -> WakeConfig(WakeKind.TAP)
  }

  /**
   * The paths that make sense for this agent (the Settings picker). [WakeKind.TEXT] needs a text route. Full text is
   * always listed for Hark and kDot (it's Hark's default; without a text route the agent shows it needs its number).
   */
  fun choices(relayId: String, hasTextRoute: Boolean): List<WakeKind> {
    val id = relayId.lowercase(Locale.ROOT)
    val base = when (id) {
      "kdot" -> listOf(WakeKind.EVENTS, WakeKind.FULL_TEXT, WakeKind.TEXT, WakeKind.TAP)
      "hark" -> listOf(WakeKind.FULL_TEXT, WakeKind.TEXT, WakeKind.TAP, WakeKind.SCHEDULE)
      "grok" -> listOf(WakeKind.WEBHOOK, WakeKind.TAP, WakeKind.SCHEDULE)
      else -> listOf(WakeKind.SCHEDULE, WakeKind.TAP)
    }
    val withText = if (hasTextRoute && WakeKind.TEXT !in base) base + WakeKind.TEXT else base
    return withText.filter { it != WakeKind.TEXT || hasTextRoute } + WakeKind.OFF
  }
}

/** When to nudge and when to fall back, for one agent. */
data class LadderPolicy(
  /** No claim or progress this long after the last wake: wake once more (by the backup path when there is one). */
  val nudgeAfterMs: Long,
  /** No claim or progress this long after posting (or since the last progress): the ask goes by text. Null: never. */
  val fallbackAfterMs: Long?,
  /** Wake-ups per task, the first included. */
  val maxWakes: Int = 2,
) {
  companion object {
    private const val MIN = 60_000L

    /**
     * §8: nudge after max(2 × typical latency, 3 min) (an agent on its own schedule: its window + 30 min); fall back at
     * the agent's soft timeout. Agents on a schedule never fall back on the clock (their window is longer than any
     * soft timeout); only a relay outage moves their ask.
     */
    fun of(cfg: WakeConfig, softTimeoutMs: Long, typicalLatencyMs: Long = 60_000L): LadderPolicy = when (cfg.kind) {
      WakeKind.SCHEDULE -> LadderPolicy(nudgeAfterMs = (cfg.windowMs ?: 180 * MIN) + 30 * MIN, fallbackAfterMs = null, maxWakes = if (cfg.backup != null) 2 else 1)
      WakeKind.OFF -> LadderPolicy(nudgeAfterMs = Long.MAX_VALUE, fallbackAfterMs = softTimeoutMs, maxWakes = 0)
      // Full text never posts a card. One left over from before the switch goes by text on the next tick, unwoken.
      WakeKind.FULL_TEXT -> LadderPolicy(nudgeAfterMs = Long.MAX_VALUE, fallbackAfterMs = 0L, maxWakes = 0)
      WakeKind.TAP -> LadderPolicy(nudgeAfterMs = maxOf(2 * typicalLatencyMs, 3 * MIN), fallbackAfterMs = softTimeoutMs, maxWakes = 1)
      else -> LadderPolicy(nudgeAfterMs = maxOf(2 * typicalLatencyMs, 3 * MIN), fallbackAfterMs = softTimeoutMs, maxWakes = 2)
    }
  }
}

/** One wake attempt: the path used and what happened. */
data class WakeAttempt(val kind: WakeKind?, val outcome: WakeOutcome, val tried: List<Pair<WakeKind, WakeOutcome>>)

/**
 * Wakes the agent a task card is addressed to, by its configured path, falling to the backup path when the first can't
 * be used. Attempt 1 starts with the primary path; a nudge (attempt 2+) starts with the backup path when there is one.
 */
class WakeDispatcher(
  private val config: (relayId: String) -> WakeConfig,
  private val sinks: WakeSinks,
  private val hasTextRoute: (peerId: String) -> Boolean = { false },
) {
  suspend fun wake(task: ConnectorTask, attempt: Int): WakeAttempt {
    val cfg = config(task.relayAgentId)
    val order = (if (attempt <= 1) listOf(cfg.kind, cfg.backup) else listOf(cfg.backup, cfg.kind)).filterNotNull().distinct()
    val tried = mutableListOf<Pair<WakeKind, WakeOutcome>>()
    for (k in order) {
      val r = try { one(k, task, attempt) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { WakeOutcome.FAILED }
      tried += k to r
      if (r == WakeOutcome.SENT || r == WakeOutcome.TAP) return WakeAttempt(k, r, tried)
    }
    val last = tried.lastOrNull()
    return WakeAttempt(last?.first, last?.second ?: WakeOutcome.UNAVAILABLE, tried)
  }

  private suspend fun one(k: WakeKind, task: ConnectorTask, attempt: Int): WakeOutcome = when (k) {
    WakeKind.TEXT -> if (hasTextRoute(task.peerId)) sinks.text(task, WakeCopy.text(task.ref)) else WakeOutcome.UNAVAILABLE
    WakeKind.WEBHOOK -> sinks.webhook(task)
    WakeKind.EVENTS -> sinks.events(task, first = attempt <= 1)
    WakeKind.TAP -> sinks.tap(task, WakeCopy.prompt(task.ref))
    // Nothing to send: the agent looks on its own. Counts as handed off.
    WakeKind.SCHEDULE -> WakeOutcome.SENT
    // Full text has no wake: the ask itself goes by text (never a card, so never a nudge).
    WakeKind.FULL_TEXT, WakeKind.OFF -> WakeOutcome.UNAVAILABLE
  }
}

/** What the ladder did on one tick, for the debug log. */
data class LadderNote(val cid: String, val ref: String, val relayAgentId: String, val step: String)

/**
 * The §8 fallback ladder over the open connector tasks: `connector (with wake) → nudge → text → tap-to-send`.
 * Call [tick] every few seconds (the relay loop does) with when the relay started failing (null while it answers).
 *
 * - A task with no wake yet is woken (also after process death: the ledger keeps the count).
 * - No claim or progress [LadderPolicy.nudgeAfterMs] after the last wake: one more wake.
 * - No claim or progress by [LadderPolicy.fallbackAfterMs], or the relay down for [outageMs]: the same ask goes by text
 *   ([byText], tagged so the reply correlates). An agent with no text route gets a one-tap notification with the
 *   whole ask instead ([tapFull]), only on the clock, since without the relay its answer can't come back anyway.
 * - First answer wins: Juno takes whichever arrives first for the ask; a later copy folds in as a late reply.
 */
class FallbackLadder(
  private val ledger: ConnectorLedger,
  private val dispatcher: WakeDispatcher,
  private val policyFor: (ConnectorTask) -> LadderPolicy,
  private val hasTextRoute: (peerId: String) -> Boolean,
  /** Sends the ask for [ConnectorTask.cid] by text. False when it couldn't (no route, needs a tap, gated). */
  private val byText: suspend (ConnectorTask, String) -> Boolean,
  /** The last rung: a one-tap notification with the whole ask (for agents with no text route, or when the text failed). */
  private val tapFull: suspend (ConnectorTask, String) -> Boolean = { _, _ -> false },
  private val clock: () -> Long = System::currentTimeMillis,
  /** The relay failing this long counts as an outage. */
  private val outageMs: Long = 60_000L,
  private val note: (LadderNote) -> Unit = {},
  /** Tasks to leave alone for now: the agent is paused (no wake, nudge, text or tap; picked up again if unpaused). */
  private val skip: (ConnectorTask) -> Boolean = { false },
) {
  private val lock = kotlinx.coroutines.sync.Mutex()

  suspend fun tick(relayDownSince: Long? = null) = lock.withLock { tickL(relayDownSince) }

  private suspend fun tickL(relayDownSince: Long?) {
    val now = clock()
    val outage = relayDownSince != null && now - relayDownSince >= outageMs
    for (t in ledger.live(now)) {
      if (t.taskId == null || t.fellBackAt != null || skip(t)) continue
      val p = policyFor(t)
      val quietSince = t.activityAt ?: t.postedAt
      val textRoute = hasTextRoute(t.peerId)
      when {
        outage && textRoute -> fallBack(t, "relay_down")
        p.fallbackAfterMs != null && now - quietSince >= p.fallbackAfterMs -> fallBack(t, if (t.activityAt == null) "no_claim" else "stalled")
        outage -> Unit // nothing to wake through while the relay is down (the agent couldn't read the card)
        t.wakes == 0 && p.maxWakes > 0 -> wake(t, 1)
        t.activityAt == null && t.wakes in 1 until p.maxWakes && now - (t.wokeAt ?: t.postedAt) >= p.nudgeAfterMs -> wake(t, t.wakes + 1)
      }
    }
  }

  private suspend fun wake(t: ConnectorTask, attempt: Int) {
    // Count it before trying, so a crash mid-wake doesn't wake twice.
    ledger.update(t.cid) { it.copy(wakes = attempt, wokeAt = clock()) }
    val r = dispatcher.wake(t, attempt)
    ledger.update(t.cid) { it.copy(wakeVia = "${r.kind?.name?.lowercase() ?: "none"}:${r.outcome.name.lowercase()}") }
    note(LadderNote(t.cid, t.ref, t.relayAgentId, (if (attempt == 1) "wake " else "nudge ") + r.tried.joinToString(" → ") { "${it.first.name.lowercase()} ${it.second.name.lowercase()}" }.ifEmpty { "none" }))
  }

  private suspend fun fallBack(t: ConnectorTask, reason: String) {
    ledger.update(t.cid) { it.copy(fellBackAt = clock(), fallback = reason) }
    val textRoute = hasTextRoute(t.peerId)
    val sent = textRoute && runCatching { byText(t, reason) }.getOrDefault(false)
    val how = when {
      sent -> "text"
      // Without the relay a connector-only agent couldn't answer anyway; only the clock moves it to a tap.
      reason == "relay_down" && !textRoute -> "none"
      runCatching { tapFull(t, reason) }.getOrDefault(false) -> "tap"
      else -> "none"
    }
    ledger.update(t.cid) { it.copy(fallback = "$reason:$how") }
    note(LadderNote(t.cid, t.ref, t.relayAgentId, "fallback $reason → $how"))
  }
}
