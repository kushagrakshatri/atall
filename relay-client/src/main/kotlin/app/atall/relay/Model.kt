package app.atall.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom

/** A task card (POST /phone/tasks). `to` is ["all"] or agent ids. */
@Serializable
data class TaskCard(
    val ref: String,
    val title: String,
    val goal: String,
    val to: List<String> = listOf(ALL),
    val context: String? = null,
    val constraints: List<String>? = null,
    @SerialName("done_criteria") val doneCriteria: List<String>? = null,
    @SerialName("dont_share") val dontShare: List<String>? = null,
    /** ISO 8601 instant. */
    val deadline: String? = null,
    /** "claims" asks agents for claims with sources. */
    val output: String? = null,
) {
    init {
        require(REF.matches(ref)) { "ref must be 4-6 of [a-z0-9]" }
        require(to.isNotEmpty()) { "to must name \"all\" or at least one agent" }
    }

    companion object {
        const val ALL = "all"
        val REF = Regex("[a-z0-9]{4,6}")
        private val rnd = SecureRandom()
        fun newRef(len: Int = 5): String = buildString { repeat(len) { append("abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)]) } }
    }
}

@Serializable
data class TaskCreated(@SerialName("task_id") val taskId: String, val ref: String)

@Serializable
data class Pairing(
    @SerialName("device_id") val deviceId: String,
    @SerialName("relay_public_key_spki_b64") val relayPublicKeySpkiB64: String,
    @SerialName("relay_key_alg") val relayKeyAlg: String = "ES256",
)

/** One connected agent connection (GET /phone/agents). */
@Serializable
data class RelayAgent(
    @SerialName("agent_id") val agentId: String,
    @SerialName("conn_id") val connId: String,
    @SerialName("client_name") val clientName: String? = null,
    val kind: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("last_tool") val lastTool: String? = null,
    /** C3: the agent subscribed to MCP Events (`task.assigned`), so the relay wakes it when a card is posted. */
    val events: Boolean? = null,
)

/**
 * One relay event. [raw] is the event exactly as received (what [verified] was computed over); [cursor] keeps its JSON
 * type (number or string) so an ack echoes it back unchanged.
 */
data class RelayEvent(
    val raw: JsonObject,
    val verified: Boolean,
) {
    val cursor: JsonPrimitive get() = raw["cursor"] as? JsonPrimitive ?: JsonNull
    val type: String get() = str("type") ?: ""
    val taskId: String? get() = str("task_id")
    val agentId: String get() = str("agent_id") ?: ""
    val connId: String? get() = str("conn_id")
    val clientName: String? get() = str("client_name")
    val at: String? get() = str("at")
    val payload: JsonElement get() = raw["payload"] ?: JsonNull

    private fun str(k: String) = (raw[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    companion object {
        const val RESULT = "result"
        const val PROGRESS = "progress"
        const val QUESTION = "question"
        const val CLAIM = "claim"
        const val APPROVAL_REQUEST = "approval_request"
        const val PRESENCE = "presence"
        const val MANIFEST = "manifest"
    }
}

data class EventsPage(val events: List<RelayEvent>, val nextCursor: JsonPrimitive?)

/** A relay error: [status] is the HTTP status, [error] the relay's `{error}` text. 401 = signature/device refused. */
class RelayException(val status: Int, val error: String) : Exception("relay $status: $error") {
    val unauthorized get() = status == 401
    val rateLimited get() = status == 429
}
