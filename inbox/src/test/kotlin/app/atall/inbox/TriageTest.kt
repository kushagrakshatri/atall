package app.atall.inbox

import app.atall.inbox.testing.FakeClassifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TriageTest {
  private val h = HeuristicTriage()

  private fun verdict(text: String, at: Long = T0): Verdict {
    val red = Redactor.redact(text)
    return h.evaluate(red.text, red.hasCode, features(red.text, at), at)
  }

  private fun lane(text: String, at: Long = T0) = verdict(text, at).lane

  @Test fun codesNeedYou() {
    val v = verdict("Your Chase verification code is 482913")
    assertEquals(Lane.NEEDS_YOU, v.lane)
    assertTrue(Signal.OTP in v.signals)
    assertFalse(v.needsLlm)
  }

  @Test fun questionsAndActionRequestsNeedYou() {
    assertEquals(Lane.NEEDS_YOU, lane("Want me to book the 7pm table at ROOH?"))
    assertEquals(Lane.NEEDS_YOU, lane("Action required: please sign the offer letter"))
    assertEquals(Lane.NEEDS_YOU, lane("Reply YES to confirm the cleaner for Sunday"))
  }

  @Test fun deadlinesAndMoney() {
    val now = at(2026, 11, 28, 9)
    val v = verdict("Your rent of \$2,450 is due Dec 1", now)
    assertEquals(Lane.NEEDS_YOU, v.lane); assertEquals("payment due", v.reason)
    assertEquals(Lane.NEEDS_YOU, lane("Your Comcast payment was declined"))
    assertEquals(Lane.NEEDS_YOU, lane("Library books due tomorrow"))
    assertEquals(Lane.FYI, lane("Your passport expires on March 3")) // far out
  }

  @Test fun confirmationsAndReceiptsAreFyi() {
    assertEquals(Lane.FYI, lane("Your table at ROOH is confirmed for Saturday 7pm."))
    val v = verdict("You were charged \$12.99 for ChatGPT Plus. Receipt sent to your email.")
    assertEquals(Lane.FYI, v.lane); assertEquals("receipt", v.reason)
    assertEquals(Lane.FYI, lane("Your package from Amazon was delivered."))
  }

  @Test fun promosAndChatterAreNoise() {
    assertEquals(Lane.NOISE, lane("Pro tip: you can ask me to track packages too"))
    assertEquals(Lane.NOISE, lane("On it 👍"))
    assertEquals(Lane.NOISE, lane("Still working on it"))
    val v = verdict("20% off all plans this weekend! Upgrade now?")
    assertEquals(Lane.NOISE, v.lane); assertTrue(v.needsLlm) // promo + question: provisional, asks the LLM
  }

  @Test fun urgentSecurityNeedsYou() {
    assertEquals(Lane.NEEDS_YOU, lane("Security alert: new sign-in to your Google account from Chrome on Linux"))
  }

  @Test fun unclearGoesToTheLlm() {
    val v = verdict("Interesting article on transformer scaling laws")
    assertEquals(Confidence.LOW, v.confidence); assertTrue(v.needsLlm)
    val r = Rig(classifier = FakeClassifier(decide = { "noise" }))
    val a = r.added(nova("Interesting article on transformer scaling laws"))
    assertEquals(Lane.NOISE, a.item.lane)
    assertEquals(TriagedBy.LLM, a.item.triagedBy)
    assertEquals(Notify.NONE, a.notify)
    assertTrue(a.suppressOriginal)
    assertEquals(1, r.classifier!!.calls.size)
  }

  @Test fun llmFailureFallsBackToFyiAfterTwoTriesAndStops() {
    val c = FakeClassifier(failWith = IllegalStateException("signed out"))
    val r = Rig(classifier = c)
    val a = r.added(nova("Interesting article on transformer scaling laws"))
    assertEquals(Lane.FYI, a.item.lane)
    assertTrue(a.item.awaitingLlm)
    runBlocking { r.inbox.classifyPending() }
    runBlocking { r.inbox.classifyPending() } // no third call
    val item = r.inbox.item(a.item.id)!!
    assertEquals(TriagedBy.DEFAULT, item.triagedBy)
    assertFalse(item.awaitingLlm)
    assertEquals(2, c.calls.size)
    assertEquals("Couldn't sort this; check it", Labels.reason(item))
  }

  @Test fun noClassifierMeansUnclearIsFyi() {
    val r = Rig(classifier = null)
    val a = r.added(nova("Interesting article on transformer scaling laws"))
    assertEquals(Lane.FYI, a.item.lane); assertFalse(a.item.awaitingLlm); assertEquals(Notify.QUIET, a.notify)
  }

  @Test fun notifyRules() {
    val r = Rig()
    assertEquals(Notify.BUZZ, r.added(nova("Want me to book the 7pm table at ROOH?")).notify)
    assertEquals(Notify.QUIET, r.added(nova("Your package from Amazon was delivered.")).notify)
    val noise = r.added(nova("Pro tip: you can ask me to track packages too"))
    assertEquals(Notify.NONE, noise.notify); assertTrue(noise.suppressOriginal)
    assertEquals(Notify.NONE, r.added(nova("Should I renew the domain atall.co?", historical = true)).notify)
  }

  @Test fun userOverrideSticksAcrossDuplicates() {
    val r = Rig()
    val a = r.added(nova("Your landlord emailed about the lease renewal."))
    r.inbox.moveTo(a.item.id, Lane.NOISE)
    val m = r.merged(scout("Landlord email about the lease renewal: want me to draft a reply?", T0 + 5 * MIN))
    assertEquals(Lane.NOISE, m.item.lane)
    assertEquals(TriagedBy.USER, m.item.triagedBy)
  }

  @Test fun doneItemReopensOnNewNeedsYouButDismissedStaysDismissed() {
    val r = Rig()
    val a = r.added(nova("Your landlord emailed about the lease renewal. Want me to reply?"))
    r.inbox.markDone(a.item.id)
    assertEquals(0, r.inbox.waitingOnYou().count)
    val m = r.merged(scout("Landlord lease renewal email still needs a reply: want me to answer it?", T0 + HOUR))
    assertEquals(ItemState.OPEN, m.item.state)
    assertEquals(1, r.inbox.waitingOnYou().count)
    r.inbox.dismiss(a.item.id)
    val again = r.merged(nova("Landlord lease renewal email: should I reply?", T0 + 2 * HOUR))
    assertEquals(ItemState.DISMISSED, again.item.state)
    assertEquals(Notify.NONE, again.notify)
  }

  @Test fun waitingOnYouIsSortedByDeadlineAndSkipsSnoozed() {
    val r = Rig()
    val later = r.added(nova("Please sign the lease by Wednesday"))
    val sooner = r.added(scout("Rent of \$2,450 is due tomorrow"))
    r.added(nova("Your package from Amazon was delivered."))
    val w = r.inbox.waitingOnYou()
    assertEquals(2, w.count)
    assertEquals(listOf(sooner.item.id, later.item.id), w.items.map { it.id })
    assertEquals("Tomorrow", w.items[0].dueLabel)
    assertEquals("2 things waiting on you", w.label)
    r.inbox.snooze(sooner.item.id, T0 + DAY)
    assertEquals(1, r.inbox.waitingOnYou().count)
    assertEquals(1, r.inbox.state.value.waitingCount)
    r.clock.advance(2 * DAY)
    r.inbox.refresh()
    assertEquals(2, r.inbox.waitingOnYou().count)
    assertTrue(r.inbox.waitingOnYou().items.first().overdue)
  }

  @Test fun titlesDropFillerAndTruncate() {
    assertEquals("Your landlord emailed about the lease renewal.", AgentInbox.titleOf("Heads up: your landlord emailed about the lease renewal. Reply soon."))
    assertTrue(AgentInbox.titleOf("x".repeat(200)).length <= 80)
  }
}
