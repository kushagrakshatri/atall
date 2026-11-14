package app.atall.coordinator.adapters

// Glue for the app module (not part of the pure coordinator): chatgpt-auth's Responses client -> coordinator Llm.

import app.atall.coordinator.Llm
import app.atall.coordinator.LlmException
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.ToolCall
import app.atall.chatgpt.ApiError
import app.atall.chatgpt.Cancellation
import app.atall.chatgpt.ChatGptException
import app.atall.chatgpt.Items
import app.atall.chatgpt.PlanUsageNotAllowed
import app.atall.chatgpt.Recovery
import app.atall.chatgpt.ResponsesClient
import app.atall.chatgpt.SignInRequired
import app.atall.chatgpt.TransientFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * [Llm] over the ChatGPT-plan Responses route. The client already shapes the body for SIWC (store:false, stream:true,
 * function tools grouped into the `additional_tools` developer item, unsupported fields stripped). Structured output
 * uses `text.format = json_schema` (unverified on SIWC; Juno degrades to UNCLEAR/fallback lines if it's rejected).
 */
class ResponsesLlm(
  private val client: ResponsesClient,
  private val model: () -> String,
  private val reasoningEffort: String? = "low",
) : Llm {
  override suspend fun respond(request: LlmRequest): LlmResponse {
    val body = buildJsonObject {
      put("model", model())
      put("instructions", request.instructions)
      put("input", JsonArray(request.input))
      if (request.tools.isNotEmpty()) {
        put("tools", JsonArray(request.tools.map { it.json() }))
        put("tool_choice", "auto")
        put("parallel_tool_calls", true)
      }
      request.jsonSchema?.let { schema ->
        put("text", buildJsonObject {
          put("format", buildJsonObject {
            put("type", "json_schema"); put("name", request.schemaName ?: "output"); put("schema", schema); put("strict", true)
          })
        })
      }
      reasoningEffort?.let { e -> put("reasoning", buildJsonObject { put("effort", e) }) }
    }
    val cancellation = Cancellation()
    coroutineContext[Job]?.invokeOnCompletion { if (it != null) cancellation.cancel() }
    val result = try {
      withContext(Dispatchers.IO) { client.stream(body, cancellation) }
    } catch (e: ChatGptException) {
      throw LlmException(kindOf(e), e.code)
    }
    val calls = result.functionCalls.map { ToolCall(it.callId, it.name, it.arguments) }
    val items: List<JsonObject> = result.output.mapNotNull { Items.replayable(it, keepReasoning = false) }
    return LlmResponse(result.text, calls, items)
  }

  private fun kindOf(e: ChatGptException): LlmException.Kind = when (e) {
    is SignInRequired, is PlanUsageNotAllowed -> LlmException.Kind.SIGN_IN
    is TransientFailure -> LlmException.Kind.TRANSIENT
    is ApiError -> when (e.recovery) {
      Recovery.SIGN_IN_AGAIN -> LlmException.Kind.SIGN_IN
      Recovery.USAGE_LIMIT -> LlmException.Kind.LIMIT
      Recovery.RETRY_LATER -> LlmException.Kind.TRANSIENT
      else -> LlmException.Kind.OTHER
    }
    else -> LlmException.Kind.OTHER
  }
}
