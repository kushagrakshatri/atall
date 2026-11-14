package app.atall.inbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The heuristic's answer for one message. */
data class Verdict(
  val lane: Lane,
  val confidence: Confidence,
  val signals: Set<Signal>,
  val reason: String,
  /** Unsure: ask the LLM (when one is configured). [lane] is the provisional answer meanwhile. */
  val needsLlm: Boolean,
)

/**
 * Cheap, on-device triage. Runs on redacted text plus [Features]. Order matters: the first rule that fires
 * decides. Everything is regex over short texts, microseconds per message.
 */
class HeuristicTriage(private val needsYouHorizonMs: Long = 72 * 3600_000L) {
  private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

  private val question = rx(
    "\\?(\\s|$)|\\b(should i|shall i|do you want|want me to|would you like|can you|could you|let me know|" +
      "reply (yes|no|y|n|stop)\\b|confirm (if|whether|that)|which (one|option|do you)|ok to|okay to|is it ok|" +
      "are you (ok|okay|free|available)|your call|up to you)\\b"
  )
  private val action = rx(
    "\\b(action (required|needed)|please (sign|review|approve|confirm|reply|respond|pay|call|update|complete|submit|rsvp|check|read|look)|" +
      "needs? your (approval|signature|input|ok|okay|answer|decision|reply|attention)|waiting (on|for) (you|your)|" +
      "requires? your|rsvp|sign (the|this|it)|approve (the|this|it)|you need to|you have to|don't miss|reply to|" +
      "(reply|respond|pay|sign|submit|rsvp|confirm|decide|register|renew|file|apply|cancel) (by|before))\\b"
  )
  private val deadline = rx(
    "\\b(due|deadline|expires?|expiring|expiry|last day|final notice|overdue|past due|late fee|ends (today|tonight|tomorrow)|" +
      "closes (today|tonight|tomorrow)|by (today|tonight|tomorrow|eod|end of day|noon|midnight|\\d|mon|tue|wed|thu|fri|sat|sun|" +
      "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|before (today|tonight|tomorrow|\\d|mon|tue|wed|thu|fri|sat|sun))"
  )
  private val money = rx("\\b(payment|invoice|bill|charged?|refund(ed)?|balance|rent|transfer|withdrawal|deposit|subscription renew|autopay)\\b|[$£€₹]\\s?\\d")
  private val moneyProblem = rx(
    "\\b(declined|payment failed|failed payment|overdue|past due|late fee|insufficient funds|overdraft|fraud|unauthori[sz]ed|" +
      "dispute|chargeback|suspicious (charge|activity|transaction))\\b"
  )
  private val confirmation = rx(
    "\\b(confirmed|confirmation (number|#|code)?|booked|reservation (is|for|at)|you'?re all set|all set|order (placed|received|confirmed)|" +
      "has shipped|shipped|delivered|out for delivery|receipt|successfully|is complete|completed|went through|scheduled for)\\b"
  )
  private val urgent = rx(
    "\\b(urgent|asap|immediately|right away|security alert|suspicious (sign[- ]?in|login|activity)|account (locked|suspended|compromised)|" +
      "action required|emergency|time[- ]sensitive)\\b"
  )
  private val promo = rx(
    "\\d+% off|\\b(sale|deals|great deal|promo|coupon|newsletter|unsubscribe|limited time|free trial|upgrade (to|now)|" +
      "pro tip|tip of the day|did you know|new feature|what'?s new|check out our|try (our|the new)|special offer|webinar)\\b"
  )
  private val chatter = rx(
    "^\\W*(ok|okay|k|kk|got it|thanks|thank you|thx|ty|noted|sure|cool|great|nice|done|on it|working on it|still working( on it)?|" +
      "good morning|gm|gn|good night|hi|hello|hey|yo|sounds good|will do|np|no problem|👍|🙏|✅)\\W*$"
  )
  private val status = rx("\\b(i'?m (still )?(working|checking|looking) (on|into)|stand by|one moment|hang tight|be right back|give me a (sec|minute|moment))\\b")

  fun signals(text: String, hasCode: Boolean, f: Features): Set<Signal> {
    val s = LinkedHashSet<Signal>()
    if (hasCode) s += Signal.OTP
    if (urgent.containsMatchIn(text)) s += Signal.URGENT
    if (moneyProblem.containsMatchIn(text)) s += Signal.MONEY_PROBLEM
    if (f.amounts.isNotEmpty() || money.containsMatchIn(text)) s += Signal.MONEY
    if (question.containsMatchIn(text)) s += Signal.QUESTION
    if (action.containsMatchIn(text)) s += Signal.ACTION_REQUEST
    if (deadline.containsMatchIn(text)) s += Signal.DEADLINE
    if (confirmation.containsMatchIn(text)) s += Signal.CONFIRMATION
    if (promo.containsMatchIn(text)) s += Signal.PROMO
    if (chatter.containsMatchIn(text.trim()) || (text.length < 80 && status.containsMatchIn(text))) s += Signal.CHATTER
    return s
  }

  fun evaluate(text: String, hasCode: Boolean, f: Features, sentAt: Long): Verdict {
    val s = signals(text, hasCode, f)
    fun v(l: Lane, c: Confidence, why: String, llm: Boolean = false) = Verdict(l, c, s, why, llm)
    val soon = f.whenAt != null && f.whenAt - sentAt <= needsYouHorizonMs
    val asks = Signal.QUESTION in s || Signal.ACTION_REQUEST in s
    return when {
      Signal.OTP in s -> v(Lane.NEEDS_YOU, Confidence.HIGH, "sign-in code")
      Signal.URGENT in s || Signal.MONEY_PROBLEM in s ->
        if (Signal.PROMO in s) v(Lane.NOISE, Confidence.MEDIUM, "looks promotional but mentions a problem", llm = true)
        else v(Lane.NEEDS_YOU, Confidence.HIGH, if (Signal.MONEY_PROBLEM in s) "money problem" else "urgent")
      Signal.PROMO in s && asks -> v(Lane.NOISE, Confidence.MEDIUM, "promotional question", llm = true)
      Signal.PROMO in s -> v(Lane.NOISE, Confidence.HIGH, "promotional")
      Signal.CHATTER in s && !asks -> v(Lane.NOISE, Confidence.HIGH, "small talk or status")
      asks -> v(Lane.NEEDS_YOU, Confidence.HIGH, if (Signal.QUESTION in s) "asks you something" else "needs your action")
      Signal.DEADLINE in s && Signal.MONEY in s && Signal.CONFIRMATION !in s -> v(Lane.NEEDS_YOU, Confidence.HIGH, "payment due")
      Signal.DEADLINE in s && Signal.CONFIRMATION !in s && (soon || f.whenAt == null) ->
        v(Lane.NEEDS_YOU, Confidence.MEDIUM, "deadline soon")
      Signal.DEADLINE in s && Signal.CONFIRMATION !in s -> v(Lane.FYI, Confidence.MEDIUM, "deadline later")
      Signal.CONFIRMATION in s -> v(Lane.FYI, Confidence.HIGH, if (Signal.MONEY in s) "receipt" else "confirmation")
      Signal.MONEY in s -> v(Lane.FYI, Confidence.MEDIUM, "money update")
      f.whenAt != null -> v(Lane.FYI, Confidence.MEDIUM, "upcoming")
      else -> v(Lane.FYI, Confidence.LOW, "unclear", llm = true)
    }
  }
}

// ------------------------------------------------------------------ LLM fallback

@Serializable
data class ClassifyItem(
  /** Opaque reference echoed back (the item id). */
  val ref: String,
  val agent: String,
  /** Redacted, trimmed. Checked by [LlmGate] before it leaves the device. */
  val text: String,
  val signals: List<String> = emptyList(),
)

@Serializable
data class ClassifyAnswer(val ref: String, val lane: String, val reason: String = "")

/**
 * Batch classification by the main agent's model. Implementations get already-redacted text only.
 * Throw on any failure (signed out, network, bad JSON); the inbox counts the attempt and falls back.
 */
interface InboxClassifier {
  suspend fun classify(items: List<ClassifyItem>): List<ClassifyAnswer>
}

/** Sends one Responses API request body and returns the model's output text. The app wires this to the
 *  chatgpt-auth module's ResponsesClient (see README); tests use a fake. Never called by the inbox for code items. */
fun interface ResponsesTransport {
  suspend fun send(body: JsonObject): String
}

/** Last check before anything leaves the phone. */
object LlmGate {
  class Blocked(msg: String) : IllegalStateException(msg)

  fun check(items: List<ClassifyItem>) {
    for (i in items) if (Redactor.looksLikeCode(i.text)) throw Blocked("item ${i.ref} still looks like it holds a code")
  }

  fun check(text: String) { if (Redactor.looksLikeCode(text)) throw Blocked("text still looks like it holds a code") }
}

/**
 * [InboxClassifier] over ChatGPT structured outputs (Responses API, `text.format` = strict `json_schema`).
 * Output: `{"items":[{"ref":"…","lane":"needs_you|fyi|noise","reason":"…"}]}`.
 */
class ChatGptJsonClassifier(
  private val transport: ResponsesTransport,
  /** Use the model Juno runs on; plan routes list theirs via ResponsesClient.listModels(). */
  private val model: String,
) : InboxClassifier {
  private val json = Json { ignoreUnknownKeys = true }

  @Serializable private data class Out(val items: List<ClassifyAnswer>)

  fun requestBody(items: List<ClassifyItem>): JsonObject {
    LlmGate.check(items)
    val payload = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ClassifyItem.serializer()), items)
    return buildJsonObject {
      put("model", model)
      put("store", false)
      put("instructions", INSTRUCTIONS)
      putJsonArray("input") {
        addJsonObject { put("type", "message"); put("role", "user"); put("content", payload) }
      }
      putJsonObject("text") {
        putJsonObject("format") {
          put("type", "json_schema")
          put("name", "inbox_triage")
          put("strict", true)
          put("schema", SCHEMA)
        }
      }
    }
  }

  override suspend fun classify(items: List<ClassifyItem>): List<ClassifyAnswer> {
    if (items.isEmpty()) return emptyList()
    val out = transport.send(requestBody(items))
    val parsed = json.decodeFromString(Out.serializer(), out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    val refs = items.map { it.ref }.toSet()
    return parsed.items.filter { it.ref in refs && laneOf(it.lane) != null }
  }

  companion object {
    fun laneOf(s: String): Lane? = when (s.trim().lowercase().replace(' ', '_').replace('-', '_')) {
      "needs_you", "needsyou" -> Lane.NEEDS_YOU
      "fyi" -> Lane.FYI
      "noise" -> Lane.NOISE
      else -> null
    }

    const val INSTRUCTIONS =
      "You triage unprompted text messages that the user's AI agents sent him. For each item choose a lane: " +
        "needs_you = he must decide, reply, pay, sign, or act soon; fyi = useful to know, no action; " +
        "noise = promotional, small talk, status chatter, or repeated with nothing new. Prefer fyi when unsure. " +
        "Placeholders like [code] are redacted on purpose; never guess them. Reason: at most 8 words. Echo each ref."

    val SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      put("additionalProperties", false)
      putJsonArray("required") { add("items") }
      putJsonObject("properties") {
        putJsonObject("items") {
          put("type", "array")
          putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("ref"); add("lane"); add("reason") }
            putJsonObject("properties") {
              putJsonObject("ref") { put("type", "string") }
              putJsonObject("lane") { put("type", "string"); putJsonArray("enum") { add("needs_you"); add("fyi"); add("noise") } }
              putJsonObject("reason") { put("type", "string") }
            }
          }
        }
      }
    }
  }
}
