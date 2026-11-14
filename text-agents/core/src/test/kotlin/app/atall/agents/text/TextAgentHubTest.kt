@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.agents.text

import app.atall.agents.text.testing.FakeMessagesApp
import app.atall.agents.text.testing.ManualClock
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end over the simulated Google Messages: the hub as the Android listener and the coordinator see it.
 * Never touches a phone or a real agent.
 */
class TextAgentHubTest {
  private val WREN = AgentId("wren")
  private val QBIT = AgentId("qbit")
  private val sec = 1_000L
  private val min = 60_000L

  private inner class Rig(
    val dir: File? = null,
    val config: TextAgentConfig = TextAgentConfig(),
    val clock: ManualClock = ManualClock(),
    val phone: FakeMessagesApp = FakeMessagesApp(clock),
  ) {
    val store: TextAgentStore = dir?.let { FileTextAgentStore(it) } ?: InMemoryStore()
    val drafts = phone.Drafts()
    val hub = TextAgentHub(store, drafts, clock, config, java.util.Random(7), Dispatchers.Unconfined)
    init {
      phone.conversation("Wren", "+14085550101")
      phone.conversation("qBit", "+14085550102")
      phone.attach(hub)
    }
    fun registerBoth(): Pair<TextAgent, TextAgent> =
      hub.register(AgentConfig(WREN, "Wren", "+1 408 555 0101")) to hub.register(AgentConfig(QBIT, "qBit", "+14085550102"))

    /** Restart the process: a new hub on the same store; live actions are gone. */
    fun restart(): Rig = Rig(dir, config, clock, phone).also { it.phone.attach(it.hub) }
  }

  private class Collected(val messages: MutableList<AgentMessage>, val events: MutableList<HubEvent>, val jobs: List<Job>)

  private fun TestScope.collect(agent: TextAgent, hub: TextAgentHub): Collected {
    val m = mutableListOf<AgentMessage>()
    val e = mutableListOf<HubEvent>()
    val d = UnconfinedTestDispatcher(testScheduler)
    return Collected(m, e, listOf(backgroundScope.launch(d) { agent.incoming.toList(m) }, backgroundScope.launch(d) { hub.events.toList(e) }))
  }

  // ------------------------------------------------------------------ the happy path

  @Test fun replyThroughLiveActionAndCorrelateMultiPartAnswer() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "Hi! I'm Wren.")
    assertEquals(1, got.messages.size)
    assertNull(got.messages[0].correlation) // unsolicited
    assertTrue(r.hub.liveStatus(WREN).usable)

    r.clock.advance(10 * sec)
    val res = wren.send("Book a table for 2 at 7pm", "req-1")
    assertTrue(res is SendResult.Sent)
    assertEquals(listOf("Wren" to "Book a table for 2 at 7pm"), r.phone.fired)
    // Our echo came back as a self line: not an incoming message, not a user line.
    assertEquals(1, got.messages.size)
    assertTrue(got.events.none { it is UserLine })
    assertTrue(wren.outstanding().single().echoedAt > 0)

    r.clock.advance(40 * sec)
    r.phone.agentTexts("Wren", "Looking now...")
    r.clock.advance(20 * sec)
    r.phone.agentTexts("Wren", "Booked: Rooh, 7pm, 2 people.")
    assertEquals(3, got.messages.size)
    val c1 = got.messages[1].correlation!!
    val c2 = got.messages[2].correlation!!
    assertEquals("req-1", c1.correlationId); assertEquals(Confidence.HIGH, c1.confidence); assertEquals(0, c1.part)
    assertEquals("req-1", c2.correlationId); assertEquals(CorrelationReason.CONTINUATION, c2.reason); assertEquals(1, c2.part)

    r.clock.advance(2 * min)
    r.hub.sweep()
    val closed = got.events.filterIsInstance<RequestClosed>().single()
    assertEquals(RequestClosed.Outcome.COMPLETED, closed.outcome)
    assertTrue(wren.outstanding().isEmpty())

    val h = wren.history(10)
    assertEquals(listOf(Direction.IN, Direction.OUT, Direction.IN, Direction.IN), h.map { it.dir })
    assertEquals(Via.ACTION, h[1].via)
    assertEquals("req-1", h[3].correlation!!.correlationId)
  }

  @Test fun repostedHistoryIsDedupedAndOtherConversationsIgnored() = runTest {
    val r = Rig()
    val (wren, qbit) = r.registerBoth()
    val gh = collect(wren, r.hub)
    val gk = collect(qbit, r.hub)
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "one")
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "two") // notification re-posts "one" too
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "two") // same text, new message: kept
    r.phone.agentTexts("Mom", "dinner?")
    r.phone.agentTexts("qBit", "yo")
    r.hub.onRescan(r.phone.active()) // a rescan re-reads it all again
    assertEquals(listOf("one", "two", "two"), gh.messages.map { it.text })
    assertEquals(listOf("yo"), gk.messages.map { it.text })
    assertEquals(4, r.store.log(null, 100).size)
  }

  // ------------------------------------------------------------------ user's own lines

  @Test fun userTypingIntoTheThreadIsLoggedAndLowersConfidence() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "hello")
    r.clock.advance(5 * sec)
    wren.send("What's on my calendar?", "q1")
    r.clock.advance(5 * sec)
    r.phone.userTypes("Wren", "actually skip that, what's the weather")
    r.clock.advance(20 * sec)
    r.phone.agentTexts("Wren", "Sunny, 18C")
    val reply = got.messages.last()
    assertEquals("Sunny, 18C", reply.text)
    assertEquals(Confidence.LOW, reply.correlation!!.confidence)
    assertTrue(reply.correlation!!.userInterjected)
    assertEquals("actually skip that, what's the weather", got.events.filterIsInstance<UserLine>().single().text)
    assertTrue(wren.history(10).any { it.dir == Direction.USER })
    // User lines never surface as agent messages.
    assertTrue(got.messages.none { it.text.startsWith("actually") })
  }

  // ------------------------------------------------------------------ fallbacks

  @Test fun noLiveActionOpensADraftAndConfirmsWhenTheUserSends() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    val res = wren.send("Hi Wren", "d1")
    assertTrue(res is SendResult.Drafted)
    assertEquals(DraftReason.NO_LIVE_ACTION, (res as SendResult.Drafted).why)
    assertEquals(DraftRequest("+14085550101", "Hi Wren"), r.drafts.opened.single())
    assertEquals(RequestState.AWAITING_USER_SEND, wren.outstanding().single().state)
    assertTrue(r.phone.conversations["Wren"]!!.chatOpen) // the draft opened the chat

    // User taps Send 20 s later, closes the chat; the agent replies, Messages notifies with history incl. our line.
    r.clock.advance(20 * sec)
    r.drafts.userTapsSend(r.drafts.opened.single())
    r.phone.closeChat("Wren")
    r.clock.advance(30 * sec)
    r.phone.conversation("Wren").unread += r.phone.conversation("Wren").thread.last() // Messages shows our line as context
    r.phone.agentTexts("Wren", "Hey!")
    val req = r.store.requests().single()
    assertEquals(RequestState.ANSWERED, req.state)
    assertTrue(req.echoedAt > 0)
    assertEquals(r.clock.now() - 30 * sec, req.sentAt) // re-based to when the user actually sent it
    assertEquals("d1", got.messages.single().correlation!!.correlationId)
    assertTrue(got.events.none { it is UserLine })
  }

  @Test fun canceledActionFallsBackToDraft() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    r.phone.revokeActions()
    val res = wren.send("hello?", "c1")
    assertEquals(DraftReason.ACTION_CANCELED, (res as SendResult.Drafted).why)
    assertFalse(r.hub.liveStatus(WREN).hasAction)
    assertTrue(r.phone.fired.isEmpty())
  }

  @Test fun actionErrorFallsBackToDraft() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    r.phone.failNextFire = true
    assertEquals(DraftReason.ACTION_FAILED, (wren.send("x", "c1") as SendResult.Drafted).why)
  }

  @Test fun actionGraceAfterTheNotificationWentAway() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    r.phone.dismiss("Wren")
    r.clock.advance(54 * sec) // the spike's proven case
    assertTrue(wren.send("still works", "c1") is SendResult.Sent)
    r.clock.advance(r.hub.config.actionGraceMs)
    val res = wren.send("too old now", "c2")
    assertEquals(DraftReason.ACTION_EXPIRED, (res as SendResult.Drafted).why)
  }

  @Test fun backgroundAppCannotOpenDraftSoItNeedsTheUser() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    r.drafts.outcome = DraftOutcome.IN_BACKGROUND
    val res = wren.send("hi", "c1")
    res as SendResult.NeedsUser
    assertEquals(NeedsUserReason.APP_IN_BACKGROUND, res.why)
    assertEquals("+14085550101", res.draft.number)
    // Not consumed: the same correlationId can be retried once the app is in front.
    assertTrue(wren.outstanding().isEmpty())
    r.drafts.outcome = DraftOutcome.OPENED
    assertTrue(wren.send("hi", "c1") is SendResult.Drafted)
  }

  @Test fun draftsCanBeDisabledAndNeedANumber() = runTest {
    val r = Rig()
    val noNumber = r.hub.register(AgentConfig(AgentId("bot"), "Bot"))
    assertEquals(FailReason.NO_NUMBER, (noNumber.send("hi", "c1") as SendResult.Failed).why)
    val (wren, _) = r.registerBoth()
    val res = wren.send("hi", "c2", SendOptions(allowDraft = false))
    assertEquals(NeedsUserReason.DRAFTS_DISABLED, (res as SendResult.NeedsUser).why)
    r.drafts.outcome = DraftOutcome.NO_APP
    assertEquals(FailReason.NO_MESSAGES_APP, (wren.send("hi", "c3") as SendResult.Failed).why)
  }

  // ------------------------------------------------------------------ validation, idempotence, limits

  @Test fun validationAndDuplicateCorrelationIds() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    assertEquals(FailReason.EMPTY, (wren.send("   ", "a") as SendResult.Failed).why)
    assertEquals(FailReason.TOO_LONG, (wren.send("x".repeat(2_001), "a") as SendResult.Failed).why)
    assertEquals(FailReason.BAD_CORRELATION_ID, (wren.send("x", "") as SendResult.Failed).why)
    assertEquals(FailReason.BAD_CORRELATION_ID, (wren.send("x", "a\tb") as SendResult.Failed).why)
    assertTrue(wren.send("x", "a") is SendResult.Sent)
    r.clock.advance(10 * sec)
    assertTrue(wren.send("x", "a") is SendResult.Duplicate)
    assertEquals(1, r.phone.fired.size)
    r.hub.unregister(WREN)
    assertNull(r.hub.agent(WREN))
    assertEquals(FailReason.UNKNOWN_AGENT, (wren.send("x", "b") as SendResult.Failed).why)
  }

  @Test fun duplicateCorrelationIdsSurviveRestart() = runTest {
    val r = Rig(dir = Files.createTempDirectory("hub").toFile())
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    assertTrue(wren.send("x", "once") is SendResult.Sent)
    val r2 = r.restart()
    r.clock.advance(10 * sec)
    assertTrue(r2.hub.agent(WREN)!!.send("x", "once") is SendResult.Duplicate)
  }

  @Test fun rateLimitedAndOutstandingCap() = runTest {
    val r = Rig(config = TextAgentConfig(maxOutstandingPerAgent = 2))
    val (wren, qbit) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    assertTrue(wren.send("1", "c1") is SendResult.Sent)
    val limited = wren.send("2", "c2")
    assertEquals(RateScope.AGENT_INTERVAL, (limited as SendResult.RateLimited).scope)
    assertTrue(limited.retryAfterMs in 1..3_000)
    r.clock.advance(3 * sec)
    assertTrue(wren.send("2", "c2") is SendResult.Sent) // rate-limited id was not consumed
    r.clock.advance(3 * sec)
    assertEquals(FailReason.TOO_MANY_OUTSTANDING, (wren.send("3", "c3") as SendResult.Failed).why)
    assertTrue(wren.send("thanks", "c4", SendOptions(expectReply = false)) is SendResult.Sent) // fire-and-forget is fine
    assertTrue(qbit.send("other agent", "k1") is SendResult.Drafted)
  }

  @Test fun fireAndForgetOpensNoRequest() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "done!")
    wren.send("thanks", "t1", SendOptions(expectReply = false))
    r.clock.advance(10 * sec)
    r.phone.agentTexts("Wren", "anytime")
    assertNull(got.messages.last().correlation)
    assertTrue(got.events.none { it is UserLine }) // the echo of "thanks" is recognized
  }

  // ------------------------------------------------------------------ correlation with several requests

  @Test fun twoQuickRequestsAreAnsweredInOrderWithMediumConfidence() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    wren.send("weather?", "w")
    r.clock.advance(4 * sec)
    wren.send("and traffic?", "t")
    r.clock.advance(30 * sec)
    r.phone.agentTexts("Wren", "Sunny")
    r.clock.advance(5 * min) // past continuation
    r.phone.agentTexts("Wren", "Light traffic")
    assertEquals(listOf("w", "t"), got.messages.drop(1).map { it.correlation!!.correlationId })
    assertEquals(Confidence.MEDIUM, got.messages[1].correlation!!.confidence)
  }

  @Test fun tagCorrelatesOutOfOrderReplies() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    val a = wren.send("long task", "a", SendOptions(tag = true)) as SendResult.Sent
    r.clock.advance(4 * sec)
    val b = wren.send("quick task", "b", SendOptions(tag = true)) as SendResult.Sent
    assertTrue(a.sentText.startsWith("long task\n(ref #"))
    val tagB = r.store.requests().first { it.correlationId == "b" }.tag!!
    r.clock.advance(10 * sec)
    r.phone.agentTexts("Wren", "Done with #$tagB")
    assertEquals("b", got.messages.last().correlation!!.correlationId)
    assertEquals(CorrelationReason.TAG, got.messages.last().correlation!!.reason)
    assertTrue(b.sentText.contains(tagB))
  }

  @Test fun closeStopsAttribution() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    wren.send("q", "c1")
    wren.close("c1")
    assertEquals(RequestClosed.Outcome.CLOSED_BY_CALLER, got.events.filterIsInstance<RequestClosed>().single().outcome)
    r.clock.advance(20 * sec)
    r.phone.agentTexts("Wren", "answer")
    assertNull(got.messages.last().correlation)
  }

  // ------------------------------------------------------------------ suppression, lag, restarts

  @Test fun replyWhileChatIsOpenIsMissedAndReportedLikelyMissed() = runTest {
    val r = Rig(config = TextAgentConfig(replyWindowMs = 10 * min))
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    wren.send("q", "c1")
    r.clock.advance(30 * sec) // past the own-reply guard
    r.phone.openChat("Wren") // user opens Messages: notification cancelled
    assertEquals(WREN, got.events.filterIsInstance<ThreadOpened>().single().agentId)
    r.phone.agentTexts("Wren", "answer you'll never see here") // suppressed
    r.clock.advance(11 * min)
    r.hub.sweep()
    assertEquals(1, got.messages.size)
    assertEquals(RequestClosed.Outcome.LIKELY_MISSED, got.events.filterIsInstance<RequestClosed>().single().outcome)
  }

  @Test fun ownReplyCancellingTheNotificationIsNotTheUserOpeningIt() = runTest {
    val r = Rig(config = TextAgentConfig(replyWindowMs = 10 * min))
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    wren.send("q", "c1")
    r.clock.advance(2 * sec)
    r.phone.openChat("Wren") // as if Messages dismissed it because we replied
    assertTrue(got.events.none { it is ThreadOpened })
    r.clock.advance(11 * min)
    r.hub.sweep()
    assertEquals(RequestClosed.Outcome.EXPIRED, got.events.filterIsInstance<RequestClosed>().single().outcome)
  }

  @Test fun draftedRequestsCarrySuppressionRisk() = runTest {
    val r = Rig(config = TextAgentConfig(replyWindowMs = 5 * min))
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    wren.send("hi", "d1")
    r.clock.advance(6 * min)
    r.hub.sweep()
    assertEquals(RequestClosed.Outcome.LIKELY_MISSED, got.events.filterIsInstance<RequestClosed>().single().outcome)
  }

  @Test fun rcsLagStillCorrelatesAfterExpiryFlaggedLate() = runTest {
    val r = Rig(config = TextAgentConfig(replyWindowMs = 10 * min))
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    wren.send("q", "c1")
    val stamp = r.clock.now() + 2 * min // the agent answered at +2 min...
    r.clock.advance(14 * min) // ...but RCS delivered it at +14 min, after expiry
    r.hub.sweep()
    assertEquals(RequestClosed.Outcome.EXPIRED, got.events.filterIsInstance<RequestClosed>().single().outcome)
    r.phone.agentTexts("Wren", "here you go", sentAt = stamp)
    val c = got.messages.last().correlation!!
    assertEquals("c1", c.correlationId)
    assertTrue(c.late)
    assertEquals(stamp, got.messages.last().sentAt)
    assertTrue(got.messages.last().observedAt > stamp)
  }

  @Test fun processRestartKeepsLogAndRequestsAndRecoversActionsByRescan() = runTest {
    val dir = Files.createTempDirectory("hub").toFile()
    val r = Rig(dir = dir)
    val (wren, _) = r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    wren.send("q", "c1")

    // Process dies. The notification is still showing.
    val r2 = r.restart()
    val wren2 = r2.hub.agent(WREN)!!
    assertEquals("c1", wren2.outstanding().single().correlationId)
    assertFalse(r2.hub.liveStatus(WREN).hasAction)
    val got = collect(wren2, r2.hub)
    r2.hub.onRescan(r2.phone.active()) // onListenerConnected
    assertTrue(r2.hub.liveStatus(WREN).usable)
    assertTrue(got.messages.isEmpty()) // nothing re-emitted
    r2.clock.advance(20 * sec)
    r2.phone.agentTexts("Wren", "answer")
    assertEquals("c1", got.messages.single().correlation!!.correlationId)
    r2.clock.advance(10 * sec)
    assertTrue(wren2.send("next", "c2") is SendResult.Sent)
  }

  @Test fun coldStartWithoutNotificationNeedsDraft() = runTest {
    val dir = Files.createTempDirectory("hub").toFile()
    val r = Rig(dir = dir)
    r.registerBoth()
    r.phone.agentTexts("Wren", "hi")
    r.phone.dismiss("Wren")
    r.clock.advance(r.hub.config.actionGraceMs + 1) // reboot much later
    val r2 = r.restart()
    r2.hub.onRescan(r2.phone.active())
    assertTrue(r2.hub.agent(WREN)!!.send("hello again", "c1") is SendResult.Drafted)
  }

  @Test fun historyBeforeRegistrationIsMarkedAndNeverCorrelated() = runTest {
    val r = Rig()
    r.phone.agentTexts("Wren", "old line")
    r.clock.advance(min)
    val wren = r.hub.register(AgentConfig(WREN, "Wren", "+14085550101"))
    val got = collect(wren, r.hub)
    r.hub.onRescan(r.phone.active())
    assertTrue(got.messages.single().historical)
    assertNull(got.messages.single().correlation)
  }

  @Test fun staleHistoryAfterLogTrimIsNotReingested() = runTest {
    val r = Rig(config = TextAgentConfig(staleHistoryMs = 60 * min))
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.clock.advance(sec)
    r.phone.agentTexts("Wren", "new")
    // A re-posted line from 2 h before the newest seen one whose key was lost.
    r.hub.onPosted(NotificationSnapshot(Rules.MESSAGES_PACKAGE, "k", r.clock.now(), conversationTitle = "Wren", selfName = "You",
      lines = listOf(SnapshotLine("ancient", r.clock.now() - 120 * min, "Wren"))))
    assertEquals(listOf("new"), got.messages.map { it.text })
  }

  @Test fun plainTextNotificationsDedupeByTextAndIgnoreEcho() = runTest {
    val r = Rig()
    r.phone.plainText = true
    r.phone.echoReplies = false
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.phone.agentTexts("Wren", "hi")
    r.clock.advance(5 * sec)
    r.hub.onRescan(r.phone.active()) // re-post, new post time
    assertEquals(1, got.messages.size)
    wren.send("q", "c1")
    // Messages shows our own reply as the plain text: not the agent.
    r.hub.onPosted(NotificationSnapshot(Rules.MESSAGES_PACKAGE, "k", r.clock.now(), title = "Wren", text = "q"))
    assertEquals(1, got.messages.size)
    r.clock.advance(20 * sec)
    r.phone.agentTexts("Wren", "answer")
    assertEquals("c1", got.messages.last().correlation!!.correlationId)
  }

  @Test fun groupConversationsAreIgnored() = runTest {
    val r = Rig()
    val (wren, _) = r.registerBoth()
    val got = collect(wren, r.hub)
    r.hub.onPosted(NotificationSnapshot(Rules.MESSAGES_PACKAGE, "g", r.clock.now(), conversationTitle = "Wren", isGroupConversation = true,
      lines = listOf(SnapshotLine("in a group", r.clock.now(), "Wren")), reply = object : ReplyHandle { override fun fire(text: String) = FireResult.OK }))
    assertTrue(got.messages.isEmpty())
    assertFalse(r.hub.liveStatus(WREN).hasAction)
  }

  @Test fun registrationRejectsOverlappingAgents() {
    val r = Rig()
    r.registerBoth()
    val clashes = listOf(
      AgentConfig(AgentId("x"), "wren"),
      AgentConfig(AgentId("x"), "Other", aliases = listOf("QBIT")),
      AgentConfig(AgentId("x"), "Other", number = "(408) 555-0101"),
    )
    for (c in clashes) {
      try { r.hub.register(c); throw AssertionError("accepted $c") } catch (e: IllegalArgumentException) { /* expected */ }
    }
    // Re-registering the same id (rename) is fine.
    r.hub.register(AgentConfig(WREN, "Wren", "+14085550101", listOf("Wren AI")))
    assertEquals(2, r.hub.agents().size)
  }
}
