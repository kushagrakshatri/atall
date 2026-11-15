package app.atall.coordinator.adapters

import app.atall.relay.B64
import app.atall.relay.Ecdsa
import app.atall.relay.RelayVerifier
import app.atall.relay.RequestSigner
import app.atall.relay.SoftwareDeviceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-process Atall relay for JVM tests: the C1 phone contract (signed /phone/tasks, /phone/events with ES256
 * countersignatures, /phone/ack, /phone/agents) plus test hooks that play the agents' MCP tool calls (claim, progress,
 * post_result). Phone signatures are checked the way the real relay does; a bad one is a 401.
 */
class FakeRelay(
  private val phoneKey: () -> ByteArray?,
  val connected: MutableList<String> = mutableListOf("grok", "duet"),
) {
  val server = MockWebServer()
  val relayKey = SoftwareDeviceKey()
  val tasks = CopyOnWriteArrayList<JsonObject>()
  private val events = CopyOnWriteArrayList<JsonObject>()
  private val cursor = AtomicInteger()
  private val ids = AtomicInteger()
  val rejected = AtomicInteger()
  @Volatile var down = false
  val baseUrl: String get() = server.url("/functions/v1/atall").toString()

  fun start(): FakeRelay {
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handle(request) }
    server.start()
    return this
  }

  fun stop() = server.shutdown()

  // ---------------------------------------------------------------- the agents' side (their MCP tool calls)

  fun taskFor(agent: String): JsonObject? = tasks.lastOrNull { t -> agent in (t["to"] as JsonArray).map { it.jsonPrimitive.content } }

  fun claim(agent: String) = emit("progress", agent, buildJsonObject { put("ref", ref(agent)); put("state", "WORKING"); put("claimed", true); put("message", "$agent started working on #${ref(agent)}") })

  fun progress(agent: String, message: String) = emit("progress", agent, buildJsonObject { put("ref", ref(agent)); put("state", "WORKING"); put("message", message) })

  fun result(agent: String, summary: String, claims: List<Triple<String, Double, String>>, status: String = "done") = emit("result", agent, buildJsonObject {
    put("result_id", "res_${ids.incrementAndGet()}"); put("ref", ref(agent)); put("status", status); put("state", "DONE"); put("summary", summary)
    put("claims", buildJsonArray {
      claims.forEach { (text, conf, url) -> add(buildJsonObject { put("text", text); put("confidence", conf); put("sources", buildJsonArray { add(buildJsonObject { put("url", url) }) }) }) }
    })
    put("artifacts", JsonArray(emptyList())); put("replaced_previous", false); put("recorded_at", Instant.now().toString())
  })

  /** An event whose countersignature is broken (a forged or altered event): the phone must drop it. */
  fun forgedResult(agent: String, summary: String) {
    val e = signed(event("result", agent, buildJsonObject { put("ref", ref(agent)); put("status", "done"); put("summary", summary) }))
    events += JsonObject(e + ("payload" to buildJsonObject { put("ref", ref(agent)); put("summary", "$summary (altered)") }))
  }

  private fun ref(agent: String) = taskFor(agent)!!["ref"]!!.jsonPrimitive.content

  private fun emit(type: String, agent: String, payload: JsonObject) { events += signed(event(type, agent, payload)) }

  private fun event(type: String, agent: String, payload: JsonObject) = buildJsonObject {
    put("cursor", cursor.incrementAndGet()); put("type", type); put("task_id", taskFor(agent)!!["task_id"]!!.jsonPrimitive.content)
    put("agent_id", agent); put("conn_id", "cn_$agent"); put("client_name", agent); put("at", Instant.now().toString()); put("payload", payload)
  }

  private fun signed(e: JsonObject): JsonObject =
    JsonObject(e + ("relay_sig" to JsonPrimitive(B64.url(Ecdsa.derToRaw(relayKey.sign(RelayVerifier.signedBytes(e)))))))

  // ---------------------------------------------------------------- the phone's side (HTTP)

  private fun json(code: Int, body: JsonElement) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
  private fun error(code: Int, msg: String) = json(code, buildJsonObject { put("error", msg) })

  private fun handle(r: RecordedRequest): MockResponse {
    if (down) return MockResponse().setResponseCode(503).setBody("{\"error\":\"down\"}")
    val path = r.requestUrl!!.encodedPath.removePrefix("/functions/v1/atall") + (r.requestUrl!!.encodedQuery?.let { "?$it" } ?: "")
    val body = r.body.readByteArray()
    val key = phoneKey() ?: return error(401, "not paired")
    val ts = r.getHeader(RequestSigner.HEADER_TS)?.toLongOrNull() ?: return error(401, "no ts")
    val sig = r.getHeader(RequestSigner.HEADER_SIG) ?: return error(401, "no sig")
    val s = RequestSigner.signedString(r.method!!, path, ts, body)
    if (!Ecdsa.verify(Ecdsa.publicKey(key), s.toByteArray(), B64.decodeAny(sig))) { rejected.incrementAndGet(); return error(401, "bad signature") }
    return when {
      r.method == "POST" && path == "/phone/tasks" -> {
        val card = Json.parseToJsonElement(body.decodeToString()).jsonObject
        val id = "tsk_${ids.incrementAndGet()}"
        tasks += JsonObject(card + mapOf("task_id" to JsonPrimitive(id), "phone_sig" to JsonPrimitive(sig)))
        json(201, buildJsonObject { put("task_id", id); put("ref", card["ref"]!!.jsonPrimitive.content) })
      }
      r.method == "GET" && path.startsWith("/phone/events") -> {
        val after = r.requestUrl!!.queryParameter("after")?.toIntOrNull() ?: 0
        val list = events.filter { it["cursor"]!!.jsonPrimitive.content.toInt() > after }
        json(200, buildJsonObject { put("events", JsonArray(list)); put("next_cursor", list.lastOrNull()?.get("cursor")?.jsonPrimitive?.content?.toInt() ?: after) })
      }
      r.method == "POST" && path == "/phone/ack" -> json(200, buildJsonObject { put("ok", true) })
      r.method == "GET" && path == "/phone/agents" -> json(200, JsonArray(connected.map { a ->
        buildJsonObject { put("agent_id", a); put("conn_id", "cn_$a"); put("client_name", a); put("kind", "oauth"); put("last_seen", Instant.now().toString()) }
      }))
      else -> error(404, "not found")
    }
  }

  /** The text of the goal the relay holds for [agent]'s task. */
  fun goalFor(agent: String): String? = taskFor(agent)?.get("goal")?.jsonPrimitive?.contentOrNull
  fun toOf(t: JsonObject): List<String> = t["to"]!!.jsonArray.map { it.jsonPrimitive.content }
}
