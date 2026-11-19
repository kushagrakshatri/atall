package app.atall.coordinator.replay

import app.atall.coordinator.AskPurpose
import app.atall.coordinator.AskState
import app.atall.coordinator.AskStatus
import app.atall.coordinator.FileRunStore
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.Phase
import app.atall.coordinator.ReplyPart
import app.atall.coordinator.RunMode
import app.atall.coordinator.RunState
import app.atall.coordinator.testing.FakeLlm
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReplayTest {
  private fun run(id: String, q: String, vararg answers: Pair<String, String>, mode: RunMode = RunMode.ALL) = RunState(
    id, mode, q, createdAt = 0, phase = Phase.DONE, answer = "recorded merge",
    asks = answers.mapIndexed { i, (agent, text) ->
      AskState("$id~a$i", agent, q, AskPurpose.BLIND, status = AskStatus.ANSWERED, createdAt = 0, sentAt = 0,
        replies = listOf(ReplyPart(if (agent == "main") "main-$id~a$i" else "$agent-m$i", text, 1_000L * (i + 1))))
    })

  private fun corpus(): File {
    val dir = Files.createTempDirectory("replay").toFile()
    kotlinx.coroutines.runBlocking {
      val store = FileRunStore(dir)
      store.save(run("r1", "Which day for a hike?", "main" to "**Sunday.**", "grok" to "Sunday. Dry.\nAngle: weather",
        "hark" to "Saturday: you're busy Sunday.\nFrom memory: Kush is busy Sunday afternoon"))
      store.save(run("r2", "Lunch spot?", "main" to "Tacos.", "grok" to "Tacos."))                                      // one agent: skipped
      store.save(run("r3", "Talk?", "main" to "x", "grok" to "a", "hark" to "b", mode = RunMode.COORDINATE))       // not @all: skipped
    }
    return dir
  }

  private fun model(): FakeLlm = FakeLlm()
    .on("claims") { _, _ -> FakeLlm.text("""{"decision":"day","answers":[{"label":"A","pick":"Sunday","pick_kind":"day","asks_user":[],"claims":[{"topic":"best day","value":"Sunday","statement":"Sunday is best","evidence":""}]},{"label":"B","pick":"Sunday","pick_kind":"day","asks_user":[],"claims":[{"topic":"best day","value":"Sunday","statement":"Sunday is best","evidence":""}]},{"label":"C","pick":"Saturday","pick_kind":"day","asks_user":[],"claims":[{"topic":"best day","value":"Saturday","statement":"Saturday is best","evidence":""}]}]}""") }
    .on("fold") { _, _ -> FakeLlm.text("""{"kind":"change","answer":"**Saturday.** Only Hark knew you're busy Sunday afternoon.","caption":"Hark knew you're busy"}""") }
    .on("cross_check") { _, _ -> FakeLlm.text("""{"conflicts":[{"answer":"A","fact":"F1","problem":"busy"}]}""") }
    .on("crux") { _, _ -> FakeLlm.text("""{"kind":"question","question":"Is Sunday afternoon free?","options":["Yes","No"],"rules":[]}""") }

  @Test fun replaysSavedRunsWithJunosModelOnlyAndNeverReachesAnAgent() = runTest {
    val llm = model().on("judge") { req, _ ->
      val t = FakeLlm.userTexts(req).single()
      // Names are hidden from the judge.
      assertFalse(Regex("\\b(Hark|Grok)\\b").containsMatchIn(t), t)
      // Prefers whichever answer mentions being busy, in either position.
      val a1 = t.substringAfter("Answer 1:").substringBefore("Answer 2:")
      FakeLlm.text(if (a1.contains("busy")) """{"better":"1"}""" else """{"better":"2"}""")
    }
    val report = Replay(llm, names = mapOf("grok" to "Grok", "hark" to "Hark")).run(corpus())
    assertEquals(mapOf("r2" to "fewer than two recorded agent answers", "r3" to "not an @all run"), report.skipped)
    val c = report.cases.single()
    assertEquals("**Saturday.** You're busy Sunday afternoon.", c.replayedAnswer)
    assertEquals("recorded merge", c.recordedAnswer)
    assertTrue(c.conflict && c.yourCall)
    assertEquals("hark", c.bestSingleAgent)
    // Juno's model is the only thing called: claims, fold(s), cross-check, crux, judge. No peer exists to send to.
    assertEquals(setOf("claims", "fold", "cross_check", "crux", "judge"), llm.requests.map { it.purpose }.toSet())
    // Both mention "busy": a tie in both orders, never a win by position.
    assertEquals(app.atall.coordinator.replay.Preference.TIE, c.vsBest!!.outcome)
    assertEquals("juno-model", report.judge)
    assertTrue(report.line().startsWith("runs 1 (skipped 2)"), report.line())
  }

  @Test fun positionBiasCancelsOut() = runTest {
    val alwaysFirst = object : Judge { override val name = "biased"; override suspend fun prefer(question: String, first: String, second: String) = Preference.FIRST }
    assertEquals(Preference.TIE, PairJudging.judge(alwaysFirst, "q", "a", "b").outcome)
    val prefersLonger = object : Judge { override val name = "l"; override suspend fun prefer(question: String, first: String, second: String) = if (first.length >= second.length) Preference.FIRST else Preference.SECOND }
    assertEquals(Preference.SECOND, PairJudging.judge(prefersLonger, "q", "short", "much longer answer").outcome)
    assertEquals("an assistant says hi; an assistant agrees", PairJudging.anonymise("Hark says hi; @Grok's agrees", listOf("Hark", "Grok")))
  }

  @Test fun lengthControlDiscountsLongerAnswersWinning() {
    // A wins only when it is much longer: at equal length it would not win most of the time.
    val res = List(8) { PairResult(Preference.FIRST, 200, 50) } + List(8) { PairResult(Preference.SECOND, 50, 200) } +
      List(2) { PairResult(Preference.FIRST, 60, 60) } + List(2) { PairResult(Preference.SECOND, 60, 60) }
    val r = WinRate.of(res)
    assertEquals(0.5, r.rate!!, 1e-9)
    assertEquals(0.5, r.lengthControlled!!, 0.05)
    val biased = List(10) { PairResult(Preference.FIRST, 300, 50) } + List(2) { PairResult(Preference.SECOND, 50, 60) }
    assertTrue(WinRate.of(biased).rate!! > WinRate.of(biased).lengthControlled!!, "${WinRate.of(biased)}")
    assertNull(WinRate.of(res.take(3)).lengthControlled)
  }

  @Test fun outsideJudgeIsTheDefaultOnlyWhenConfigured() = runTest {
    assertIs<ModelJudge>(Judges.default(FakeLlm(), emptyMap()))
    assertIs<ModelJudge>(Judges.default(FakeLlm(), mapOf("ATALL_JUDGE_KEY" to "k")))
    val env = mapOf("ATALL_JUDGE_ENDPOINT" to "https://judge.example/v1/messages", "ATALL_JUDGE_KEY" to "test-key", "ATALL_JUDGE_MODEL" to "grader-1")
    assertIs<OutsideJudge>(Judges.default(FakeLlm(), env))
    var seen: Triple<String, Map<String, String>, String>? = null
    val j = OutsideJudge("https://judge.example/v1/messages", "test-key", "grader-1") { url, headers, body -> seen = Triple(url, headers, body); """{"content":[{"type":"text","text":"2"}]}""" }
    assertEquals(Preference.SECOND, j.prefer("q", "Answer one", "Answer two"))
    val (url, headers, body) = assertNotNull(seen)
    assertEquals("https://judge.example/v1/messages", url)
    assertEquals("test-key", headers["x-api-key"])
    assertTrue(body.contains("\"model\":\"grader-1\"") && body.contains("Answer 1:\\nAnswer one"), body)
    // A failure is a tie, never a win.
    assertEquals(Preference.TIE, OutsideJudge("u", "k", "m") { _, _, _ -> error("down") }.prefer("q", "a", "b"))
  }

  /**
   * Opt-in check of a real export: ATALL_REPLAY_DIR=/path/to/runs ./gradlew :coordinator:test --tests '*ReplayTest*'.
   * Lists which runs are replayable; scoring a real corpus needs Juno's real model wired in (not done in CI).
   */
  @Test fun listReplayableRunsInARealExportWhenAsked() {
    val dir = System.getenv("ATALL_REPLAY_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
    val replay = Replay(FakeLlm())
    val runs = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { runCatching { app.atall.coordinator.RunCodec.decode(it.readText()) }.getOrNull() }
    println("replayable ${runs.count { replay.replayable(it) == null }} of ${runs.size}")
  }
}
