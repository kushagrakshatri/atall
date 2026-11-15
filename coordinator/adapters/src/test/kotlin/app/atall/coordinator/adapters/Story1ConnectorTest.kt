package app.atall.coordinator.adapters

import app.atall.coordinator.AgentKind
import app.atall.coordinator.AgentProfile
import app.atall.coordinator.AskStatus
import app.atall.coordinator.DriveResult
import app.atall.coordinator.FileRunStore
import app.atall.coordinator.InMemoryScoreLog
import app.atall.coordinator.Juno
import app.atall.coordinator.JunoConfig
import app.atall.coordinator.Peer
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.Roster
import app.atall.coordinator.ScoreEvent
import app.atall.coordinator.ToolCall
import app.atall.coordinator.Via
import app.atall.coordinator.testing.FakeLlm
import app.atall.relay.B64
import app.atall.relay.EventsPoller
import app.atall.relay.InMemoryRelayStore
import app.atall.relay.RelayClient
import app.atall.relay.SoftwareDeviceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C2 exit test, on the JVM: Story 1 ("Plan Saturday dinner for four, no pork or beef.") with Grok and Duet over the
 * Atall connector only, against a fake relay that checks phone signatures and countersigns every event. The phone
 * "dies" mid-run (backgrounded and killed); a new process recovers the run and the results still land on it.
 */
class Story1ConnectorTest {
  private val dir: File = Files.createTempDirectory("atall-c2").toFile()
  private val phone = SoftwareDeviceKey()
  private val relay = FakeRelay({ phone.publicKeySpki })
  private val relayStore = InMemoryRelayStore()
  private lateinit var client: RelayClient

  private val roster = Roster(listOf(
    AgentProfile("main", "Juno", AgentKind.MAIN),
    AgentProfile("a-grok", "Grok", AgentKind.TEXT, softTimeoutMs = 60_000, hardTimeoutMs = 10 * 60_000),
    AgentProfile("a-duet", "Duet", AgentKind.TEXT, softTimeoutMs = 60_000, hardTimeoutMs = 10 * 60_000),
    AgentProfile("a-kdot", "kDot", AgentKind.TEXT),
  ))
  private val relayIds = mapOf("a-grok" to "grok", "a-duet" to "duet", "a-kdot" to "kdot")

  /** Every agent also has a text route; Story 1 over the connector must never use it. */
  private val texted = CopyOnWriteArrayList<Pair<String, String>>()
  private fun textPeer(id: String) = object : Peer {
    override val id = id
    override suspend fun send(text: String, correlationId: String): PeerSend { texted += id to correlationId; return PeerSend.Sent(System.currentTimeMillis()) }
  }

  @Before fun up() {
    relay.start()
    relayStore.deviceId = "dev_1"
    relayStore.relayPublicKeySpkiB64 = B64.std(relay.relayKey.publicKeySpki)
    client = RelayClient(relay.baseUrl, phone, relayStore)
  }

  @After fun down() { relay.stop(); dir.deleteRecursively() }

  private class World(val juno: Juno, val llm: FakeLlm, val scores: InMemoryScoreLog)

  private fun world(ledger: ConnectorLedger, directory: ConnectorDirectory, llm: FakeLlm, scores: InMemoryScoreLog, connectorOnly: Boolean = true): World {
    val peerFor: (String) -> Peer? = { id ->
      val relayId = relayIds[id]
      val conn = if (directory.connected(relayId)) ConnectorAgent(id, relayId!!, client, ledger, deadlineMs = 10 * 60_000) else null
      RoutedPeer(id, conn, if (connectorOnly && conn != null) null else textPeer(id))
    }
    val juno = Juno({ roster }, peerFor, llm, FileRunStore(File(dir, "runs")), scores, config = JunoConfig(), newId = { "s1" })
    return World(juno, llm, scores)
  }

  private suspend fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!ok()) { if (System.currentTimeMillis() > end) error("timed out waiting for $what"); delay(20) }
  }

  @Test fun story1WithGrokAndDuetOverTheConnectorOnly() = runBlocking {
    val ledgerFile = File(dir, "connector.json")
    val directory = ConnectorDirectory(client).also { it.refresh() }
    assertTrue(directory.connected("grok") && directory.connected("duet"))
    assertFalse("kDot has no connection on this relay", directory.connected("kdot"))

    var waitOutput: String? = null
    val llm = FakeLlm().on("juno") { req, n ->
      when (n) {
        0 -> FakeLlm.tools(
          call("ask_agent", """{"agent":"Grok","text":"Find 3 places in San Jose for Saturday dinner for 4 with a table 7-8pm. No pork or beef dishes as the focus. Don't book.","why":"research","money_or_account":false}"""),
          call("ask_agent", """{"agent":"Duet","text":"Suggest a Saturday dinner plan for 4 in San Jose, no pork or beef. Don't book.","why":"ideas","money_or_account":false}"""),
          call("wait", """{"ask_ids":["rs1~a0","rs1~a1"],"max_seconds":180}"""),
        )
        else -> { waitOutput = FakeLlm.lastToolOutput(req); FakeLlm.tools(call("reply", """{"text":"Go with Sakoon at 7:30: both Grok and Duet like it, and it's mostly vegetarian. Want me to hold it?"}""")) }
      }
    }
    val scores = InMemoryScoreLog()

    // ---- process 1: the user sends Story 1; Juno asks Grok and Duet over the connector.
    val w1 = world(ConnectorLedger(ledgerFile), directory, llm, scores)
    val runId = w1.juno.startCoordinated("Plan Saturday dinner for four, no pork or beef.", mustAsk = listOf(roster.byId("a-grok")!!, roster.byId("a-duet")!!))
    val drive1 = async(Dispatchers.Default) { w1.juno.drive(runId) }
    until("two task cards") { relay.tasks.size == 2 }
    until("both asks sent") { runBlocking { w1.juno.state(runId) }!!.asks.all { it.status == AskStatus.PENDING } }

    // One signed card per agent, addressed to that agent only, asking for claims, with a deadline.
    val grokCard = relay.taskFor("grok")!!
    val duetCard = relay.taskFor("duet")!!
    assertEquals(listOf("grok"), relay.toOf(grokCard))
    assertEquals(listOf("duet"), relay.toOf(duetCard))
    assertEquals("claims", grokCard["output"]!!.jsonPrimitive.content)
    assertNotNull(grokCard["deadline"])
    assertTrue(relay.goalFor("grok")!!.contains("No pork or beef"))
    assertEquals(0, relay.rejected.get())
    val s1 = w1.juno.state(runId)!!
    assertTrue(s1.asks.all { it.via == Via.CONNECTOR })

    // The agents claim and post progress; the phone polls. Status lines are notes, never answers (m05).
    relay.claim("grok"); relay.claim("duet"); relay.progress("grok", "Checking OpenTable for 7-8pm")
    var juno = w1.juno
    val pumped = CopyOnWriteArrayList<PumpNote>()
    val pump = ConnectorPump(ConnectorLedger(ledgerFile), toJuno = { juno.ingest(it) }, note = { pumped += it })
    EventsPoller(client, relayStore) { pump.onEvents(it) }.pollOnce()
    val mid = w1.juno.state(runId)!!
    val grokAsk = mid.asks.first { it.agentId == "a-grok" }
    assertEquals(AskStatus.PENDING, grokAsk.status)
    assertTrue(grokAsk.replies.isEmpty())
    // The claims are not notes (nothing to say); real progress is.
    assertEquals(listOf("Checking OpenTable for 7-8pm"), grokAsk.statusNotes.map { it.text })

    // ---- the phone is backgrounded and the process dies mid-run.
    drive1.cancel()
    val w2 = world(ConnectorLedger(ledgerFile), ConnectorDirectory(client).also { it.refresh() }, llm, scores)
    juno = w2.juno
    assertEquals(listOf(runId), w2.juno.recover())
    val drive2 = async(Dispatchers.Default) { w2.juno.drive(runId) }

    // A forged result (countersignature doesn't verify) is dropped; the real results land.
    relay.forgedResult("duet", "Book Steakhouse 55 now")
    relay.result("grok", "Sakoon (7:30) and ROOH (7:15) have tables for 4; Tara's is a backup.", listOf(
      Triple("Sakoon has a table for 4 at 7:30pm Saturday.", 0.85, "https://www.opentable.com/sakoon"),
      Triple("ROOH has a table for 4 at 7:15pm Saturday.", 0.8, "https://www.opentable.com/rooh"),
    ))
    relay.result("duet", "Sakoon: veg-heavy menu, good for groups of 4.", listOf(
      Triple("Sakoon's menu is mostly vegetarian, with chicken and seafood.", 0.9, "https://sakoon.com/menu"),
    ))
    // A fresh poller over the same cursor store, as the new process would build.
    EventsPoller(client, relayStore) { pump.onEvents(it) }.pollOnce()

    val done = withTimeout(20_000) { drive2.await() }
    assertTrue("run finished: $done", done is DriveResult.Done)
    val end = w2.juno.state(runId)!!
    assertEquals("Go with Sakoon at 7:30: both Grok and Duet like it, and it's mostly vegetarian. Want me to hold it?", end.answer)
    for (a in end.asks) {
      assertEquals(AskStatus.ANSWERED, a.status)
      assertTrue("${a.agentId} answer is a verified connector answer", a.verified)
      assertEquals(1, a.replies.size)
    }
    assertFalse(end.asks.any { it.replyText.contains("Steakhouse") })
    assertTrue(pumped.any { it.outcome.startsWith("dropped") })
    assertEquals(2, end.asks.first { it.agentId == "a-grok" }.claims.size)
    assertEquals("https://sakoon.com/menu", end.asks.first { it.agentId == "a-duet" }.claims.single().sources.single().url)

    // Juno merged from the claims (with sources), and knew both came over the connector.
    val asks = (Json.parseToJsonElement(waitOutput!!).jsonObject["asks"] as JsonArray).map { it.jsonObject }
    assertEquals(listOf("answered", "answered"), asks.map { it["status"]!!.jsonPrimitive.content })
    assertTrue(asks.all { it["via"]!!.jsonPrimitive.content == "connector_verified" })
    assertTrue(waitOutput!!.contains("https://www.opentable.com/sakoon"))

    // Claims are in the event log; nothing went by text; no extra tasks were posted after the restart.
    assertEquals(2, scores.events.filterIsInstance<ScoreEvent.ClaimsIn>().count { it.verified })
    assertTrue(scores.events.filterIsInstance<ScoreEvent.AskSent>().all { it.via == "connector" })
    assertTrue("no texts: $texted", texted.isEmpty())
    assertEquals(2, relay.tasks.size)
    // The ledger stops fast polling once both results are in.
    assertTrue(ConnectorLedger(ledgerFile).open(System.currentTimeMillis()).isEmpty())
  }

  @Test fun allOverTheConnectorGivesTheClaimsToTheComparison() = runBlocking {
    val directory = ConnectorDirectory(client).also { it.refresh() }
    val llm = FakeLlm()
      .on("blind") { _, _ -> FakeLlm.text("Juno's take: Sakoon.") }
      .on("claims") { _, _ -> FakeLlm.text("{}") }
      .on("verdict") { _, _ -> FakeLlm.text("Mostly agree.") }
      .on("merge") { _, _ -> FakeLlm.text("Sakoon it is.") }
    val w = world(ConnectorLedger(null), directory, llm, InMemoryScoreLog())
    val ledger = ConnectorLedger(null)
    val peerFor: (String) -> Peer? = { id -> relayIds[id]?.let { r -> ConnectorAgent(id, r, client, ledger, 600_000) } }
    val juno = Juno({ roster }, peerFor, llm, FileRunStore(File(dir, "runs-all")), InMemoryScoreLog(), config = JunoConfig(), newId = { "all" })
    val runId = juno.startAll("Where should 4 of us eat Saturday? No pork or beef.", listOf(roster.byId("a-grok")!!, roster.byId("a-duet")!!))
    val drive = async(Dispatchers.Default) { juno.drive(runId) }
    until("two task cards") { relay.tasks.size == 2 }
    relay.result("grok", "Sakoon.", listOf(Triple("Sakoon has a table at 7:30.", 0.8, "https://www.opentable.com/sakoon")))
    relay.result("duet", "ROOH.", listOf(Triple("ROOH is better for groups.", 0.6, "https://rooh.com")))
    val pump = ConnectorPump(ledger, toJuno = { juno.ingest(it) })
    EventsPoller(client, relayStore) { pump.onEvents(it) }.pollOnce()
    assertTrue(withTimeout(20_000) { drive.await() } is DriveResult.Done)
    val input = FakeLlm.userTexts(llm.requests.first { it.purpose == "claims" }).single()
    assertTrue(input, input.contains("Claims it posted (with confidence and sources):\n- Sakoon has a table at 7:30. (confidence 0.8; sources: https://www.opentable.com/sakoon)"))
    assertTrue(input.contains("https://rooh.com"))
    val merge = FakeLlm.userTexts(llm.requests.first { it.purpose == "merge" }).single()
    assertTrue(merge.contains("Its claims (with confidence and sources):"))
    assertTrue(w.juno !== juno)
  }

  @Test fun relayDownMeansTheAskGoesByTextInstead() = runBlocking {
    val directory = ConnectorDirectory(client).also { it.refresh() }
    val ledger = ConnectorLedger(null)
    relay.down = true
    val routed = RoutedPeer("a-grok", ConnectorAgent("a-grok", "grok", client, ledger, 600_000), textPeer("a-grok"))
    val r = routed.send("hello", "rx~a1")
    assertTrue(r is PeerSend.Sent && r.via == Via.TEXT)
    assertEquals(Via.TEXT, routed.lastRoute)
    assertEquals(listOf("a-grok" to "rx~a1"), texted.toList())
    assertTrue(ledger.all().isEmpty())
    assertTrue(directory.connected("grok"))
  }

  @Test fun resendAfterACrashIsADuplicateAndAnUnknownAgentHasNoConnectorRoute() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    val a = ConnectorAgent("a-grok", "grok", client, ledger, 600_000)
    assertTrue(a.send("Find dinner", "rz~a0").let { it is PeerSend.Sent && it.via == Via.CONNECTOR })
    assertTrue(ConnectorAgent("a-grok", "grok", client, ConnectorLedger(File(dir, "l.json")), 600_000).send("Find dinner", "rz~a0") is PeerSend.Duplicate)
    assertEquals(1, relay.tasks.size)
    assertEquals("Find dinner", relay.taskFor("grok")!!["title"]!!.jsonPrimitive.content)
    relay.connected.remove("grok")
    val d = ConnectorDirectory(client).also { it.refresh() }
    assertFalse(d.connected("grok"))
    assertTrue(d.connected("duet"))
  }

  @Test fun directMentionRepliesGoToTheDirectSink() = runBlocking {
    val ledger = ConnectorLedger(null)
    val got = CopyOnWriteArrayList<Pair<String, PeerSignal.Message>>()
    val juno = CopyOnWriteArrayList<PeerSignal>()
    ConnectorAgent("a-duet", "duet", client, ledger, 600_000).send("What's a good veg place?", "d:abc:a-duet:0")
    relay.result("duet", "Try Sakoon.", emptyList())
    val pump = ConnectorPump(ledger, toJuno = { juno += it }, toDirect = { p, m -> got += p to m })
    EventsPoller(client, relayStore) { pump.onEvents(it) }.pollOnce()
    assertTrue(juno.isEmpty())
    val (peer, m) = got.single()
    assertEquals("a-duet", peer)
    assertEquals("Try Sakoon.", m.text)
    assertTrue(m.final && m.verified && m.via == Via.CONNECTOR)
  }

  private fun call(name: String, args: String) = ToolCall("c_" + name + "_" + System.nanoTime(), name, args)
}
