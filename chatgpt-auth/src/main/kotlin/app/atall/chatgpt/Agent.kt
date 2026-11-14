package app.atall.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a tool call produced. */
sealed class ToolResult {
    /** Sent back to the model as the `function_call_output`; the loop continues. */
    data class Output(val text: String) : ToolResult()
    /** Ends the run (e.g. `reply(text)` in the coordinator). [text] is the final answer and is also sent as the output. */
    data class Final(val text: String) : ToolResult()
    /**
     * Pauses the run without an output (e.g. `wait(askIds)` while text agents are still answering). The caller
     * persists [AgentRunResult.newItems], and later calls [AgentRunner.resume] with the output for this call id.
     */
    data class Suspend(val reason: String) : ToolResult()
}

/** Context handed to tools: which call this is, and the run's cancellation. */
data class ToolContext(val callId: String, val cancellation: Cancellation?)

/** A function tool the main agent can call (`ask_agent`, `wait`, `reply`, …). Implementations run on the runner's thread. */
interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject
    /** OpenAI strict mode (all properties required, additionalProperties:false). */
    val strict: Boolean get() = false

    fun call(arguments: JsonObject, context: ToolContext): ToolResult

    fun spec(): JsonObject = buildJsonObject {
        put("type", "function"); put("name", name); put("description", description)
        put("parameters", parameters); put("strict", strict)
    }
}

/** Builds a JSON-Schema object quickly: `schema("agent" to stringProp("Which agent"), required = listOf("agent"))`. */
object Schema {
    fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = props.map { it.first }): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    fun string(description: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string"); put("description", description)
        enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
    }
    fun integer(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
    fun stringArray(description: String) = buildJsonObject {
        put("type", "array"); put("description", description); put("items", buildJsonObject { put("type", "string") })
    }
}

enum class RunEnd { TEXT, FINAL_TOOL, SUSPENDED, MODEL_CALL_LIMIT }

data class AgentRunResult(
    val end: RunEnd,
    /** Final assistant text (from a plain message or a [ToolResult.Final]). */
    val text: String,
    /** Items to append to the persisted transcript (model outputs made replayable + tool outputs), in order. */
    val newItems: List<JsonObject>,
    /** Calls awaiting an output when [end] == SUSPENDED. */
    val pendingCallIds: List<String>,
    val usage: List<JsonObject>,
)

interface AgentListener {
    fun onTextDelta(delta: String) {}
    fun onToolCall(name: String, arguments: JsonObject) {}
    fun onToolResult(name: String, result: ToolResult) {}
}

/**
 * The model/tool loop for the main agent over the ChatGPT-plan Responses route. Stateless between calls: the
 * caller owns the transcript (store:false and no previous_response_id, so the full context goes every turn).
 */
class AgentRunner(
    private val client: ResponsesClient,
    private val model: String,
    private val instructions: String?,
    tools: List<AgentTool>,
    private val maxModelCalls: Int = 8,
    /** e.g. "low" | "medium" | "high"; null = model default. */
    private val reasoningEffort: String? = null,
    /** Ask for `reasoning.encrypted_content` and replay reasoning items (what Codex does with store:false). Unverified on SIWC. */
    private val includeEncryptedReasoning: Boolean = false,
    private val parallelToolCalls: Boolean = false,
) {
    private val tools: Map<String, AgentTool> = tools.associateBy { it.name }.also { require(it.size == tools.size) { "duplicate tool names" } }

    fun run(history: List<JsonObject>, listener: AgentListener = object : AgentListener {}, cancellation: Cancellation? = null): AgentRunResult =
        loop(history, mutableListOf(), listener, cancellation)

    /** Continue a suspended run once the coordinator has outputs for the pending calls. */
    fun resume(history: List<JsonObject>, outputs: Map<String, String>, listener: AgentListener = object : AgentListener {}, cancellation: Cancellation? = null): AgentRunResult {
        val added = outputs.map { (id, out) -> Items.functionCallOutput(id, out) }.toMutableList()
        return loop(history, added, listener, cancellation)
    }

    fun requestBody(input: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", model)
        instructions?.let { put("instructions", it) }
        put("input", JsonArray(input))
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.values.map { it.spec() }))
            put("tool_choice", "auto")
            put("parallel_tool_calls", parallelToolCalls)
        }
        reasoningEffort?.let { e -> put("reasoning", buildJsonObject { put("effort", e) }) }
        if (includeEncryptedReasoning) put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        put("store", false); put("stream", true)
    }

    private fun loop(history: List<JsonObject>, added: MutableList<JsonObject>, listener: AgentListener, cancellation: Cancellation?): AgentRunResult {
        val usage = mutableListOf<JsonObject>()
        val base = history
        repeat(maxModelCalls) {
            val result = client.stream(requestBody(base + added), cancellation) { ev ->
                if (ev is StreamEvent.TextDelta) listener.onTextDelta(ev.delta)
            }
            result.usage?.let { usage += it }
            result.output.mapNotNull { Items.replayable(it, includeEncryptedReasoning) }.forEach { added += it }
            if (result.functionCalls.isEmpty()) return AgentRunResult(RunEnd.TEXT, result.text, added, emptyList(), usage)

            var final: String? = null
            val pending = mutableListOf<String>()
            for (call in result.functionCalls) {
                val tool = tools[call.name]
                val args = parseArgs(call.arguments)
                val outcome: ToolResult = when {
                    tool == null -> ToolResult.Output("""{"error":"unknown_tool","name":${JsonPrimitive(call.name)}}""")
                    args == null -> ToolResult.Output("""{"error":"invalid_arguments","detail":"arguments were not a JSON object"}""")
                    else -> {
                        listener.onToolCall(call.name, args)
                        try { tool.call(args, ToolContext(call.callId, cancellation)) }
                        catch (e: Exception) { ToolResult.Output("""{"error":"tool_failed","detail":${JsonPrimitive(e.message?.take(200) ?: e.javaClass.simpleName)}}""") }
                    }
                }
                listener.onToolResult(call.name, outcome)
                when (outcome) {
                    is ToolResult.Output -> added += Items.functionCallOutput(call.callId, outcome.text)
                    is ToolResult.Final -> { added += Items.functionCallOutput(call.callId, outcome.text); final = final ?: outcome.text }
                    is ToolResult.Suspend -> pending += call.callId
                }
            }
            final?.let { return AgentRunResult(RunEnd.FINAL_TOOL, it, added, pending, usage) }
            if (pending.isNotEmpty()) return AgentRunResult(RunEnd.SUSPENDED, result.text, added, pending, usage)
        }
        return AgentRunResult(RunEnd.MODEL_CALL_LIMIT, "", added, emptyList(), usage)
    }

    private fun parseArgs(raw: String): JsonObject? =
        (OAuthClient.parseOrNull(raw.ifBlank { "{}" }) as? JsonObject)

    companion object {
        fun argString(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun argStrings(args: JsonObject, key: String): List<String> =
            (args[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}
