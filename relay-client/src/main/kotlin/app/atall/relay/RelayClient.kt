package app.atall.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** What the phone keeps after pairing, plus the events cursor. Not secret: the private key never leaves the Keystore. */
interface RelayStore {
    var deviceId: String?
    /** The relay's ES256 public key (SPKI DER, base64) from /phone/pair. */
    var relayPublicKeySpkiB64: String?
    /** The last acknowledged cursor, as its JSON text (`12` or `"c_12"`), or null before the first poll. */
    var cursorJson: String?

    val paired: Boolean get() = !deviceId.isNullOrBlank() && !relayPublicKeySpkiB64.isNullOrBlank()
}

class InMemoryRelayStore : RelayStore {
    override var deviceId: String? = null
    override var relayPublicKeySpkiB64: String? = null
    override var cursorJson: String? = null
}

/**
 * The phone's client for the Atall relay (C1 contract). Every /phone/ call except [pair] is signed with [key].
 * Calls run on Dispatchers.IO and throw [RelayException] on a non-2xx answer, IOException when the relay is unreachable.
 */
class RelayClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val key: DeviceKey,
    private val store: RelayStore,
    private val http: OkHttpClient = defaultHttp(),
    /** Unix seconds. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val base = baseUrl.trimEnd('/')
    private val signer = RequestSigner(key)

    val paired: Boolean get() = store.paired

    /** The verifier for event countersignatures, or null before pairing. */
    fun verifier(): RelayVerifier? = store.relayPublicKeySpkiB64?.let { runCatching { RelayVerifier(B64.decodeAny(it)) }.getOrNull() }

    /** One time per phone: registers the device key with the owner code. Unsigned. Saves device_id and the relay key. */
    suspend fun pair(ownerCode: String, deviceName: String): Pairing {
        val body = buildJsonObject {
            put("owner_code", ownerCode.trim())
            put("device_name", deviceName)
            put("public_key_spki_b64", B64.std(key.publicKeySpki))
        }
        val res = call("POST", "/phone/pair", JSON.encodeToString(JsonObject.serializer(), body).toByteArray(), signed = false)
        val p = JSON.decodeFromJsonElement<Pairing>(res)
        require(p.relayKeyAlg.equals("ES256", ignoreCase = true)) { "unexpected relay key alg ${p.relayKeyAlg}" }
        RelayVerifier(B64.decodeAny(p.relayPublicKeySpkiB64)) // fails here, not at the first event, if the key is unusable
        store.deviceId = p.deviceId
        store.relayPublicKeySpkiB64 = p.relayPublicKeySpkiB64
        store.cursorJson = null
        return p
    }

    suspend fun postTask(card: TaskCard): TaskCreated =
        JSON.decodeFromJsonElement(call("POST", "/phone/tasks", JSON.encodeToString(TaskCard.serializer(), card).toByteArray()))

    /** GET /phone/events?after=<cursor>. Each event's relay_sig is checked; a bad or missing one gives verified=false. */
    suspend fun events(after: JsonPrimitive? = null): EventsPage {
        val path = if (after == null || after is JsonNull) "/phone/events" else "/phone/events?after=" + URLEncoder.encode(after.content, "UTF-8")
        val res = call("GET", path, ByteArray(0)).jsonObject
        val verifier = verifier()
        val events = (res["events"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { e ->
            RelayEvent(e, verified = verifier?.verify(e) == true)
        }
        val next = (res["next_cursor"] as? JsonPrimitive)?.takeUnless { it is JsonNull }
        return EventsPage(events, next)
    }

    suspend fun ack(cursor: JsonPrimitive) {
        call("POST", "/phone/ack", JSON.encodeToString(JsonObject.serializer(), JsonObject(mapOf("cursor" to cursor))).toByteArray())
    }

    /** Answers an atall_ask question; it shows up in that agent's atall_inbox. */
    suspend fun answer(taskId: String, questionId: String, text: String) {
        val body = buildJsonObject { put("task_id", taskId); put("question_id", questionId); put("text", text) }
        call("POST", "/phone/answers", JSON.encodeToString(JsonObject.serializer(), body).toByteArray())
    }

    suspend fun agents(): List<RelayAgent> {
        val res = call("GET", "/phone/agents", ByteArray(0))
        val list = when (res) {
            is JsonArray -> res
            is JsonObject -> res["agents"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { runCatching { JSON.decodeFromJsonElement<RelayAgent>(it) }.getOrNull() }
    }

    /** Cuts that connection's tokens now; its next tool call gets 401. */
    suspend fun revoke(connId: String) {
        call("POST", "/phone/connections/" + URLEncoder.encode(connId, "UTF-8").replace("+", "%20") + "/revoke", ByteArray(0))
    }

    private suspend fun call(method: String, pathAndQuery: String, body: ByteArray, signed: Boolean = true): JsonElement =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(base + pathAndQuery)
            if (signed) {
                val device = store.deviceId ?: throw RelayException(0, "not paired")
                signer.sign(device, method, pathAndQuery, body, clock()).headers().forEach { (k, v) -> rb.header(k, v) }
            }
            when (method) {
                "GET" -> rb.get()
                else -> rb.method(method, body.toRequestBody(JSON_TYPE))
            }
            http.newCall(rb.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = runCatching { if (text.isBlank()) JsonNull else JSON.parseToJsonElement(text) }.getOrDefault(JsonNull)
                if (!r.isSuccessful) {
                    val err = ((json as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull ?: r.message.ifBlank { "HTTP ${r.code}" }
                    throw RelayException(r.code, err)
                }
                json
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall"
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private val JSON_TYPE = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
