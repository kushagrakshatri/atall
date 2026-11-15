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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C3 exit test, outage part, on the JVM: Juno asks Hark (who also has a text route) and Grok (connector only) over the
 * connector; the relay dies mid-run. After a minute of failed polls the ladder sends Hark's ask by text, Hark answers by
 * text, Grok times out, and the run completes. When the relay comes back, Hark's late connector result folds in as a
 * late reply (first answer wins).
 */
class RelayOutageRunTest {
  private val dir: File = Files.createTempDirectory("atall-c3-outage").toFile()
  private val phone = SoftwareDeviceKey()
  private val relay = FakeRelay({ phone.publicKeySpki }, connected = mutableListOf("hark", "grok"))
  private val relayStore = InMemoryRelayStore()
  private lateinit var client: RelayClient

  private val roster = Roster(listOf(
    AgentProfile("main", "Juno", AgentKind.MAIN),
    AgentProfile("a-hark", "Hark", AgentKind.TEXT, softTimeoutMs = 120_000, hardTimeoutMs = 600_000),
    AgentProfile("a-grok", "Grok", AgentKind.TEXT, softTimeoutMs = 1_000, hardTimeoutMs = 2_500),
  ))
  private val relayIds = mapOf("a-hark" to "hark", "a-grok" to "grok")
  private val texted = CopyOnWriteArrayList<Pair<String, String>>()

  @Before fun up() {
    relay.start()
    relayStore.deviceId = "dev_1"
    relayStore.relayPublicKeySpkiB64 = B64.std(relay.relayKey.publicKeySpki)
    client = RelayClient(relay.baseUrl, phone, relayStore)
  }

  @After fun down() { relay.stop(); dir.deleteRecursively() }

  private suspend fun until(what: String, ms: Long = 10_000, ok: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!ok()) { if (System.currentTimeMillis() > end) error("timed out waiting for $what"); delay(20) }
  }

  @Test fun aRelayKilledMidRunCompletesByText() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "connector.json"))
    val textPeer = object : Peer {
      override val id = "a-hark"
      override suspend fun send(text: String, correlationId: String): PeerSend { texted += "a-hark" to correlationId; return PeerSend.Sent(System.currentTimeMillis()) }
    }
    val peerFor: (String) -> Peer? = { id -> RoutedPeer(id, ConnectorAgent(id, relayIds.getValue(id), client, ledger, 600_000), if (id == "a-hark") textPeer else null) }
    val llm = FakeLlm().on("juno") { _, n ->
      when (n) {
        0 -> FakeLlm.tools(
          FakeLlm.call("ask_agent", """{"agent":"Hark","text":"Is Sakoon free Saturday 7:30 for 4? Don't book.","why":"calendar","money_or_account":false}"""),
          FakeLlm.call("ask_agent", """{"agent":"Grok","text":"Find a backup for Saturday dinner for 4.","why":"research","money_or_account":false}"""),
          FakeLlm.call("wait", """{"ask_ids":["rout~a0","rout~a1"],"max_seconds":30}"""),
        )
        else -> FakeLlm.tools(FakeLlm.call("reply", """{"text":"Sakoon at 7:30 works (Hark checked). Grok didn't answer in time."}"""))
      }
    }
    val juno = Juno({ roster }, peerFor, llm, FileRunStore(File(dir, "runs")), InMemoryScoreLog(), config = JunoConfig(quietWindowMs = 300), newId = { "out" })

    // The ladder: wake-ups recorded, the text rung goes through Hark's text route with the same ask id.
    val wakes = CopyOnWriteArrayList<String>()
    val sinks = object : WakeSinks {
      override suspend fun text(task: ConnectorTask, text: String) = WakeOutcome.SENT.also { wakes += "text ${task.relayAgentId}" }
      override suspend fun webhook(task: ConnectorTask) = WakeOutcome.SENT.also { wakes += "webhook ${task.relayAgentId}" }
      override suspend fun events(task: ConnectorTask, first: Boolean) = WakeOutcome.UNAVAILABLE
      override suspend fun tap(task: ConnectorTask, prompt: String, full: String?) = WakeOutcome.TAP.also { wakes += "tap ${task.relayAgentId}" }
    }
    val cfg: (String) -> WakeConfig = { WakeDefaults.forAgent(it) }
    val ladder = FallbackLadder(
      ledger, WakeDispatcher(cfg, sinks) { it == "a-hark" },
      policyFor = { LadderPolicy.of(cfg(it.relayAgentId), softTimeoutMs = 6 * 60_000) },
      hasTextRoute = { it == "a-hark" },
      byText = { t, _ ->
        val ask = juno.state(PeerLinesRunId.of(t.cid))!!.ask(t.cid)!!
        textPeer.send(ask.text, t.cid) is PeerSend.Sent
      },
    )
    val pump = ConnectorPump(ledger, toJuno = { juno.ingest(it) })
    val poller = EventsPoller(client, relayStore) { pump.onEvents(it) }

    val runId = juno.startCoordinated("Saturday dinner for 4 at Sakoon?", mustAsk = listOf(roster.byId("a-hark")!!, roster.byId("a-grok")!!))
    val drive = async(Dispatchers.Default) { juno.drive(runId) }
    until("two task cards") { relay.tasks.size == 2 }
    until("both asks sent") { juno.state(runId)!!.asks.all { it.sentAt != null } }
    ladder.tick()
    assertEquals(setOf("text hark", "webhook grok"), wakes.toSet())
    relay.claim("hark")
    poller.pollOnce()

    // ---- the relay dies mid-run.
    relay.down = true
    var downSince: Long? = null
    runCatching { poller.pollOnce() }.onFailure { downSince = System.currentTimeMillis() }
    assertTrue("poll failed", downSince != null)
    ladder.tick(relayDownSince = downSince)
    assertTrue("not an outage after one failed poll", texted.isEmpty())
    ladder.tick(relayDownSince = downSince!! - 61_000) // a minute of failures
    assertEquals(listOf("a-hark" to "rout~a0"), texted.toList())
    assertEquals("relay_down:text", ledger.get("rout~a0")!!.fallback)

    // Hark answers the text; Grok (connector only) times out; the run completes.
    juno.ingest(PeerSignal.Message("a-hark", "sms-1", "Yes, Sakoon has 7:30 Saturday for 4.", System.currentTimeMillis(), "rout~a0"))
    val done = withTimeout(20_000) { drive.await() }
    assertTrue("run finished: $done", done is DriveResult.Done)
    val end = juno.state(runId)!!
    assertEquals("Sakoon at 7:30 works (Hark checked). Grok didn't answer in time.", end.answer)
    val hark = end.asks.first { it.agentId == "a-hark" }
    assertEquals(AskStatus.ANSWERED, hark.status)
    assertEquals(Via.TEXT, hark.replies.single().via)
    assertTrue(end.asks.first { it.agentId == "a-grok" }.status != AskStatus.ANSWERED)
    assertEquals("Grok was never texted", 1, texted.size)

    // ---- the relay comes back; Hark's connector result arrives late and folds in (the text answer stays the answer).
    relay.down = false
    relay.result("hark", "Sakoon: 7:30 Saturday, table for 4.", emptyList())
    poller.pollOnce()
    val after = juno.state(runId)!!.asks.first { it.agentId == "a-hark" }
    assertEquals(AskStatus.ANSWERED, after.status)
    assertEquals(2, after.replies.size)
    assertTrue(after.replies.last().late)
    assertEquals("Sakoon at 7:30 works (Hark checked). Grok didn't answer in time.", juno.state(runId)!!.answer)
  }
}

/** Run id of an ask id ("<runId>~a<n>"), as the app's PeerLines does. */
private object PeerLinesRunId { fun of(cid: String) = cid.substringBefore('~') }
