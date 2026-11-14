package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class StripAndScoreboardTest {
  private val cfg = JunoConfig()
  private val roster = Roster(testRoster.agents + listOf(AgentProfile("a3", "Ada", AgentKind.API), AgentProfile("a4", "Bo", AgentKind.API)))

  private fun ask(agent: String, status: AskStatus, sentAt: Long = 0, replyAt: Long? = null) = AskState(
    "r~a$agent", agent, "do $agent", status = status, createdAt = 0, sentAt = sentAt,
    replies = replyAt?.let { listOf(ReplyPart("m$agent", "reply from $agent", it)) }.orEmpty(), lastReplyAt = replyAt ?: 0,
  )

  private fun run(phase: Phase, vararg asks: AskState, answeredAt: Long? = null) =
    RunState("r", RunMode.COORDINATE, "u", createdAt = 0, phase = phase, asks = asks.toList(), answeredAt = answeredAt, answer = answeredAt?.let { "ans" })

  @Test fun liveLabels() {
    val one = Views.strip(run(Phase.WAITING, ask("nova", AskStatus.PENDING)), roster, 9_000, cfg)!!
    assertEquals(StripPhase.LIVE, one.phase); assertEquals("Asking Nova", one.labelText); assertEquals("0:09", one.timer)
    val two = Views.strip(run(Phase.WAITING, ask("nova", AskStatus.PENDING), ask("scout", AskStatus.PENDING)), roster, 5_000, cfg)!!
    assertEquals("Asking Nova and Scout", two.labelText)
    val half = Views.strip(run(Phase.WAITING, ask("nova", AskStatus.ANSWERED, replyAt = 4_000), ask("scout", AskStatus.PENDING)), roster, 50_000, cfg)!!
    assertEquals("Asked Nova · waiting on Scout", half.labelText)
    assertTrue(half.slow); assertEquals("Still waiting on Scout", half.slowHint); assertEquals("0:50", half.timer)
    assertEquals(listOf(LabelPart("Asked "), LabelPart("Nova", "nova"), LabelPart(" · waiting on "), LabelPart("Scout", "scout")), half.label)
  }

  @Test fun doneAndFailedLabels() {
    val done = Views.strip(run(Phase.DONE, ask("nova", AskStatus.ANSWERED, replyAt = 6_000), ask("scout", AskStatus.ANSWERED, replyAt = 9_000), answeredAt = 18_000), roster, 99_000, cfg)!!
    assertEquals(StripPhase.DONE, done.phase); assertEquals("Asked Nova and Scout", done.labelText); assertEquals("18s", done.timer)
    assertEquals("Juno asked Nova and Scout, done in 18 seconds. Double-tap to show details.", done.accessibility)
    assertEquals(listOf(6_000L, 9_000L), done.lines.map { it.durationMs })
    val many = Views.strip(run(Phase.DONE, ask("nova", AskStatus.ANSWERED, replyAt = 1), ask("scout", AskStatus.ANSWERED, replyAt = 1), ask("a3", AskStatus.ANSWERED, replyAt = 1), ask("a4", AskStatus.ANSWERED, replyAt = 1), answeredAt = 125_000), roster, 0, cfg)!!
    assertEquals("Asked Nova, Scout and 2 more", many.labelText); assertEquals("2m 5s", many.timer)
    val failed = Views.strip(run(Phase.DONE, ask("scout", AskStatus.TIMED_OUT), answeredAt = 1_000), roster, 0, cfg)!!
    assertEquals(StripPhase.FAILED, failed.phase); assertEquals("Scout didn't answer", failed.labelText)
    assertNull(Views.strip(run(Phase.DONE), roster, 0, cfg))
  }

  @Test fun previewTrims() {
    assertEquals("a\nb", Views.preview("a\n\nb\nc"))
    assertTrue(Views.preview("x".repeat(400)).endsWith("…"))
  }

  @Test fun scoreboardRollup() {
    val e = listOf(
      ScoreEvent.AskSent("nova", 0, "1", "sent"), ScoreEvent.FirstReply("nova", 10_000, "1", 10_000, Certainty.HIGH, false),
      ScoreEvent.AskSettled("nova", 30_000, "1", AskStatus.ANSWERED, true),
      ScoreEvent.AskSent("nova", 0, "2", "needs_tap"), ScoreEvent.NeededUser("nova", 0, "2", "tap_to_send"),
      ScoreEvent.FirstReply("nova", 0, "2", 30_000, Certainty.HIGH, false), ScoreEvent.AskSettled("nova", 0, "2", AskStatus.ANSWERED, false),
      ScoreEvent.AskSent("nova", 0, "3", "sent"), ScoreEvent.AskSettled("nova", 0, "3", AskStatus.TIMED_OUT, false),
      ScoreEvent.Unsolicited("scout", 5), ScoreEvent.DirectSent("scout", 6, "sent"),
    )
    val s = Scoreboard.compute(e, now = 100_000).associateBy { it.agentId }
    val h = s.getValue("nova")
    assertEquals(3, h.asks); assertEquals(2, h.answered); assertEquals(1, h.answeredInTime); assertEquals(1, h.timedOut)
    assertEquals(20_000L, h.medianReplyMs); assertEquals(1, h.neededTap)
    assertEquals(2.0 / 3, h.completionRate!!, 1e-9); assertEquals(1.0 / 3, h.tapRate!!, 1e-9)
    assertEquals(5L, s.getValue("scout").lastUnsolicitedAt); assertEquals(1, s.getValue("scout").directSends)
    // Serializes as JSON lines.
    val f = kotlin.io.path.createTempFile("scores", ".jsonl").toFile()
    val log = JsonLinesScoreLog(f)
    kotlinx.coroutines.runBlocking { e.forEach { log.append(it) }; assertEquals(e, log.since(0)) }
  }

  /** The scoreboard is fed from a real run from day 1. */
  @Test fun junoLogsScoreEvents() = runTest {
    val h = Harness(this)
    h.nova.behavior = { Behavior.Reply(listOf(Part(7_000, "Free after 3."))) }
    h.llm.on("juno") { _, n -> if (n == 0) FakeLlm.tools(askAgent("Nova", "Free Sunday?"), waitCall()) else FakeLlm.tools(replyCall("After 3.")) }
    val runId = h.juno.startCoordinated("Sunday?")
    val d = async { h.juno.drive(runId) }
    advanceTimeBy(3_000); runCurrent()
    assertEquals(StripPhase.LIVE, h.juno.view(runId)!!.strip!!.phase)
    d.await()
    val sc = h.juno.scoreboard().single { it.agentId == "nova" }
    assertEquals(1, sc.asks); assertEquals(1, sc.answered); assertEquals(1, sc.answeredInTime); assertEquals(7_000L, sc.medianReplyMs)
  }

  @Test fun fileStoreRoundTripsAndListsLiveRuns() = runTest {
    val dir = kotlin.io.path.createTempDirectory("runs").toFile()
    val st = FileRunStore(dir)
    val live = run(Phase.WAITING, ask("nova", AskStatus.PENDING)).copy(runId = "r1", transcript = listOf(Items.user("hi"), Items.functionCall("c1", "wait", "{}")))
    val done = run(Phase.DONE, ask("nova", AskStatus.ANSWERED, replyAt = 1)).copy(runId = "r2")
    st.save(live); st.save(done)
    assertEquals(live, FileRunStore(dir).load("r1"))
    assertEquals(listOf("r1"), FileRunStore(dir).live().map { it.runId })
  }
}
