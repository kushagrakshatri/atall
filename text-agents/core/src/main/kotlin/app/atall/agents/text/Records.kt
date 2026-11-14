package app.atall.agents.text

import kotlinx.serialization.Serializable

/** One line of the durable log. */
@Serializable
data class LogEntry(
  val id: String,
  val agentId: AgentId,
  val dir: Direction,
  val text: String,
  val at: Long,
  val via: Via? = null,
  val correlation: Correlation? = null,
  /** For OUT: the request this line was sent for. */
  val correlationId: String? = null,
)

@Serializable
enum class Direction {
  /** From the agent. */
  IN,
  /** Sent (or drafted) by this library. */
  OUT,
  /** Typed by the user straight into the Messages thread (seen as a self line we did not send). */
  USER,
}

@Serializable
enum class Via { ACTION, DRAFT }

@Serializable
enum class RequestState {
  /** Draft opened; not known to be sent until the user's line shows up in a notification. */
  AWAITING_USER_SEND,
  /** Sent (or draft confirmed); no reply yet. */
  WAITING,
  /** At least one reply attributed; more parts may follow within the continuation window. */
  ANSWERED,
}

/** A request the coordinator sent and may still get replies for. Persisted so restarts keep correlating. */
@Serializable
data class OutstandingRequest(
  val correlationId: String,
  val agentId: AgentId,
  val text: String,
  /** Exactly what went into the thread (text plus tag when tagged). */
  val sentText: String,
  val sentAt: Long,
  val via: Via,
  val state: RequestState,
  val replyDeadline: Long,
  val tag: String? = null,
  val replies: Int = 0,
  val lastReplyAt: Long = 0,
  /** When our own line appeared back in a Messages notification (confirms it reached the thread). */
  val echoedAt: Long = 0,
  /** Messages cancelled the agent's notification while this was waiting: the chat was probably open, so
   *  replies may have been shown in Messages without a notification (missed). */
  val threadOpenedAt: Long = 0,
  /** When it left the outstanding list (expired/completed). Kept a while after so a late-observed reply whose
   *  own timestamp falls in the window (RCS lag) still correlates, flagged late. */
  val closedAt: Long = 0,
)

/** Things the coordinator may want besides agent messages. */
sealed interface HubEvent {
  val agentId: AgentId
}

/** Messages cancelled the agent's notification (chat opened/read) while requests were waiting: replies that
 *  arrive while the chat stays open get no notification and are invisible to this library. */
data class ThreadOpened(override val agentId: AgentId, val at: Long) : HubEvent

/** The user typed into the agent's thread themselves (a self line this library did not send). */
data class UserLine(override val agentId: AgentId, val text: String, val at: Long) : HubEvent

/** A request left [Agent.outstanding]. */
data class RequestClosed(
  val request: OutstandingRequest,
  val outcome: Outcome,
) : HubEvent {
  override val agentId get() = request.agentId
  enum class Outcome {
    /** Got replies; continuation window passed. */
    COMPLETED,
    /** No reply in the window. */
    EXPIRED,
    /** No reply in the window and the thread was opened meanwhile: the reply was probably shown in Messages
     *  without a notification. Tell the user to look in Messages. */
    LIKELY_MISSED,
    /** The coordinator closed it. */
    CLOSED_BY_CALLER,
  }
}
