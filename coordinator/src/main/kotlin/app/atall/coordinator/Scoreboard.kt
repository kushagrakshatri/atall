package app.atall.coordinator

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Append-only per-agent events for the reliability scoreboard (innovation.md #2). Logged from day 1 by [Juno];
 * the app stores them next to its event log (Room) and rolls them up with [Scoreboard.compute].
 */
@Serializable
sealed class ScoreEvent {
  abstract val agentId: String
  abstract val at: Long

  /** [via]: "sent" | "drafted" | "needs_tap" | "queued". */
  @Serializable @SerialName("ask_sent")
  data class AskSent(override val agentId: String, override val at: Long, val askId: String, val via: String, val purpose: AskPurpose = AskPurpose.TASK) : ScoreEvent()

  @Serializable @SerialName("first_reply")
  data class FirstReply(override val agentId: String, override val at: Long, val askId: String, val latencyMs: Long, val certainty: Certainty, val late: Boolean) : ScoreEvent()

  /** [outcome]: the ask's final status. [withinSoft]: answered before the soft timeout. */
  @Serializable @SerialName("ask_settled")
  data class AskSettled(override val agentId: String, override val at: Long, val askId: String, val outcome: AskStatus, val withinSoft: Boolean) : ScoreEvent()

  /** The ask needed the user: [kind] "tap_to_send" | "approval" | "look_in_messages". */
  @Serializable @SerialName("needed_user")
  data class NeededUser(override val agentId: String, override val at: Long, val askId: String, val kind: String) : ScoreEvent()

  @Serializable @SerialName("unsolicited")
  data class Unsolicited(override val agentId: String, override val at: Long) : ScoreEvent()

  @Serializable @SerialName("direct_sent")
  data class DirectSent(override val agentId: String, override val at: Long, val via: String) : ScoreEvent()
}

interface ScoreLog {
  suspend fun append(e: ScoreEvent)
  suspend fun since(t: Long): List<ScoreEvent>
}

class InMemoryScoreLog : ScoreLog {
  val events = CopyOnWriteArrayList<ScoreEvent>()
  override suspend fun append(e: ScoreEvent) { events += e }
  override suspend fun since(t: Long) = events.filter { it.at >= t }
}

/** JSON lines, one event per line. */
class JsonLinesScoreLog(private val file: File) : ScoreLog {
  @Synchronized private fun write(line: String) = file.appendText(line + "\n")
  override suspend fun append(e: ScoreEvent) = write(CoordJson.encodeToString(ScoreEvent.serializer(), e))
  override suspend fun since(t: Long) = if (!file.exists()) emptyList() else
    file.readLines().filter { it.isNotBlank() }.map { CoordJson.decodeFromString(ScoreEvent.serializer(), it) }.filter { it.at >= t }
}

/** One agent's strip in Settings → Agents. Counts sit next to rates because samples are small. */
data class AgentScore(
  val agentId: String,
  val asks: Int,
  val answered: Int,
  val answeredInTime: Int,
  val timedOut: Int,
  val likelyMissed: Int,
  val medianReplyMs: Long?,
  val neededTap: Int,
  val neededApproval: Int,
  val directSends: Int,
  val lastUnsolicitedAt: Long?,
) {
  /** Settled asks that ended answered. */
  val completionRate: Double? get() = (answered + timedOut + likelyMissed).takeIf { it > 0 }?.let { answered.toDouble() / it }
  val inTimeRate: Double? get() = asks.takeIf { it > 0 }?.let { answeredInTime.toDouble() / it }
  /** How often sending needed the user's tap (all sends, asks and direct). */
  val tapRate: Double? get() = (asks + directSends).takeIf { it > 0 }?.let { neededTap.toDouble() / it }
}

object Scoreboard {
  const val WEEK_MS = 7L * 24 * 3600 * 1000

  fun compute(events: List<ScoreEvent>, now: Long, windowMs: Long = WEEK_MS): List<AgentScore> {
    val recent = events.filter { it.at in (now - windowMs)..now }
    return recent.groupBy { it.agentId }.map { (agent, es) ->
      val latencies = es.filterIsInstance<ScoreEvent.FirstReply>().map { it.latencyMs }.sorted()
      val settled = es.filterIsInstance<ScoreEvent.AskSettled>()
      val needed = es.filterIsInstance<ScoreEvent.NeededUser>()
      AgentScore(
        agentId = agent,
        asks = es.filterIsInstance<ScoreEvent.AskSent>().map { it.askId }.distinct().size,
        answered = settled.count { it.outcome == AskStatus.ANSWERED },
        answeredInTime = settled.count { it.outcome == AskStatus.ANSWERED && it.withinSoft },
        timedOut = settled.count { it.outcome == AskStatus.TIMED_OUT },
        likelyMissed = settled.count { it.outcome == AskStatus.LIKELY_MISSED },
        medianReplyMs = latencies.takeIf { it.isNotEmpty() }?.let { l -> if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 },
        neededTap = needed.count { it.kind == "tap_to_send" },
        neededApproval = needed.count { it.kind == "approval" },
        directSends = es.count { it is ScoreEvent.DirectSent },
        lastUnsolicitedAt = es.filterIsInstance<ScoreEvent.Unsolicited>().maxOfOrNull { it.at },
      )
    }.sortedBy { it.agentId }
  }
}
