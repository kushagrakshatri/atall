@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.Clock
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.InMemoryScoreLog
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.PresenceState
import app.atall.coordinator.PresenceView
import app.atall.coordinator.ScoreEvent
import app.atall.coordinator.SendGate
import app.atall.coordinator.testing.FakeLlm
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Juno as editor" in the thread: the collab UI (one avatar row, the living answer, Your call, ♥, summaries, @mentions). */
class CollabBridgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val T0 = 1_763_000_000_000L
    private var ids = 0

    private class Captured(val message: String, val runId: String?, val extras: Map<String, String>)

    private inner class Rig(val scope: TestScope, respond: Map<String, (String) -> List<String>>, withMuse: Boolean = false, measured: Boolean = true) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        val scout = registry.add("Scout", ChannelType.RCS, "+14085550102")
        val kdot = registry.add("kDot", ChannelType.RCS, "+14085550103").let { registry.update(it.copy(paused = true)) }
        val muse = if (withMuse) registry.add("Muse", ChannelType.RCS, "+14085550104") else null
        val agents: Map<String, FakeAgent> = listOfNotNull(nova, scout, kdot, muse).associate { p ->
            p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, respond[p.name] ?: { emptyList() })
        }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        // Texting agents are slow by prior until measured (v0.1.9); these rigs model agents already measured fast.
        val scores = InMemoryScoreLog().also { log -> kotlinx.coroutines.runBlocking {
            if (measured) listOf(nova, scout).forEach { log.append(ScoreEvent.FirstReply(it.id, T0, "old~a0", 20_000, app.atall.coordinator.Certainty.HIGH, false)) } }
        }
        val captured = mutableListOf<Captured>()
        val telemetry = object : Telemetry {
            override fun capture(message: String, level: Telemetry.Level, runId: String?, extras: Map<String, String>) { captured += Captured(message, runId, extras) }
        }
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, scores = scores, userName = { "Kush" }, clock = clock, telemetry = telemetry)
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) {} } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true })
        init {
            bridge.start()
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
        fun lines() = thread.all()
        fun sent(p: AgentProfile) = agents.getValue(p.id).sent.map { it.second }
        fun presence(runId: String) = lines().single { it.kind == MessageKind.PRESENCE && it.runId == runId }.let { Json.decodeFromString(PresenceView.serializer(), it.detail!!) }
    }

    private fun TestScope.settle(ms: Long = 5 * 60_000L) { advanceTimeBy(ms); runCurrent() }

    private fun claims(req: LlmRequest): String {
        val input = FakeLlm.userTexts(req).single()
        val labels = Regex("(?m)^Answer ([A-Z]):\\n(.*)$").findAll(input).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return "{\"decision\":\"where to eat\",\"answers\":[" + labels.joinToString(",") { (l, t) ->
            val v = if (t.contains("Rooh")) "Rooh" else "Sakoon"
            "{\"label\":\"$l\",\"pick\":\"$v\",\"pick_kind\":\"restaurant\",\"asks_user\":[],\"claims\":[{\"topic\":\"where to eat\",\"value\":\"$v\",\"statement\":\"$v is the pick\",\"evidence\":\"\"}]}"
        } + "]}"
    }

    private fun Rig.script() {
        llm.on("blind") { _, _ -> FakeLlm.text("**Sakoon at 7:30.** Quiet and bookable.") }
        llm.on("claims") { req, _ -> FakeLlm.text(claims(req)) }
        llm.on("fold") { _, _ -> FakeLlm.text("""{"kind":"change","answer":"Changed: **Rooh at 7.** Only Nova knew you're vegetarian this month.","caption":"Nova knew you're vegetarian"}""") }
        llm.on("cross_check") { _, _ -> FakeLlm.text("""{"conflicts":[]}""") }
        llm.on("crux") { _, _ -> FakeLlm.text("""{"kind":"question","question":"Quiet dinner or a lively one?","options":["Quiet","Lively"],"rules":[]}""") }
    }

    @Test fun atAllShowsAFirstTakeThenEditsItInPlaceUnderOneAvatarRow() = runTest {
        val r = Rig(this, mapOf(
            "Nova" to { _ -> listOf("Rooh at 7: it has the best vegetarian menu.\nAngle: your diet\nFrom memory: Kush is vegetarian this month (he told me)\nCan't see: tables\nNext: check 7 pm") },
            "Scout" to { _ -> listOf("Sakoon at 7:30.\nAngle: reviews\nFrom memory: none") },
        ))
        r.script()
        r.router.send("@all dinner Friday?")
        settle(1_000)
        val runId = r.lines().first().runId!!
        // Seconds in: Juno's first take, labelled, and one avatar row. Paused kDot is nowhere.
        val first = r.lines().single { it.id == "answer:$runId" }
        assertEquals("**Sakoon at 7:30.** Quiet and bookable.", first.text)
        assertEquals("First take · others adding", first.detail)
        assertEquals(listOf("Nova", "Scout"), r.presence(runId).agents.map { it.name })
        // Every ask ends with the tail; kDot (paused) got nothing.
        assertTrue(r.sent(r.nova).single().endsWith(app.atall.coordinator.AskTail.text("Kush")))
        assertTrue(r.sent(r.kdot).isEmpty())

        settle()
        val l = r.lines()
        // No ask cards, no strip, no reply lines, no map card, nothing raw: the answer and its avatar row.
        assertTrue(l.none { it.kind in setOf(MessageKind.SUBTASK, MessageKind.RUN, MessageKind.AGREEMENT) })
        assertTrue(l.none { it.author == r.nova.id || it.author == r.scout.id })
        assertTrue(l.none { it.text.contains("Angle:") || it.text.contains("From memory") })
        val answer = l.single { it.id == "answer:$runId" }
        assertEquals(first.at, answer.at) // edited in place
        assertEquals("Changed: **Rooh at 7.** Only Nova knew you're vegetarian this month.", answer.text)
        assertEquals("Updated · Nova knew you're vegetarian", answer.detail)
        // The split became Your call, not a talk.
        assertEquals("Quiet dinner or a lively one?", answer.call)
        assertEquals(listOf("Quiet", "Lively"), answer.choices)
        assertTrue(l.none { it.kind == MessageKind.TALK })
        val p = r.presence(runId)
        assertEquals(listOf(PresenceState.IN, PresenceState.IN), p.agents.map { it.state })
        assertEquals("Rooh at 7: it has the best vegetarian menu.", p.agents.first { it.name == "Nova" }.text)
        assertNotNull(p.map)
        // One text-free summary went to telemetry.
        val sum = r.captured.single { it.message == "Run summary" }
        assertEquals(runId, sum.runId)
        assertTrue(sum.extras.values.none { v -> listOf("Rooh", "Sakoon", "vegetarian", "dinner").any { v.contains(it) } })
    }

    @Test fun replyingFreezesTheAnswerAndFeedbackIsAGesture() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Rooh at 7.") }, "Scout" to { _ -> listOf("Sakoon at 7:30.") }))
        r.script()
        r.router.send("@all dinner Friday?")
        settle()
        val runId = r.lines().first().runId!!
        val id = "answer:$runId"
        // Double-tap: ♥ (the same chip agents' tapbacks use), logged; again: off.
        r.bridge.heart(id)
        assertEquals(listOf(ReactionChip(JunoBridge.HEART, ThreadMessage.USER)), r.thread.get(id)!!.reactions)
        r.bridge.project(runId)
        assertEquals(1, r.thread.get(id)!!.reactions.size) // survives re-projection
        r.bridge.heart(id)
        assertTrue(r.thread.get(id)!!.reactions.isEmpty())
        r.bridge.oneBetter(runId, r.nova.id)
        assertEquals(listOf("heart" to null, "heart_removed" to null, "one_better" to r.nova.id),
            r.scores.events.filterIsInstance<ScoreEvent.UserSignal>().map { it.kind to it.betterAgent })
        // Tapping a Your call choice freezes the answer.
        r.bridge.choiceTapped(runId, "Quiet")
        assertNotNull(r.bridge.juno.state(runId)!!.editor!!.frozenAt)
    }

    @Test fun anAgentsMentionReachesTheOtherAgentWithNoTap() = runTest {
        val r = Rig(this, mapOf(
            "Nova" to { t -> if (t.contains("asked you:")) emptyList() else listOf("Rooh at 7. @Scout can you check the 7 pm tables?") },
            "Scout" to { t -> if (t.contains("asked you:")) listOf("Yes, 7 pm is open at Rooh.") else listOf("Sakoon at 7:30.") },
        ))
        r.script()
        r.router.send("@all dinner Friday?")
        settle()
        val toScout = r.sent(r.scout)
        assertEquals(2, toScout.size)
        assertTrue(toScout[1], toScout[1].contains("Nova, another of Kush's assistants, asked you: \"Rooh at 7. Scout can you check the 7 pm tables?\"") ||
            toScout[1].contains("asked you:"))
        // Nothing waits on a tap, and no "Ask Scout about this?" line is left in the thread.
        assertTrue(r.lines().none { it.kind == MessageKind.NEEDS_APPROVAL })
        assertTrue(r.lines().none { it.suggest.isNotEmpty() })
    }

    @Test fun anUnaddressedMessageGetsJunosFirstTakeAndOnlyTheAgentItPicked() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Nov 26-29 works.\nAngle: calendar\nFrom memory: Kush flies out of SJC") }))
        r.llm.on("pick") { req, _ ->
            assertTrue(req.instructions, req.instructions.contains("Nova") && req.instructions.contains("Scout") && !req.instructions.contains("kDot"))
            FakeLlm.text("""{"answer":"**Thanksgiving week works.** I'd keep it to four days.","ask":["Nova"]}""")
        }
        r.llm.on("claims") { req, _ -> FakeLlm.text(claims(req)) }
        r.llm.on("fold") { _, _ -> FakeLlm.text("""{"kind":"add","answer":"**Nov 26-29, flying out of SJC.** Only Nova knew your airport.","caption":"Nova knew your airport"}""") }
        r.router.send("plan Thanksgiving")
        settle(1_000)
        val runId = r.lines().first().runId!!
        // Seconds in: Juno's take, labelled, with one avatar: the agent it picked. Scout wasn't asked.
        assertEquals("**Thanksgiving week works.** I'd keep it to four days.", r.lines().single { it.id == "answer:$runId" }.text)
        assertEquals("First take · others adding", r.lines().single { it.id == "answer:$runId" }.detail)
        assertTrue(r.sent(r.scout).isEmpty())
        settle()
        val l = r.lines()
        assertTrue(l.none { it.kind == MessageKind.SUBTASK || it.kind == MessageKind.RUN || it.author == r.nova.id })
        assertTrue(l.none { it.text.contains("Angle:") || it.text.contains("From memory") })
        val answer = l.single { it.id == "answer:$runId" }
        assertEquals("**Nov 26-29, flying out of SJC.** Only Nova knew your airport.", answer.text)
        assertEquals("Updated · Nova knew your airport", answer.detail)
        assertEquals("Nov 26-29 works.", r.presence(runId).agents.single().text)
        // Nova got Kush's words with the tail, alone.
        val toNova = r.sent(r.nova).single()
        assertTrue(toNova, toNova.contains("plan Thanksgiving") && !toNova.contains("Also asking") && toNova.endsWith(app.atall.coordinator.AskTail.text("Kush")))
    }

    @Test fun chatIsJustJunosAnswerWithNoAvatarRow() = runTest {
        val r = Rig(this, emptyMap())
        r.llm.on("pick") { _, _ -> FakeLlm.text("""{"answer":"Anytime!","ask":[]}""") }
        r.router.send("thanks juno")
        settle()
        val l = r.lines()
        val runId = l.first().runId!!
        val answer = l.single { it.id == "answer:$runId" }
        assertEquals("Anytime!", answer.text)
        assertNull(answer.detail)
        assertTrue(l.none { it.kind == MessageKind.PRESENCE || it.kind == MessageKind.RUN || it.kind == MessageKind.NOTICE })
        assertTrue(r.sent(r.nova).isEmpty() && r.sent(r.scout).isEmpty())
        assertEquals(listOf("pick"), r.llm.requests.map { it.purpose })
    }

    @Test fun twoMentionsGetOneLivingAnswerFromJustThoseAgents() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Rooh at 7.") }, "Scout" to { _ -> listOf("Sakoon at 7:30.") }, "Muse" to { _ -> listOf("Jaks.") }), withMuse = true)
        r.script()
        r.router.send("@Nova @Scout dinner Friday?")
        settle()
        val l = r.lines()
        val runId = l.first().runId!!
        assertEquals(listOf("Nova", "Scout"), r.presence(runId).agents.map { it.name })
        assertTrue(r.sent(r.muse!!).isEmpty())
        // One answer from Juno, not two separate direct replies.
        assertNotNull(l.single { it.id == "answer:$runId" })
        assertTrue(l.none { it.author == r.nova.id || it.author == r.scout.id })
        assertEquals(app.atall.coordinator.EditorScope.SOME, r.bridge.juno.state(runId)!!.editor!!.scope)
        // One agent @mentioned stays a direct message: its own answer, no Juno take.
        r.router.send("@Nova and Saturday?")
        settle()
        val reply = r.lines().last { it.author == r.nova.id }
        assertEquals("Rooh at 7.", reply.text)
        assertEquals(Attribution.DIRECT, reply.attribution)
    }

    @Test fun aDirectReplysMentionIsRelayedAutomaticallyAndAnsweredInItsOwnLine() = runTest {
        val r = Rig(this, mapOf(
            "Scout" to { t -> if (t.contains("book it")) listOf("Sure. @Nova book it now.") else listOf("7 pm works. @Nova is Rooh open then?") },
            "Nova" to { t -> if (t.contains("asked you:")) listOf("Yes, Rooh is open till 10.") else emptyList() },
        ))
        r.router.send("@Scout what time works Friday?")
        settle()
        val toNova = r.sent(r.nova)
        assertEquals(1, toNova.size)
        assertTrue(toNova.single(), toNova.single().contains("Scout, another of Kush's assistants, asked you: \"Nova is Rooh open then?\""))
        val l = r.lines()
        // No "Ask Nova about this?" chip on Scout's line; Nova's answer is its own line, "answered Scout".
        assertTrue(l.single { it.author == r.scout.id }.suggest.isEmpty())
        val novaLine = l.single { it.author == r.nova.id }
        assertEquals("Yes, Rooh is open till 10.", novaLine.text)
        assertEquals(Attribution.RELAY, novaLine.attribution)
        assertEquals("Scout", novaLine.relayFrom)
        assertTrue(l.none { it.kind == MessageKind.PRESENCE || it.id.startsWith("answer:") })
        assertTrue(r.llm.requests.isEmpty())
        // An order in the reply never relays: the chip stays for Kush.
        r.router.send("@Scout can you book it?")
        settle()
        assertEquals(1, r.sent(r.nova).size)
        assertEquals(listOf(r.nova.id), r.lines().last { it.author == r.scout.id }.suggest)
    }

    @Test fun aFreshInstallDoesNotWaitOnTextingAgents() = runTest {
        // Nothing measured: texting agents are slow by prior, so the answer settles on Juno's take, not at 3 minutes.
        val r = Rig(this, mapOf("Nova" to { _ -> emptyList() }, "Scout" to { _ -> emptyList() }), measured = false)
        r.script()
        r.router.send("@all dinner Friday?")
        settle(10_000)
        val runId = r.lines().first().runId!!
        val st = r.bridge.juno.state(runId)!!
        assertTrue(st.asks.filter { it.agentId != Main.AUTHOR }.all { it.background })
        assertEquals(app.atall.coordinator.Phase.DONE, st.phase)
        assertTrue(r.presence(runId).agents.all { it.state == PresenceState.LATER })
    }

    @Test fun aRepairQuotingKushsBookWordsPassesTheTransportGateButAgentsOrdersDoNot() {
        val q = "Book a table for 4: Rooh or Sakoon?"
        fun repair(pick: String) = app.atall.coordinator.AskState("r1~a3", "scout",
            "Quick check on: $q\nYour answer: $pick\nSomething another of Kush's assistants knows: Kush is away Friday\nDoes that change your answer? Just answer; don't act on it.",
            app.atall.coordinator.AskPurpose.REPAIR, status = app.atall.coordinator.AskStatus.SENDING, createdAt = 0, quoted = listOf(pick, "Kush is away Friday"), userQuote = q)
        fun packed(a: app.atall.coordinator.AskState, parts: Boolean) = ContextPack.packed(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "scout", recipientName = "Scout", request = q, need = a.text,
            parts = if (parts) app.atall.coordinator.AskSegments.of(a) else null, tail = true))
        val ok = packed(repair("Sakoon on Friday."), parts = true)
        assertFalse(SendGate.check(ok.segments, "Kush").gated)
        assertTrue(ok.text.contains("Quick check on: Book a table for 4"))
        // v0.1.8 checked the whole repair as relayed words, so Kush's "Book" refused it; an agent's own order still is.
        val whole = repair("Sakoon on Friday.").text
        assertTrue(SendGate.check(listOf(app.atall.coordinator.Segment(whole, app.atall.coordinator.Provenance.PEER_RELAY)), "Kush").gated)
        assertTrue(SendGate.check(packed(repair("Book Sakoon now."), parts = true).segments, "Kush").gated)
    }
}
