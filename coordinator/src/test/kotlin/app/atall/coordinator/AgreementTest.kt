package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AgreementTest {
  private fun c(topic: String, value: String, ev: String = "") = Claim(topic, value, "$topic: $value", ev)

  @Test fun clustersAgreeDisputedUnique() {
    val out = Agreement.cluster(listOf(
      AgentClaims("juno", listOf(c("best day to hike", "Sunday", "forecast"), c("rain on sunday", "no"), c("high temperature", "65F"))),
      AgentClaims("scout", listOf(c("Best day to hike", "sunday"), c("rain on Sunday?", "No"), c("high temperature", "72F"))),
      AgentClaims("nova", listOf(c("best day for hike", "Saturday", "your calendar is free"), c("trail", "Mission Peak"))),
    ))
    assertEquals(listOf("rain on sunday"), out.agreed.map { it.topic })
    assertEquals(listOf("juno", "scout"), out.agreed.single().agentIds)
    val day = out.disputed.first { it.topic == "best day to hike" }
    assertEquals(listOf("Sunday", "Saturday"), day.sides.map { it.value })
    assertEquals(0, day.majority)
    assertEquals(mapOf("nova" to "your calendar is free"), day.sides[1].evidence)
    val temp = out.disputed.first { it.topic == "high temperature" }
    assertNull(temp.majority) // 1 vs 1: split; different numbers never merge
    assertEquals(listOf("trail"), out.unique.map { it.topic })
    assertEquals(VerdictLevel.SPLIT, Agreement.level(3, out))
  }

  @Test fun levels() {
    val unanimous = Agreement.cluster(listOf(AgentClaims("a", listOf(c("x", "1"))), AgentClaims("b", listOf(c("x", "1")))))
    assertEquals(VerdictLevel.UNANIMOUS, Agreement.level(2, unanimous))
    assertEquals(VerdictLevel.TOO_FEW, Agreement.level(1, unanimous))
    assertEquals(VerdictLevel.UNCLEAR, Agreement.level(2, null))
  }

  @Test fun parsesClaimsLeniently() {
    val raw = "```json\n{\"answers\":[{\"label\":\"a\",\"claims\":[{\"topic\":\"t\",\"value\":\"v\",\"statement\":\"\",\"evidence\":\"\"}]},{\"label\":\"Z\",\"claims\":[]}]}\n```"
    val parsed = Agreement.parseClaims(raw, mapOf("A" to "juno", "B" to "scout"))!!
    assertEquals(listOf("juno"), parsed.map { it.agentId })
    assertEquals("t: v", parsed.single().claims.single().statement)
    assertNull(Agreement.parseClaims("sorry, I can't", mapOf("A" to "juno")))
  }

  private fun claimsJson(vararg answers: Pair<String, List<List<String>>>) = buildString {
    append("{\"answers\":[")
    append(answers.joinToString(",") { (label, cs) ->
      "{\"label\":\"$label\",\"claims\":[" + cs.joinToString(",") { (t, v, e) -> "{\"topic\":\"$t\",\"value\":\"$v\",\"statement\":\"$t is $v\",\"evidence\":\"$e\"}" } + "]}"
    })
    append("]}")
  }

  /** @all end to end: blind fan-out, partial map while collecting, claims, verdict, then one targeted challenge. */
  @Test fun allFanOutAgreementMapAndChallenge() = runTest {
    val h = Harness(this)
    val q = "Which day this weekend is best for a hike near San Jose?"
    h.scout.behavior = { t -> if (t.startsWith("Quick check")) Behavior.Reply(listOf(Part(3_000, "I'll stick with Sunday."))) else Behavior.Reply(listOf(Part(10_000, "Sunday: no rain."))) }
    h.nova.behavior = { t -> if (t.startsWith("Quick check")) Behavior.Reply(listOf(Part(4_000, "Fair, Sunday is better given the rain."))) else Behavior.Reply(listOf(Part(40_000, "Saturday, your calendar is free. Try Mission Peak."))) }
    h.llm.on("blind") { req, n -> if (n == 0) FakeLlm.text("Sunday. No rain expected Sunday.") else FakeLlm.text("Sunday, still.") }
    var claimsInput = ""
    h.llm.on("claims") { req, _ ->
      claimsInput = FakeLlm.userTexts(req).single()
      assertNotNull(req.jsonSchema)
      // A = Juno, B = Scout, C = Nova (ask order); names are hidden from the extractor.
      FakeLlm.text(claimsJson(
        "A" to listOf(listOf("best day", "Sunday", "forecast"), listOf("rain on sunday", "no", "")),
        "B" to listOf(listOf("best day", "Sunday", ""), listOf("rain on sunday", "no", "")),
        "C" to listOf(listOf("best day", "Saturday", "calendar free"), listOf("trail", "Mission Peak", "")),
      ))
    }
    h.llm.on("verdict") { req, n -> FakeLlm.text(if (n == 0) "Go Sunday: most agree, and it should stay dry. Nova prefers Saturday for your calendar." else "Go Sunday. Everyone agrees now.") }
    h.llm.on("challenge") { req, _ ->
      assertTrue(FakeLlm.userTexts(req).single().contains("Disputed topic: best day"))
      FakeLlm.text("{\"positions\":[{\"agent\":\"A\",\"keeps\":true,\"value\":\"Sunday\",\"reason\":\"forecast\"},{\"agent\":\"B\",\"keeps\":true,\"value\":\"Sunday\",\"reason\":\"\"},{\"agent\":\"C\",\"keeps\":false,\"value\":\"Sunday\",\"reason\":\"rain\"}]}")
    }

    val route = assertIs<Route.FanOut>(Router.route("@all $q", testRoster))
    val runId = (h.juno.submit(route) as Juno.Submitted.RunStarted).runId
    val d = async { h.juno.drive(runId) }
    advanceTimeBy(15_000); runCurrent()
    // Partial map: Juno and Scout are in, Nova still out. No claims yet (no plan usage spent on half a map).
    val partial = h.juno.view(runId)!!.agreement!!
    assertEquals(MapStatus.COLLECTING, partial.status)
    assertEquals(listOf(ParticipantState.ANSWERED, ParticipantState.ANSWERED, ParticipantState.WAITING), partial.participants.map { it.state })
    assertEquals(0, h.llm.calls("claims"))
    // Every agent got exactly the same question, blind.
    assertEquals(q, h.scout.sent.single().second); assertEquals(q, h.nova.sent.single().second)

    val done = assertIs<DriveResult.Done>(d.await())
    val map = done.view.agreement!!
    assertEquals(MapStatus.READY, map.status)
    assertTrue(!claimsInput.contains("Nova") && !claimsInput.contains("Scout"))
    assertEquals(listOf("rain on sunday"), map.agreed.map { it.topic })
    val dispute = map.disputed.single()
    assertEquals(listOf(listOf("juno", "scout"), listOf("nova")), dispute.sides.map { it.agentIds })
    assertEquals(0, dispute.majority)
    assertEquals("Mission Peak", map.unique.single().value)
    assertEquals(VerdictLevel.MAJORITY, map.verdict!!.level)
    assertEquals(map.verdict!!.line, done.view.answer)
    assertEquals(3, map.raw.size)
    assertEquals(StripPhase.DONE, done.view.strip!!.phase)

    // Challenge only the disputed claim, once.
    assertTrue(h.juno.challenge(runId, dispute.id))
    assertEquals(false, h.juno.challenge(runId, dispute.id))
    assertEquals(MapStatus.CHALLENGING, h.juno.view(runId)!!.agreement!!.status)
    val after = assertIs<DriveResult.Done>(h.juno.drive(runId)).view.agreement!!
    // Nova got only the contested claim plus the other side's evidence, nothing else from the thread.
    val hText = h.nova.sent.last().second
    assertTrue(hText.contains("\"best day is Sunday\"\nTheir reason: forecast") && hText.contains("You said: \"best day is Saturday\""), hText)
    assertTrue(h.scout.sent.last().second.contains("\"best day is Saturday\"\nTheir reason: calendar free"))
    assertTrue(after.disputed.isEmpty())
    assertEquals(setOf("rain on sunday", "best day"), after.agreed.map { it.topic }.toSet())
    assertEquals(listOf("juno", "scout", "nova"), after.agreed.first { it.topic == "best day" }.agentIds)
    assertEquals(VerdictLevel.UNANIMOUS, after.verdict!!.level)
    assertEquals(1, h.llm.calls("claims")) // the challenge didn't re-extract everything
  }

  @Test fun allTooFewAndUnclearAndLateRemerge() = runTest {
    val h = Harness(this, config = JunoConfig(includeMainInAll = false, allWaitMs = 60_000))
    h.scout.behavior = { Behavior.Reply(listOf(Part(5_000, "Yes, it opens at 9."))) }
    h.nova.behavior = { Behavior.Silent }
    h.llm.on("claims") { _, _ -> FakeLlm.text("not json at all") }
    val runId = h.juno.startAll("Does the DMV open at 9 on Saturday?")
    val done = assertIs<DriveResult.Done>(h.juno.drive(runId))
    assertEquals(VerdictLevel.TOO_FEW, done.view.agreement!!.verdict!!.level)
    assertEquals(listOf(ParticipantState.ANSWERED, ParticipantState.WAITING), done.view.agreement!!.participants.map { it.state })
    assertEquals(0, h.llm.calls("claims"))

    // Nova answers late: the map re-merges by itself; claims can't be parsed -> UNCLEAR, raw answers kept.
    h.nova.replyNow(h.juno.state(runId)!!.asks.first { it.agentId == "nova" }.id, "No, Saturdays it's 10.", late = true)
    advanceTimeBy(21_000); h.juno.sweep()
    val m = h.juno.view(runId)!!.agreement!!
    assertEquals(VerdictLevel.UNCLEAR, m.verdict!!.level)
    assertEquals("I couldn't line these answers up point by point. Read them below.", m.verdict!!.line)
    assertEquals(2, m.raw.size)
    assertTrue(m.raw.first { it.agentId == "nova" }.late)
    assertEquals(1, h.llm.calls("claims"))
  }

  @Test fun agreementMapSerializesForPersistence() {
    val m = AgreementMap("q", MapStatus.READY, listOf(Participant("scout", "Scout", ParticipantState.ANSWERED, 1200)),
      disputed = listOf(Dispute("d0", "t", listOf(DisputeSide("a", "t is a", listOf("scout"))), null)), verdict = Verdict(VerdictLevel.SPLIT, "split", 0, 1, 0))
    val s = RunState("r1", RunMode.ALL, "q", createdAt = 0, phase = Phase.DONE, agreement = m)
    assertEquals(s, RunCodec.decode(RunCodec.encode(s)))
  }
}
