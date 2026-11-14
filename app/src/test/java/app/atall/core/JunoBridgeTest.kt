@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.AgentMessage
import app.atall.agents.text.Clock
import app.atall.agents.text.Reaction
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.coordinator.AgreementMap
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.ToolCall
import app.atall.coordinator.testing.FakeLlm
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Juno wired into the thread: Router -> JunoBridge -> coordinator Juno -> (packed) sends to fake texting agents ->
 * their replies back through the Router -> grouped thread lines. FakeLlm scripts the model. Placeholders only.
 */
class JunoBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val T0 = 1_763_000_000_000L
    private var ids = 0
    private fun call(name: String, vararg args: Pair<String, Any>) = ToolCall("call_${++ids}", name, JsonObject(args.associate { (k, v) ->
        k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) }); else -> error("arg") }
    }).toString())
    private fun tools(vararg c: ToolCall) = LlmResponse("", c.toList())

    private inner class Rig(val scope: TestScope, respond: Map<String, (String) -> List<String>> = emptyMap()) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        val scout = registry.add("Scout", ChannelType.RCS, "+14085550102")
        val agents: Map<String, FakeAgent> = listOf(nova, scout).associate { p ->
            p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, respond[p.name] ?: { emptyList() })
        }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        var plainTurns = 0
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, userName = { "Kush" }, clock = clock)
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) { plainTurns++ } } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true })
        init {
            bridge.start()
            // The app's collectors: every agent message goes through the Router.
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
        fun lines() = thread.all()
    }

    private fun TestScope.settle(ms: Long = 5 * 60_000L) { advanceTimeBy(ms); runCurrent() }

    @Test fun unaddressedMessageIsCoordinatedAndRepliesAreGroupedUnderJuno() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Nov 26-29 works") }, "Scout" to { _ -> listOf("Keep it under $800") }))
        r.llm.on("juno") { req, n ->
            when (n) {
                0 -> tools(
                    call("ask_agent", "agent" to "Nova", "text" to "Which Thanksgiving dates are free?", "why" to "calendar", "money_or_account" to false),
                    call("ask_agent", "agent" to "Scout", "text" to "What budget fits a Thanksgiving trip?", "why" to "money", "money_or_account" to false),
                    call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120),
                )
                else -> {
                    val out = FakeLlm.lastToolOutput(req)!!
                    assertTrue(out.contains("Nov 26-29 works") && out.contains("under \$800"))
                    tools(call("reply", "text" to "Let's do Nov 26-29 and keep it under \$800."))
                }
            }
        }
        r.router.send("can you all decide on the trip for Thanksgiving")
        settle()

        assertEquals(0, r.plainTurns)
        // Each agent got Juno's rewritten sub-task, wrapped in compact context, under Juno's ask id.
        val (cid, text) = r.agents.getValue(r.nova.id).sent.single()
        assertTrue(PeerLines.isJunoCid(cid))
        assertTrue(text, text.startsWith("From Atall (Kush's thread):\nJuno needs: Which Thanksgiving dates are free?"))
        assertTrue(text.contains("Kush asked: \"can you all decide on the trip for Thanksgiving\""))
        assertTrue(text.contains("Also asking: Scout"))

        val l = r.lines()
        val runId = l.first().runId!!
        assertTrue(l.all { it.runId == runId })
        assertEquals(listOf(MessageKind.TEXT, MessageKind.RUN), l.take(2).map { it.kind })
        assertEquals("Asked Nova and Scout", l[1].text)
        val subtasks = l.filter { it.kind == MessageKind.SUBTASK }
        assertEquals(listOf(r.nova.id, r.scout.id), subtasks.map { it.peerId })
        assertEquals("Which Thanksgiving dates are free?", subtasks[0].text)
        val replies = l.filter { it.author == r.nova.id || it.author == r.scout.id }
        assertEquals(listOf("Nov 26-29 works", "Keep it under \$800"), replies.map { it.text })
        assertTrue(replies.all { it.attribution == Attribution.JUNO })
        // Each reply sits under its own sub-task.
        assertTrue(l.indexOf(replies[0]) > l.indexOf(subtasks[0]))
        assertEquals("Let's do Nov 26-29 and keep it under \$800.", l.last().text)
        assertEquals(Main.AUTHOR, l.last().author)
    }

    @Test fun atAllFansOutBlindAndBuildsTheAgreementMap() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Saturday") }, "Scout" to { _ -> listOf("Sunday, it's drier") }))
        r.llm.on("blind") { _, _ -> FakeLlm.text("Sunday") }
        r.llm.on("claims") { _, _ -> FakeLlm.text("not json") }
        r.router.send("@all best day for the hike?")
        settle()

        for (a in r.agents.values) {
            val (cid, text) = a.sent.single()
            assertTrue(PeerLines.isJunoCid(cid))
            assertTrue(text, text.contains("Juno needs: best day for the hike?") && text.contains("(answer on your own)"))
        }
        val map = r.lines().single { it.kind == MessageKind.AGREEMENT }
        val decoded = Json.decodeFromString(AgreementMap.serializer(), map.detail!!)
        assertEquals(setOf("Saturday", "Sunday, it's drier", "Sunday"), decoded.raw.map { it.text }.toSet())
        assertEquals(0, r.llm.calls("juno"))
    }

    @Test fun approvalGateHoldsTheAskInCode() = runTest {
        val r = Rig(this, mapOf("Nova" to { _ -> listOf("Booked for 7") }))
        r.llm.on("juno") { _, n ->
            if (n == 0) tools(
                // The model forgot the flag: the code gate still catches "book".
                call("ask_agent", "agent" to "Nova", "text" to "Book a table for 2 at 7pm", "why" to "bookings", "money_or_account" to false),
                call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 60),
            ) else tools(call("reply", "text" to "Done, 7pm."))
        }
        r.router.send("get us dinner at 7")
        settle()
        assertTrue(r.agents.getValue(r.nova.id).sent.isEmpty())
        val ask = r.lines().single { it.kind == MessageKind.NEEDS_APPROVAL }
        assertEquals("Okay to have Nova do this?", ask.text)

        r.router.approve(ask.id, yes = true)
        settle()
        assertEquals(1, r.agents.getValue(r.nova.id).sent.size)
        assertEquals(SendStatus.SENT, r.thread.get(ask.id)!!.status)
        assertEquals("Done, 7pm.", r.lines().last().text)
    }

    @Test fun strayReplyAndTapbackDuringARunLandInTheRun() = runTest {
        val r = Rig(this)
        r.llm.on("juno") { _, n ->
            if (n == 0) tools(
                call("ask_agent", "agent" to "Scout", "text" to "Any plans on Saturday?", "why" to "calendar", "money_or_account" to false),
                call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120),
            ) else tools(call("reply", "text" to "Scout says you're free."))
        }
        r.router.send("am I free Saturday?")
        advanceTimeBy(10_000); runCurrent()
        val scout = r.agents.getValue(r.scout.id)
        val cid = scout.sent.single().first
        // A tapback first: a chip on the user's request, never a reply.
        r.router.onPeerMessage(r.scout.id, AgentMessage("t1", AgentId(r.scout.id), "👍 to “From Atall”", T0 + 11_000, T0 + 11_000, null,
            reaction = Reaction("👍", "From Atall", targetCorrelationId = cid)))
        // Then a reply the transport couldn't correlate: attached to the open ask, flagged uncertain.
        scout.push("Nothing on Saturday")
        settle()
        val l = r.lines()
        assertEquals(listOf(ReactionChip("👍", r.scout.id)), l.first { it.author == ThreadMessage.USER }.reactions)
        val reply = l.single { it.author == r.scout.id }
        assertEquals("Nothing on Saturday", reply.text)
        assertEquals(Attribution.JUNO, reply.attribution)
        assertEquals("Might not be an answer to this", reply.detail)
        assertFalse(l.any { it.text.startsWith("👍") })
        assertEquals("Scout says you're free.", l.last().text)
    }

    @Test fun signedOutAsksToSignInWithoutStartingARun() = runTest {
        val r = Rig(this)
        val router = Router(r.thread, r.registry, r.peers, { error("unused") }, { null }, clock = r.clock, juno = { r.bridge }, signedIn = { false })
        router.send("plan the weekend")
        assertEquals(MessageKind.NEEDS_SIGN_IN, r.lines().last().kind)
        assertEquals(0, r.llm.calls("juno"))
    }

    @Test fun withoutAgentsThePlainChatAnswers() = runTest {
        val r = Rig(this)
        r.registry.all().forEach { r.registry.update(it.copy(paused = true)) }
        val peers = object : PeerDirectory {
            override fun peers() = r.registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = null
        }
        var turns = 0
        val bridge = JunoBridge(r.thread, r.registry, peers, { r.llm }, InMemoryRunStore(), backgroundScope, clock = r.clock)
        val router = Router(r.thread, r.registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) { turns++ } } },
            { null }, clock = r.clock, juno = { bridge }, signedIn = { true })
        router.send("hello")
        assertEquals(1, turns)
        assertNotNull(r.lines().single { it.author == ThreadMessage.USER })
    }
}
