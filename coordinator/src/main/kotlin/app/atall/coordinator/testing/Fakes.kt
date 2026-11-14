package app.atall.coordinator.testing

import app.atall.coordinator.Certainty
import app.atall.coordinator.CloseOutcome
import app.atall.coordinator.Llm
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.LlmResponse
import app.atall.coordinator.Peer
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.ToolCall
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A scripted model. Handlers are chosen by [LlmRequest.purpose] ("juno", "blind", "claims", "verdict", "challenge",
 * "late"); each gets the request and how many calls of that purpose came before it. Never touches the network.
 */
class FakeLlm : Llm {
  val requests = CopyOnWriteArrayList<LlmRequest>()
  private val handlers = HashMap<String, suspend (LlmRequest, Int) -> LlmResponse>()
  private val counts = HashMap<String, AtomicInteger>()
  var failWith: Exception? = null

  fun on(purpose: String, handler: suspend (LlmRequest, Int) -> LlmResponse): FakeLlm { handlers[purpose] = handler; return this }

  fun calls(purpose: String) = requests.count { it.purpose == purpose }

  override suspend fun respond(request: LlmRequest): LlmResponse {
    requests += request
    failWith?.let { throw it }
    val n = counts.getOrPut(request.purpose) { AtomicInteger() }.getAndIncrement()
    val h = handlers[request.purpose] ?: error("FakeLlm: no handler for ${request.purpose}")
    return h(request, n)
  }

  companion object {
    private val ids = AtomicInteger()
    fun call(name: String, args: String) = ToolCall("call_${ids.incrementAndGet()}", name, args)
    fun tools(vararg calls: ToolCall) = LlmResponse("", calls.toList())
    fun text(t: String) = LlmResponse(t)

    /** Outputs of function calls in the request's input, in order. */
    fun toolOutputs(r: LlmRequest): List<String> = r.input.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "function_call_output" }
      .map { (it["output"] as JsonPrimitive).content }

    fun lastToolOutput(r: LlmRequest): String? = toolOutputs(r).lastOrNull()
    fun userTexts(r: LlmRequest): List<String> = r.input.filter { (it["role"] as? JsonPrimitive)?.contentOrNull == "user" }
      .map { (it["content"] as JsonPrimitive).content }
    fun developerTexts(r: LlmRequest): List<String> = r.input.filter { (it["role"] as? JsonPrimitive)?.contentOrNull == "developer" }
      .map { (it["content"] as JsonPrimitive).content }
  }
}

/** One scripted reply fragment, [afterMs] after the previous one (or after the send). */
data class Part(
  val afterMs: Long,
  val text: String,
  val certainty: Certainty = Certainty.HIGH,
  val late: Boolean = false,
  val userInterjected: Boolean = false,
  /** False: the transport couldn't attribute it (arrives uncorrelated). */
  val correlated: Boolean = true,
)

sealed interface Behavior {
  data class Reply(val parts: List<Part>) : Behavior
  data object Silent : Behavior
  /** No live reply action: the user must tap. Replies [after] the tap if the test calls [FakePeer.userTaps]. */
  data class NeedsTap(val after: List<Part> = emptyList()) : Behavior
  data class Drafted(val after: List<Part> = emptyList()) : Behavior
  /** Silent, then the transport reports the thread was opened and the reply likely missed, after [afterMs]. */
  data class LikelyMissed(val afterMs: Long) : Behavior
  data class Fail(val reason: String) : Behavior
}

/**
 * An in-memory peer in the spirit of text-agents' FakeAgent, but async: replies are emitted after virtual delays on
 * [scope] (use the test's backgroundScope), delivered to [sink] (wire it to Juno.ingest). Sends are idempotent by
 * correlation id like the real transport.
 */
class FakePeer(
  override val id: String,
  private val scope: CoroutineScope,
  private val clock: () -> Long,
  var behavior: (String) -> Behavior = { Behavior.Reply(listOf(Part(1_000, "ok: $it"))) },
) : Peer {
  lateinit var sink: suspend (PeerSignal) -> Unit
  val sent = CopyOnWriteArrayList<Pair<String, String>>()
  val closed = CopyOnWriteArrayList<String>()
  private val accepted = HashMap<String, Long>()
  private val held = HashMap<String, List<Part>>()
  private var seq = 0

  override suspend fun send(text: String, correlationId: String): PeerSend {
    accepted[correlationId]?.let { return PeerSend.Duplicate(it) }
    val now = clock()
    return when (val b = behavior(text)) {
      is Behavior.Fail -> PeerSend.Failed(b.reason)
      is Behavior.NeedsTap -> { held[correlationId] = b.after; PeerSend.NeedsUser(text, "+15550000000") }
      else -> {
        accepted[correlationId] = now
        sent += correlationId to text
        when (b) {
          is Behavior.Reply -> emit(correlationId, b.parts)
          is Behavior.Drafted -> emit(correlationId, b.after)
          is Behavior.LikelyMissed -> scope.launch { delay(b.afterMs); sink(PeerSignal.Closed(id, correlationId, CloseOutcome.LIKELY_MISSED, clock())) }
          else -> {}
        }
        if (b is Behavior.Drafted) PeerSend.Drafted(now) else PeerSend.Sent(now)
      }
    }
  }

  /** The user tapped Send on a tap-to-send draft. */
  suspend fun userTaps(correlationId: String) {
    accepted[correlationId] = clock()
    sent += correlationId to "(tapped)"
    sink(PeerSignal.DraftSent(id, correlationId, clock()))
    emit(correlationId, held.remove(correlationId).orEmpty())
  }

  /** The agent texts on its own (story 3). */
  suspend fun textsUnprompted(text: String) = sink(PeerSignal.Message(id, "$id-u${seq++}", text, clock()))

  /** Deliver a reply now, outside any script (e.g. after a simulated process death). */
  suspend fun replyNow(correlationId: String, text: String, certainty: Certainty = Certainty.HIGH, late: Boolean = false) =
    sink(PeerSignal.Message(id, "$id-m${seq++}", text, clock(), correlationId, certainty, 0, late))

  override suspend fun close(correlationId: String) { closed += correlationId }

  private fun emit(cid: String, parts: List<Part>) {
    if (parts.isEmpty()) return
    scope.launch {
      parts.forEachIndexed { i, p ->
        delay(p.afterMs)
        sink(PeerSignal.Message(id, "$id-m${seq++}", p.text, clock(), if (p.correlated) cid else null, p.certainty, i, p.late, p.userInterjected))
      }
    }
  }
}

internal fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
