package app.atall.coordinator

import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.FakePeer
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

val testRoster = Roster(listOf(
  AgentProfile("juno", "Juno", AgentKind.MAIN),
  AgentProfile("scout", "Scout", AgentKind.TEXT, aliases = listOf("dot"), goodAt = listOf("ideas", "research", "writing")),
  AgentProfile("nova", "Nova", AgentKind.TEXT, goodAt = listOf("bookings", "calendar", "reservations")),
  AgentProfile("hermes", "Hermes", AgentKind.API, goodAt = listOf("code"), paused = true),
))

class Harness(
  val scope: TestScope,
  val config: JunoConfig = JunoConfig(),
  val store: RunStore = InMemoryRunStore(),
  val llm: FakeLlm = FakeLlm(),
  val scores: InMemoryScoreLog = InMemoryScoreLog(),
) {
  val clock: () -> Long = { scope.testScheduler.currentTime }
  val scout = FakePeer("scout", scope.backgroundScope, clock)
  val nova = FakePeer("nova", scope.backgroundScope, clock)
  private var n = 0
  val juno = Juno(testRoster, mapOf("scout" to scout, "nova" to nova), llm, store, scores, clock, config, newId = { "${++n}" })
  init { scout.sink = juno::ingest; nova.sink = juno::ingest }
}

fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun waitAsks(output: String): List<JsonObject> = (json(output)["asks"] as JsonArray).map { it.jsonObject }
fun args(vararg pairs: Pair<String, Any>): String = JsonObject(pairs.associate { (k, v) ->
  k to when (v) {
    is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
    else -> error("bad arg")
  }
}).toString()

fun askAgent(agent: String, text: String, money: Boolean = false) = FakeLlm.call("ask_agent", args("agent" to agent, "text" to text, "why" to "test", "money_or_account" to money))
fun waitCall(vararg ids: String, max: Int = 180) = FakeLlm.call("wait", args("ask_ids" to ids.toList(), "max_seconds" to max))
fun replyCall(text: String) = FakeLlm.call("reply", args("text" to text))
