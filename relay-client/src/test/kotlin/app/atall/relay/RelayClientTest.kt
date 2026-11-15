package app.atall.relay

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The client against a fake relay that checks signatures the way the contract says. */
class RelayClientTest {
    private val server = MockWebServer()
    private val phone = SoftwareDeviceKey()
    private val relay = SoftwareDeviceKey()
    private val store = InMemoryRelayStore()
    private lateinit var client: RelayClient
    private val now = 1763229600L

    @Before fun up() {
        server.start()
        client = RelayClient(server.url("/functions/v1/atall").toString(), phone, store, clock = { now })
    }

    @After fun down() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun pairedClient() {
        store.deviceId = "dev_1"
        store.relayPublicKeySpkiB64 = B64.std(relay.publicKeySpki)
    }

    /** What the relay does: rebuild the signed string from the request and check it with the paired key. */
    private fun assertSignedByPhone(r: RecordedRequest) {
        assertEquals("dev_1", r.getHeader("X-Atall-Device"))
        assertEquals(now.toString(), r.getHeader("X-Atall-Ts"))
        val path = r.requestUrl!!.encodedPath.removePrefix("/functions/v1/atall") + (r.requestUrl!!.encodedQuery?.let { "?$it" } ?: "")
        val s = RequestSigner.signedString(r.method!!, path, now, r.body.readByteArray())
        assertTrue("phone sig must verify over $s", Ecdsa.verify(Ecdsa.publicKey(phone.publicKeySpki), s.toByteArray(), B64.decodeAny(r.getHeader("X-Atall-Sig")!!)))
    }

    private fun relayEvent(cursor: Int, agent: String, type: String = "result", tamper: Boolean = false): JsonObject {
        val e = buildJsonObject {
            put("cursor", cursor); put("type", type); put("task_id", "tk_1"); put("agent_id", agent); put("conn_id", "cn_$agent")
            put("client_name", agent); put("at", "2026-11-15T18:00:0${cursor % 10}Z"); put("payload", buildJsonObject { put("summary", "from $agent") })
        }
        val sig = B64.url(Ecdsa.derToRaw(relay.sign(RelayVerifier.signedBytes(e))))
        val out = JsonObject(e + ("relay_sig" to JsonPrimitive(sig)))
        return if (tamper) JsonObject(out + ("agent_id" to JsonPrimitive("grok"))) else out
    }

    @Test fun pairSendsTheSpkiUnsignedAndStoresDeviceAndRelayKey() = runBlocking {
        server.enqueue(json(201, """{"device_id":"dev_9","relay_public_key_spki_b64":"${B64.std(relay.publicKeySpki)}","relay_key_alg":"ES256"}"""))
        val p = client.pair(" 123456 ", "Pixel 9")
        val r = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/pair", r.path)
        assertNull(r.getHeader("X-Atall-Sig"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("123456", body["owner_code"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", body["device_name"]!!.jsonPrimitive.content)
        assertTrue(B64.decodeAny(body["public_key_spki_b64"]!!.jsonPrimitive.content).contentEquals(phone.publicKeySpki))
        assertEquals("dev_9", p.deviceId)
        assertEquals("dev_9", store.deviceId)
        assertTrue(client.paired)
    }

    @Test fun badOwnerCodeIsARelayError() = runBlocking {
        server.enqueue(json(403, """{"error":"bad owner code"}"""))
        try { client.pair("nope", "Pixel"); fail() } catch (e: RelayException) {
            assertEquals(403, e.status); assertEquals("bad owner code", e.error)
        }
        assertFalse(client.paired)
    }

    @Test fun postTaskIsSignedAndOmitsUnsetFields() = runBlocking {
        pairedClient()
        server.enqueue(json(201, """{"task_id":"tk_1","ref":"ab12c"}"""))
        val created = client.postTask(TaskCard(ref = "ab12c", title = "Test", goal = "Say hi", deadline = "2026-11-15T19:00:00Z"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/functions/v1/atall/phone/tasks", r.path)
        val sent = r.body.clone().readUtf8()
        assertEquals("""{"ref":"ab12c","title":"Test","goal":"Say hi","to":["all"],"deadline":"2026-11-15T19:00:00Z"}""", sent)
        assertSignedByPhone(r)
        assertEquals(TaskCreated("tk_1", "ab12c"), created)
    }

    @Test fun eventsAreVerifiedOneByOne() = runBlocking {
        pairedClient()
        val events = listOf(relayEvent(1, "kdot"), relayEvent(2, "duet", tamper = true))
        server.enqueue(json(200, """{"events":[${events.joinToString(",")}],"next_cursor":2}"""))
        val page = client.events(JsonPrimitive(0))
        val r = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/events?after=0", r.path)
        assertSignedByPhone(r)
        assertEquals(listOf(true, false), page.events.map { it.verified })
        assertEquals("kdot", page.events[0].agentId)
        assertEquals("from kdot", page.events[0].payload.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), page.nextCursor)
    }

    @Test fun pollerMovesTheCursorAndAcksIt() = runBlocking {
        pairedClient()
        val seen = mutableListOf<RelayEvent>()
        val poller = EventsPoller(client, store) { seen += it }
        server.enqueue(json(200, """{"events":[${relayEvent(5, "kdot")},${relayEvent(6, "hark")}],"next_cursor":6}"""))
        server.enqueue(json(200, """{"ok":true}"""))
        server.enqueue(json(200, """{"events":[],"next_cursor":6}"""))
        assertEquals(2, poller.pollOnce().size)
        val first = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/events", first.path) // no cursor yet
        val ack = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/ack", ack.path)
        assertEquals("""{"cursor":6}""", ack.body.clone().readUtf8())
        assertSignedByPhone(ack)
        assertEquals("6", store.cursorJson)
        assertEquals(0, poller.pollOnce().size)
        assertEquals("/functions/v1/atall/phone/events?after=6", server.takeRequest().path)
        assertEquals(listOf("kdot", "hark"), seen.map { it.agentId })
        assertTrue(seen.all { it.verified })
    }

    @Test fun stringCursorsStayStrings() = runBlocking {
        pairedClient()
        val poller = EventsPoller(client, store) {}
        server.enqueue(json(200, """{"events":[${relayEvent(1, "kdot")}],"next_cursor":"c_01 x"}"""))
        server.enqueue(json(200, "{}"))
        poller.pollOnce()
        server.takeRequest()
        assertEquals("""{"cursor":"c_01 x"}""", server.takeRequest().body.readUtf8())
        server.enqueue(json(200, """{"events":[],"next_cursor":"c_01 x"}"""))
        poller.pollOnce()
        val r = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/events?after=c_01+x", r.path)
        assertSignedByPhone(r)
    }

    @Test fun agentsAnswersAndRevoke() = runBlocking {
        pairedClient()
        server.enqueue(json(200, """[{"agent_id":"kdot","conn_id":"cn_1","client_name":"ChatGPT","kind":"oauth","created_at":"2026-11-15T17:40:00Z","last_seen":"2026-11-15T18:01:00Z","last_tool":"atall_post_result"},{"agent_id":"hark","conn_id":"cn_2"}]"""))
        val agents = client.agents()
        assertEquals(listOf("kdot", "hark"), agents.map { it.agentId })
        assertEquals("atall_post_result", agents[0].lastTool)
        assertSignedByPhone(server.takeRequest())

        server.enqueue(json(200, "{}"))
        client.answer("tk_1", "q_1", "Under $50")
        val a = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/answers", a.path)
        assertEquals("""{"task_id":"tk_1","question_id":"q_1","text":"Under $50"}""", a.body.clone().readUtf8())
        assertSignedByPhone(a)

        server.enqueue(json(200, "{}"))
        client.revoke("cn_1")
        val rv = server.takeRequest()
        assertEquals("/functions/v1/atall/phone/connections/cn_1/revoke", rv.path)
        assertEquals(0L, rv.bodySize)
        assertSignedByPhone(rv)
    }

    @Test fun unauthorizedIsFlagged() = runBlocking {
        pairedClient()
        server.enqueue(json(401, """{"error":"stale ts"}"""))
        try { client.agents(); fail() } catch (e: RelayException) { assertTrue(e.unauthorized); assertEquals("stale ts", e.error) }
    }

    @Test fun unpairedClientRefusesSignedCalls() = runBlocking {
        try { client.agents(); fail() } catch (e: RelayException) { assertEquals("not paired", e.error) }
        assertEquals(0, server.requestCount)
    }

    @Test fun taskRefValidation() {
        repeat(50) { assertTrue(TaskCard.REF.matches(TaskCard.newRef())) }
        try { TaskCard(ref = "AB", title = "t", goal = "g"); fail() } catch (_: IllegalArgumentException) {}
    }
}
