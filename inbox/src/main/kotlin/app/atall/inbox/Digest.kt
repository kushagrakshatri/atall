package app.atall.inbox

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * When digests go out. Default: one evening summary at 18:00 local. The app turns [nextAfter] into a WorkManager
 * job and checks Do Not Disturb at fire time (a digest is a quiet notification, so DND only delays the sound).
 */
data class DigestSchedule(
  val times: List<LocalTime> = listOf(LocalTime.of(18, 0)),
  val zone: ZoneId = ZoneId.systemDefault(),
) {
  init { require(times.isNotEmpty()) { "at least one digest time" } }

  /** The next digest time strictly after [now], epoch ms. DST-safe (java.time resolves gaps forward). */
  fun nextAfter(now: Long): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    for (d in 0L..1L) for (t in times.sorted()) {
      val at = today.plusDays(d).atTime(t).atZone(zone).toInstant().toEpochMilli()
      if (at > now) return at
    }
    return today.plusDays(2).atTime(times.min()).atZone(zone).toInstant().toEpochMilli()
  }
}

@Serializable
data class DigestEntry(
  val itemId: String,
  val title: String,
  val agents: List<String>,
  val lane: Lane,
  val dueLabel: String? = null,
  val overdue: Boolean = false,
  /** How many messages folded into it (across agents and repeats). */
  val messages: Int = 1,
  val crossAgent: Boolean = false,
)

/**
 * What Juno's evening summary should say. Built only from redacted titles: no message bodies, no codes.
 * [isEmpty] means skip sending (nothing waiting and nothing new worth a line).
 */
@Serializable
data class Digest(
  val generatedAt: Long,
  /** Window start: the previous digest (or 24 h back on first run). */
  val since: Long,
  val headline: String,
  /** Everything still open in Needs you, oldest deadline first. Not limited to the window: it's still waiting. */
  val needsYou: List<DigestEntry>,
  /** New or updated FYI items in the window, newest first, at most [DigestBuilder.maxFyi]. */
  val fyi: List<DigestEntry>,
  val moreFyi: Int,
  /** Noise items in the window, per agent name. Shown as one line ("muted 7 from kDot"). */
  val noiseByAgent: Map<String, Int>,
  /** Items the user closed in the window. */
  val doneCount: Int,
  /** Items in this digest that more than one agent raised (pings saved). */
  val dedupedCount: Int,
  /** Sign-in codes that arrived in the window. Count only, never the code or its sender's message. */
  val codesCount: Int,
) {
  val noiseCount: Int get() = noiseByAgent.values.sum()
  val isEmpty: Boolean get() = needsYou.isEmpty() && fyi.isEmpty()

  /** Deterministic text when the model is unavailable (signed out, limit hit). */
  fun plainText(): String = buildString {
    append(headline)
    if (needsYou.isNotEmpty()) {
      append("\n\nNeeds you:")
      needsYou.forEach { append("\n• ").append(line(it)) }
    }
    if (fyi.isNotEmpty()) {
      append("\n\nFYI:")
      fyi.forEach { append("\n• ").append(line(it)) }
      if (moreFyi > 0) append("\n• and $moreFyi more")
    }
    val tail = buildList {
      if (dedupedCount > 0) add("$dedupedCount ${if (dedupedCount == 1) "thing" else "things"} came from more than one agent")
      if (noiseCount > 0) add("muted $noiseCount low-value ${if (noiseCount == 1) "message" else "messages"}")
      if (codesCount > 0) add("$codesCount sign-in ${if (codesCount == 1) "code" else "codes"} came and went")
    }
    if (tail.isNotEmpty()) append("\n\n").append(tail.joinToString("; ").replaceFirstChar { it.uppercase() }).append('.')
  }

  /**
   * Input for Juno to phrase the summary in its own voice. Compact JSON of this digest plus style rules.
   * Passes [LlmGate]: throws if any code slipped through.
   */
  fun promptForJuno(): String {
    val body = Json.encodeToString(serializer(), this)
    val prompt = "Write the user's evening summary of what his agents sent today, from this JSON. " +
      "Lead with what needs him, by deadline; then at most three FYI lines; then one short line for the rest. " +
      "Name which agent said what; say when two agents flagged the same thing. No greetings, no lists longer " +
      "than the JSON, never invent items, never mention codes beyond the count.\n$body"
    LlmGate.check(prompt)
    return prompt
  }

  private fun line(e: DigestEntry): String = buildString {
    append(e.title)
    append(" (").append(Labels.names(e.agents))
    if (e.dueLabel != null) append(", ").append(e.dueLabel.lowercase().replaceFirstChar { it.uppercase() })
    append(")")
  }
}

class DigestBuilder(private val zone: ZoneId, val maxFyi: Int = 6) {
  fun build(items: List<InboxItem>, now: Long, since: Long): Digest {
    fun entry(i: InboxItem) = DigestEntry(
      itemId = i.id,
      title = i.title,
      agents = i.agentNames,
      lane = i.lane,
      dueLabel = Labels.due(i.dueAt, now, zone),
      overdue = i.dueAt != null && i.dueAt < now,
      messages = i.sources.size,
      crossAgent = i.isCrossAgent,
    )
    val open = items.filter { it.isOpenAt(now) }
    val needs = open.filter { it.lane == Lane.NEEDS_YOU && !it.hasCode }
      .sortedWith(compareBy<InboxItem>({ it.dueAt ?: Long.MAX_VALUE }, { it.firstAt }))
    val fyiAll = open.filter { it.lane == Lane.FYI && it.lastAt > since && !it.hasCode }.sortedByDescending { it.lastAt }
    val fyi = fyiAll.take(maxFyi)
    val noise = items.filter { it.lane == Lane.NOISE && it.lastAt > since }
    val noiseByAgent = LinkedHashMap<String, Int>()
    noise.forEach { i -> i.sources.filter { it.at > since }.forEach { s -> noiseByAgent[s.agentName] = (noiseByAgent[s.agentName] ?: 0) + 1 } }
    val done = items.count { it.state != ItemState.OPEN && it.stateAt > since }
    val codes = items.count { it.hasCode && it.firstAt > since }
    val shown = needs + fyi
    val headline = when {
      needs.isEmpty() && fyi.isEmpty() -> "Nothing needs you tonight."
      needs.isEmpty() -> "Nothing needs you. ${fyiAll.size} ${plural(fyiAll.size, "update")} from your agents."
      fyiAll.isEmpty() -> "${needs.size} ${plural(needs.size, "thing")} ${if (needs.size == 1) "needs" else "need"} you."
      else -> "${needs.size} ${plural(needs.size, "thing")} ${if (needs.size == 1) "needs" else "need"} you, plus ${fyiAll.size} ${plural(fyiAll.size, "update")}."
    }
    return Digest(
      generatedAt = now,
      since = since,
      headline = headline,
      needsYou = needs.map(::entry),
      fyi = fyi.map(::entry),
      moreFyi = fyiAll.size - fyi.size,
      noiseByAgent = noiseByAgent,
      doneCount = done,
      dedupedCount = shown.count { it.isCrossAgent },
      codesCount = codes,
    )
  }

  private fun plural(n: Int, w: String) = if (n == 1) w else w + "s"
}
