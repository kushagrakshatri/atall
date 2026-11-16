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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** "Juno as editor": first take, edits in place, claims table, cross-check + repair, Your call, reviews, surplus. */
class EditorTest {

  // ------------------------------------------------------------------ 1. the ask tail

  @Test fun tailParsesLenientlyAndNeverShowsRaw() {
    val r = AskTail.parse("""
      Saturday at Mission Peak. Get there by 9.

      **Angle:** your calendar
      **From memory:**
      - Kush is busy Sunday afternoon (his calendar)
      - Kush doesn't drive at night
      Can't see: the trail conditions
      Next - hold Saturday morning
    """.trimIndent())
    assertEquals("Saturday at Mission Peak. Get there by 9.", r.body)
    assertEquals("your calendar", r.tail.angle)
    assertEquals(listOf("Kush is busy Sunday afternoon (his calendar)", "Kush doesn't drive at night"), r.tail.facts)
    assertEquals("the trail conditions", r.tail.cantSee)
    assertEquals("hold Saturday morning", r.tail.next)

    // One line of facts, semicolons, "none" values, different wording and case.
    val r2 = AskTail.parse("Rooh at 7.\nMY ANGLE: price\nFrom my memory: vegetarian guest; budget ${'$'}80; likes quiet; 4th fact\nCannot see: none\nNext step: n/a")
    assertEquals("Rooh at 7.", r2.body)
    assertEquals(3, r2.tail.facts.size)
    assertNull(r2.tail.cantSee); assertNull(r2.tail.next)

    // No tail at all, or a label in the middle of an answer: all body.
    assertEquals(Tail(), AskTail.parse("Sunday. No rain.").tail)
    val mid = "Next: Sunday works best.\nIt stays dry all day and the trails are open, so I'd go early before it gets crowded at the top."
    assertEquals(mid, AskTail.parse(mid).body)
    // A reply that is only a tail still shows plain words, never the labels.
    val only = AskTail.parse("Angle: weather\nFrom memory: nothing\nNext: check the forecast")
    assertFalse(only.body.contains("Angle:")); assertTrue(only.body.contains("weather"))
  }

  @Test fun tailTextIsShortAndPassesTheSendGate() {
    val t = AskTail.text("Kush")
    assertTrue(t.length < 400, "${t.length}")
    assertTrue(t.contains("about Kush"))
    assertFalse(SendGate.check(listOf(Segment(t, Provenance.JUNO_REWRITE))).gated)
    assertFalse(SendGate.check(listOf(Segment(t, Provenance.PEER_RELAY))).gated)
    assertTrue(AskTail.text("").contains("the user"))
  }

  // ------------------------------------------------------------------ 2. claims table and the combine step

  @Test fun onlyBlindAgreementConfirms() {
    val map = AgreementMap("q", MapStatus.READY, emptyList(),
      agreed = listOf(ClaimCluster("g0", "best day", "Saturday is best", "saturday", listOf("scout", "nova"))),
      unique = listOf(ClaimCluster("u1", "trail", "Mission Peak", "mission peak", listOf("nova"))))
    val t = ClaimsTables.build(map, listOf(
      SourcedTail("hark", Tail(facts = listOf("Kush is busy Sunday afternoon")), blind = true),
      // Grok repeats it after a cross-check: a source, but not an independent one.
      SourcedTail("grok", Tail(facts = listOf("Kush is busy on Sunday afternoon")), blind = false),
      SourcedTail("nova", Tail(facts = listOf("Kush doesn't eat pork")), blind = true),
    ))
    val busy = t.rows.single { it.text.startsWith("Kush is busy Sunday") }
    assertEquals(listOf("hark", "grok"), busy.sources)
    assertFalse(busy.confirmed)
    assertTrue(t.rows.single { it.text == "Saturday is best" }.confirmed)
    assertEquals(listOf("Kush doesn't eat pork", "Mission Peak"), t.onlyOne("juno").map { it.text })
    assertTrue(t.onlyAgrees("scout")); assertFalse(t.onlyAgrees("nova"))

    val names = mapOf("hark" to "Hark", "grok" to "Grok", "nova" to "Nova", "scout" to "Scout", "juno" to "Juno")
    val lines = t.lines({ names.getValue(it) }, "juno")
    assertTrue(lines.contains("- Only Nova (from its memory of the user): Kush doesn't eat pork"), lines)
    assertTrue(lines.contains("Hark and Grok, not independent"), lines)
    assertTrue(lines.contains("Scout and Nova, each on its own: confirmed"), lines)

    // Without the model: lead with what only one agent knew, then the split, then one line of overlap.
    val fb = Combine.fallback(t, { names.getValue(it) }, "juno", null)
    assertTrue(fb.startsWith("Only Nova knew: Kush doesn't eat pork; Mission Peak."), fb)
    assertTrue(fb.lines().last().startsWith("They agree: Saturday is best"), fb)
  }

  @Test fun repairGoesToAFastAgentByMeasuredLatency() {
    val m = mapOf("grok" to 36_000L, "hark" to 60_000L, "duet" to 3_600_000L, "muse" to null)
    assertEquals("grok", Repair.pick("grok", listOf("grok", "hark", "duet", "muse"), m, 150_000))
    // The conflicting agent is slow: the fastest measured one gets it. Unmeasured agents are never assumed fast.
    assertEquals("grok", Repair.pick("duet", listOf("grok", "hark", "duet", "muse"), m, 150_000))
    assertEquals("hark", Repair.pick("duet", listOf("hark", "duet", "muse"), m, 150_000))
    assertNull(Repair.pick("duet", listOf("duet", "muse"), m, 150_000))
  }

  // ------------------------------------------------------------------ the whole run

  private fun claimsFor(req: LlmRequest): String {
    val input = FakeLlm.userTexts(req).single()
    val labels = Regex("(?m)^Answer ([A-Z]):\\n(.*)$").findAll(input).map { it.groupValues[1] to it.groupValues[2] }.toList()
    val answers = labels.joinToString(",") { (l, text) ->
      val day = if (text.contains("Saturday")) "Saturday" else "Sunday"
      val extra = if (text.contains("Mission Peak")) ",{\"topic\":\"trail\",\"value\":\"Mission Peak\",\"statement\":\"Mission Peak is the trail\",\"evidence\":\"\"}" else ""
      """{"label":"$l","pick":"$day","pick_kind":"day","asks_user":[],"claims":[{"topic":"best day","value":"$day","statement":"$day is best","evidence":""}$extra]}"""
    }
    return """{"decision":"which day to hike","answers":[$answers]}"""
  }

  private fun fold(kind: String, answer: String, caption: String) = FakeLlm.text("""{"kind":"$kind","answer":"$answer","caption":"$caption"}""")

  private class Rig(val scope: TestScope, config: JunoConfig = JunoConfig(editor = true, allWaitMs = 180_000), sensitiveNova: Boolean = false) {
    val clock: () -> Long = { scope.testScheduler.currentTime }
    val llm = FakeLlm()
    val scores = InMemoryScoreLog()
    val roster = Roster(listOf(
      AgentProfile("juno", "Juno", AgentKind.MAIN),
      AgentProfile("scout", "Scout", AgentKind.TEXT),
      AgentProfile("nova", "Nova", AgentKind.TEXT, sensitive = sensitiveNova),
      AgentProfile("kdot", "kDot", AgentKind.TEXT, paused = true),
    ))
    val scout = FakePeer("scout", scope.backgroundScope, clock)
    val nova = FakePeer("nova", scope.backgroundScope, clock)
    private var n = 0
    val juno = Juno(roster, mapOf("scout" to scout, "nova" to nova), llm, InMemoryRunStore(), scores, clock, config, newId = { "${++n}" })
    val updates = mutableListOf<RunUpdate>()
    init {
      scout.sink = juno::ingest; nova.sink = juno::ingest
      scope.backgroundScope.launch { juno.updates.collect { updates += it } }
    }
  }

  @Test fun firstTakeThenEditsInPlaceThenCrossCheckRepairAndYourCall() = runTest {
    val r = Rig(this)
    r.scout.behavior = { t ->
      if (t.startsWith("Quick check on:")) Behavior.Reply(listOf(Part(5_000, "Fair: Saturday morning then.\nAngle: weather\nFrom memory: Kush is busy Sunday afternoon")))
      else Behavior.Reply(listOf(Part(10_000, "Sunday. It stays dry.\nAngle: the weather\nFrom memory: none\nCan't see: your calendar\nNext: check trail closures")))
    }
    r.nova.behavior = { Behavior.Reply(listOf(Part(40_000, "Saturday at Mission Peak.\nAngle: your calendar\nFrom memory: Kush is busy Sunday afternoon (calendar); Kush doesn't drive at night\nCan't see: the weather\nNext: hold Saturday morning"))) }
    r.llm.on("blind") { req, _ ->
      assertTrue(req.instructions.contains("first take"))
      FakeLlm.text("**Sunday.** Dry all day.")
    }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    var foldInputs = mutableListOf<String>()
    r.llm.on("fold") { req, n ->
      foldInputs += FakeLlm.userTexts(req).single()
      assertNotNull(req.jsonSchema)
      when (n) {
        0 -> fold("change", "Changed: **Saturday at Mission Peak.** Only Nova knew you're busy Sunday afternoon.", "Nova knew you're busy Sunday")
        1 -> fold("fix", "**Saturday morning at Mission Peak.** Only Nova knew you're busy Sunday afternoon, so Scout's Sunday doesn't fit.", "fixed Scout's day for Nova's note")
        else -> fold("add", "**Saturday morning at Mission Peak.** Only Nova knew you're busy Sunday afternoon; Scout agrees after a check.", "Scout checked it")
      }
    }
    var crossInput = ""
    r.llm.on("cross_check") { req, _ ->
      crossInput = FakeLlm.userTexts(req).single()
      FakeLlm.text("""{"conflicts":[{"answer":"A","fact":"F1","problem":"Sunday afternoon is busy"},{"answer":"B","fact":"F1","problem":"own fact"}]}""")
    }
    r.llm.on("crux") { _, _ -> FakeLlm.text("""{"kind":"conditional","question":"","options":[],"rules":[{"if":"Sunday afternoon frees up","then":"Sunday hike"},{"if":"you're busy Sunday","then":"Saturday at Mission Peak"}]}""") }

    val runId = r.juno.startAll("Which day this weekend for a hike?")
    val d = async { r.juno.drive(runId) }
    advanceTimeBy(1_000); runCurrent()
    // ~5 s: Juno's first take is the answer, labelled; paused kDot is never asked or shown.
    var v = r.juno.view(runId)!!
    assertEquals("**Sunday.** Dry all day.", v.answer)
    assertEquals(Combine.FIRST_TAKE, v.caption)
    assertEquals(listOf("scout", "nova"), v.presence!!.agents.map { it.agentId })
    assertTrue(v.presence!!.agents.all { it.state == PresenceState.WORKING })

    // Scout lands and only agrees: a check on its avatar, no edit, no editor call.
    advanceTimeBy(35_000); runCurrent()
    v = r.juno.view(runId)!!
    assertEquals("**Sunday.** Dry all day.", v.answer)
    assertEquals(0, r.llm.calls("fold"))
    assertEquals(PresenceState.AGREED, v.presence!!.agents.first { it.agentId == "scout" }.state)

    val done = assertIs<DriveResult.Done>(d.await())
    val st = r.juno.state(runId)!!
    val e = st.editor!!
    // Nova changed it: the answer leads with what only Nova knew; one caption says what changed.
    assertTrue(foldInputs[0].contains("Only Nova (from its memory of the user): Kush is busy Sunday afternoon (calendar)"), foldInputs[0])
    assertTrue(foldInputs[0].contains("They disagree:"), foldInputs[0])
    assertFalse(foldInputs[0].contains("Angle:"), "the tail goes in structured, never raw")
    // Cross-check: Scout's Sunday vs Nova's fact. Its own fact never counts against Nova.
    assertTrue(crossInput.contains("F1 (from the author of answer B): Kush is busy Sunday afternoon (calendar)"), crossInput)
    assertEquals(Conflict("scout", "nova", "Kush is busy Sunday afternoon (calendar)", "Sunday afternoon is busy"), e.conflict)
    assertTrue(foldInputs[1].contains("Cross-check: Scout's answer conflicts with something Nova knows"), foldInputs[1])
    assertTrue(done.view.answer!!.startsWith("**Saturday morning at Mission Peak.** Only Nova knew"))
    assertTrue(done.view.caption!!.startsWith("Updated · "))
    // One repair ask, to Scout (fast by measured reply time), carrying only the fact; no share tap by default.
    val repair = st.asks.single { it.purpose == AskPurpose.REPAIR }
    assertEquals("scout", repair.agentId)
    assertTrue(repair.text.contains("Something another of the user's assistants knows: Kush is busy Sunday afternoon (calendar)"), repair.text)
    assertTrue(repair.shareFrom.isEmpty())
    // A real split became Your call (no automatic talk).
    assertEquals(CallKind.CONDITIONAL, e.yourCall!!.kind)
    assertEquals("If Sunday afternoon frees up: Sunday hike\nIf you're busy Sunday: Saturday at Mission Peak", e.yourCall!!.text)
    assertNull(st.room)

    // The repair lands after the run settled: folded in place (not frozen), caption updated.
    advanceTimeBy(30_000); runCurrent(); r.juno.sweep()
    val after = r.juno.state(runId)!!
    assertTrue(after.answer!!.contains("Scout agrees after a check"))
    assertEquals("Updated · Scout checked it", after.editor!!.caption)
    assertEquals(listOf(FoldKind.FIRST, FoldKind.CHANGE, FoldKind.FIX, FoldKind.ADD), after.editor!!.versions.map { it.kind })
    // Surplus: the parts that needed two or more agents.
    assertEquals(setOf(SurplusKind.CROSS_CHECK, SurplusKind.REPAIR, SurplusKind.SPLIT_CARD), after.editor!!.surplus.map { it.kind }.toSet())
    assertTrue(r.scores.events.filterIsInstance<ScoreEvent.Surplus>().last().parts.any { it.kind == SurplusKind.REPAIR })

    // One text-free summary per run.
    val sums = r.updates.filterIsInstance<RunUpdate.Summary>()
    assertEquals(1, sums.size)
    val extras = sums.single().summary.extras()
    assertTrue(extras.values.none { v2 -> listOf("Sunday", "Saturday", "Mission", "busy", "hike").any { v2.contains(it, ignoreCase = true) } }, extras.toString())
    assertEquals("scout,nova", extras["agents_answered"])
    assertEquals("conditional", extras["your_call"])
    assertEquals("true", extras["conflict"])
  }

  @Test fun frozenAnswerTurnsALateChangeIntoANewTurnAndSlowAgentsAreReviewers() = runTest {
    val r = Rig(this, JunoConfig(editor = true, allWaitMs = 60_000))
    // Nova is measured slow (two hours): asked in the background, never waited on.
    r.scores.append(ScoreEvent.FirstReply("nova", 0, "old~a1", 2 * 3_600_000L, Certainty.HIGH, false))
    r.scout.behavior = { Behavior.Reply(listOf(Part(5_000, "Sunday. Dry.\nAngle: weather"))) }
    r.nova.behavior = { Behavior.Silent }
    r.llm.on("blind") { _, _ -> FakeLlm.text("**Sunday.**") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    r.llm.on("fold") { _, _ -> fold("change", "Changed: **Saturday.** Nova knew Sunday is taken.", "Nova knew Sunday is taken") }
    r.llm.on("crux") { _, _ -> FakeLlm.text("""{"kind":"none","question":"","options":[],"rules":[]}""") }
    val runId = r.juno.startAll("Which day for a hike?")
    assertTrue(r.juno.state(runId)!!.asks.single { it.agentId == "nova" }.background)
    val done = assertIs<DriveResult.Done>(r.juno.drive(runId))
    // Settled on Scout alone, well before the 60 s wait: the slow agent shows a moon.
    assertEquals(PresenceState.LATER, done.view.presence!!.agents.single { it.agentId == "nova" }.state)
    assertEquals("**Sunday.**", done.view.answer)
    assertTrue(r.juno.freeze(runId))

    r.nova.replyNow(r.juno.state(runId)!!.asks.single { it.agentId == "nova" }.id, "Saturday: Sunday is taken.\nFrom memory: Kush has a family lunch Sunday", late = true)
    advanceTimeBy(21_000); r.juno.sweep()
    val st = r.juno.state(runId)!!
    // Never edited after Kush acted: the change is one short new turn.
    assertEquals("**Sunday.**", st.answer)
    assertEquals("**Nova's late reply changes this.** **Saturday.** Nova knew Sunday is taken.", st.followUps.single().text)
    assertEquals(FoldKind.CHANGE, st.editor!!.reviews.single().kind)
    assertTrue(st.editor!!.reviews.single().asTurn)
    assertEquals(PresenceState.IN, Views.presence(st, r.roster, 0)!!.agents.single { it.agentId == "nova" }.state)
  }

  @Test fun aReviewThatOnlyAgreesIsACheckWithNoEdit() = runTest {
    val r = Rig(this, JunoConfig(editor = true, allWaitMs = 60_000))
    r.scores.append(ScoreEvent.FirstReply("nova", 0, "old~a1", 2 * 3_600_000L, Certainty.HIGH, false))
    r.scout.behavior = { Behavior.Reply(listOf(Part(5_000, "Sunday."))) }
    r.nova.behavior = { Behavior.Silent }
    r.llm.on("blind") { _, _ -> FakeLlm.text("**Sunday.**") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    val runId = r.juno.startAll("Which day for a hike?")
    assertIs<DriveResult.Done>(r.juno.drive(runId))
    r.nova.replyNow(r.juno.state(runId)!!.asks.single { it.agentId == "nova" }.id, "Sunday, agreed.", late = true)
    advanceTimeBy(21_000); r.juno.sweep()
    val st = r.juno.state(runId)!!
    assertEquals(0, r.llm.calls("fold"))
    assertEquals("**Sunday.**", st.answer)
    assertTrue(st.followUps.isEmpty())
    assertEquals(listOf("scout", "nova"), st.editor!!.agreedOnly)
    assertEquals(FoldKind.NONE, st.editor!!.reviews.single().kind)
  }

  @Test fun aRepairCarryingAnAskBeforeSharingAgentsNoteWaitsForTheShareTap() = runTest {
    val r = Rig(this, sensitiveNova = true)
    r.scout.behavior = { t -> if (t.startsWith("Quick check on:")) Behavior.Reply(listOf(Part(1_000, "Saturday then."))) else Behavior.Reply(listOf(Part(1_000, "Sunday."))) }
    r.nova.behavior = { Behavior.Reply(listOf(Part(2_000, "Saturday.\nFrom memory: Kush is away Sunday"))) }
    r.llm.on("blind") { _, _ -> FakeLlm.text("**Sunday.**") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    r.llm.on("fold") { _, n -> fold("fix", "**Saturday.** Only Nova knew you're away Sunday.", "Nova knew you're away") }
    r.llm.on("cross_check") { _, _ -> FakeLlm.text("""{"conflicts":[{"answer":"A","fact":"F1","problem":"away Sunday"}]}""") }
    r.llm.on("crux") { _, _ -> FakeLlm.text("not json") }
    val runId = r.juno.startAll("Which day?")
    val done = assertIs<DriveResult.Done>(r.juno.drive(runId))
    // The model failed on the crux: the split still becomes one plain question.
    assertEquals("Which way: Sunday or Saturday?", done.view.yourCall!!.text)
    val repair = r.juno.state(runId)!!.asks.single { it.purpose == AskPurpose.REPAIR }
    assertEquals(AskStatus.AWAITING_APPROVAL, repair.status)
    assertEquals("Share a note from Nova with Scout?", done.view.needsUser.single().text)
    assertEquals(1, r.scout.sent.size)
    assertTrue(r.juno.approve(runId, repair.id, true))
    assertEquals(2, r.scout.sent.size)
  }

  @Test fun agentMentionsRelayWithNoTapWithinCaps() = runTest {
    val r = Rig(this, JunoConfig(editor = true, allWaitMs = 60_000, mentionRelaysPerAgent = 1))
    r.scout.behavior = { t -> if (t.contains("asked you:")) Behavior.Reply(listOf(Part(1_000, "Yes, 7:15 is open."))) else Behavior.Reply(listOf(Part(1_000, "Sunday."))) }
    r.nova.behavior = { Behavior.Reply(listOf(Part(1_000, "Saturday. @Scout can you check 7 pm tables?"))) }
    r.llm.on("blind") { _, _ -> FakeLlm.text("**Sunday.**") }
    r.llm.on("claims") { req, _ -> FakeLlm.text(claimsFor(req)) }
    r.llm.on("fold") { _, _ -> fold("add", "**Sunday.** Scout checked tables.", "Scout checked tables") }
    r.llm.on("crux") { _, _ -> FakeLlm.text("""{"kind":"none","question":"","options":[],"rules":[]}""") }
    val runId = r.juno.startAll("Which day?")
    assertIs<DriveResult.Done>(r.juno.drive(runId))
    val novaAsk = r.juno.state(runId)!!.asks.single { it.agentId == "nova" }
    assertTrue(r.juno.relayMention(novaAsk.id, "scout", "can you check 7 pm tables?"))
    assertTrue(r.scout.sent.last().second.startsWith("Nova, another of the user's assistants, asked you: \"can you check 7 pm tables?\""))
    // Caps: the same target again, Juno, a paused agent, itself, and action words never relay.
    assertFalse(r.juno.relayMention(novaAsk.id, "scout", "and 8 pm?"))
    assertFalse(r.juno.relayMention(novaAsk.id, "juno", "thoughts?"))
    assertFalse(r.juno.relayMention(novaAsk.id, "kdot", "thoughts?"))
    assertFalse(r.juno.relayMention(novaAsk.id, "nova", "thoughts?"))
    val r2 = Rig(this, JunoConfig(editor = true, allWaitMs = 60_000))
    r2.scout.behavior = { Behavior.Silent }; r2.nova.behavior = { Behavior.Silent }
    r2.llm.on("blind") { _, _ -> FakeLlm.text("x") }
    val run2 = r2.juno.startAll("q")
    assertFalse(r2.juno.relayMention("$run2~a2", "scout", "Book the 7:30 for us."))
    advanceTimeBy(30_000); runCurrent()
    val st = r.juno.state(runId)!!
    val m = st.asks.single { it.purpose == AskPurpose.MENTION }
    assertEquals(1, m.mentionDepth); assertEquals("nova", m.mentionFrom)
  }

  @Test fun feedbackIsLoggedTextFree() = runTest {
    val r = Rig(this)
    r.juno.signal("r9", "heart")
    r.juno.signal("r9", "one_better", "nova")
    val ev = r.scores.events.filterIsInstance<ScoreEvent.UserSignal>()
    assertEquals(listOf("heart" to null, "one_better" to "nova"), ev.map { it.kind to it.betterAgent })
    // And it round-trips through the score log's JSON.
    val line = CoordJson.encodeToString(ScoreEvent.serializer(), ev[1])
    assertEquals(ev[1], CoordJson.decodeFromString(ScoreEvent.serializer(), line))
  }

  @Test fun editorStateRoundTripsForPersistence() {
    val s = RunState("r1", RunMode.ALL, "q", createdAt = 0, phase = Phase.DONE, editor = EditorState(
      versions = listOf(AnswerVersion(1, "a", Combine.FIRST_TAKE, 1, FoldKind.FIRST)), table = ClaimsTable(listOf(ClaimRow("f0", RowKind.FACT, "x", listOf("a"), listOf("a")))),
      conflict = Conflict("a", "b", "f", "p"), yourCall = YourCall(CallKind.CONDITIONAL, rules = listOf(CallRule("x", "y"), CallRule("z", "w"))),
      reviews = listOf(Review("b", "r1~a2", FoldKind.ADD, 3)), surplus = listOf(SurplusPart(SurplusKind.REPAIR, listOf("a", "b")))))
    assertEquals(s, RunCodec.decode(RunCodec.encode(s)))
  }
}
