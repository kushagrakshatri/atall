package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * m05: an agent keeps its slot until its own soft timeout (text: 180 s).
 *  - Run r6cca6eef (2026-11-14 15:59 PST): Hark's "Looks like your signal dropped…" was ingested as its answer and
 *    the run replied 20 s later (quiet window) without Hark's real answer.
 *  - Run r35c7296e (18:13 PST): Juno waited with max_seconds = 120; the wait ended at exactly 120 s with kDot still
 *    pending (1 s after Messages APP_CANCELed kDot's notification), and Juno answered without kDot, 60 s before
 *    kDot's soft timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentSlotTest {
  private val SIGNAL = "Looks like your signal dropped. Feel free to keep texting, and I'll pick everything up once you're back online."

  @Test fun statusTextNeverAnswersAndTheAgentKeepsWaiting() = runTest {
    val h = Harness(this)
    // Scout (Hark's role) sends its status text at 24 s, then the real answer at 100 s.
    h.scout.behavior = { Behavior.Reply(listOf(Part(24_000, SIGNAL, status = "connection"), Part(76_000, "Van is $812 with fees; hotels $1,831.11."))) }
    var waitOut: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Resolve the trip costs."), waitCall())
      else { waitOut = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Van $812, hotels $1,831.11.")) }
    }
    val runId = h.juno.startCoordinated("Resolve the costs first")
    assertIs<DriveResult.Done>(h.juno.drive(runId))
    // Not at 24 s + 20 s quiet window (the device bug): the wait ended on the real answer.
    assertEquals(100_000L + 20_000L, currentTime)
    val a = waitAsks(waitOut!!).single()
    assertEquals("answered", a.text("status"))
    assertEquals("Van is $812 with fees; hotels $1,831.11.", a.text("reply"))
    val ask = h.juno.state(runId)!!.asks.single()
    assertEquals(1, ask.replies.size)
    assertEquals(listOf(SIGNAL), ask.statusNotes.map { it.text })
  }

  @Test fun statusTextAloneHoldsTheSlotUntilTheSoftTimeout() = runTest {
    val h = Harness(this)
    h.scout.behavior = { Behavior.Reply(listOf(Part(24_000, SIGNAL, status = "connection"))) }
    var waitOut: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Resolve the trip costs."), waitCall(max = 60))
      else { waitOut = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Scout is still on it.")) }
    }
    val runId = h.juno.startCoordinated("Resolve the costs first")
    assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(180_000L, currentTime) // the text soft timeout, not 44 s and not the model's 60 s
    val a = waitAsks(waitOut!!).single()
    assertEquals("still_working", a.text("status"))
    assertTrue(a.text("note")!!.contains("status text"), a.toString())
    assertEquals(null, a.text("reply"))
  }

  @Test fun shortWaitDoesNotDropAPendingAgentBeforeItsSoftTimeout() = runTest {
    val h = Harness(this)
    // r35c7296e: Hark (scout) answers at ~74 s; kDot (nova) is still writing when Juno's 120 s wait runs out.
    h.scout.behavior = { Behavior.Reply(listOf(Part(74_000, "Keep both Beatty nights."))) }
    h.nova.behavior = { Behavior.Reply(listOf(Part(150_000, "One full day covers the highlights; two is nicer."))) }
    var waitOut: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Two days for Death Valley?"), askAgent("Nova", "Two days for Death Valley?"), waitCall(max = 120))
      else { waitOut = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Both say keep the nights.")) }
    }
    val runId = h.juno.startCoordinated("and what did kDot and Hark say?")
    assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(150_000L + 20_000L, currentTime)
    assertEquals(listOf("answered", "answered"), waitAsks(waitOut!!).map { it.text("status") })
  }

  @Test fun theTransportStillEndsTheSlotForReal() = runTest {
    // A real close (here: LIKELY_MISSED at the transport's window) still settles the ask; only the wait's own timer is held.
    val h = Harness(this)
    h.nova.behavior = { Behavior.LikelyMissed(30_000) }
    h.llm.on("juno") { _, n ->
      if (n == 0) FakeLlm.tools(askAgent("Nova", "Anything on my calendar?"), waitCall(max = 120))
      else FakeLlm.tools(replyCall("Nova may have answered in Messages."))
    }
    val runId = h.juno.startCoordinated("calendar?")
    assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(30_000L, currentTime)
  }
}
