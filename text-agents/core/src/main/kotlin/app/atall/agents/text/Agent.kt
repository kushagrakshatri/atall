package app.atall.agents.text

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * What the coordinator talks to. One instance per external agent (a texting agent, a fake, later other routes).
 *
 * - [send] hands one message to the agent. [correlationId] is the coordinator's id for this request; replies
 *   that the transport attributes to it carry the same id in [AgentMessage.correlation]. Re-sending a
 *   correlationId that was already accepted never texts twice: it returns [SendResult.Duplicate].
 * - [incoming] is hot and live: every new message from the agent, once, in arrival order. Nothing is replayed
 *   to a late collector; use [history] to catch up (the log is durable).
 */
interface Agent {
  val id: AgentId
  val displayName: String
  val incoming: Flow<AgentMessage>

  suspend fun send(text: String, correlationId: String, options: SendOptions = SendOptions()): SendResult

  /** The newest [limit] logged entries for this agent (both directions), oldest first. */
  suspend fun history(limit: Int = 50): List<LogEntry>

  /** Requests still waiting for (more) replies, oldest first. Expired ones are swept before returning. */
  suspend fun outstanding(): List<OutstandingRequest>

  /** Stop attributing replies to [correlationId] (the coordinator gave up or got its answer). */
  suspend fun close(correlationId: String)
}

@JvmInline
@Serializable
value class AgentId(val value: String) {
  init { require(value.isNotBlank()) { "blank agent id" } }
  override fun toString() = value
}

/** One message from the agent. [id] is stable across Messages' notification re-posts (the dedupe key). */
data class AgentMessage(
  val id: String,
  val agentId: AgentId,
  val text: String,
  /** The message's own timestamp (MessagingStyle "time"), epoch ms on the device clock. */
  val sentAt: Long,
  /** When this library first saw it. Later than [sentAt] for history recovered by a rescan. */
  val observedAt: Long,
  val correlation: Correlation?,
  /** True when the line was already in the notification's history before the agent was registered. */
  val historical: Boolean = false,
  /** Set when the line is a tapback ("👍 to “…”"): show it as a reaction chip, not as text. Never correlated. */
  val reaction: Reaction? = null,
)

@Serializable
data class Correlation(
  val correlationId: String,
  val confidence: Confidence,
  val reason: CorrelationReason,
  /** 0 for the first reply attributed to the request, 1 for the next part, ... */
  val part: Int,
  /** Set when the user typed into the thread themselves between the request and this reply. */
  val userInterjected: Boolean = false,
  /** The request had already been reported closed (expired) when this reply was observed: RCS delivered late. */
  val late: Boolean = false,
)

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW }

@Serializable
enum class CorrelationReason {
  /** The reply quoted the request's tag. */
  TAG,
  /** Exactly one request was waiting on this agent. */
  ONLY_OPEN_REQUEST,
  /** Several were waiting; the oldest unanswered one took it (agents answer in order). */
  OLDEST_UNANSWERED,
  /** A further part of a reply already attributed to the request (agents split long answers). */
  CONTINUATION,
}

data class SendOptions(
  /** Append a short reference tag the agent may echo; a reply quoting it correlates with HIGH confidence. */
  val tag: Boolean = false,
  /** False for fire-and-forget lines ("thanks"): no request is opened, replies stay uncorrelated. */
  val expectReply: Boolean = true,
  /** How long replies may take to start. Null uses [TextAgentConfig.replyWindowMs]. RCS can lag minutes. */
  val replyWindowMs: Long? = null,
  /** When the live reply action is unusable, open a prefilled Messages draft (the user taps Send). */
  val allowDraft: Boolean = true,
)

sealed interface SendResult {
  val correlationId: String

  /** Handed to Google Messages through the notification's reply action. Not proof of delivery (RCS can stall). */
  data class Sent(override val correlationId: String, val at: Long, val actionAgeMs: Long, val sentText: String) : SendResult

  /** Messages was opened on the thread with the text typed in; nothing is sent until the user taps Send. */
  data class Drafted(override val correlationId: String, val at: Long, val why: DraftReason, val sentText: String) : SendResult

  /** Could not send or open a draft by itself (e.g. app in background); surface [draft] to the user. */
  data class NeedsUser(override val correlationId: String, val why: NeedsUserReason, val draft: DraftRequest) : SendResult

  data class RateLimited(override val correlationId: String, val retryAfterMs: Long, val scope: RateScope) : SendResult

  /** This correlationId was already accepted; nothing was sent again. */
  data class Duplicate(override val correlationId: String, val firstAt: Long) : SendResult

  data class Failed(override val correlationId: String, val why: FailReason) : SendResult
}

enum class DraftReason { NO_LIVE_ACTION, ACTION_EXPIRED, ACTION_CANCELED, ACTION_FAILED }
enum class NeedsUserReason { APP_IN_BACKGROUND, DRAFTS_DISABLED }
enum class RateScope { AGENT_INTERVAL, AGENT_MINUTE, AGENT_HOUR, GLOBAL_MINUTE }
enum class FailReason { EMPTY, TOO_LONG, BAD_CORRELATION_ID, UNKNOWN_AGENT, NO_NUMBER, NO_MESSAGES_APP, TOO_MANY_OUTSTANDING }

@Serializable
data class DraftRequest(val number: String, val text: String)
