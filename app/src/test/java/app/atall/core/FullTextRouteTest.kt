@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.Clock
import app.atall.agents.text.Confidence
import app.atall.agents.text.Correlation
import app.atall.agents.text.CorrelationReason
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.coordinator.AskStatus
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.ToolCall
import app.atall.coordinator.Via
import app.atall.coordinator.adapters.CardPeer
import app.atall.coordinator.adapters.WakeConfig
import app.atall.coordinator.adapters.WakeDefaults
import app.atall.coordinator.adapters.WakeKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * C3.1 "Full text": Hark (by default) and kDot (when picked) are connected on the relay but never get a task card. @all,
 * Juno's asks and direct @mentions send the whole packed ask by text at once; the text reply is a normal answer for
 * claims, merge and the room (Hark's share tap stays).
 */
class FullTextRouteTest {
    @get:Rule val tmp = TemporaryFolder()
    private val T0 = 1_763_000_000_000L
    private var ids = 0
    private fun call(name: String, vararg args: Pair<String, Any>) = ToolCall("call_${++ids}", name, JsonObject(args.associate { (k, v) ->
        k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) }); else -> error("arg") }
    }).toString())
    private fun tools(vararg c: ToolCall) = LlmResponse("", c.toList())

    /** Everyone in [connected] is live on the relay; cards are recorded, never posted. */
    private class Connected(val connected: Set<String>) : ConnectorAccess {
        val cards = CopyOnWriteArrayList<Pair<String, String>>() // relayId, cid
        override fun relayIdFor(p: AgentProfile): String? = ConnectorRouting.relayIdOf(p, connected)
        override fun peer(p: AgentProfile): CardPeer? {
            val r = relayIdFor(p) ?: return null
            return object : CardPeer {
                override val id = p.id
                override val relayAgentId = r
                override suspend fun send(text: String, correlationId: String) = sendCard(correlationId, text, text)
                override suspend fun sendCard(correlationId: String, title: String, goal: String): PeerSend {
                    cards += r to correlationId
                    return PeerSend.Sent(System.currentTimeMillis(), Via.CONNECTOR)
                }
            }
        }
    }

    private inner class Rig(val scope: TestScope, val configs: Map<String, WakeConfig> = emptyMap(), harkByConnectorOnly: Boolean = false) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val hark = if (harkByConnectorOnly) registry.addConnector("Hark", "hark") else registry.add("Hark", ChannelType.RCS, "+14085550111")
        val kdot = registry.add("kDot", ChannelType.RCS, "+14085550112")
        val agents: Map<String, FakeAgent> = listOf(hark, kdot).filter { it.channel == ChannelType.RCS }
            .associate { p -> p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, { emptyList() }) }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val relay = Connected(setOf("hark", "kdot"))
        // What the app wires (Relay.access): the relay connection, with each agent's saved route on top.
        val access = FullTextRouting(relay) { id -> configs[id] ?: WakeDefaults.forAgent(id) }
        val llm = FakeLlm()
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, userName = { "Kush" }, clock = clock, connector = access)
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) {} } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true }, connector = { access })
        init {
            bridge.start()
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
        fun lines() = thread.all()
        fun sent(p: AgentProfile) = agents.getValue(p.id).sent
        fun corr(p: AgentProfile, part: Int = 0) = Correlation(sent(p).first().first, Confidence.HIGH, CorrelationReason.ONLY_OPEN_REQUEST, part)
    }

    private fun TestScope.settle(ms: Long = 60_000L) { advanceTimeBy(ms); runCurrent() }

    @Test fun harkIsConnectedButRoutedByFullTextByDefault() = runTest {
        val r = Rig(this)
        assertEquals(WakeKind.FULL_TEXT, WakeDefaults.forAgent("hark").kind)
        assertNull("no connector route for Hark", r.access.relayIdFor(r.hark))
        assertNull(r.access.peer(r.hark))
        assertEquals("Settings still knows the connection", "hark", r.access.linkedRelayId(r.hark))
        assertTrue(r.access.byFullText(r.hark))
        // kDot keeps its connector route (MCP events) unless Full text is picked for it.
        assertEquals("kdot", r.access.relayIdFor(r.kdot))
        assertFalse(r.access.byFullText(r.kdot))
        // Roster: Hark is reachable by text with the text timeouts (as in v0.1.4), and still shares privately.
        val hark = r.bridge.roster().byId(r.hark.id)!!
        assertFalse(hark.paused)
        assertNull(hark.softTimeoutMs)
        assertTrue("Hark's answers wait for the share tap in a talk", hark.sensitive)
    }

    /** The v0.1.4 birthday transcript with Hark and kDot both on Full text: no cards, no "Atall #", a normal merge and talk. */
    @Test fun atAllTextsTheWholeAskAndTheRepliesAreNormalAnswers() = runTest {
        val r = Rig(this, configs = mapOf("kdot" to WakeConfig(WakeKind.FULL_TEXT)))
        var claimsInput = ""
        r.llm.on("blind") { _, _ -> FakeLlm.text(BirthdayRunTest.JUNO_BLIND) }
        r.llm.on("claims") { req, _ -> claimsInput = FakeLlm.userTexts(req).single(); FakeLlm.text(BirthdayRunTest.realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Most lean toward a gift she'll use; kDot would make it an experience.") }
        r.llm.on("merge") { req, _ ->
            val input = FakeLlm.userTexts(req).single()
            assertTrue(input, input.contains("WHOOP") && input.contains("experience together"))
            FakeLlm.text("Upgrade her **WHOOP**, or make it a spa day for two.\n\nWhen's her birthday?")
        }
        r.llm.on("room") { _, _ -> FakeLlm.text("") }

        r.router.send(BirthdayRunTest.QUESTION)
        settle(1_000)
        assertTrue("no relay task for a Full text agent", r.relay.cards.isEmpty())
        for (p in listOf(r.hark, r.kdot)) {
            val (cid, text) = r.sent(p).single()
            assertTrue(PeerLines.isJunoCid(cid))
            // The whole ask with its context, the same text the ladder's text rung would send; never an "Atall #ref" nudge.
            assertTrue(text, text.startsWith("From Atall (Kush's thread):") && text.contains("what should I get Mugdha for her birthday"))
            assertFalse(text, text.contains("Atall #"))
            assertEquals(r.bridge.pack(p.id, "what should I get Mugdha for her birthday", cid), text)
        }
        r.agents.getValue(r.kdot.id).push(BirthdayRunTest.KDOT, r.corr(r.kdot))
        settle(7_000)
        r.agents.getValue(r.hark.id).push(BirthdayRunTest.HARK, r.corr(r.hark))
        settle()

        val runId = r.lines().first().runId!!
        val st = r.bridge.juno.state(runId)!!
        val harkAsk = st.asks.first { it.agentId == r.hark.id }
        assertEquals(Via.TEXT, harkAsk.via)
        assertTrue(harkAsk.status == AskStatus.ANSWERED)
        assertEquals(BirthdayRunTest.HARK, harkAsk.replies.joinToString("\n") { it.text })
        // Claims and merge read Hark's text like any answer.
        assertTrue(claimsInput, claimsInput.contains("WHOOP"))
        val l = r.lines()
        val answer = l.single { it.id.startsWith("answer:") && !it.hidden }
        assertTrue(answer.text, answer.text.contains("WHOOP"))
        assertTrue("no verified badge on a texted answer", l.none { it.via == Via.CONNECTOR })
        // Room mode: the talk starts and Hark's answer waits for the share tap before kDot sees it.
        assertTrue(l.any { it.kind == MessageKind.TALK })
        assertEquals("Share Hark's answer with kDot?", l.single { it.kind == MessageKind.NEEDS_APPROVAL }.text)
        assertFalse(r.sent(r.kdot).drop(1).any { it.second.contains("WHOOP") })
        assertTrue(r.relay.cards.isEmpty())
    }

    @Test fun junoAsksGoToHarkByTextWhileKDotKeepsItsCard() = runTest {
        val r = Rig(this)
        r.llm.on("juno") { req, n ->
            when (n) {
                0 -> tools(
                    call("ask_agent", "agent" to "Hark", "text" to "Find a table for 4 Saturday 7-8pm, no pork or beef.", "why" to "x", "money_or_account" to false),
                    call("ask_agent", "agent" to "kDot", "text" to "Ideas for Saturday dinner for 4?", "why" to "x", "money_or_account" to false),
                    call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 600),
                )
                else -> {
                    val out = FakeLlm.lastToolOutput(req).orEmpty()
                    assertTrue(out, out.contains("Sakoon has 7:30"))
                    tools(call("reply", "text" to "Sakoon at 7:30."))
                }
            }
        }
        r.router.send("@Juno ask @Hark and @kDot: Saturday dinner for four, no pork or beef.")
        settle(1_000)
        assertEquals(listOf("kdot"), r.relay.cards.map { it.first })
        val (harkCid, harkText) = r.sent(r.hark).single()
        assertTrue(harkText, harkText.startsWith("From Atall (Kush's thread):\nFind a table for 4"))
        assertTrue("kDot's ask went as a card, not a text", r.sent(r.kdot).isEmpty())

        r.agents.getValue(r.hark.id).push("Sakoon has 7:30 Saturday for 4.", r.corr(r.hark))
        r.bridge.juno.ingest(PeerSignal.Message(r.kdot.id, "relay:res:1", "Sakoon or Rooh.", T0, r.relay.cards.single().second, via = Via.CONNECTOR, verified = true, final = true))
        settle()
        val st = r.bridge.juno.state(PeerLines.runIdOf(harkCid))!!
        assertTrue(st.asks.filter { it.agentId != Main.AUTHOR }.all { it.status == AskStatus.ANSWERED })
        assertEquals(Via.TEXT, st.asks.first { it.agentId == r.hark.id }.via)
        assertEquals("Sakoon at 7:30.", r.lines().last().text)
    }

    @Test fun aDirectMentionTextsHarkAndItsReplyIsTheDirectAnswer() = runTest {
        val r = Rig(this)
        r.router.send("@Hark what's a good veg place near Santana Row?")
        settle(1_000)
        assertTrue(r.relay.cards.isEmpty())
        val (cid, text) = r.sent(r.hark).single()
        assertTrue(cid.startsWith(Router.DIRECT))
        assertTrue(text, text.contains("what's a good veg place near Santana Row?") && !text.contains("Atall #"))
        assertEquals(SendStatus.SENT, r.lines().first().status)
        r.agents.getValue(r.hark.id).push("Try Sakoon.", r.corr(r.hark))
        settle()
        val answer = r.lines().single { it.author == r.hark.id }
        assertEquals("Try Sakoon.", answer.text)
        assertEquals(Attribution.DIRECT, answer.attribution)
        assertNull(answer.via)
    }

    @Test fun kDotCanBeSetToFullTextToo() = runTest {
        val r = Rig(this, configs = mapOf("kdot" to WakeConfig(WakeKind.FULL_TEXT)))
        assertNull(r.access.relayIdFor(r.kdot))
        assertTrue(WakeKind.FULL_TEXT in WakeDefaults.choices("kdot", hasTextRoute = true))
        r.router.send("@kDot quick one: is Rooh open Sunday?")
        settle(1_000)
        assertTrue(r.relay.cards.isEmpty())
        assertEquals(1, r.sent(r.kdot).size)
    }

    @Test fun aConnectorOnlyHarkOnFullTextNeedsANumber() = runTest {
        val r = Rig(this, harkByConnectorOnly = true)
        assertTrue(r.access.byFullText(r.hark))
        assertTrue("nothing to text and no card: unreachable", r.bridge.roster().byId(r.hark.id)!!.paused)
        r.router.send("@Hark hi")
        settle(1_000)
        assertTrue(r.relay.cards.isEmpty())
        val user = r.lines().first()
        assertEquals(SendStatus.NOT_SENT, user.status)
        assertEquals("Hark needs a text number for Full text.", user.statusNote)
    }
}
