package app.atall.inbox

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** UI-ready values. Compose renders these as they are: no logic in the UI. */

data class AgentBadge(val agentId: String, val name: String) {
  val monogram: String get() = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}

enum class CardAction { REPLY, DONE, SNOOZE, DISMISS, MOVE, SHOW_CODE, UNDO_DONE }

data class SourceUi(val agent: AgentBadge, val text: String, val at: Long, val timeLabel: String)

data class InboxCardUi(
  val id: String,
  val title: String,
  val lane: Lane,
  /** One per agent, in the order they raised it. Two badges = deduped across agents. */
  val agents: List<AgentBadge>,
  /** Newest message text (redacted). */
  val preview: String,
  val sources: List<SourceUi>,
  /** "Due today 5:00 PM", "Overdue", "Tomorrow", null when no date. */
  val dueLabel: String?,
  val overdue: Boolean,
  /** "Hark and kDot both flagged this" / "Hark reminded you 3 times" / null. */
  val dedupeLabel: String?,
  /** Why it is in this lane, in plain words. */
  val reason: String,
  val hasCode: Boolean,
  /** The code is still in memory and fresh: show "Show code". */
  val canRevealCode: Boolean,
  val state: ItemState,
  val lastAt: Long,
  val lastLabel: String,
  val actions: List<CardAction>,
)

data class InboxUiState(
  val needsYou: List<InboxCardUi> = emptyList(),
  val fyi: List<InboxCardUi> = emptyList(),
  val noise: List<InboxCardUi> = emptyList(),
  /** Same number as [WaitingOnYou.count]. */
  val waitingCount: Int = 0,
  /** "12 muted" for the collapsed Noise row, null when empty. */
  val noiseLabel: String? = null,
  val nextDigestAt: Long? = null,
)

/** For the home-screen widget: the count plus the top few. */
data class WaitingOnYou(
  val count: Int,
  val items: List<WaitingItemUi>,
  val updatedAt: Long,
) {
  /** "Nothing waiting" / "1 thing waiting on you" / "3 things waiting on you". */
  val label: String get() = when (count) { 0 -> "Nothing waiting"; 1 -> "1 thing waiting on you"; else -> "$count things waiting on you" }
}

data class WaitingItemUi(
  val id: String,
  val title: String,
  val agents: List<AgentBadge>,
  val dueLabel: String?,
  val overdue: Boolean,
)

object Labels {
  private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
  private val day = DateTimeFormatter.ofPattern("EEE", Locale.US)
  private val date = DateTimeFormatter.ofPattern("MMM d", Locale.US)

  fun due(dueAt: Long?, now: Long, zone: ZoneId): String? {
    if (dueAt == null) return null
    if (dueAt < now) return "Overdue"
    val d = Instant.ofEpochMilli(dueAt).atZone(zone)
    val n = Instant.ofEpochMilli(now).atZone(zone)
    val days = ChronoUnit.DAYS.between(n.toLocalDate(), d.toLocalDate())
    val t = if (d.hour == 23 && d.minute == 59) "" else " " + time.format(d)
    return when {
      days == 0L -> "Today$t"
      days == 1L -> "Tomorrow$t"
      days < 7 -> day.format(d) + t
      else -> date.format(d)
    }
  }

  fun ago(at: Long, now: Long): String {
    val m = Duration.ofMillis((now - at).coerceAtLeast(0)).toMinutes()
    return when {
      m < 1 -> "now"
      m < 60 -> "${m}m"
      m < 24 * 60 -> "${m / 60}h"
      else -> "${m / (24 * 60)}d"
    }
  }

  fun names(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
  }

  fun dedupe(item: InboxItem): String? = when {
    item.isCrossAgent -> if (item.agentNames.size == 2) "${names(item.agentNames)} both flagged this" else "${names(item.agentNames)} all flagged this"
    item.sources.size > 1 -> "${item.agentNames.first()} reminded you ${item.sources.size} times"
    else -> null
  }

  fun reason(item: InboxItem): String = when (item.triagedBy) {
    TriagedBy.USER -> "You moved this"
    TriagedBy.DEFAULT -> "Couldn't sort this; check it"
    else -> item.reason.replaceFirstChar { it.uppercase() }
  }
}

internal fun InboxItem.toCard(now: Long, zone: ZoneId, codeFresh: Boolean): InboxCardUi {
  val badges = sources.distinctBy { it.agentId }.map { AgentBadge(it.agentId, it.agentName) }
  val due = Labels.due(dueAt, now, zone)
  val actions = buildList {
    if (state == ItemState.OPEN) {
      add(CardAction.REPLY)
      if (hasCode && codeFresh) add(CardAction.SHOW_CODE)
      add(CardAction.DONE); add(CardAction.SNOOZE); add(CardAction.MOVE); add(CardAction.DISMISS)
    } else add(CardAction.UNDO_DONE)
  }
  return InboxCardUi(
    id = id,
    title = title,
    lane = lane,
    agents = badges,
    preview = sources.last().text,
    sources = sources.map { SourceUi(AgentBadge(it.agentId, it.agentName), it.text, it.at, Labels.ago(it.at, now)) },
    dueLabel = due,
    overdue = dueAt != null && dueAt < now && lane == Lane.NEEDS_YOU,
    dedupeLabel = Labels.dedupe(this),
    reason = Labels.reason(this),
    hasCode = hasCode,
    canRevealCode = hasCode && codeFresh,
    state = state,
    lastAt = lastAt,
    lastLabel = Labels.ago(lastAt, now),
    actions = actions,
  )
}
