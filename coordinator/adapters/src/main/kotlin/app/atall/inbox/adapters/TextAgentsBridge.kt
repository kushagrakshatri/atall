package app.atall.inbox.adapters

import app.atall.inbox.AgentInbox
import app.atall.inbox.IncomingMessage
import app.atall.inbox.IngestResult
import app.atall.agents.text.Agent
import app.atall.agents.text.AgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Glue between the text-agents transport (package app.atall.agents.text) and the inbox. Lives outside the inbox
 * module so the inbox has no dependency on text-agents; copy it into the app module. Type-checked by
 * scripts/jvm-test.sh against text-agents/core without modifying it.
 */
fun AgentMessage.toInboxMessage(agentName: String): IncomingMessage = IncomingMessage(
  id = id,
  agentId = agentId.value,
  agentName = agentName,
  text = text,
  sentAt = sentAt,
  observedAt = observedAt,
  // A message the transport attributed to one of Juno's requests is a reply, not an unprompted ping.
  inReplyTo = correlation?.correlationId,
  historical = historical,
)

/** Feeds every message from [agent] into [inbox]; [onResult] decides buzz / quiet / clear-original. */
fun CoroutineScope.pipe(agent: Agent, inbox: AgentInbox, onResult: (IngestResult) -> Unit = {}): Job = launch {
  agent.incoming.collect { m -> onResult(inbox.ingest(m.toInboxMessage(agent.displayName))) }
}
