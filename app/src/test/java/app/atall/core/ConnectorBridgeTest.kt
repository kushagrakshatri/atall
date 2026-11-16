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
import app.atall.coordinator.AskStatus
import app.atall.coordinator.ClaimSource
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.SourcedClaim
import app.atall.coordinator.ToolCall
import app.atall.coordinator.Via
import app.atall.coordinator.adapters.CardPeer
import app.atall.coordinator.adapters.ConnectorAgent
import app.atall.coordinator.testing.FakeLlm
import app.atall.ui.provenanceLabel
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
 * C2 in the app: routing (connector when paired + connected, text otherwise), the card that goes out, verified answers
 * projected with the provenance badge, connector-only agents, direct @mentions over the connector.
 */
// Pinned to the pre-collab projection (editorUi = false, auto talk on): the collab UI is covered by CollabBridgeTest.
class ConnectorBridgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val T0 = 1_763_000_000_000L
    private var ids = 0
    private fun call(name: String, vararg args: Pair<String, Any>) = ToolCall("call_${++ids}", name, JsonObject(args.associate { (k, v) ->
        k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) }); else -> error("arg") }
    }).toString())
    private fun tools(vararg c: ToolCall) = LlmResponse("", c.toList())

    /** A connector route that records the cards instead of posting them. */
    private class FakeConnector(val connected: MutableSet<String>, var down: Boolean = false) : ConnectorAccess {
        val cards = CopyOnWriteArrayList<Triple<String, String, String>>() // relayId, cid, goal
        val titles = CopyOnWriteArrayList<String>()
        val only = HashSet<String>()
        override fun relayIdFor(p: AgentProfile): String? = ConnectorRouting.relayIdOf(p, connected)
        override fun peer(p: AgentProfile): CardPeer? {
            val r = relayIdFor(p) ?: return null
            return object : CardPeer {
                override val id = p.id
                override val relayAgentId = r
                override suspend fun send(text: String, correlationId: String) = sendCard(correlationId, text, text)
                override suspend fun sendCard(correlationId: String, title: String, goal: String): PeerSend {
                    if (down) return PeerSend.Failed(ConnectorAgent.RELAY_UNREACHABLE)
                    cards += Triple(r, correlationId, goal); titles += title
                    return PeerSend.Sent(System.currentTimeMillis(), Via.CONNECTOR)
                }
            }
        }
        override fun connectorOnly(runId: String) = runId in only
    }

    private inner class Rig(val scope: TestScope, val connector: FakeConnector = FakeConnector(mutableSetOf("grok", "duet"))) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        val grok = registry.add("Grok", ChannelType.RCS, "+14085550103") // texts too, but connected on the relay
        val duet = registry.addConnector("Duet", "duet")
        val agents: Map<String, FakeAgent> = listOf(nova, grok).associate { p ->
            p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, if (p.name == "Nova") { _ -> listOf("Rooh has 7:30") } else { _ -> listOf("texted answer") })
        }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, config = app.atall.coordinator.JunoConfig(autoTalk = true), editorUi = false, userName = { "Kush" }, clock = clock, connector = connector)
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) {} } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true }, connector = { connector })
        init {
            bridge.start()
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
    }

    private fun TestScope.settle(ms: Long = 60_000L) { advanceTimeBy(ms); runCurrent() }

    private fun result(peerId: String, cid: String, text: String, verified: Boolean = true) = PeerSignal.Message(
        peerId, "relay:res:${cid.hashCode()}", text, T0, cid, via = Via.CONNECTOR, verified = verified, final = true,
        claims = listOf(SourcedClaim("Sakoon has a table at 7:30.", 0.8, sources = listOf(ClaimSource("https://example.com/sakoon")))),
    )

    @Test fun connectedAgentsGetCardsOthersGetTextsAndBadgesShowWhich() = runTest {
        val r = Rig(this)
        r.llm.on("juno") { req, n ->
            when (n) {
                0 -> tools(
                    call("ask_agent", "agent" to "Grok", "text" to "Find a table for 4 Saturday 7-8pm, no pork or beef.", "why" to "x", "money_or_account" to false),
                    call("ask_agent", "agent" to "Duet", "text" to "Ideas for Saturday dinner for 4, no pork or beef?", "why" to "x", "money_or_account" to false),
                    call("ask_agent", "agent" to "Nova", "text" to "Is Rooh open Saturday?", "why" to "x", "money_or_account" to false),
                    call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120),
                )
                else -> { assertTrue(FakeLlm.lastToolOutput(req)!!.contains("connector_verified")); tools(call("reply", "text" to "Sakoon at 7:30.")) }
            }
        }
        // Roster: per-agent connector timeouts; Duet (connector only) is reachable.
        val roster = r.bridge.roster()
        assertEquals(ConnectorRouting.timeouts("grok").softMs, roster.byId(r.grok.id)!!.softTimeoutMs)
        assertEquals(ConnectorRouting.timeouts("duet").hardMs, roster.byId(r.duet.id)!!.hardTimeoutMs)
        assertNull(roster.byId(r.nova.id)!!.softTimeoutMs)
        assertFalse(roster.byId(r.duet.id)!!.paused)

        r.router.send("Plan Saturday dinner for four, no pork or beef.")
        settle(1_000)
        // Grok and Duet got signed cards (the same packed text a text carries, Juno's sub-task as the title); Nova a text.
        assertEquals(listOf("grok", "duet"), r.connector.cards.map { it.first })
        assertTrue(r.connector.cards[0].third.startsWith("From Atall (Kush's thread):\nFind a table for 4"))
        assertEquals("Find a table for 4 Saturday 7-8pm, no pork or beef.", r.connector.titles[0])
        assertTrue(r.agents.getValue(r.grok.id).sent.isEmpty())
        assertEquals(1, r.agents.getValue(r.nova.id).sent.size)
        val runId = r.thread.all().first().runId!!
        val st = r.bridge.juno.state(runId)!!
        assertEquals(listOf(Via.CONNECTOR, Via.CONNECTOR, Via.TEXT), st.asks.map { it.via })

        // Progress over the connector is a status line, not an answer; then the verified results land.
        val grokCid = r.connector.cards[0].second
        val duetCid = r.connector.cards[1].second
        r.bridge.juno.ingest(PeerSignal.Message(r.grok.id, "relay:7", "Checking OpenTable", T0, grokCid, status = "progress", via = Via.CONNECTOR, verified = true))
        r.bridge.juno.ingest(result(r.grok.id, grokCid, "Sakoon has a table for 4 at 7:30."))
        r.bridge.juno.ingest(result(r.duet.id, duetCid, "Sakoon or Rooh, both veg-friendly."))
        settle()

        val lines = r.thread.all()
        assertTrue(lines.any { it.kind == MessageKind.NOTICE && it.text == "Grok: Checking OpenTable" })
        val grokLine = lines.single { it.author == r.grok.id }
        val duetLine = lines.single { it.author == r.duet.id }
        val novaLine = lines.single { it.author == r.nova.id }
        assertEquals(Via.CONNECTOR, grokLine.via); assertEquals("Verified", provenanceLabel(grokLine))
        assertEquals(Via.CONNECTOR, duetLine.via)
        assertNull("texted answers get no badge", novaLine.via); assertNull(provenanceLabel(novaLine))
        assertEquals("Sakoon at 7:30.", lines.last().text)
        val done = r.bridge.juno.state(runId)!!
        assertEquals(1, done.asks.first { it.agentId == r.grok.id }.claims.size)
        assertTrue(done.asks.all { it.status == AskStatus.ANSWERED })
    }

    @Test fun anUnverifiedConnectorAnswerIsNotShownAsVerified() = runTest {
        val r = Rig(this)
        r.llm.on("juno") { _, n ->
            if (n == 0) tools(call("ask_agent", "agent" to "Duet", "text" to "Ideas?", "why" to "x", "money_or_account" to false), call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120))
            else tools(call("reply", "text" to "Done."))
        }
        r.router.send("@Juno ask @Duet for dinner ideas")
        settle(1_000)
        r.bridge.juno.ingest(result(r.duet.id, r.connector.cards.single().second, "Try Sakoon.", verified = false))
        settle()
        assertNull(r.thread.all().single { it.author == r.duet.id }.via)
    }

    @Test fun notConnectedMeansTextAndAConnectorOnlyAgentIsUnreachable() = runTest {
        val r = Rig(this, FakeConnector(mutableSetOf()))
        val roster = r.bridge.roster()
        assertTrue("Duet has no text route and no connection", roster.byId(r.duet.id)!!.paused)
        assertFalse(roster.byId(r.grok.id)!!.paused)
        assertNull(roster.byId(r.grok.id)!!.softTimeoutMs)
        r.llm.on("juno") { _, n ->
            if (n == 0) tools(call("ask_agent", "agent" to "Grok", "text" to "Table for 4?", "why" to "x", "money_or_account" to false), call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120))
            else tools(call("reply", "text" to "Ok."))
        }
        r.router.send("@Juno ask @Grok about a table")
        settle()
        assertTrue(r.connector.cards.isEmpty())
        assertEquals(1, r.agents.getValue(r.grok.id).sent.size)
        assertNull(r.thread.all().single { it.author == r.grok.id }.via)
    }

    @Test fun relayDownFallsBackToTextExceptInAConnectorOnlyRun() = runTest {
        val r = Rig(this)
        r.connector.down = true
        r.llm.on("juno") { _, n ->
            if (n == 0) tools(call("ask_agent", "agent" to "Grok", "text" to "Table for 4?", "why" to "x", "money_or_account" to false), call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120))
            else tools(call("reply", "text" to "Ok."))
        }
        r.router.send("@Juno ask @Grok about a table")
        settle()
        assertEquals("by text when the relay can't be reached", 1, r.agents.getValue(r.grok.id).sent.size)

        val r2 = Rig(this)
        r2.connector.down = true
        r2.bridge.onRunStarted = { runId, _ -> r2.connector.only += runId }
        r2.llm.on("juno") { req, n ->
            if (n == 0) tools(call("ask_agent", "agent" to "Grok", "text" to "Table for 4?", "why" to "x", "money_or_account" to false), call("wait", "ask_ids" to emptyList<String>(), "max_seconds" to 120))
            else tools(call("reply", "text" to "Ok."))
        }
        r2.router.send(ConnectorRouting.story1("Grok", "Duet"))
        settle()
        assertTrue("never by text in a connector-only run", r2.agents.getValue(r2.grok.id).sent.isEmpty())
        val st = r2.bridge.juno.state(r2.thread.all().first().runId!!)!!
        assertEquals(AskStatus.FAILED, st.asks.first { it.agentId == r2.grok.id }.status)
    }

    @Test fun directMentionGoesOverTheConnectorAndTheAnswerIsVerified() = runTest {
        val r = Rig(this)
        r.router.send("@Duet what's a good veg place near Santana Row?")
        settle(1_000)
        val (relayId, cid, goal) = r.connector.cards.single()
        assertEquals("duet", relayId)
        assertTrue(cid.startsWith(Router.DIRECT))
        assertTrue(goal.contains("what's a good veg place"))
        val user = r.thread.all().first()
        assertEquals(SendStatus.SENT, user.status)
        r.router.onConnectorReply(r.duet.id, PeerSignal.Message(r.duet.id, "relay:3", "Checking menus", T0, cid, status = "progress", via = Via.CONNECTOR, verified = true))
        r.router.onConnectorReply(r.duet.id, PeerSignal.Message(r.duet.id, "relay:res:1", "Try Sakoon.", T0 + 5, cid, via = Via.CONNECTOR, verified = true, final = true))
        r.router.onConnectorReply(r.duet.id, PeerSignal.Message(r.duet.id, "relay:res:1", "Try Sakoon.", T0 + 5, cid, via = Via.CONNECTOR, verified = true, final = true))
        val lines = r.thread.all()
        assertEquals(1, lines.count { it.author == r.duet.id })
        val answer = lines.single { it.author == r.duet.id }
        assertEquals(Attribution.DIRECT, answer.attribution)
        assertEquals(Via.CONNECTOR, answer.via)
        assertTrue(lines.any { it.text == "Duet: Checking menus" && it.kind == MessageKind.NOTICE })
    }

    @Test fun routingRulesMatchNamesAndAddOnlyMissingAgents() {
        val reg = AgentRegistry(File(tmp.root, "r.json"), clock = { 0 })
        val kdot = reg.add("kDot", ChannelType.RCS, "+14085550110")
        val claude = reg.add("Claude", ChannelType.RCS, "+14085550111").let { reg.update(it.copy(aliases = listOf("Claude Duet"))) }
        val ids = listOf("kdot", "grok", "hark", "duet", "muse")
        assertEquals("kdot", ConnectorRouting.relayIdOf(kdot, ids))
        assertEquals("duet", ConnectorRouting.relayIdOf(claude, ids))
        assertNull(ConnectorRouting.relayIdOf(kdot, listOf("grok")))
        assertEquals(listOf("grok" to "Grok", "hark" to "Hark", "muse" to "Muse"), ConnectorRouting.missing(reg.all(), ids))
        val g = reg.addConnector("Grok", "grok")
        assertEquals("grok", ConnectorRouting.relayIdOf(g, ids))
        assertNull("a saved relay id that isn't connected has no route", ConnectorRouting.relayIdOf(g, listOf("duet")))
        assertEquals(ChannelType.CONNECTOR, g.channel)
        // Story 1 routes to Juno with both agents as must-ask.
        val route = Mentions.route(ConnectorRouting.story1("Grok", "Claude"), reg.all())
        assertTrue(route is Route.ToMain && route.mentionedPeers.toSet() == setOf(g.id, claude.id))
        assertTrue(ConnectorRouting.timeouts("duet").softMs > ConnectorRouting.timeouts("grok").softMs)
    }
}
