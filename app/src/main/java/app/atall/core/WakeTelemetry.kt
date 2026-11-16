package app.atall.core

import app.atall.coordinator.PeerSend
import app.atall.coordinator.adapters.LadderNote
import app.atall.coordinator.adapters.WakeKind

/**
 * v0.1.9.1: a webhook agent (Grok) that is never woken never answers, silently. Every wake that reached nobody is an
 * ERROR breadcrumb (Relay's note); for a webhook agent it is also a Sentry event, and so is an ask that ended with no
 * wake at all because its post failed. Ids and enums only, no text.
 */
object WakeTelemetry {
  data class Event(val message: String, val extras: Map<String, String>)

  /** The Sentry event for a ladder wake/nudge that reached nobody, or null when it isn't a webhook agent's. */
  fun forNote(n: LadderNote, webhookAgent: Boolean): Event? {
    if (!n.failed || !(webhookAgent || n.kind == WakeKind.WEBHOOK)) return null
    return Event("Wake failed: ${n.relayAgentId}", mapOf("ref" to n.ref, "step" to n.step, "ask" to n.cid.substringAfter('~')))
  }

  /** An ask to a webhook agent that failed at the post: no card, so no wake. Null for any other agent or outcome. */
  fun missingWake(relayAgentId: String, wakeKind: WakeKind?, cid: String, r: PeerSend): Event? {
    if (wakeKind != WakeKind.WEBHOOK || r !is PeerSend.Failed) return null
    return Event("No wake: ${relayAgentId} ask failed", mapOf("reason" to r.reason, "ask" to cid.substringAfter('~')))
  }
}
