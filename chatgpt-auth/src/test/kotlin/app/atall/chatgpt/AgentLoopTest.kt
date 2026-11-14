package app.atall.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator's tools, as the new app would expose them (ask_agent / wait / reply). */
class AgentLoopTest {
    private val mock = MockOpenAI()
    private val tokens = TokenSource { _ -> mock.validAccess.firstOrNull() ?: "at-x".also { mock.validAccess.add(it) } }
    private val client = ResponsesClient(mock.config(), OkHttpClient(), tokens)
    private val asked = mutableListOf<Pair<String, String>>()

    private val askAgent = object : AgentTool {
        override val name = "ask_agent"
        override val description = "Send a message to one of the user's agents. Returns an ask id."
        override val parameters = Schema.obj("agent" to Schema.string("Agent name", listOf("qBit", "Wren")), "text" to Schema.string("What to ask"))
        override val strict = true
        override fun call(arguments: JsonObject, context: ToolContext): ToolResult {
            asked += AgentRunner.argString(arguments, "agent")!! to AgentRunner.argString(arguments, "text")!!
            return ToolResult.Output("""{"ask_id":"ask_${asked.size}","status":"sent"}""")
        }
    }
    private val wait = object : AgentTool {
        override val name = "wait"
        override val description = "Wait for replies to asks."
        override val parameters = Schema.obj("ask_ids" to Schema.stringArray("Ask ids"), "max_seconds" to Schema.integer("Soft limit"))
        override fun call(arguments: JsonObject, context: ToolContext) = ToolResult.Suspend("waiting for ${AgentRunner.argStrings(arguments, "ask_ids")}")
    }
    private val reply = object : AgentTool {
        override val name = "reply"
        override val description = "Give the final answer to the user."
        override val parameters = Schema.obj("text" to Schema.string("Answer"))
        override fun call(arguments: JsonObject, context: ToolContext) = ToolResult.Final(AgentRunner.argString(arguments, "text")!!)
    }
    private val runner = AgentRunner(client, "gpt-6.1-sol", "You coordinate the user's agents.", listOf(askAgent, wait, reply))

    @After fun tearDown() = mock.close()

    @Test fun toolCallThenTextAnswer() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.functionCallStream(Triple("call_1", "ask_agent", """{"agent":"Wren","text":"Find a table for 2"}"""))) }
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.textStream("Asked Wren.")) }
        val r = runner.run(listOf(Items.user("Ask Wren for a table")))
        assertEquals(RunEnd.TEXT, r.end); assertEquals("Asked Wren.", r.text)
        assertEquals(listOf("Wren" to "Find a table for 2"), asked)

        val first = mock.responsesRequests[0]
        val tools = (first["input"] as JsonArray)[0].jsonObject
        assertEquals("additional_tools", tools.str("type"))
        assertEquals(listOf("ask_agent", "wait", "reply"), (tools["tools"] as JsonArray).map { it.jsonObject.str("name") })
        assertFalse(first.containsKey("tools"))
        assertEquals("You coordinate the user's agents.", first.str("instructions"))

        val second = mock.responsesRequests[1]["input"] as JsonArray
        val types = second.map { it.jsonObject.str("type") }
        assertEquals(listOf("additional_tools", "message", "function_call", "function_call_output"), types)
        val fc = second[2].jsonObject
        assertFalse("server ids are not replayed with store:false", fc.containsKey("id"))
        assertEquals("call_1", fc.str("call_id"))
        assertEquals("call_1", second[3].jsonObject.str("call_id"))
        assertTrue(second[3].jsonObject.str("output")!!.contains("ask_1"))
        // newItems = function_call, output, final message
        assertEquals(listOf("function_call", "function_call_output", "message"), r.newItems.map { it.str("type") })
    }

    @Test fun finalToolEndsRun() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.functionCallStream(Triple("c1", "reply", """{"text":"Done: 7pm at Rooh."}"""))) }
        val r = runner.run(listOf(Items.user("book it")))
        assertEquals(RunEnd.FINAL_TOOL, r.end); assertEquals("Done: 7pm at Rooh.", r.text)
        assertEquals(1, mock.responsesRequests.size)
    }

    @Test fun suspendThenResume() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.functionCallStream(
            Triple("c1", "ask_agent", """{"agent":"qBit","text":"what's on my calendar"}"""),
            Triple("c2", "wait", """{"ask_ids":["ask_1"],"max_seconds":180}"""))) }
        val history = listOf(Items.user("what's on today?"))
        val r = runner.run(history)
        assertEquals(RunEnd.SUSPENDED, r.end); assertEquals(listOf("c2"), r.pendingCallIds)
        // Coordinator persists history + r.newItems, waits for qBit's text, then resumes.
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.textStream("You have standup at 10.")) }
        val r2 = runner.resume(history + r.newItems, mapOf("c2" to """{"ask_1":{"state":"answered","replies":["Standup 10am"]}}"""))
        assertEquals(RunEnd.TEXT, r2.end); assertEquals("You have standup at 10.", r2.text)
        val sent = mock.responsesRequests[1]["input"] as JsonArray
        val outputs = sent.map { it.jsonObject }.filter { it.str("type") == "function_call_output" }.map { it.str("call_id") }
        assertEquals(listOf("c1", "c2"), outputs)
    }

    @Test fun unknownToolAndBadArgumentsGoBackToModel() {
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.functionCallStream(Triple("c1", "launch_rockets", "{}"), Triple("c2", "ask_agent", "not json"))) }
        mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.textStream("Sorry.")) }
        val r = runner.run(listOf(Items.user("x")))
        assertEquals("Sorry.", r.text)
        val outs = r.newItems.filter { it.str("type") == "function_call_output" }.map { it.str("output")!! }
        assertTrue(outs[0].contains("unknown_tool")); assertTrue(outs[1].contains("invalid_arguments"))
        assertTrue(asked.isEmpty())
    }

    @Test fun modelCallLimitStopsRunawayLoops() {
        val tight = AgentRunner(client, "m", null, listOf(askAgent), maxModelCalls = 2)
        repeat(2) { i -> mock.scripts += { _ -> MockOpenAI.sse(MockOpenAI.functionCallStream(Triple("c$i", "ask_agent", """{"agent":"Wren","text":"again"}"""))) } }
        assertEquals(RunEnd.MODEL_CALL_LIMIT, tight.run(listOf(Items.user("loop"))).end)
    }

    @Test fun strictSchemaShape() {
        val spec = askAgent.spec()
        assertEquals("function", spec.str("type"))
        val params = spec["parameters"]!!.jsonObject
        assertEquals("false", params["additionalProperties"].toString())
        assertEquals(2, (params["required"] as JsonArray).size)
    }
}
