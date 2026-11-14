package app.atall.agents.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrelatorTest {
  private val a = AgentId("wren")
  private val min = 60_000L

  private fun req(cid: String, sentAt: Long, replies: Int = 0, lastReplyAt: Long = 0, tag: String? = null, window: Long = 30 * min) =
    OutstandingRequest(cid, a, "q", "q", sentAt, Via.ACTION, if (replies > 0) RequestState.ANSWERED else RequestState.WAITING, sentAt + window, tag, replies, lastReplyAt)

  @Test fun singleOpenRequestIsHigh() {
    val d = Correlator.decide(listOf(req("r1", 1_000)), "answer", 10_000, 0)!!
    assertEquals("r1", d.correlation.correlationId)
    assertEquals(Confidence.HIGH, d.correlation.confidence)
    assertEquals(CorrelationReason.ONLY_OPEN_REQUEST, d.correlation.reason)
    assertEquals(0, d.correlation.part)
  }

  @Test fun lineBeforeTheRequestIsNotItsReply() {
    assertNull(Correlator.decide(listOf(req("r1", 100_000)), "old", 10_000, 0))
    // ...but small clock skew is tolerated.
    assertEquals("r1", Correlator.decide(listOf(req("r1", 100_000)), "x", 97_000, 0)!!.request.correlationId)
  }

  @Test fun outsideTheWindowIsUncorrelated() {
    assertNull(Correlator.decide(listOf(req("r1", 0, window = 10 * min)), "late", 11 * min, 0))
  }

  @Test fun oldestUnansweredFirstWithMediumConfidence() {
    val open = listOf(req("r2", 20_000), req("r1", 10_000))
    val d = Correlator.decide(open, "first", 30_000, 0)!!
    assertEquals("r1", d.correlation.correlationId)
    assertEquals(CorrelationReason.OLDEST_UNANSWERED, d.correlation.reason)
    assertEquals(Confidence.MEDIUM, d.correlation.confidence)
  }

  @Test fun continuationGetsFurtherParts() {
    val d = Correlator.decide(listOf(req("r1", 0, replies = 1, lastReplyAt = 10_000)), "part 2", 40_000, 0)!!
    assertEquals(CorrelationReason.CONTINUATION, d.correlation.reason)
    assertEquals(1, d.correlation.part)
    assertNull(Correlator.decide(listOf(req("r1", 0, replies = 1, lastReplyAt = 10_000)), "much later", 10_000 + 91_000, 0))
  }

  @Test fun newRequestTakesTheNextLineUnlessTooQuick() {
    val r1 = req("r1", 0, replies = 1, lastReplyAt = 100_000)
    val r2 = req("r2", 105_000)
    // 30 s after r2: r2's reply.
    assertEquals("r2", Correlator.decide(listOf(r1, r2), "x", 135_000, 0)!!.request.correlationId)
    // 1 s after r2: too quick to answer r2 -> still r1's answer arriving.
    val quick = Correlator.decide(listOf(r1, r2), "x", 106_000, 0)!!
    assertEquals("r1", quick.request.correlationId)
    assertEquals(CorrelationReason.CONTINUATION, quick.correlation.reason)
  }

  @Test fun latestAnsweredThreadWinsContinuation() {
    val r1 = req("r1", 0, replies = 1, lastReplyAt = 50_000)
    val r2 = req("r2", 60_000, replies = 1, lastReplyAt = 70_000)
    assertEquals("r2", Correlator.decide(listOf(r1, r2), "x", 80_000, 0)!!.request.correlationId)
  }

  @Test fun tagBeatsOrder() {
    val open = listOf(req("r1", 0), req("r2", 1_000, tag = "k3f9"))
    val d = Correlator.decide(open, "Re #K3F9: done", 5_000, 0)!!
    assertEquals("r2", d.correlation.correlationId)
    assertEquals(CorrelationReason.TAG, d.correlation.reason)
    assertEquals(Confidence.HIGH, d.correlation.confidence)
  }

  @Test fun tagMatchingIsWholeWord() {
    assertTrue(Correlator.containsTag("(ref #ab23)", "ab23"))
    assertTrue(Correlator.containsTag("ab23 done", "ab23"))
    assertFalse(Correlator.containsTag("xab23", "ab23"))
    assertFalse(Correlator.containsTag("ab234", "ab23"))
  }

  @Test fun newTagsAvoidTakenOnesAndLookAlikes() {
    val rnd = java.util.Random(1)
    val taken = HashSet<String>()
    repeat(500) {
      val t = Correlator.newTag(taken, rnd)
      assertEquals(4, t.length)
      assertTrue(t.none { it in "01ilo" })
      assertTrue(taken.add(t))
    }
    assertNotEquals("", Correlator.withTag("hi", "abcd"))
  }

  @Test fun userInterjectionDropsConfidenceToLow() {
    val d = Correlator.decide(listOf(req("r1", 1_000)), "answer", 20_000, lastUserLineAt = 10_000)!!
    assertEquals(Confidence.LOW, d.correlation.confidence)
    assertTrue(d.correlation.userInterjected)
    // A user line before the request does not count.
    assertEquals(Confidence.HIGH, Correlator.decide(listOf(req("r1", 1_000)), "answer", 20_000, lastUserLineAt = 500)!!.correlation.confidence)
  }

  @Test fun closureOutcomes() {
    val t = Correlator.Tuning()
    val waiting = req("r", 0, window = 10 * min)
    assertNull(Correlator.closure(waiting, 5 * min, t))
    assertEquals(RequestClosed.Outcome.EXPIRED, Correlator.closure(waiting, 11 * min, t))
    assertEquals(RequestClosed.Outcome.LIKELY_MISSED, Correlator.closure(waiting.copy(threadOpenedAt = 2 * min), 11 * min, t))
    val answered = req("r", 0, replies = 2, lastReplyAt = min)
    assertNull(Correlator.closure(answered, min + 30_000, t))
    assertEquals(RequestClosed.Outcome.COMPLETED, Correlator.closure(answered, min + 91_000, t))
  }

  @Test fun attributedCountsParts() {
    val r = Correlator.attributed(Correlator.attributed(req("r", 0), 5_000), 3_000)
    assertEquals(2, r.replies)
    assertEquals(5_000, r.lastReplyAt)
    assertEquals(RequestState.ANSWERED, r.state)
  }
}
