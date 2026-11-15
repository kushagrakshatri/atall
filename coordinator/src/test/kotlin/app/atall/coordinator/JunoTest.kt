package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class JunoTest {

  /** Story 1: two agents asked in parallel, one merged answer, raw replies folded, strip done. */
  @Test fun multiAgentMerge() = runTest {
    val h = Harness(this)
    h.scout.behavior = { Behavior.Reply(listOf(Part(8_000, "Try Sakoon or ROOH, both have veg-heavy menus."))) }
    // Nova answers in two fragments; the quiet window must join them.
    h.nova.behavior = { Behavior.Reply(listOf(Part(12_000, "ROOH has 7:30 for 4 on Saturday."), Part(4_000, "Sakoon only has 9:15."))) }
    var waitOut: String? = null
    h.llm.on("juno") { req, n ->
      when (n) {
        0 -> FakeLlm.tools(askAgent("Scout", "Ideas for Saturday dinner for 4 in San Jose, no pork or beef?"),
          askAgent("Nova", "Is there a table for 4 Saturday 7-8pm at ROOH or Sakoon? Don't book anything yet."), waitCall())
        else -> { waitOut = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("ROOH at 7:30 works for four. Want Nova to book it?")) }
      }
    }
    val runId = h.juno.startCoordinated("Plan Saturday dinner for four, no pork or beef.")
    val result = h.juno.drive(runId)
    val done = assertIs<DriveResult.Done>(result)
    assertEquals("ROOH at 7:30 works for four. Want Nova to book it?", done.view.answer)

    // Sends went out concurrently before any reply, and nothing was gated.
    assertEquals(1, h.scout.sent.size); assertEquals(1, h.nova.sent.size)
    val asks = waitAsks(waitOut!!)
    assertEquals(listOf("answered", "answered"), asks.map { it.text("status") })
    assertEquals("ROOH has 7:30 for 4 on Saturday.\nSakoon only has 9:15.", asks.first { it.text("agent") == "Nova" }.text("reply"))
    // The wait resolved at Nova's last fragment + the 20 s quiet window, not at the 180 s ceiling.
    assertEquals(16_000L + 20_000L, currentTime)

    val folded = assertNotNull(done.view.folded)
    assertEquals("From Scout and Nova", folded.label)
    assertEquals(2, folded.replies.size)
    val strip = assertNotNull(done.view.strip)
    assertEquals(StripPhase.DONE, strip.phase)
    assertEquals("Asked Scout and Nova", strip.labelText)
    assertEquals("36s", strip.timer)
    assertEquals(2, h.llm.calls("juno"))
    // The model got the roster, the planner hint and the tools.
    val first = h.llm.requests.first()
    assertTrue(first.instructions.contains("- Scout: good at ideas, research, writing"))
    // "dinner" matches no tag literally, so the hint tells Juno to judge for itself (tags are hints, never routes).
    assertTrue(FakeLlm.developerTexts(first).any { it.contains("No agent's tags clearly match") })
    assertEquals("Plan Saturday dinner for four, no pork or beef.", FakeLlm.userTexts(first).single())
    assertEquals(setOf("ask_agent", "wait", "ask_user", "reply"), first.tools.map { it.name }.toSet())
  }

  /** Soft timeout: Juno answers with what it has; Nova's late reply becomes a follow-up. Hard timeout -> didn't answer. */
  @Test fun timeoutsPartialMergeAndLateFollowUp() = runTest {
    // m05: a wait never ends before a pending agent's soft timeout, so this story's 60 s soft timeout is set explicitly.
    val h = Harness(this, config = JunoConfig(textSoftTimeoutMs = 60_000))
    h.scout.behavior = { Behavior.Reply(listOf(Part(5_000, "Sunday looks dry."))) }
    h.nova.behavior = { Behavior.Silent }
    var waitOut: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Will it rain this weekend in SF?"), askAgent("Nova", "Any plans on my calendar this weekend?"), waitCall(max = 60))
      else { waitOut = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Sunday's dry. Nova is still checking your calendar; I'll add it when it lands.")) }
    }
    h.llm.on("late") { req, _ -> FakeLlm.text("Nova's answer is in: you're free Sunday afternoon, so Sunday it is.") }
    val runId = h.juno.startCoordinated("Which weekend day should we hike?")
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(60_000L, currentTime)
    val asks = waitAsks(waitOut!!)
    assertEquals("still_working", asks.first { it.text("agent") == "Nova" }.text("status"))
    assertEquals("Asked Scout and Nova", done.view.strip!!.labelText)
    assertEquals(listOf("Nova didn't answer"), done.view.strip!!.notes)
    assertEquals(listOf(LabelPart("Asked "), LabelPart("Scout", "scout"), LabelPart(" and "), LabelPart("Nova", "nova")), done.view.strip!!.label)

    // Nova replies 6 minutes later: recorded, then one follow-up after the quiet window.
    advanceTimeBy(6 * 60_000L)
    val askId = h.juno.state(runId)!!.asks.first { it.agentId == "nova" }.id
    h.nova.replyNow(askId, "You're free Sunday after 1pm.")
    advanceTimeBy(21_000); h.juno.sweep()
    val s = h.juno.state(runId)!!
    assertEquals(1, s.followUps.size)
    assertTrue(s.followUps.single().text.startsWith("Nova's answer is in"))
    assertTrue(s.asks.first { it.agentId == "nova" }.let { it.afterMerge && it.followedUp && it.status == AskStatus.ANSWERED })
    h.juno.sweep()
    assertEquals(1, h.juno.state(runId)!!.followUps.size) // only once
    assertEquals(false, h.juno.state(runId)!!.live)
  }

  @Test fun hardTimeoutMarksDidntAnswer() = runTest {
    val h = Harness(this, config = JunoConfig(textHardTimeoutMs = 120_000, maxWaitSeconds = 180))
    h.nova.behavior = { Behavior.Silent }
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Nova", "What's on my calendar tomorrow?"), waitCall(max = 180))
      else FakeLlm.tools(replyCall("Nova didn't get back to me. Want me to try again later?"))
    }
    val runId = h.juno.startCoordinated("What's on tomorrow?")
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(120_000L, currentTime) // hard timeout settled the wait before the 180 s ceiling
    val strip = done.view.strip!!
    assertEquals(StripPhase.FAILED, strip.phase)
    assertEquals("Nova didn't answer", strip.labelText)
    assertEquals(listOf(h.juno.state(runId)!!.asks.single().id), h.nova.closed)
  }

  /** Process death mid-wait: a new Juno over the same store resumes and merges a reply that arrived after restart. */
  @Test fun processDeathResume() = runTest {
    val dir = kotlin.io.path.createTempDirectory("juno-runs").toFile()
    val h1 = Harness(this, store = FileRunStore(dir))
    h1.scout.behavior = { Behavior.Silent }
    h1.nova.behavior = { Behavior.Silent }
    h1.llm.on("juno") { _, n -> if (n == 0) FakeLlm.tools(askAgent("Scout", "Gift ideas for a 30th birthday, under $80?"), askAgent("Nova", "When is Manav's birthday party?"), waitCall()) else error("old process") }
    val runId = h1.juno.startCoordinated("Help me plan Manav's birthday")
    val job = launch { h1.juno.drive(runId) }
    advanceTimeBy(30_000); runCurrent()
    assertEquals(Phase.WAITING, h1.juno.state(runId)!!.phase)
    job.cancel() // the process dies

    // New process: fresh Juno, fresh peers, same files.
    val h2 = Harness(this, store = FileRunStore(dir))
    h2.llm.on("juno") { req, n ->
      val out = waitAsks(FakeLlm.lastToolOutput(req)!!)
      assertEquals(listOf("answered", "answered"), out.map { it.text("status") })
      FakeLlm.tools(replyCall("Party's Saturday the 21st. A good gift: a nice board game, about $60."))
    }
    assertEquals(listOf(runId), h2.juno.recover())
    val s = h2.juno.state(runId)!!
    // Replies arriving after restart (the transport replays them from its durable log) attach to the persisted asks.
    h2.scout.replyNow(s.asks[0].id, "A nice board game, about \$60.")
    h2.nova.replyNow(s.asks[1].id, "Saturday the 21st at his place.")
    val done = assertIs<DriveResult.Done>(h2.juno.drive(runId))
    assertTrue(done.view.answer!!.startsWith("Party's Saturday"))
    // Nothing was re-sent and the model wasn't re-asked to plan.
    assertTrue(h2.scout.sent.isEmpty() && h2.nova.sent.isEmpty())
    assertEquals(1, h2.llm.calls("juno"))
    assertEquals(Phase.DONE, FileRunStore(dir).load(runId)!!.phase)
  }

  /** Crash after the model returned its calls but before the sends finished: resend is idempotent. */
  @Test fun crashBetweenModelCallAndSendDoesNotTextTwice() = runTest {
    val store = InMemoryRunStore()
    val h1 = Harness(this, store = store)
    h1.llm.on("juno") { _, _ -> FakeLlm.tools(askAgent("Nova", "What's on my calendar tomorrow?"), waitCall()) }
    val runId = h1.juno.startCoordinated("Tomorrow?")
    // Simulate: model output persisted as unprocessed, the ask persisted as SENDING, and the peer already accepted it.
    val s = h1.juno.state(runId)!!
    val call = askAgent("Nova", "What's on my calendar tomorrow?")
    val askId = "$runId~a0"
    store.save(s.copy(unprocessed = listOf(CallRecord(call.callId, call.name, call.arguments), CallRecord("w1", "wait", args("ask_ids" to listOf<String>(), "max_seconds" to 60))),
      asks = listOf(AskState(askId, "nova", "What's on my calendar tomorrow?", callId = call.callId, status = AskStatus.SENDING, createdAt = 0))))
    val h2 = Harness(this, store = store)
    h2.nova.behavior = { Behavior.Reply(listOf(Part(3_000, "Dentist at 10."))) }
    h2.nova.send("What's on my calendar tomorrow?", askId) // the first process's send had gone through
    h2.nova.sent.clear()
    h2.llm.on("juno") { req, _ -> FakeLlm.tools(replyCall("Just the dentist at 10.")) }
    h2.juno.recover()
    val done = assertIs<DriveResult.Done>(h2.juno.drive(runId))
    assertEquals("Just the dentist at 10.", done.view.answer)
    assertTrue(h2.nova.sent.isEmpty()) // Duplicate, not a second text
    assertEquals(1, h2.juno.state(runId)!!.asks.size)
  }

  /** Money/account asks pause for the user; approve sends, decline doesn't. */
  @Test fun approvalGating() = runTest {
    val h = Harness(this)
    h.nova.behavior = { Behavior.Reply(listOf(Part(4_000, "Booked: ROOH, Sat 7:30, 4 people."))) }
    h.llm.on("juno") { req, n ->
      when (n) {
        0 -> FakeLlm.tools(askAgent("Nova", "Book ROOH for 4 on Saturday at 7:30pm."), waitCall())
        else -> FakeLlm.tools(replyCall("Done: ROOH, Saturday 7:30, table for four."))
      }
    }
    val runId = h.juno.startCoordinated("Book it")
    val updates = async { h.juno.updates.toList() }
    val paused = assertIs<DriveResult.WaitingOnUser>(h.juno.drive(runId))
    assertTrue(h.nova.sent.isEmpty())
    val line = paused.view.needsUser.single()
    assertEquals(NeedsUserKind.APPROVE, line.kind)
    assertEquals("Okay to have Nova do this?", line.text)
    assertEquals(AgentStatus.NEEDS_APPROVAL, paused.view.strip!!.agents.single().status)
    // The user waits two minutes, then says yes.
    advanceTimeBy(120_000)
    assertTrue(h.juno.approve(runId, line.askId!!, approved = true))
    assertEquals(1, h.nova.sent.size)
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals("Done: ROOH, Saturday 7:30, table for four.", done.view.answer)
    val outs = FakeLlm.toolOutputs(h.llm.requests.last())
    assertTrue(outs.any { it.contains("Approved by the user") })
    assertEquals(1, h.scores.events.count { it is ScoreEvent.NeededUser && it.kind == "approval" })
    updates.cancel()
  }

  @Test fun approvalDeclined() = runTest {
    val h = Harness(this)
    var sawDecline = false
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Yes, go ahead and order it."), waitCall())
      else { sawDecline = FakeLlm.toolOutputs(req).any { it.contains("\"declined\"") }; FakeLlm.tools(replyCall("Okay, I won't order it.")) }
    }
    val runId = h.juno.startCoordinated("hmm")
    val paused = assertIs<DriveResult.WaitingOnUser>(h.juno.drive(runId))
    h.juno.approve(runId, paused.view.needsUser.single().askId!!, approved = false)
    assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertTrue(sawDecline)
    assertTrue(h.scout.sent.isEmpty())
  }

  /** LOW-confidence correlation is passed to the model flagged; LIKELY_MISSED gives a "look in Messages" line. */
  @Test fun lowConfidenceAndLikelyMissed() = runTest {
    val h = Harness(this)
    h.scout.behavior = { Behavior.Reply(listOf(Part(6_000, "lol yes", certainty = Certainty.LOW, userInterjected = true))) }
    h.nova.behavior = { Behavior.LikelyMissed(50_000) }
    var out: List<kotlinx.serialization.json.JsonObject> = emptyList()
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Is the museum open Monday?"), askAgent("Nova", "Do I have anything Monday?"), waitCall())
      else { out = waitAsks(FakeLlm.lastToolOutput(req)!!); FakeLlm.tools(replyCall("Not sure about the museum; check Messages for Nova's answer.")) }
    }
    val runId = h.juno.startCoordinated("Monday museum?")
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    val k = out.first { it.text("agent") == "Scout" }
    assertTrue(k.text("note")!!.startsWith("Uncertain"))
    assertEquals("maybe_answered_in_messages", out.first { it.text("agent") == "Nova" }.text("status"))
    assertTrue(done.view.needsUser.any { it.kind == NeedsUserKind.LOOK_IN_MESSAGES && it.text == "Nova may have answered in Messages" })
    assertTrue(done.view.strip!!.lines.first { it.agentId == "scout" }.uncertain)
    assertEquals(listOf("Nova may have answered in Messages"), done.view.strip!!.notes)
  }

  /** NeedsUser -> "Tap to send to Scout"; the tap starts the clock; Drafted counts as sent. */
  @Test fun needsTapAndDrafted() = runTest {
    val h = Harness(this)
    h.scout.behavior = { Behavior.NeedsTap(listOf(Part(5_000, "Here are three ideas."))) }
    h.nova.behavior = { Behavior.Drafted(listOf(Part(9_000, "You're free after 3."))) }
    h.llm.on("juno") { _, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "Three ideas for a Sunday outing?"), askAgent("Nova", "When am I free Sunday?"), waitCall())
      else FakeLlm.tools(replyCall("After 3 on Sunday, try the botanical garden."))
    }
    val runId = h.juno.startCoordinated("Sunday plans?")
    val driver = async { h.juno.drive(runId) }
    advanceTimeBy(1_000); runCurrent()
    val v = h.juno.view(runId)!!
    val tap = v.needsUser.single { it.kind == NeedsUserKind.TAP_TO_SEND }
    assertEquals("Tap to send to Scout", tap.text)
    assertEquals("Three ideas for a Sunday outing?", tap.detail)
    assertEquals(AgentStatus.NEEDS_TAP, v.strip!!.agents.first { it.agentId == "scout" }.status)
    assertEquals(AskStatus.DRAFTED, h.juno.state(runId)!!.asks.first { it.agentId == "nova" }.status)
    advanceTimeBy(20_000)
    h.scout.userTaps(tap.askId!!)
    val done = assertIs<DriveResult.Done>(driver.await())
    assertEquals("After 3 on Sunday, try the botanical garden.", done.view.answer)
    assertEquals(1, h.scores.events.count { it is ScoreEvent.NeededUser && it.kind == "tap_to_send" })
  }

  /** Story 3 and direct @mentions: no run, attributed messages. */
  @Test fun unsolicitedAndDirect() = runTest {
    val h = Harness(this)
    val seen = mutableListOf<RunUpdate>()
    val collector = launch { h.juno.updates.collect { seen += it } }
    runCurrent()
    h.scout.textsUnprompted("Your report is ready")
    val sub = h.juno.submit(Router.route("@Nova what's on my calendar tomorrow?", testRoster))
    val direct = assertIs<Juno.Submitted.DirectSent>(sub)
    assertIs<PeerSend.Sent>(direct.result)
    assertEquals("what's on my calendar tomorrow?", h.nova.sent.single().second)
    advanceTimeBy(2_000); runCurrent()
    assertTrue(seen.any { it is RunUpdate.Unsolicited && it.text == "Your report is ready" })
    assertTrue(seen.any { it is RunUpdate.DirectReply && it.agentId == "nova" })
    assertEquals(0, h.llm.requests.size)
    collector.cancel()
  }

  /** Story 6: named agents are a hard constraint; Juno is nudged once if it skips one. */
  @Test fun mustAskNudge() = runTest {
    val h = Harness(this)
    h.llm.on("juno") { req, n ->
      when (n) {
        0 -> FakeLlm.tools(replyCall("Just go with the cheaper one."))
        1 -> { assertTrue(FakeLlm.lastToolOutput(req)!!.contains("must_ask")); FakeLlm.tools(askAgent("Scout", "Price of option A?"), askAgent("Nova", "Price of option B?"), waitCall()) }
        else -> FakeLlm.tools(replyCall("B is cheaper."))
      }
    }
    val route = Router.route("@Juno ask @Scout and @Nova, then pick the cheaper option", testRoster) as Route.ToJuno
    val runId = h.juno.startCoordinated(route.text, route.mustAsk)
    assertEquals("B is cheaper.", assertIs<DriveResult.Done>(h.juno.drive(runId)).view.answer)
  }

  /** Caps: same agent twice, too many asks, and a model that never replies. */
  @Test fun limitsAndLoopGuard() = runTest {
    val h = Harness(this, config = JunoConfig(maxModelCalls = 3))
    h.scout.behavior = { Behavior.Reply(listOf(Part(1_000, "idea one"))) }
    var errors = emptyList<String>()
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Scout", "a?"), askAgent("Scout", "b?"), askAgent("Juno", "c?"), waitCall(max = 30))
      else { if (n == 1) errors = FakeLlm.toolOutputs(req); FakeLlm.tools(waitCall(max = 5)) } // keeps waiting forever
    }
    val runId = h.juno.startCoordinated("ideas")
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertTrue(errors.any { it.contains("agent_busy") })
    assertTrue(errors.any { it.contains("unknown_agent") })
    assertEquals(1, h.scout.sent.size)
    val s = h.juno.state(runId)!!
    assertTrue(s.fallback)
    assertEquals("model_call_limit", s.error)
    assertTrue(done.view.answer!!.contains("Scout: idea one"))
    assertEquals(3, h.llm.calls("juno"))
  }

  @Test fun modelFailureFallsBackOrFails() = runTest {
    val h = Harness(this)
    h.llm.on("juno") { _, _ -> throw LlmException(LlmException.Kind.SIGN_IN, "invalid_grant") }
    val runId = h.juno.startCoordinated("hi")
    val r = assertIs<DriveResult.Failed>(h.juno.drive(runId))
    assertEquals(LlmException.Kind.SIGN_IN, r.view!!.errorKind)
    assertEquals(Phase.FAILED, r.view!!.phase)
  }

  @Test fun askUserRoundTrip() = runTest {
    val h = Harness(this)
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(FakeLlm.call("ask_user", args("question" to "For how many people? I'd guess 4.")))
      else FakeLlm.tools(replyCall("Got it, " + json(FakeLlm.lastToolOutput(req)!!).text("answer")))
    }
    val runId = h.juno.startCoordinated("dinner")
    val w = assertIs<DriveResult.WaitingOnUser>(h.juno.drive(runId))
    assertEquals(NeedsUserKind.ANSWER_QUESTION, w.view.needsUser.single().kind)
    h.juno.answer(runId, "6")
    assertEquals("Got it, 6", assertIs<DriveResult.Done>(h.juno.drive(runId)).view.answer)
  }

  @Test fun userInterjectionEndsWaitEarly() = runTest {
    val h = Harness(this)
    h.nova.behavior = { Behavior.Silent }
    var added: String? = null
    h.llm.on("juno") { req, n ->
      if (n == 0) FakeLlm.tools(askAgent("Nova", "Free Friday?"), waitCall())
      else { added = FakeLlm.lastToolOutput(req); FakeLlm.tools(replyCall("Okay, Saturday instead.")) }
    }
    val runId = h.juno.startCoordinated("Friday?")
    val d = async { h.juno.drive(runId) }
    advanceTimeBy(10_000); runCurrent()
    h.juno.interject(runId, "actually make it Saturday")
    assertIs<DriveResult.Done>(d.await())
    assertTrue(json(added!!).toString().contains("actually make it Saturday"))
    assertTrue(currentTime < 30_000)
  }

  @Test fun oneInFlightAskPerTextAgentAcrossRuns() = runTest {
    val h = Harness(this)
    h.nova.behavior = { Behavior.Reply(listOf(Part(10_000, "answer to: $it"))) }
    h.llm.on("juno") { req, n ->
      val q = FakeLlm.userTexts(req).last()
      if (FakeLlm.toolOutputs(req).isEmpty()) FakeLlm.tools(askAgent("Nova", q), waitCall()) else FakeLlm.tools(replyCall("done: $q"))
    }
    val a = h.juno.startCoordinated("first")
    val b = h.juno.startCoordinated("second")
    val da = async { h.juno.drive(a) }
    val db = async { h.juno.drive(b) }
    advanceTimeBy(1_000); runCurrent()
    assertEquals(1, h.nova.sent.size) // the second is queued behind the first
    assertEquals(AskStatus.QUEUED, h.juno.state(b)!!.asks.single().status)
    assertIs<DriveResult.Done>(da.await()); assertIs<DriveResult.Done>(db.await())
    assertEquals(2, h.nova.sent.size)
  }

  @Test fun noninlineDriveSuspendsWithWakeTime() = runTest {
    val h = Harness(this)
    h.nova.behavior = { Behavior.Silent }
    h.llm.on("juno") { _, _ -> FakeLlm.tools(askAgent("Nova", "x?"), waitCall(max = 90)) }
    val runId = h.juno.startCoordinated("x")
    val s = assertIs<DriveResult.Suspended>(h.juno.drive(runId, inline = false))
    assertEquals(60_000L, s.wakeAt) // capped at maxSleepMs; the WorkManager job re-drives then
    assertEquals(StripPhase.LIVE, s.view.strip!!.phase)
    assertEquals("Asking Nova", s.view.strip!!.labelText)
  }
}
