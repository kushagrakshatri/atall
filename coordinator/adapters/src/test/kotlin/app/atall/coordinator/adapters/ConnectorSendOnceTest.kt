package app.atall.coordinator.adapters

import app.atall.coordinator.PeerSend
import app.atall.relay.B64
import app.atall.relay.EventsPoller
import app.atall.relay.InMemoryRelayStore
import app.atall.relay.RelayClient
import app.atall.relay.SoftwareDeviceKey
import app.atall.relay.TaskCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.1.9.1 (Sentry run re00283ca): every connector ask was posted twice; Grok's second post got a 409 "ref already
 * used", the ask was marked FAILED, its ledger entry dropped, and Grok was never woken. One POST per ask, a 409 on our
 * own ref is "sent" (and woken), and a failed post never drops a task that is on the relay.
 */
class ConnectorSendOnceTest {
  private val dir: File = Files.createTempDirectory("atall-once").toFile()
  private val phone = SoftwareDeviceKey()
  private val relay = FakeRelay({ phone.publicKeySpki })
  private val relayStore = InMemoryRelayStore()
  private lateinit var client: RelayClient

  @Before fun up() {
    relay.start()
    relayStore.deviceId = "dev_1"
    relayStore.relayPublicKeySpkiB64 = B64.std(relay.relayKey.publicKeySpki)
    client = RelayClient(relay.baseUrl, phone, relayStore)
  }

  @After fun down() { relay.stop(); dir.deleteRecursively() }

  @Test fun twoSendsOfOneAskAtOncePostOnce() = runBlocking {
    relay.postDelayMs = 300
    val ledger = ConnectorLedger(File(dir, "l.json"))
    // A new ConnectorAgent per send, as the app builds them (peerFor runs per flush); they share the ledger.
    fun agent() = ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000)
    val results = (1..3).map { async(Dispatchers.IO) { agent().sendCard("r1~a1", "Saturday", "Saturday plans?") } }.awaitAll()
    assertEquals("one POST for the ask", 1, relay.posts.get())
    assertEquals(1, relay.tasks.size)
    assertEquals(1, results.count { it is PeerSend.Sent })
    assertEquals(2, results.count { it is PeerSend.Duplicate })
    assertNotNull(ledger.get("r1~a1")!!.taskId)
    // And later: already posted, no POST.
    assertTrue(agent().sendCard("r1~a1", "Saturday", "Saturday plans?") is PeerSend.Duplicate)
    assertEquals(1, relay.posts.get())
  }

  @Test fun a409ForOurOwnRefIsSentNotFailed() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    // The card is already on the relay (an earlier post of this ask went through; its answer was lost).
    client.postTask(TaskCard(ref = "affcr", title = "t", goal = "g", to = listOf("grok"), deadline = "2030-01-01T00:00:00Z", output = "claims"))
    ledger.put(ConnectorTask("r2~a1", "p-grok", "grok", "affcr", null, postedAt = 1, deadlineAt = Long.MAX_VALUE))
    val r = ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000).sendCard("r2~a1", "t", "g")
    assertTrue("409 on our ref = sent, got $r", r is PeerSend.Sent)
    val t = ledger.get("r2~a1")!!
    assertTrue("kept and marked on the relay", t.onRelay && t.posted)
    // A later send of the same ask doesn't post again.
    assertTrue(ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000).sendCard("r2~a1", "t", "g") is PeerSend.Duplicate)
    assertEquals(2, relay.posts.get())
  }

  @Test fun a409OnAFreshPostIsSentAndKeptToo() = runBlocking {
    // Even with no earlier entry (the v0.1.9 race: the other post wrote the ledger and won at the relay), a 409 is sent.
    val ledger = ConnectorLedger(File(dir, "l.json"))
    relay.uniqueRefs = false
    val agent = ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000)
    assertTrue(agent.sendCard("r3~a1", "t", "g") is PeerSend.Sent)
    val ref = ledger.get("r3~a1")!!.ref
    // Simulate: the ledger lost the task id (the other post's answer), the relay has the ref.
    ledger.put(ledger.get("r3~a1")!!.copy(taskId = null))
    relay.uniqueRefs = true
    val r = ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000).sendCard("r3~a1", "t", "g")
    assertTrue(r is PeerSend.Sent)
    assertEquals(ref, ledger.get("r3~a1")!!.ref)
    assertTrue(ledger.get("r3~a1")!!.onRelay)
  }

  @Test fun aFailedPostNeverDropsATaskThatIsOnTheRelay() {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    ledger.put(ConnectorTask("r4~a1", "p-grok", "grok", "abcde", "tsk_9", postedAt = 1, deadlineAt = Long.MAX_VALUE))
    ledger.dropUnposted("r4~a1", "abcde")
    assertNotNull("posted (has a task id): kept", ledger.get("r4~a1"))
    ledger.put(ConnectorTask("r4~a2", "p-grok", "grok", "fghij", null, postedAt = 1, deadlineAt = Long.MAX_VALUE, onRelay = true))
    ledger.dropUnposted("r4~a2", "fghij")
    assertNotNull("on the relay (409): kept", ledger.get("r4~a2"))
    ledger.put(ConnectorTask("r4~a3", "p-grok", "grok", "klmno", null, postedAt = 1, deadlineAt = Long.MAX_VALUE))
    ledger.dropUnposted("r4~a3", "klmno")
    assertNull("never reached the relay: dropped", ledger.get("r4~a3"))
  }

  @Test fun aRelayErrorStillFailsAndDropsTheUnpostedEntry() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    relay.down = true
    val r = ConnectorAgent("p-grok", "grok", client, ledger, deadlineMs = 600_000).sendCard("r5~a1", "t", "g")
    assertTrue(r is PeerSend.Failed)
    assertNull(ledger.get("r5~a1"))
  }

  @Test fun aCardKnownOnlyByItsRefIsWokenAndGetsItsTaskIdFromEvents() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    client.postTask(TaskCard(ref = "affcr", title = "t", goal = "g", to = listOf("grok"), deadline = "2030-01-01T00:00:00Z", output = "claims"))
    val now = System.currentTimeMillis()
    ledger.put(ConnectorTask("r6~a1", "p-grok", "grok", "affcr", null, postedAt = now, deadlineAt = now + 600_000, onRelay = true))
    // The ladder wakes it (Grok: webhook) even though the task id isn't known.
    val webhooks = CopyOnWriteArrayList<String>()
    val sinks = object : WakeSinks {
      override suspend fun text(task: ConnectorTask, text: String) = WakeOutcome.UNAVAILABLE
      override suspend fun webhook(task: ConnectorTask): WakeOutcome { webhooks += "#${task.ref} ${task.taskId}"; return WakeOutcome.SENT }
      override suspend fun events(task: ConnectorTask, first: Boolean) = WakeOutcome.UNAVAILABLE
      override suspend fun tap(task: ConnectorTask, prompt: String, full: String?) = WakeOutcome.UNAVAILABLE
    }
    val notes = CopyOnWriteArrayList<LadderNote>()
    val cfg: (String) -> WakeConfig = { WakeDefaults.forAgent(it) }
    FallbackLadder(ledger, WakeDispatcher(cfg, sinks), policyFor = { LadderPolicy.of(cfg(it.relayAgentId), 600_000) },
      hasTextRoute = { false }, byText = { _, _ -> false }, note = { notes += it }).tick()
    assertEquals(listOf("#affcr null"), webhooks)
    assertFalse(notes.single().failed)
    // Grok claims it: the pump fills in the task id from the verified event.
    relay.claim("grok")
    val pump = ConnectorPump(ledger, toJuno = {})
    EventsPoller(client, relayStore) { pump.onEvents(it) }.pollOnce()
    assertEquals(relay.taskFor("grok")!!["task_id"].toString().trim('"'), ledger.get("r6~a1")!!.taskId)
  }

  @Test fun aWakeThatReachesNobodyIsMarkedFailed() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    val now = System.currentTimeMillis()
    ledger.put(ConnectorTask("r7~a1", "p-grok", "grok", "qrstu", "tsk_1", postedAt = now, deadlineAt = now + 600_000))
    val sinks = object : WakeSinks {
      override suspend fun text(task: ConnectorTask, text: String) = WakeOutcome.UNAVAILABLE
      override suspend fun webhook(task: ConnectorTask) = WakeOutcome.FAILED
      override suspend fun events(task: ConnectorTask, first: Boolean) = WakeOutcome.UNAVAILABLE
      override suspend fun tap(task: ConnectorTask, prompt: String, full: String?) = WakeOutcome.FAILED
    }
    val notes = CopyOnWriteArrayList<LadderNote>()
    val cfg: (String) -> WakeConfig = { WakeDefaults.forAgent(it) }
    FallbackLadder(ledger, WakeDispatcher(cfg, sinks), policyFor = { LadderPolicy.of(cfg(it.relayAgentId), 600_000) },
      hasTextRoute = { false }, byText = { _, _ -> false }, note = { notes += it }).tick()
    val n = notes.single()
    assertTrue(n.failed)
    assertEquals(WakeKind.WEBHOOK, n.kind)
    assertTrue(n.step, n.step.startsWith("wake webhook failed"))
  }
}
