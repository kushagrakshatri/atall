package app.atall.coordinator.adapters

// Glue for the app module: text-agents (app.atall.agents.text) -> coordinator Peer and event pump.

import app.atall.coordinator.Certainty
import app.atall.coordinator.CloseOutcome
import app.atall.coordinator.Juno
import app.atall.coordinator.Peer
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.agents.text.Agent
import app.atall.agents.text.AgentMessage
import app.atall.agents.text.Confidence
import app.atall.agents.text.HubEvent
import app.atall.agents.text.RequestClosed
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A text-agents [Agent] as a coordinator [Peer]. The transport's reply window should match Juno's hard timeout
 * ([replyWindowMs]) so the transport keeps attributing replies as long as Juno is willing to wait.
 */
class TextAgentPeer(
  private val agent: Agent,
  private val replyWindowMs: Long = 30 * 60_000L,
  /** Off by default (design.md): tags are clutter unless the agent turns out to echo them. */
  private val tag: Boolean = false,
  /** False: with no live reply action, return NeedsUser ("Tap to send to X") instead of opening a Messages draft
   *  by itself (two drafts opened back to back would replace each other). */
  private val allowDraft: Boolean = true,
) : Peer {
  override val id: String get() = agent.id.value

  override suspend fun send(text: String, correlationId: String): PeerSend =
    when (val r = agent.send(text, correlationId, SendOptions(tag = tag, expectReply = true, replyWindowMs = replyWindowMs, allowDraft = allowDraft))) {
      is SendResult.Sent -> PeerSend.Sent(r.at)
      is SendResult.Drafted -> PeerSend.Drafted(r.at)
      is SendResult.NeedsUser -> PeerSend.NeedsUser(r.draft.text, r.draft.number, r.why.name.lowercase())
      is SendResult.RateLimited -> PeerSend.RateLimited(r.retryAfterMs)
      is SendResult.Duplicate -> PeerSend.Duplicate(r.firstAt)
      is SendResult.Failed -> PeerSend.Failed(r.why.name.lowercase())
    }

  override suspend fun close(correlationId: String) = agent.close(correlationId)
}

/**
 * The app's event pump: every agent message and hub event goes to [Juno.ingest]. Start it once per process, before
 * [Juno.recover], so replies recovered by the listener's rescan are attributed to persisted asks.
 */
object TextAgentPump {
  fun start(scope: CoroutineScope, juno: Juno, agents: List<Agent>, hubEvents: Flow<HubEvent>): List<Job> =
    agents.map { a -> scope.launch { a.incoming.collect { juno.ingest(toSignal(it)) } } } +
      scope.launch {
        hubEvents.collect { e ->
          // Other hub events (ThreadOpened, UserLine) are already folded into correlation confidence and
          // LIKELY_MISSED by the hub; Juno doesn't need them separately.
          if (e is RequestClosed) {
            val outcome = when (e.outcome) {
              RequestClosed.Outcome.COMPLETED -> CloseOutcome.COMPLETED
              RequestClosed.Outcome.EXPIRED -> CloseOutcome.EXPIRED
              RequestClosed.Outcome.LIKELY_MISSED -> CloseOutcome.LIKELY_MISSED
              RequestClosed.Outcome.CLOSED_BY_CALLER -> CloseOutcome.CLOSED_BY_CALLER
            }
            juno.ingest(PeerSignal.Closed(e.agentId.value, e.request.correlationId, outcome, System.currentTimeMillis()))
          }
        }
      }

  fun toSignal(m: AgentMessage): PeerSignal.Message = PeerSignal.Message(
    agentId = m.agentId.value,
    messageId = m.id,
    text = m.text,
    at = m.sentAt,
    correlationId = m.correlation?.correlationId,
    certainty = when (m.correlation?.confidence) { Confidence.HIGH, null -> Certainty.HIGH; Confidence.MEDIUM -> Certainty.MEDIUM; Confidence.LOW -> Certainty.LOW },
    part = m.correlation?.part ?: 0,
    late = m.correlation?.late == true,
    userInterjected = m.correlation?.userInterjected == true,
  )
}
