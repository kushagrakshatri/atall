package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** C2 in the engine: a connector result is the whole answer (no quiet window); progress is a note, never an answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorSignalsTest {
  @Test fun finalResultSettlesAtOnceAndProgressNeverCounts() = runTest {
    val h = Harness(this, config = JunoConfig(textSoftTimeoutMs = 120_000))
    h.scout.behavior = { Behavior.Silent }
    h.nova.behavior = { Behavior.Silent }
    var out: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Dinner ideas for 4?"), waitCall(max = 30))
      else { out = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Sakoon.")) }
    }
    val runId = h.juno.startCoordinated("Plan Saturday dinner for four, no pork or beef.")
    val drive = async { h.juno.drive(runId) }
    runCurrent()
    val cid = "$runId~a0"
    advanceTimeBy(5_000); runCurrent()
    h.juno.ingest(PeerSignal.Message("scout", "relay:1", "On it", currentTime, cid, status = "progress", via = Via.CONNECTOR, verified = true))
    // The model's 30 s wait passes; the progress line doesn't end it (m05: status texts aren't answers).
    advanceTimeBy(60_000); runCurrent()
    assertEquals(AskStatus.PENDING, h.juno.state(runId)!!.ask(cid)!!.status)
    val claims = listOf(SourcedClaim("Sakoon has a table at 7:30.", 0.8, sources = listOf(ClaimSource("https://example.com"))))
    h.juno.ingest(PeerSignal.Message("scout", "relay:res:1", "Sakoon at 7:30.", currentTime, cid, via = Via.CONNECTOR, verified = true, final = true, claims = claims))
    val at = currentTime
    runCurrent()
    assertIs<DriveResult.Done>(drive.await())
    assertEquals(at, currentTime, "answered without waiting out a quiet window")
    val a = h.juno.state(runId)!!.ask(cid)!!
    assertEquals(AskStatus.ANSWERED, a.status)
    assertTrue(a.verified)
    assertEquals(listOf("On it"), a.statusNotes.map { it.text })
    assertTrue(out!!.contains("\"via\":\"connector_verified\"") && out!!.contains("https://example.com"))
    assertTrue(h.scores.events.any { it is ScoreEvent.ClaimsIn && it.claims == 1 && it.sources == 1 && it.verified })
  }

  @Test fun repliesStoredBeforeC2StillDecode() {
    val old = """{"runId":"r1","mode":"COORDINATE","userText":"x","createdAt":1,"phase":"DONE","asks":[{"id":"r1~a0","agentId":"scout","text":"t","status":"ANSWERED","createdAt":1,"replies":[{"messageId":"m","text":"hi","at":2}]}]}"""
    val s = RunCodec.decode(old)
    val a = s.asks.single()
    assertEquals(null, a.via)
    assertEquals(Via.TEXT, a.replies.single().provenance)
    assertEquals(false, a.verified)
  }
}
