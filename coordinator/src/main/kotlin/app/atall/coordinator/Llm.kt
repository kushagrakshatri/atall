package app.atall.coordinator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A function tool offered to the model, in Responses format. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject, val strict: Boolean = true) {
  fun json(): JsonObject = buildJsonObject {
    put("type", "function"); put("name", name); put("description", description)
    put("parameters", parameters); put("strict", strict)
  }
}

/**
 * One model call. [input] is the full Responses input (store:false, no previous_response_id: the caller owns the
 * transcript). [jsonSchema] asks for structured output (`text.format = json_schema`). [purpose] names the call
 * ("juno", "blind", "claims", "verdict", "challenge", "late") so fakes and logs can tell calls apart.
 */
data class LlmRequest(
  val purpose: String,
  val instructions: String,
  val input: List<JsonObject>,
  val tools: List<ToolSpec> = emptyList(),
  val jsonSchema: JsonObject? = null,
  val schemaName: String? = null,
)

data class ToolCall(val callId: String, val name: String, val arguments: String)

/**
 * What came back. [outputItems] are the replayable output items (server ids stripped) to append to the transcript;
 * when empty, Juno synthesizes them from [text] and [toolCalls].
 */
data class LlmResponse(val text: String, val toolCalls: List<ToolCall> = emptyList(), val outputItems: List<JsonObject> = emptyList())

/** The model. In the app: Responses over Sign in with ChatGPT (see adapters/ResponsesLlm.kt). In tests: a fake. */
fun interface Llm {
  suspend fun respond(request: LlmRequest): LlmResponse
}

/** A model failure the UI turns into one line. */
class LlmException(val kind: Kind, message: String) : Exception(message) {
  enum class Kind {
    /** Sign-in ended: "Sign in again". */
    SIGN_IN,
    /** Plan usage cap hit: "Limit reached · Manage usage". */
    LIMIT,
    /** Try again in a minute. */
    TRANSIENT,
    OTHER,
  }
}

/** Responses input items. */
object Items {
  fun user(text: String) = buildJsonObject { put("type", "message"); put("role", "user"); put("content", text) }
  fun developer(text: String) = buildJsonObject { put("type", "message"); put("role", "developer"); put("content", text) }
  fun assistant(text: String) = buildJsonObject {
    put("type", "message"); put("role", "assistant")
    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
  }
  fun functionCall(callId: String, name: String, arguments: String) = buildJsonObject {
    put("type", "function_call"); put("call_id", callId); put("name", name); put("arguments", arguments)
  }
  fun functionCallOutput(callId: String, output: String) = buildJsonObject {
    put("type", "function_call_output"); put("call_id", callId); put("output", output)
  }

  fun outputsOf(r: LlmResponse): List<JsonObject> = r.outputItems.ifEmpty {
    buildList {
      if (r.text.isNotBlank()) add(assistant(r.text))
      r.toolCalls.forEach { add(functionCall(it.callId, it.name, it.arguments)) }
    }
  }
}

internal val CoordJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; isLenient = true }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
internal fun JsonObject.strings(key: String): List<String> =
  (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Parses a model's JSON leniently: tolerates code fences and prose around one top-level object. */
internal fun parseJsonObject(raw: String): JsonObject? {
  val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
  fun tryParse(x: String): JsonObject? = try { CoordJson.parseToJsonElement(x) as? JsonObject } catch (_: Exception) { null }
  tryParse(s)?.let { return it }
  val a = s.indexOf('{'); val b = s.lastIndexOf('}')
  return if (a in 0 until b) tryParse(s.substring(a, b + 1)) else null
}

internal fun jsonString(s: String): String = JsonPrimitive(s).toString()
internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

/** JSON Schema helpers (strict mode: every property required, no extras). */
object Schema {
  fun obj(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(props.toMap()))
    put("required", buildJsonArray { props.forEach { add(JsonPrimitive(it.first)) } })
    put("additionalProperties", false)
  }
  fun string(description: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "string"); put("description", description)
    enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
  }
  fun integer(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
  fun boolean(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }
  fun array(description: String, items: JsonObject) = buildJsonObject { put("type", "array"); put("description", description); put("items", items) }
  fun stringArray(description: String) = array(description, buildJsonObject { put("type", "string") })
}
