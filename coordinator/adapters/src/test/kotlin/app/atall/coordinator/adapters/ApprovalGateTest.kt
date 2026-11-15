package app.atall.coordinator.adapters

import app.atall.relay.B64
import app.atall.relay.CanonicalJson
import app.atall.relay.Ecdsa
import app.atall.relay.EventsPoller
import app.atall.relay.InMemoryRelayStore
import app.atall.relay.RelayClient
import app.atall.relay.SoftwareDeviceKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C3 exit test, approval part, on the JVM: an agent asks to book (and pay); nothing goes back to the relay until the
 * user's fingerprint lets the approval key sign. The fake relay runs the real relay's token checks.
 */
class ApprovalGateTest {
  private val phone = SoftwareDeviceKey()
  private val relay = FakeRelay({ phone.publicKeySpki })
  private val store = InMemoryRelayStore()
  private lateinit var client: RelayClient
  private val ledger = ConnectorLedger(null)

  /** A software stand-in for the Keystore key that needs a fingerprint for every signature. */
  private class Fingerprint(val key: SoftwareDeviceKey = SoftwareDeviceKey()) : ApprovalSigner {
    @Volatile var present = false
    val prompts = AtomicInteger()
    override val publicKeySpkiB64: String get() = B64.std(key.publicKeySpki)
    override suspend fun signWithFingerprint(req: ApprovalRequest, data: ByteArray): ByteArray? {
      prompts.incrementAndGet()
      return if (present) key.sign(data) else null
    }
  }

  @Before fun up() {
    relay.start()
    store.deviceId = "dev_1"
    store.relayPublicKeySpkiB64 = B64.std(relay.relayKey.publicKeySpki)
    client = RelayClient(relay.baseUrl, phone, store)
  }

  @After fun down() = relay.stop()

  private suspend fun pumpInto(gate: ApprovalGate, statuses: MutableList<String> = CopyOnWriteArrayList()) {
    val pump = ConnectorPump(ledger, toJuno = { s -> (s as? app.atall.coordinator.PeerSignal.Message)?.status?.let { statuses += it } }, onApproval = gate::onRequest)
    EventsPoller(client, store) { pump.onEvents(it) }.pollOnce()
  }

  @Test fun aPurchaseCantGoAheadWithoutAFingerprint() = runBlocking {
    ConnectorAgent("a-grok", "grok", client, ledger, 600_000).send("Book Sakoon for 4 at 7:30", "rp~a0")
    val fp = Fingerprint()
    var keySaved = false
    val gate = ApprovalGate(ApprovalTransport.of(client), fp, keyRegistered = { keySaved }, onKeyRegistered = { keySaved = true })
    val approvalId = relay.approvalRequest("grok", "Book Sakoon 7:30 for 4 ($42 deposit)", amount = 42.0, currency = "USD")
    val statuses = CopyOnWriteArrayList<String>()
    pumpInto(gate, statuses)
    assertEquals(listOf("approval"), statuses.toList()) // the thread's "Wants your OK" line still shows
    val req = gate.pending.value.single()
    assertEquals(approvalId, req.approvalId)
    assertEquals("a-grok", req.peerId)
    assertTrue(req.spendsMoney)

    // Fingerprint cancelled or failed: nothing is sent, the request stays.
    assertEquals(ApprovalResult.Cancelled, gate.approve(approvalId))
    assertTrue(relay.decisions.isEmpty())
    assertNull("the approval key isn't even registered before a fingerprint", relay.approvalKey)
    assertEquals(1, gate.pending.value.size)

    // Fingerprint OK: a signed, single-use token bound to this request.
    fp.present = true
    val r = gate.approve(approvalId)
    assertTrue(r is ApprovalResult.Approved)
    val sent = relay.decisions.single()
    val token = sent["token"]!!.jsonObject
    assertEquals(approvalId, token["approval_id"]!!.jsonPrimitive.content)
    assertEquals("grok", token["agent_id"]!!.jsonPrimitive.content)
    assertEquals(req.taskId, token["task_id"]!!.jsonPrimitive.content)
    assertEquals(ApprovalRequest.actionHashOf(req.payload), token["action_hash"]!!.jsonPrimitive.content)
    assertEquals(42.0, token["max_amount"]!!.jsonObject["value"]!!.jsonPrimitive.double, 0.0)
    assertEquals("USD", token["max_amount"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
    assertEquals(1, token["uses"]!!.jsonPrimitive.content.toInt())
    val life = Instant.parse(token["exp"]!!.jsonPrimitive.content).epochSecond - Instant.parse(token["nbf"]!!.jsonPrimitive.content).epochSecond
    assertTrue("token lives 10 min (relay cap 15)", life in 1..900)
    // The signature is the approval key's, over the canonical token.
    assertTrue(Ecdsa.verify(Ecdsa.publicKey(fp.key.publicKeySpki), CanonicalJson.encode(token).toByteArray(), B64.decodeAny(sent["token_sig"]!!.jsonPrimitive.content)))
    assertEquals(fp.publicKeySpkiB64, relay.approvalKey)
    assertTrue(keySaved)
    assertTrue(gate.pending.value.isEmpty())

    // Single use on the phone too.
    assertEquals(ApprovalResult.AlreadyDecided, gate.approve(approvalId))
    assertEquals(1, relay.decisions.size)
    assertEquals(0, relay.refusedDecisions.get())
  }

  @Test fun denyNeedsNoFingerprintAndSendsNoToken() = runBlocking {
    ConnectorAgent("a-grok", "grok", client, ledger, 600_000).send("Book it", "rp~a1")
    val fp = Fingerprint()
    val gate = ApprovalGate(ApprovalTransport.of(client), fp)
    val id = relay.approvalRequest("grok", "Change the reservation name")
    pumpInto(gate)
    assertEquals(ApprovalResult.Denied, gate.deny(id))
    assertEquals(0, fp.prompts.get())
    val d = relay.decisions.single()
    assertEquals("deny", d["decision"]!!.jsonPrimitive.content)
    assertFalse(d.containsKey("token"))
    assertEquals(ApprovalResult.AlreadyDecided, gate.approve(id))
  }

  @Test fun aTokenFromTheWrongKeyIsRefusedByTheRelayAndTheRequestStays() = runBlocking {
    ConnectorAgent("a-grok", "grok", client, ledger, 600_000).send("Book it", "rp~a2")
    relay.approvalKey = B64.std(SoftwareDeviceKey().publicKeySpki) // another phone's key was pinned
    val fp = Fingerprint().also { it.present = true }
    val gate = ApprovalGate(ApprovalTransport.of(client), fp, keyRegistered = { true })
    val id = relay.approvalRequest("grok", "Pay $12 booking fee", 12.0, "USD")
    pumpInto(gate)
    val r = gate.approve(id)
    assertTrue("$r", r is ApprovalResult.Failed)
    assertEquals(1, relay.refusedDecisions.get())
    assertTrue(relay.decisions.isEmpty())
    assertEquals(1, gate.pending.value.size)
  }

  @Test fun unverifiedOrForeignApprovalRequestsAreNeverOffered() = runBlocking {
    val fp = Fingerprint()
    val gate = ApprovalGate(ApprovalTransport.of(client), fp)
    // A task this phone never posted (ledger is empty) asks for approval: dropped as not ours.
    relay.tasks += JsonObject(mapOf("task_id" to kotlinx.serialization.json.JsonPrimitive("tsk_x"), "ref" to kotlinx.serialization.json.JsonPrimitive("zzzz"),
      "to" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("grok")))))
    relay.approvalRequest("grok", "Buy something")
    pumpInto(gate)
    assertTrue(gate.pending.value.isEmpty())
  }
}
