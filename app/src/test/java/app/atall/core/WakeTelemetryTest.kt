package app.atall.core

import app.atall.coordinator.PeerSend
import app.atall.coordinator.Via
import app.atall.coordinator.adapters.LadderNote
import app.atall.coordinator.adapters.WakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.9.1: Grok was never woken and nothing said so. A failed or missing wake for a webhook agent is a Sentry event. */
class WakeTelemetryTest {
  @Test fun aFailedWebhookWakeIsAnEvent() {
    val n = LadderNote("re00283ca~a1", "affcr", "grok", "wake webhook failed → tap failed", failed = true, kind = WakeKind.WEBHOOK)
    val e = WakeTelemetry.forNote(n, webhookAgent = true)
    assertNotNull(e)
    assertEquals("Wake failed: grok", e!!.message)
    assertEquals(mapOf("ref" to "affcr", "step" to "wake webhook failed → tap failed", "ask" to "a1"), e.extras)
  }

  @Test fun aWakeThatWorkedOrAnotherAgentsIsNoEvent() {
    assertNull(WakeTelemetry.forNote(LadderNote("r~a1", "affcr", "grok", "wake webhook sent", failed = false, kind = WakeKind.WEBHOOK), true))
    assertNull(WakeTelemetry.forNote(LadderNote("r~a2", "xyz12", "duet", "wake tap failed", failed = true, kind = WakeKind.TAP), false))
  }

  @Test fun aWebhookAgentsFailedPostIsAMissingWake() {
    val e = WakeTelemetry.missingWake("grok", WakeKind.WEBHOOK, "re00283ca~a1", PeerSend.Failed("relay_error_409"))
    assertEquals("No wake: grok ask failed", e!!.message)
    assertEquals("relay_error_409", e.extras["reason"])
    assertNull(WakeTelemetry.missingWake("grok", WakeKind.WEBHOOK, "r~a1", PeerSend.Sent(1, Via.CONNECTOR)))
    assertNull(WakeTelemetry.missingWake("duet", WakeKind.SCHEDULE, "r~a1", PeerSend.Failed("relay_error_409")))
  }

  @Test fun theWebhookBodyWorksWithoutATaskId() {
    assertEquals("""{"ref":"affcr","task_id":"tsk_1","source":"atall"}""", WakeSetup.webhookBody("affcr", "tsk_1"))
    val b = WakeSetup.webhookBody("affcr", null)
    assertEquals("""{"ref":"affcr","source":"atall"}""", b)
    assertFalse("task_id" in b)
    assertTrue("affcr" in b)
  }
}
