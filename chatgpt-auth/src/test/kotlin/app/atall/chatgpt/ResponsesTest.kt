package app.atall.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponsesTest {
    private val mock = MockOpenAI()
    private val config = mock.config()
    /** A token source over the mock: starts with a valid token; force = mint a new one (as a refresh would). */
    private var forcedCount = 0
    private val tokens = TokenSource { force ->
        if (force || mock.validAccess.isEmpty()) { forcedCount += if (force) 1 else 0; "at-fresh-${forcedCount}".also { mock.validAccess.clear(); mock.validAccess.add(it) } }
        else mock.validAccess.first()
    }
    private val client = ResponsesClient(config, OkHttpClient(), tokens)

    @After fun tearDown() = mock.close()

    private fun body(vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(
        "model" to JsonPrimitive("gpt-6.1-sol"),
        "input" to JsonArray(listOf(Items.user("hi"))),
    ) + extra)

    @Test fun shaperAppliesPreviewLimits() {
        val raw = LenientJson.parseToJsonElement("""{
          "model":"m","input":[{"role":"system","content":"be brief"},{"type":"message","role":"user","content":"hi"}],
          "temperature":0.2,"max_output_tokens":99,"metadata":{"a":"b"},"previous_response_id":"resp_x","store":true,"stream":false,
          "tools":[{"type":"function","name":"ask_agent","parameters":{}},{"type":"web_search"},{"type":"file_search"},{"type":"code_interpreter"}],
          "tool_choice":"auto"}""").jsonObject
        val s = RequestShaper.shape(raw)
        listOf("temperature", "max_output_tokens", "metadata", "previous_response_id").forEach { assertFalse(it, s.containsKey(it)) }
        assertEquals(JsonPrimitive(false), s["store"]); assertEquals(JsonPrimitive(true), s["stream"])
        val input = s["input"] as JsonArray
        val first = input[0].jsonObject
        assertEquals("additional_tools", first.str("type")); assertEquals("developer", first.str("role"))
        assertEquals("ask_agent", (first["tools"] as JsonArray)[0].jsonObject.str("name"))
        assertEquals("developer", input[1].jsonObject.str("role")) // system -> developer
        assertEquals(listOf("web_search"), (s["tools"] as JsonArray).map { it.jsonObject.str("type") }) // hosted unsupported dropped
        // String input -> one user item; no tools -> tool_choice removed
        val s2 = RequestShaper.shape(buildJsonObject { put("model", "m"); put("input", "yo"); put("tool_choice", "auto") })
        assertEquals("user", (s2["input"] as JsonArray)[0].jsonObject.str("role"))
        assertFalse(s2.containsKey("tool_choice"))
        // Top-level placement keeps standard tools array.
        val s3 = RequestShaper.shape(raw, ToolPlacement.TOP_LEVEL)
        assertEquals(listOf("function", "web_search"), (s3["tools"] as JsonArray).map { it.jsonObject.str("type") })
    }

    @Test fun sseParserHandlesMultilineCommentsAndTrailingEvent() {
        val src = Buffer().writeUtf8(": hello\n\nevent: a\ndata: line1\ndata: line2\n\ndata:no-space\n\ndata: tail")
        val got = mutableListOf<SseEvent>()
        SseParser.read(src) { got += it; true }
        assertEquals(listOf(SseEvent("a", "line1\nline2"), SseEvent(null, "no-space"), SseEvent(null, "tail")), got)
    }

    @Test fun streamsTextToCompletion() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.textStream("Hello, world!")) }
        val deltas = StringBuilder()
        val r = client.stream(body(), null) { if (it is StreamEvent.TextDelta) deltas.append(it.delta) }
        assertEquals("Hello, world!", r.text); assertEquals("Hello, world!", deltas.toString())
        assertNull(r.incompleteReason)
        assertEquals(13, (r.usage!!["total_tokens"] as JsonPrimitive).content.toInt())
        val sent = mock.responsesRequests.single()
        assertEquals(JsonPrimitive(false), sent["store"]); assertEquals(JsonPrimitive(true), sent["stream"])
        assertTrue(mock.responsesAuth.single().startsWith("Bearer "))
    }

    @Test fun usageLimitMidStreamIsStructured() {
        mock.scripts += { _ -> MockOpenAI.sse(
            MockOpenAI.ev("""{"type":"response.output_text.delta","delta":"Hel"}""") +
            MockOpenAI.ev("""{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""")) }
        try { client.stream(body()); fail() } catch (e: ApiError) {
            assertEquals("subscription_sharing_usage_limit_exceeded", e.code)
            assertEquals(429, e.status); assertEquals(Recovery.USAGE_LIMIT, e.recovery)
        }
    }

    @Test fun httpErrorsKeepCodeParamAndRequestId() {
        mock.scripts += { _ -> okhttp3.mockwebserver.MockResponse().setResponseCode(400).setHeader("x-request-id", "req_123")
            .setBody("""{"error":{"code":"subscription_sharing_unsupported_capability","param":"tools[0]","message":"nope"}}""") }
        try { client.stream(body()); fail() } catch (e: ApiError) {
            assertEquals("subscription_sharing_unsupported_capability", e.code); assertEquals("tools[0]", e.param)
            assertEquals("req_123", e.requestId); assertEquals(Recovery.FIX_REQUEST, e.recovery)
        }
        mock.scripts += { _ -> okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"detail":"region"}""") }
        try { client.stream(body()); fail() } catch (e: ApiError) {
            assertEquals("direct_admission_forbidden", e.code); assertEquals(Recovery.POLICY_OR_REGION, e.recovery)
        }
        mock.scripts += { _ -> okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"error":{"code":"subscription_sharing_user_not_eligible"}}""") }
        try { client.stream(body()); fail() } catch (e: ApiError) { assertEquals(Recovery.NOT_ELIGIBLE, e.recovery) }
    }

    @Test fun unauthorizedTriggersOneForcedRefreshAndRetry() {
        mock.validAccess.clear(); mock.validAccess.add("at-good")
        var calls = 0
        val stale = TokenSource { force -> calls++; if (force) "at-good" else "at-revoked-early" }
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.textStream("after refresh")) }
        val r = ResponsesClient(config, OkHttpClient(), stale).stream(body())
        assertEquals("after refresh", r.text)
        assertEquals(listOf("Bearer at-revoked-early", "Bearer at-good"), mock.responsesAuth.toList())
        assertEquals(2, calls)
    }

    @Test fun streamWithoutCompletedIsIncomplete() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.ev("""{"type":"response.output_text.delta","delta":"half"}""")) }
        try { client.stream(body()); fail() } catch (e: TransientFailure) { assertEquals("stream_incomplete", e.code) }
    }

    @Test fun incompleteResponseReportsReason() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.ev("""{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[${MockOpenAI.message("par")}]}}""")) }
        val r = client.stream(body())
        assertEquals("max_output_tokens", r.incompleteReason); assertEquals("par", r.text)
    }

    @Test fun tokenGoesOnlyToApiBase() {
        try { client.guard(okhttp3.HttpUrl.Builder().scheme("https").host("chatgpt.com").encodedPath("/backend-api/codex/responses").build()); fail() } catch (_: BlockedDestination) {}
        try { client.guard(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(mock.server.port).encodedPath("/v1evil/responses").build()); fail() } catch (_: BlockedDestination) {}
        client.guard(client.url("responses"))
    }

    @Test fun listsVisibleModelsInServerOrder() {
        assertEquals(listOf("gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6.1-mini" to "GPT-6.1 mini"), client.listModels())
    }
}
