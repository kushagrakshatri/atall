package app.atall.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Where function tools go in the request. */
enum class ToolPlacement {
    /** SIWC preview: one `{type:"additional_tools", role:"developer", tools:[…]}` input item (what Hither ships; verify on a real account). */
    ADDITIONAL_TOOLS_ITEM,
    /** Standard Responses `tools` array (API-key route). The SIWC preview says function tools must be grouped instead. */
    TOP_LEVEL,
}

/**
 * Makes a Responses body acceptable to the ChatGPT-plan route (SIWC preview limits) without changing its meaning:
 * `store:false`, `stream:true`, unsupported fields removed, `system` items -> `developer`, string input -> one user item,
 * function/custom/namespace tools moved per [ToolPlacement], unsupported hosted tools dropped.
 */
object RequestShaper {
    val UNSUPPORTED_FIELDS = setOf(
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature",
        "top_logprobs", "top_p", "truncation", "user", "previous_response_id", "service_tier",
    )
    val UNSUPPORTED_TOOL_TYPES = setOf(
        "image_generation", "file_search", "code_interpreter", "computer", "computer_use",
        "computer_use_preview", "mcp", "tool_search", "programmatic_tool_calling", "local_shell",
    )
    private val GROUPED = setOf("function", "custom", "namespace")

    fun shape(body: JsonObject, placement: ToolPlacement = ToolPlacement.ADDITIONAL_TOOLS_ITEM): JsonObject {
        val out = LinkedHashMap(body)
        UNSUPPORTED_FIELDS.forEach { out.remove(it) }
        out["store"] = JsonPrimitive(false)
        out["stream"] = JsonPrimitive(true)
        val items = when (val input = out["input"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("role", "user"); put("content", input.content) })
            is JsonArray -> input.map { el ->
                val o = el as? JsonObject
                if (o != null && o.str("role") == "system" && (o.str("type") ?: "message") == "message") JsonObject(o + ("role" to JsonPrimitive("developer"))) else el
            }
            else -> emptyList()
        }.toMutableList()
        val tools = (out.remove("tools") as? JsonArray).orEmpty()
        val grouped = mutableListOf<JsonElement>()
        val top = mutableListOf<JsonElement>()
        for (t in tools) {
            val kind = (t as? JsonObject)?.str("type") ?: continue
            if (kind in UNSUPPORTED_TOOL_TYPES) continue
            if (kind in GROUPED && placement == ToolPlacement.ADDITIONAL_TOOLS_ITEM) grouped += t else top += t
        }
        // Drop any additional_tools item the caller already built, then add ours first.
        if (grouped.isNotEmpty()) {
            items.removeAll { (it as? JsonObject)?.str("type") == "additional_tools" }
            items.add(0, buildJsonObject { put("type", "additional_tools"); put("role", "developer"); put("tools", JsonArray(grouped)) })
        }
        if (top.isNotEmpty()) out["tools"] = JsonArray(top)
        else if (grouped.isEmpty()) { out.remove("tool_choice"); out.remove("parallel_tool_calls") }
        out["input"] = JsonArray(items)
        return JsonObject(out)
    }
}

/** One Server-Sent Event. */
data class SseEvent(val event: String?, val data: String)

/** Minimal SSE reader (WHATWG rules that matter here: `data:` lines joined with \n, blank line dispatches, `:` comments). */
object SseParser {
    fun read(source: BufferedSource, onEvent: (SseEvent) -> Boolean) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (hasData) { if (!onEvent(SseEvent(event, data.toString()))) return }
                event = null; data.setLength(0); hasData = false
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            var value = if (line.contains(':')) line.substringAfter(':') else ""
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
                "event" -> event = value
            }
        }
        if (hasData) onEvent(SseEvent(event, data.toString()))
    }
}

/** What the caller sees while a response streams. */
sealed class StreamEvent {
    data class Created(val responseId: String?) : StreamEvent()
    data class TextDelta(val itemId: String?, val delta: String) : StreamEvent()
    data class ReasoningSummaryDelta(val delta: String) : StreamEvent()
    data class FunctionCallArgumentsDelta(val itemId: String?, val delta: String) : StreamEvent()
    data class OutputItemDone(val item: JsonObject) : StreamEvent()
    data class Other(val type: String, val raw: JsonObject) : StreamEvent()
}

data class FunctionCall(val callId: String, val name: String, val arguments: String, val item: JsonObject)

/** A finished response (`response.completed`, or `response.incomplete` with [incompleteReason]). */
data class ResponseResult(
    val response: JsonObject,
    val output: List<JsonObject>,
    val text: String,
    val functionCalls: List<FunctionCall>,
    val usage: JsonObject?,
    val incompleteReason: String?,
)

/** Lets the UI stop a stream (e.g. the user sent a new message). */
class Cancellation {
    private val call = AtomicReference<Call?>()
    @Volatile var cancelled = false; private set
    internal fun bind(c: Call) { call.set(c); if (cancelled) c.cancel() }
    fun cancel() { cancelled = true; call.get()?.cancel() }
}

/**
 * `POST {apiBase}/responses` with the signed-in token, streamed. Blocking; run on an IO thread.
 * The token is attached here and only for URLs under [SiwcConfig.apiBase]. A 401 before the stream opens
 * triggers one forced refresh and one retry. Success = `response.completed`; anything else throws.
 */
class ResponsesClient(
    private val config: SiwcConfig,
    private val http: OkHttpClient,
    private val tokens: TokenSource,
    private val placement: ToolPlacement = ToolPlacement.ADDITIONAL_TOOLS_ITEM,
    /** Extra headers (e.g. `originator`). Authorization is always set by this class. */
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    private val base: HttpUrl = config.apiBaseUrl

    fun url(path: String): HttpUrl = (base.toString().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl()

    internal fun guard(url: HttpUrl) {
        val prefix = base.encodedPath.trimEnd('/')
        val ok = url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            (url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))
        if (!ok) throw BlockedDestination(url.toString())
    }

    /** Available models for this account: `GET /models`, keeping `visibility == "list"`, server order. */
    fun listModels(): List<Pair<String, String>> {
        val body = execute(Request.Builder().url(url("models")).get(), null) { src -> src.readUtf8() }
        val o = OAuthClient.parseOrNull(body) as? JsonObject ?: return emptyList()
        (o["models"] as? JsonArray)?.let { arr ->
            return arr.mapNotNull { it as? JsonObject }
                .filter { (it.str("visibility") ?: "list") == "list" }
                .mapNotNull { m -> m.str("slug")?.let { it to (m.str("display_name") ?: it) } }
        }
        return (o["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("id")?.let { id -> id to id } }
    }

    fun stream(body: JsonObject, cancellation: Cancellation? = null, onEvent: (StreamEvent) -> Unit = {}): ResponseResult {
        val shaped = RequestShaper.shape(body, placement)
        val payload = LenientJson.encodeToString(JsonObject.serializer(), shaped)
        val builder = Request.Builder().url(url("responses"))
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
        return execute(builder, cancellation) { src -> consume(src, onEvent) }
    }

    private fun <T> execute(builder: Request.Builder, cancellation: Cancellation?, read: (BufferedSource) -> T): T {
        val url = builder.build().url
        guard(url)
        var forced = false
        while (true) {
            val token = tokens.accessToken(forced)
            val req = builder.header("Authorization", "Bearer $token").header("User-Agent", config.userAgent)
                .apply { extraHeaders.filterKeys { !it.equals("authorization", true) }.forEach { (k, v) -> header(k, v) } }
                .build()
            val call = http.newCall(req)
            cancellation?.bind(call)
            var opened = false
            try {
                call.execute().use { r ->
                    opened = true
                    if (r.code == 401 && !forced) { forced = true; return@use null }
                    if (!r.isSuccessful) throw apiError(r.code, r.body?.string(), r.header("x-request-id"))
                    return read(r.body!!.source())
                }
            } catch (e: IOException) {
                if (cancellation?.cancelled == true) throw TransientFailure("cancelled", cause = e)
                throw TransientFailure(if (opened) "stream_interrupted" else "offline", cause = e)
            }
        }
    }

    private fun consume(src: BufferedSource, onEvent: (StreamEvent) -> Unit): ResponseResult {
        var result: ResponseResult? = null
        var failure: ChatGptException? = null
        val doneItems = mutableListOf<JsonObject>()
        SseParser.read(src) { ev ->
            if (ev.data == "[DONE]") return@read false
            val o = OAuthClient.parseOrNull(ev.data) as? JsonObject ?: return@read true
            val type = o.str("type") ?: ev.event ?: ""
            when (type) {
                "response.created" -> onEvent(StreamEvent.Created((o["response"] as? JsonObject)?.str("id")))
                "response.output_text.delta" -> onEvent(StreamEvent.TextDelta(o.str("item_id"), o.str("delta") ?: ""))
                "response.reasoning_summary_text.delta" -> onEvent(StreamEvent.ReasoningSummaryDelta(o.str("delta") ?: ""))
                "response.function_call_arguments.delta" -> onEvent(StreamEvent.FunctionCallArgumentsDelta(o.str("item_id"), o.str("delta") ?: ""))
                "response.output_item.done" -> (o["item"] as? JsonObject)?.let { doneItems += it; onEvent(StreamEvent.OutputItemDone(it)) }
                "response.completed", "response.incomplete" -> {
                    val resp = o["response"] as? JsonObject ?: JsonObject(emptyMap())
                    val reason = if (type == "response.incomplete") ((resp["incomplete_details"] as? JsonObject)?.str("reason") ?: "incomplete") else null
                    result = buildResult(resp, doneItems, reason)
                    return@read false
                }
                "response.failed" -> {
                    val err = (o["response"] as? JsonObject)?.get("error") as? JsonObject
                    failure = ApiError(err?.str("code") ?: "response_failed", statusFor(err?.str("code")), err?.str("param"), upstreamMessage = err?.str("message"))
                    return@read false
                }
                "error" -> {
                    val inner = o["error"] as? JsonObject
                    val code = o.str("code") ?: inner?.str("code") ?: "stream_error"
                    failure = ApiError(code, statusFor(code), o.str("param") ?: inner?.str("param"), upstreamMessage = o.str("message") ?: inner?.str("message"))
                    return@read false
                }
                else -> onEvent(StreamEvent.Other(type, o))
            }
            true
        }
        failure?.let { throw it }
        return result ?: throw TransientFailure("stream_incomplete")
    }

    private fun buildResult(resp: JsonObject, streamed: List<JsonObject>, incomplete: String?): ResponseResult {
        // With store:false the final `response.output` is usually present; fall back to the items we saw.
        val output = (resp["output"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() } ?: streamed
        val text = output.filter { it.str("type") == "message" }.flatMap { m ->
            (m["content"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.takeIf { it.str("type") == "output_text" }?.str("text") }
        }.joinToString("")
        val calls = output.filter { it.str("type") == "function_call" }.mapNotNull { fc ->
            val id = fc.str("call_id") ?: return@mapNotNull null
            FunctionCall(id, fc.str("name") ?: "", fc.str("arguments") ?: "{}", fc)
        }
        return ResponseResult(resp, output, text, calls, resp["usage"] as? JsonObject, incomplete)
    }

    companion object {
        fun statusFor(code: String?): Int? = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> 429
            "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
            "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> 403
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> 503
            "subscription_sharing_unsupported_capability" -> 400
            "subscription_sharing_invalid_user" -> 401
            else -> null
        }

        /** Preserves the exact status, code, param and request id. `{"detail": …}` admission errors carry no code. */
        fun apiError(status: Int, raw: String?, requestId: String?): ApiError {
            val o = OAuthClient.parseOrNull(raw) as? JsonObject
            val err = o?.get("error") as? JsonObject
            if (err != null) return ApiError(err.str("code") ?: err.str("type") ?: "http_$status", status, err.str("param"), requestId, upstreamMessage = err.str("message"))
            val code = when (status) { 401 -> "direct_admission_unauthorized"; 403 -> "direct_admission_forbidden"; 503 -> "direct_route_unavailable"; else -> "http_$status" }
            return ApiError(code, status, null, requestId, upstreamMessage = o?.str("detail"))
        }
    }
}

/** Small JSON builders for input items. */
object Items {
    fun user(text: String) = buildJsonObject { put("type", "message"); put("role", "user"); put("content", text) }
    fun developer(text: String) = buildJsonObject { put("type", "message"); put("role", "developer"); put("content", text) }
    fun assistant(text: String) = buildJsonObject {
        put("type", "message"); put("role", "assistant")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
    }
    fun functionCallOutput(callId: String, output: String) = buildJsonObject { put("type", "function_call_output"); put("call_id", callId); put("output", output) }

    /** An output item made safe to send back with store:false: drop server `id`s (not persisted) and status. */
    fun replayable(item: JsonObject, keepReasoning: Boolean): JsonObject? {
        val type = item.str("type")
        if (type == "reasoning") {
            if (!keepReasoning || item["encrypted_content"] == null || item["encrypted_content"] is JsonNull) return null
            return item // reasoning items keep their id together with encrypted_content
        }
        return JsonObject(item.filterKeys { it != "id" && it != "status" })
    }

    fun text(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
}
