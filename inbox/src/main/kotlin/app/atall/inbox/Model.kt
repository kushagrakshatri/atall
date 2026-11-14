package app.atall.inbox

import kotlinx.serialization.Serializable

/**
 * Core data for the Agent Inbox. Everything here is plain values and is persisted as JSON, so text fields hold
 * only REDACTED text (see [Redactor]): a verification code never reaches disk, the LLM, or the digest.
 */

fun interface Clock { fun now(): Long }

object SystemClock : Clock { override fun now() = System.currentTimeMillis() }

/**
 * One message an agent sent. The app maps the transport's message into this (see adapters/text-agents).
 * [inReplyTo] is the coordinator's correlation id when the transport attributed the message to one of Juno's
 * requests: those are replies, not unprompted pings, and the inbox ignores them.
 */
data class IncomingMessage(
  /** Stable across re-posts (text-agents' AgentMessage.id). Re-ingesting the same id is a no-op. */
  val id: String,
  val agentId: String,
  val agentName: String,
  /** Raw text as received. May contain a code; it is redacted before anything is stored. */
  val text: String,
  val sentAt: Long,
  val observedAt: Long = sentAt,
  val inReplyTo: String? = null,
  /** History recovered by a rescan rather than seen live: never buzzes. */
  val historical: Boolean = false,
)

@Serializable
enum class Lane { NEEDS_YOU, FYI, NOISE }

/** Who set the lane. USER beats everything; LLM beats HEURISTIC only when the heuristic was unsure. */
@Serializable
enum class TriagedBy {
  HEURISTIC,
  LLM,
  /** Heuristics were unsure and the LLM could not answer (signed out, error, bad JSON): a safe default. */
  DEFAULT,
  USER,
}

@Serializable
enum class Confidence { LOW, MEDIUM, HIGH }

/** Cheap on-device signals found in a message. */
@Serializable
enum class Signal {
  OTP,
  QUESTION,
  ACTION_REQUEST,
  DEADLINE,
  MONEY,
  MONEY_PROBLEM,
  CONFIRMATION,
  URGENT,
  PROMO,
  CHATTER,
}

@Serializable
enum class ItemState { OPEN, DONE, DISMISSED }

/** One agent's message folded into an item. */
@Serializable
data class Source(
  val messageId: String,
  val agentId: String,
  val agentName: String,
  /** Redacted. */
  val text: String,
  val at: Long,
)

/**
 * One inbox card: one "thing" that one or more agents told the user about. Near-duplicates across agents (and
 * an agent repeating itself) fold into one item with several [sources].
 */
@Serializable
data class InboxItem(
  val id: String,
  /** Short headline (redacted), from the first message. */
  val title: String,
  val lane: Lane,
  val triagedBy: TriagedBy,
  val confidence: Confidence,
  /** One line on why it sits in [lane] ("asks you a question", "LLM: newsletter"). Never contains a code. */
  val reason: String,
  val signals: Set<Signal> = emptySet(),
  val sources: List<Source>,
  val firstAt: Long,
  val lastAt: Long,
  /** Parsed deadline, epoch ms, when one was found. */
  val dueAt: Long? = null,
  val state: ItemState = ItemState.OPEN,
  val stateAt: Long = 0,
  val snoozedUntil: Long = 0,
  /** Contains a verification code (redacted in [sources]; the code itself lives only in memory). */
  val hasCode: Boolean = false,
  /** Code items stop needing the user after this (codes expire quickly). */
  val expiresAt: Long? = null,
  /** Heuristics were unsure and the LLM has not answered yet. */
  val awaitingLlm: Boolean = false,
  val llmAttempts: Int = 0,
) {
  val agentIds: List<String> get() = sources.map { it.agentId }.distinct()
  val agentNames: List<String> get() = sources.distinctBy { it.agentId }.map { it.agentName }
  val isCrossAgent: Boolean get() = agentIds.size > 1

  fun isOpenAt(now: Long): Boolean =
    state == ItemState.OPEN && snoozedUntil <= now && (expiresAt == null || expiresAt > now)
}

/** What the app should do with the phone's attention after an ingest. */
@Serializable
enum class Notify {
  /** Post/keep a heads-up: only for a new NEEDS_YOU item seen live. */
  BUZZ,
  /** Show on the card and in the digest; no sound. */
  QUIET,
  /** Nothing new for the user (exact re-ingest, a reply to Juno, or a duplicate of a card already shown). */
  NONE,
}

sealed interface IngestResult {
  /** A reply to one of Juno's requests ([IncomingMessage.inReplyTo] set). The thread shows it; the inbox doesn't. */
  data object IgnoredReply : IngestResult
  /** Already ingested (same message id). */
  data object AlreadySeen : IngestResult
  data class Added(val item: InboxItem, val notify: Notify, val suppressOriginal: Boolean) : IngestResult
  /** Folded into [item] as another source. [crossAgent] when a different agent had already raised it. */
  data class Merged(val item: InboxItem, val crossAgent: Boolean, val notify: Notify, val suppressOriginal: Boolean) : IngestResult
}
