package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.FakePeer
import app.atall.coordinator.testing.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The answer card (design-system/mocks/answer-card): credits, one real dissent, actions, no system words, the name. */
class AnswerCardTest {

  private val ids = mapOf("grok" to "grok", "hark" to "hark", "muse" to "muse")
  private fun resolve(n: String) = ids[n.lowercase()]

  @Test fun creditMarkersBecomeTilesOnTheirLineAndNeverShowRaw() {
    val lines = Credits.parse("Book a Suburban or Expedition MAX. A minivan won't fit six people and their bags. {Grok}\n" +
      "You're 25, so the van age rule doesn't block you. {Grok, Hark}\n- Your card likely won't cover it. {Muse} {Juno}", ::resolve)
    assertEquals(3, lines.size)
    assertEquals("Book a Suburban or Expedition MAX. A minivan won't fit six people and their bags.", lines[0].text)
    assertEquals(listOf("grok"), lines[0].credits)
    assertEquals(listOf("grok", "hark"), lines[1].credits)
    // Juno (unknown to resolve) is dropped; the bullet goes.
    assertEquals(CardLine("Your card likely won't cover it.", listOf("muse")), lines[2])
    assertTrue(lines.none { it.text.contains("{") || it.text.contains("}") })
    // A marker mid-line after a sentence ends that line there; one before the period stays with its sentence.
    val split = Credits.parse("Pick the SUV. {Grok} The Pacifica is cheaper. {Hark}", ::resolve)
    assertEquals(listOf(CardLine("Pick the SUV.", listOf("grok")), CardLine("The Pacifica is cheaper.", listOf("hark"))), split)
    assertEquals(listOf(CardLine("Pick the SUV.", listOf("grok"))), Credits.parse("Pick the SUV {Grok}.", ::resolve))
    assertEquals("Pick the SUV.\nThe Pacifica is cheaper.", Credits.plain("Pick the SUV. {Grok} The Pacifica is cheaper. {Hark}"))
  }

  @Test fun noSystemOrRelayWordsReachTheAnswer() {
    val names = listOf("Grok", "Hark", "Muse")
    val cases = mapOf(
      "Changed: **Rooh at 7.** Only Grok knew you're vegetarian this month." to "**Rooh at 7.** You're vegetarian this month.",
      "**Changed:** Book the Suburban." to "Book the Suburban.",
      "Book the Suburban. Hark's run stalled in MERGING, so this is from Grok." to "Book the Suburban.",
      "**Duet's late reply changes this.** Saturday." to "Saturday.",
      "Hark knew you're 25, so the age rule doesn't block you. {Hark}" to "You're 25, so the age rule doesn't block you. {Hark}",
      "Only Grok knew: the minivan won't fit. {Grok}" to "The minivan won't fit. {Grok}",
    )
    for ((raw, want) in cases) {
      val got = AnswerWords.clean(raw, names)
      assertEquals(want, got, raw)
      assertFalse(AnswerWords.leaks(got), got)
    }
    // Ordinary words stay: "only" in a sentence, a fold-down seat, an update the user asked about.
    val plain = "Only six seats fold flat, so take the Suburban. The firmware update is out."
    assertEquals(plain, AnswerWords.clean(plain, names))
    // The fold's answer is cleaned on the way in; its actions never carry system words.
    val r = Fold.parse("""{"kind":"change","answer":"Changed: Book the SUV. Only Grok knew the van is too small. {Grok}","caption":"x","dissent_agent":"","dissent":"","actions":["Find SUV prices","Retry relay"]}""", "old")!!
    assertEquals("Book the SUV. The van is too small. {Grok}", r.answer)
    assertEquals(listOf("Find SUV prices"), r.actions)
    assertFalse(AnswerWords.leaks(r.answer))
  }

  @Test fun theEditorPromptAsksForCreditsAndForbidsTheOldWording() {
    val p = Prompts.render("juno_fold", mapOf("main" to "Juno", "userLine" to Prompts.userLine("Kush")))
    assertTrue(p.contains("{Grok}"), p)
    assertTrue(p.contains("dissent_agent"), p)
    assertFalse(p.contains("start with \"Changed:\""), p)
    assertFalse(Regex("Lead with what only one agent knew").containsMatchIn(p), p)
    assertTrue(p.contains("Call them Kush, never by a longer or more formal name"), p)
    for (name in listOf("juno_first", "juno_pick")) assertFalse(Prompts.raw(name).contains("in bold"), name)
  }

  @Test fun aDissentIsRealOnlyWhenTheComparisonHoldsASplitWithThatAgentOnASide() {
    val split = Dispute("d1", "vehicle", listOf(DisputeSide("SUV", "Skip the minivan.", listOf("grok")), DisputeSide("minivan", "The Pacifica is $707 all-in.", listOf("hark"))), null)
    assertTrue(Cards.real(DissentNote("hark", "the Pacifica is \$707 all-in"), listOf(split), "juno"))
    assertFalse(Cards.real(DissentNote("muse", "something"), listOf(split), "juno"), "not on a side")
    assertFalse(Cards.real(DissentNote("hark", "the Pacifica"), emptyList(), "juno"), "no split at all")
    assertFalse(Cards.real(DissentNote("juno", "mine"), listOf(split), "juno"), "never Juno")
    // Without the model: the smaller side's agent, in its own words, one line.
    assertEquals(DissentNote("hark", "The Pacifica is \$707 all-in"), Cards.fallbackDissent(listOf(split), "juno"))
    assertNull(Cards.fallbackDissent(emptyList(), "juno"))
  }

  // ------------------------------------------------------------------ whole runs

  private fun claimsFor(req: LlmRequest): String {
    val input = FakeLlm.userTexts(req).single()
    val labels = Regex("(?m)^Answer ([A-Z]):\\n(.*)$").findAll(input).map { it.groupValues[1] to it.groupValues[2] }.toList()
    val answers = labels.joinToString(",") { (l, text) ->
      val day = if (text.contains("Saturday")) "Saturday" else "Sunday"
      """{"label":"$l","pick":"$day","pick_kind":"day","asks_user":[],"claims":[{"topic":"best day","value":"$day","statement":"$day is best","evidence":""}]}"""
    }
    return """{"decision":"which day to hike","answers":[$answers]}"""
  }

  private class Rig(val scope: TestScope) {
    val clock: () -> Long = { scope.testScheduler.currentTime }
    val llm = FakeLlm()
    val roster = Roster(listOf(
      AgentProfile("juno", "Juno", AgentKind.MAIN),
      AgentProfile("scout", "Scout", AgentKind.TEXT),
      AgentProfile("nova", "Nova", AgentKind.TEXT),
    ))
    val scout = FakePeer("scout", scope.backgroundScope, clock)
    val nova = FakePeer("nova", scope.backgroundScope, clock)
    private var n = 0
    val juno = Juno(roster, mapOf("scout" to scout, "nova" to nova), llm, InMemoryRunStore(), InMemoryScoreLog(), clock,
      JunoConfig(editor = true, allWaitMs = 180_000), newId = { "${++n}" })
    init { scout.sink = juno::ingest; nova.sink = juno::ingest; scope.backgroundScope.launch { juno.updates.collect { } } }
  }

  @Test fun agreementGivesCreditsAndNoDissentEvenWhenTheModelInventsOne() = runTest {
    val r = Rig(this)
    r.scout.behavior = { Behavior.Reply(listOf(Part(2_000, "Sunday. It stays dry."))) }
    r.nova.behavior = { Behavior.Reply(listOf(Part(3_000, "Sunday, the trails reopen.\nFrom memory: Kush likes early starts"))) }
    r.llm.on("blind") { _, _ -> FakeLlm.text("Sunday.") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    r.llm.on("fold") { _, _ -> FakeLlm.text("""{"kind":"add","answer":"Hike Sunday morning. {Scout, Nova}\nYou like early starts, so go at 8. {Nova}","caption":"","dissent_agent":"Nova","dissent":"saturday is fine too","actions":["Hold Sunday 8am"]}""") }
    r.llm.on("cross_check") { _, _ -> FakeLlm.text("""{"conflicts":[]}""") }
    val runId = r.juno.startAll("Which day for a hike?")
    val done = assertIs<DriveResult.Done>(r.juno.drive(runId))
    val card = done.view.card!!
    assertEquals(listOf(CardLine("Hike Sunday morning.", listOf("scout", "nova")), CardLine("You like early starts, so go at 8.", listOf("nova"))), card.lines)
    assertNull(card.dissent, "they agree, so no disagreement line")
    assertNull(card.sheet)
    assertEquals(listOf("Hold Sunday 8am"), card.actions)
    assertFalse(card.thinking)
    assertNull(card.changedAt)
    assertEquals("Hike Sunday morning.\nYou like early starts, so go at 8.", card.plain)
  }

  @Test fun aRealSplitGivesOneDissentLineThatOpensYourCall() = runTest {
    val r = Rig(this)
    r.scout.behavior = { Behavior.Reply(listOf(Part(2_000, "Sunday. It stays dry."))) }
    r.nova.behavior = { Behavior.Reply(listOf(Part(3_000, "Saturday. Sunday you have lunch with family."))) }
    r.llm.on("blind") { _, _ -> FakeLlm.text("Sunday.") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    r.llm.on("fold") { _, _ -> FakeLlm.text("""{"kind":"add","answer":"Hike Sunday. It stays dry all day. {Scout}","caption":"","dissent_agent":"Nova","dissent":"Saturday, since Sunday has a family lunch.","actions":["Hold Sunday","Hold Saturday"]}""") }
    r.llm.on("cross_check") { _, _ -> FakeLlm.text("""{"conflicts":[]}""") }
    r.llm.on("crux") { _, _ -> FakeLlm.text("""{"kind":"question","question":"Is the family lunch on?","options":["Yes, Saturday","No, Sunday"],"rules":[]}""") }
    val runId = r.juno.startAll("Which day for a hike?")
    val done = assertIs<DriveResult.Done>(r.juno.drive(runId))
    val card = done.view.card!!
    assertEquals(DissentNote("nova", "Saturday, since Sunday has a family lunch"), card.dissent)
    assertEquals("Nova", card.dissentName)
    val sheet = assertNotNull(card.sheet)
    assertEquals("Is the family lunch on?", sheet.title)
    assertEquals("Scout and Nova see it differently.", sheet.subtitle)
    assertEquals(setOf("scout", "nova"), sheet.takes.map { it.agentId }.toSet())
    assertEquals(listOf(CallOption("Yes, Saturday"), CallOption("No, Sunday")), sheet.options)
    assertEquals(listOf("Hold Sunday", "Hold Saturday"), card.actions)
  }
}
