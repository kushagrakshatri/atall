@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.agents.text

import app.atall.agents.text.testing.FakeMessagesApp
import app.atall.agents.text.testing.ManualClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m05 regressions from the 2026-11-14 device runs (Sentry, release app.atall@0.1.4-m04+5):
 *  - run r6cca6eef: Hark's "Looks like your signal dropped…" was taken as its answer;
 *  - run r35c7296e: kDot's notification was cancelled by Messages (APP_CANCEL) and kDot was dropped from the run.
 */
class StatusTextsAndAppCancelTest {
  private val sec = 1_000L
  private val min = 60_000L
  private val HARK = AgentId("hark")
  private val KDOT = AgentId("kdot")

  // ------------------------------------------------------------------ the classifier

  @Test fun deviceStatusTextsAreNotAnswers() {
    // Word for word from the Sentry breadcrumb of run r6cca6eef.
    assertEquals(NonAnswers.Kind.CONNECTION, NonAnswers.classify(
      "Looks like your signal dropped. Feel free to keep texting, and I'll pick everything up once you're back online."))
    assertEquals(NonAnswers.Kind.CONNECTION, NonAnswers.classify("Something went wrong. Please try again."))
    assertEquals(NonAnswers.Kind.CONNECTION, NonAnswers.classify("Sorry, I'm having trouble connecting right now."))
    assertEquals(NonAnswers.Kind.CONNECTION, NonAnswers.classify("Network error"))
    assertEquals(NonAnswers.Kind.HOLDING, NonAnswers.classify("Let me check."))
    assertEquals(NonAnswers.Kind.HOLDING, NonAnswers.classify("On it, one sec!"))
    assertEquals(NonAnswers.Kind.HOLDING, NonAnswers.classify("Give me a minute 🙏"))
    assertEquals(NonAnswers.Kind.HOLDING, NonAnswers.classify("Hang tight, I'll get back to you shortly."))
    assertEquals(NonAnswers.Kind.HOLDING, NonAnswers.classify("I'm looking into it. I'll text you when I have something."))
    assertEquals(NonAnswers.Kind.TYPING, NonAnswers.classify("typing…"))
    assertEquals(NonAnswers.Kind.TYPING, NonAnswers.classify("..."))
    assertEquals(NonAnswers.Kind.DELIVERY_FAILED, NonAnswers.classify("Message not delivered"))
    assertEquals(NonAnswers.Kind.DELIVERY_FAILED, NonAnswers.classify("Your message failed to send. Please try again."))
  }

  @Test fun realAnswersStayAnswers() {
    for (t in listOf(
      "I'd lean toward something she'll actually use: a WHOOP upgrade, since she kept the last one, a spa day for two, or a small fine-jewelry piece. When's her birthday, and roughly what budget? Then I'll pull real picks.",
      "Keep both Beatty nights. Wednesday is mostly driving from Bakersfield, so Thursday is your only full day in the park.",
      "On it! Get her a Kindle.",
      "Let me check — what's her budget?",
      "The Atomic Inn is unavailable Nov 25.",
      "I'm thinking a spa day.",
      "Let me check: the van is $812 for 5 days.",
      "Looking now...", // a holding reply, but text-agents' own multi-part test treats it as part 0; left to the answer path
      "Booked: Rooh, 7pm, 2 people.",
      "No.",
      "👍",
    )) assertNull(t, NonAnswers.classify(t))
    assertNull(NonAnswers.classify("Let me check. " + "word ".repeat(80)))
  }

  // ------------------------------------------------------------------ the hub

  private inner class Rig(config: TextAgentConfig = TextAgentConfig()) {
    val clock = ManualClock()
    val phone = FakeMessagesApp(clock)
    val diag = mutableListOf<String>()
    val hub = TextAgentHub(InMemoryStore(), phone.Drafts(), clock, config, java.util.Random(7), Dispatchers.Unconfined, diagnostics = { diag += it })
    init {
      phone.conversation("Hark", "+14085550111"); phone.conversation("kDot", "+14085550112")
      phone.attach(hub)
    }
    val hark = hub.register(AgentConfig(HARK, "Hark", "+14085550111"))
    val kdot = hub.register(AgentConfig(KDOT, "kDot", "+14085550112"))
  }

  @Test fun statusTextIsTiedToTheRequestButNeverAnswersIt() = runTest {
    val r = Rig()
    val msgs = mutableListOf<AgentMessage>(); val events = mutableListOf<HubEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.hark.incoming.toList(msgs) }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.hub.events.toList(events) }
    r.phone.agentTexts("Hark", "hi")
    r.clock.advance(sec)
    assertTrue(r.hark.send("Resolve the trip costs", "r6cca6eef~a0") is SendResult.Sent)
    r.clock.advance(24 * sec)
    r.phone.agentTexts("Hark", "Looks like your signal dropped. Feel free to keep texting, and I'll pick everything up once you're back online.")
    val status = msgs.last()
    assertEquals(NonAnswers.Kind.CONNECTION, status.nonAnswer)
    assertEquals("r6cca6eef~a0", status.correlation!!.correlationId)
    // The request stays unanswered: no COMPLETED 90 s later, still outstanding.
    r.clock.advance(3 * min); r.hub.sweep()
    assertTrue(events.none { it is RequestClosed })
    assertEquals(0, r.hark.outstanding().single().replies)
    // The real answer, minutes later, is still correlated with full confidence (not a stray line).
    r.phone.agentTexts("Hark", "Van total is $812 with fees; hotels come to $1,831.11.")
    val c = msgs.last().correlation!!
    assertNull(msgs.last().nonAnswer)
    assertEquals("r6cca6eef~a0", c.correlationId)
    assertEquals(Confidence.HIGH, c.confidence)
    assertEquals(0, c.part)
  }

  @Test fun appCancelKeepsTheRequestOpenAndTheReplyActionUsable() = runTest {
    val r = Rig()
    val msgs = mutableListOf<AgentMessage>(); val events = mutableListOf<HubEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.kdot.incoming.toList(msgs) }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.hub.events.toList(events) }
    r.phone.agentTexts("kDot", "hey")
    assertTrue(r.kdot.send("Two days for Death Valley?", "r35c7296e~a1") is SendResult.Sent)
    r.clock.advance(2 * min)
    // Messages cancels the notification itself (the chat was opened/read): run r35c7296e, 18:15:20 PST.
    r.phone.openChat("kDot"); r.phone.closeChat("kDot")
    assertTrue(r.diag.any { it.startsWith("removed agent=kdot reason=APP_CANCEL") })
    // Nothing closes: the request keeps its whole reply window.
    r.clock.advance(5 * min); r.hub.sweep()
    assertTrue(events.none { it is RequestClosed })
    assertEquals("r35c7296e~a1", r.kdot.outstanding().single().correlationId)
    // The stored reply action stays usable well past the grace a dismissed notification gets.
    r.clock.advance(r.hub.config.actionGraceMs + min)
    assertTrue(r.hub.liveStatus(KDOT).usable)
    val again = r.kdot.send("Also: is Thursday sunset worth it?", "r35c7296e~a2")
    assertTrue(again.toString(), again is SendResult.Sent)
    // kDot's answer still lands on the first request.
    r.phone.agentTexts("kDot", "Keep both nights; Thursday is your only full park day.")
    assertEquals("r35c7296e~a1", msgs.last().correlation!!.correlationId)
  }

  @Test fun aDismissedNotificationStillGetsOnlyTheGrace() = runTest {
    val r = Rig()
    r.phone.agentTexts("kDot", "hey")
    r.phone.dismiss("kDot")
    r.clock.advance(r.hub.config.actionGraceMs + sec)
    assertTrue(!r.hub.liveStatus(KDOT).usable)
  }
}
