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

    private inner class Rig(val scope: TestScope, respond: Map<String, (String) -> List<String>>) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        val scout = registry.add("Scout", ChannelType.RCS, "+14085550102")
        val kdot = registry.add("kDot", ChannelType.RCS, "+14085550103").let { registry.update(it.copy(paused = true)) }
        val agents: Map<String, FakeAgent> = listOf(nova, scout, kdot).associate { p ->
            p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, respond[p.name] ?: { emptyList() })
        }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        val scores = InMemoryScoreLog()
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

    @Test fun aDelegatingRunAlsoShowsOneAvatarRowAndNoRawTail() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Nov 26-29 works.\nAngle: calendar\nFrom memory: Kush flies out of SJC") }))
        r.llm.on("juno") { req, n ->
            if (n == 0) FakeLlm.tools(FakeLlm.call("ask_agent", """{"agent":"Nova","text":"Which Thanksgiving dates are free?","why":"calendar","money_or_account":false}"""),
                FakeLlm.call("wait", """{"ask_ids":[],"max_seconds":120}"""))
            else {
                val out = FakeLlm.lastToolOutput(req)!!
                // The tail reaches Juno structured, never inside the reply.
                assertTrue(out, out.contains("\"reply\":\"Nov 26-29 works.\"") && out.contains("\"from_memory\":[\"Kush flies out of SJC\"]"))
                FakeLlm.tools(FakeLlm.call("reply", """{"text":"Nov 26-29, flying out of SJC."}"""))
            }
        }
        r.router.send("plan Thanksgiving")
        settle()
        val l = r.lines()
        val runId = l.first().runId!!
        assertTrue(l.none { it.kind == MessageKind.SUBTASK || it.kind == MessageKind.RUN || it.author == r.nova.id })
        assertEquals("Nov 26-29, flying out of SJC.", l.single { it.id == "answer:$runId" }.text)
        assertNull(l.single { it.id == "answer:$runId" }.detail)
        assertEquals("Nov 26-29 works.", r.presence(runId).agents.single().text)
        assertFalse(r.sent(r.nova).single().contains("Also asking"))
    }
}
