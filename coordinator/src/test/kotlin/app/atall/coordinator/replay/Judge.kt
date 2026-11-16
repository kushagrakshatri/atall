package app.atall.coordinator.replay

import app.atall.coordinator.Items
import app.atall.coordinator.Llm
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.Schema
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Offline replay only. A judge compares two answers to one question and never answers anything itself (Kush,
 * 2026-11-16: an outside, non-OpenAI grader is OK for judging). It always sees anonymised answers ("Answer 1",
 * "Answer 2"); [PairJudging] adds the order swap and the length control from measure.md §1/§3.4.
 */
interface Judge {
  val name: String
  /** Which of the two (already anonymised) answers is better for the question. */
  suspend fun prefer(question: String, first: String, second: String): Preference
}

enum class Preference { FIRST, SECOND, TIE }

internal const val JUDGE_INSTRUCTIONS = """You judge which of two answers better serves the person who asked. Judge usefulness for them: specific, correct, fits what they said, nothing invented. Longer is not better. The answers are anonymous; ignore their order. Reply with exactly one of: 1, 2, same."""

internal fun judgeInput(q: String, a: String, b: String) = "Question: $q\n\nAnswer 1:\n$a\n\nAnswer 2:\n$b"

internal fun readPreference(raw: String?): Preference {
  val t = raw?.trim()?.lowercase().orEmpty()
  val v = runCatching { (Json.parseToJsonElement(t).jsonObject["better"] as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: t
  return when {
    v.startsWith("1") || v == "first" -> Preference.FIRST
    v.startsWith("2") || v == "second" -> Preference.SECOND
    else -> Preference.TIE
  }
}

/** Juno's own model as the judge (same model family as Juno's merge: self-preference is a known risk, measure.md §3.4). */
class ModelJudge(private val llm: Llm) : Judge {
  override val name = "juno-model"
  private val schema = Schema.obj("better" to Schema.string("The better answer", listOf("1", "2", "same")))
  override suspend fun prefer(question: String, first: String, second: String): Preference =
    readPreference(runCatching { llm.respond(LlmRequest("judge", JUDGE_INSTRUCTIONS, listOf(Items.user(judgeInput(question, first, second))), jsonSchema = schema, schemaName = "judgement")).text }.getOrNull())
}

/**
 * An outside grading model over an Anthropic-style Messages endpoint. Configured only by environment variables, all
 * unset by default and never committed: ATALL_JUDGE_ENDPOINT, ATALL_JUDGE_KEY, ATALL_JUDGE_MODEL. It only ever
 * receives the question and two anonymised answers.
 */
class OutsideJudge(
  private val endpoint: String,
  private val key: String,
  private val model: String,
  /** (url, headers, body) -> response body. Real HTTP by default; a fake in tests. */
  private val post: (String, Map<String, String>, String) -> String = ::httpPost,
) : Judge {
  override val name = "outside:$model"

  override suspend fun prefer(question: String, first: String, second: String): Preference {
    val body = buildJsonObject {
      put("model", model); put("max_tokens", 8); put("system", JUDGE_INSTRUCTIONS)
      put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", judgeInput(question, first, second)) }) })
    }.toString()
    val out = runCatching { post(endpoint, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"), body) }.getOrNull()
    val text = out?.let { runCatching { ((Json.parseToJsonElement(it).jsonObject["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content }.getOrNull() }
    return readPreference(text)
  }

  companion object {
    fun fromEnv(env: Map<String, String>): OutsideJudge? {
      val e = env["ATALL_JUDGE_ENDPOINT"]?.trim().orEmpty(); val k = env["ATALL_JUDGE_KEY"]?.trim().orEmpty(); val m = env["ATALL_JUDGE_MODEL"]?.trim().orEmpty()
      return if (e.isEmpty() || k.isEmpty() || m.isEmpty()) null else OutsideJudge(e, k, m)
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): String {
      val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body))
      headers.forEach { (k, v) -> req.header(k, v) }
      return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString()).body()
    }
  }
}

object Judges {
  /** The outside judge when it is configured, otherwise Juno's own model. */
  fun default(junoModel: Llm, env: Map<String, String> = System.getenv()): Judge = OutsideJudge.fromEnv(env) ?: ModelJudge(junoModel)
}

/** One pairwise judgement with the bias controls applied. [aWords]/[bWords] feed the length control. */
data class PairResult(val outcome: Preference, val aWords: Int, val bWords: Int)

object PairJudging {
  /**
   * A vs B with names hidden and both orders: A wins only if it wins in both orders (same for B), otherwise it's a tie
   * (position bias cancels out). [hide]: agent names to replace with "an assistant" before the judge sees anything.
   */
  suspend fun judge(judge: Judge, question: String, a: String, b: String, hide: Collection<String> = emptyList()): PairResult {
    val ha = anonymise(a, hide); val hb = anonymise(b, hide)
    val p1 = judge.prefer(question, ha, hb)
    val p2 = judge.prefer(question, hb, ha)
    val outcome = when {
      p1 == Preference.FIRST && p2 == Preference.SECOND -> Preference.FIRST
      p1 == Preference.SECOND && p2 == Preference.FIRST -> Preference.SECOND
      else -> Preference.TIE
    }
    return PairResult(outcome, words(a), words(b))
  }

  fun anonymise(text: String, names: Collection<String>): String = names.filter { it.isNotBlank() }.sortedByDescending { it.length }
    .fold(text) { t, n -> t.replace(Regex("(?i)(?<![\\p{L}\\p{N}])@?" + Regex.escape(n) + "(?:'s)?(?![\\p{L}\\p{N}])"), "an assistant") }

  fun words(t: String) = t.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** Win rates: raw (ties dropped), Wilson 95% interval, and length-controlled (measure.md §1, after LC-AlpacaEval). */
object WinRate {
  data class Report(val wins: Int, val losses: Int, val ties: Int, val rate: Double?, val low: Double?, val high: Double?, val lengthControlled: Double?)

  fun of(results: List<PairResult>): Report {
    val w = results.count { it.outcome == Preference.FIRST }; val l = results.count { it.outcome == Preference.SECOND }
    val n = w + l
    val (lo, hi) = if (n == 0) null to null else wilson(w, n)
    return Report(w, l, results.size - n, if (n == 0) null else w.toDouble() / n, lo, hi, lengthControlled(results))
  }

  private fun wilson(w: Int, n: Int): Pair<Double, Double> {
    val z = 1.96; val p = w.toDouble() / n
    val d = 1 + z * z / n; val c = p + z * z / (2 * n); val r = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))
    return (c - r) / d to (c + r) / d
  }

  /**
   * P(A wins) at equal length: logistic regression of the outcome on the scaled length difference (tanh of words/100),
   * read off at difference 0. Null with fewer than 4 non-tied pairs. Plain Newton steps, a small ridge for stability.
   */
  fun lengthControlled(results: List<PairResult>): Double? {
    val xs = results.filter { it.outcome != Preference.TIE }
    if (xs.size < 4) return null
    val x = xs.map { Math.tanh((it.aWords - it.bWords) / 100.0) }; val y = xs.map { if (it.outcome == Preference.FIRST) 1.0 else 0.0 }
    var b0 = 0.0; var b1 = 0.0
    repeat(50) {
      var g0 = -0.01 * b0; var g1 = -0.01 * b1; var h00 = 0.01; var h01 = 0.0; var h11 = 0.01
      for (i in x.indices) {
        val p = 1 / (1 + Math.exp(-(b0 + b1 * x[i]))); val wgt = p * (1 - p)
        g0 += y[i] - p; g1 += (y[i] - p) * x[i]; h00 += wgt; h01 += wgt * x[i]; h11 += wgt * x[i] * x[i]
      }
      val det = h00 * h11 - h01 * h01
      if (Math.abs(det) < 1e-12) return@repeat
      b0 += (h11 * g0 - h01 * g1) / det; b1 += (-h01 * g0 + h00 * g1) / det
    }
    return 1 / (1 + Math.exp(-b0))
  }
}
