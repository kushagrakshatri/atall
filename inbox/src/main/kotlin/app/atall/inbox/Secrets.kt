package app.atall.inbox

/**
 * Finds verification codes (OTP, 2FA, sign-in codes, PINs, temporary passwords, magic sign-in links) and replaces
 * them with placeholders, entirely on device. Runs on every message before it is stored, deduped, triaged,
 * shown in a digest, or sent to the LLM.
 *
 * Rule: a number-like token counts as a code only within [PROXIMITY] characters of a code keyword ("code",
 * "OTP", "verification", "passcode", "PIN", "2FA", ...). That keeps prices, times, phone numbers and order
 * numbers in ordinary messages intact, and over-redacts a little inside code messages, which is the safe side.
 */
object Redactor {
  const val CODE = "[code]"
  const val LINK = "[sign-in link]"
  const val SECRET = "[secret]"
  const val PROXIMITY = 60

  private val keyword = Regex(
    "(?i)\\b(codes?|otp|passcodes?|pass code|verification|verify|verifying|one[- ]time|2fa|mfa|two[- ]factor|" +
      "security code|log ?in code|sign[- ]?in code|auth(?:entication)? code|pin)\\b"
  )
  /** "promo code", "zip code", "code review"...: the word "code" that is not about signing in. */
  private val notACode = Regex(
    "(?i)\\b(promo|discount|coupon|zip|postal|area|dress|source|qr|referral|gift|country|error|status|bar|tracking|confirmation|booking|order)\\s+codes?\\b|\\bcodes?\\s+(review|base|freeze|red)\\b|\\bpin(ned|s)?\\s+(it|this|that|the)\\b"
  )

  private fun keywordSpans(s: String): List<IntRange> {
    val bad = notACode.findAll(s).map { it.range }.toList()
    return keyword.findAll(s).map { it.range }.filter { k -> bad.none { b -> k.first >= b.first && k.last <= b.last } }.toList()
  }

  private val linkKeyword = Regex("(?i)\\b(magic link|sign[- ]?in link|log ?in link|verification link|verify link|one[- ]time link)\\b")

  // Ordered: most specific first. Each must not touch money ($12.50), times (10:30), percentages, or long ids.
  private val candidates = listOf(
    Regex("\\b[A-Z]{1,3}-\\d{4,8}\\b"), // G-123456
    Regex("(?<![\\d$£€₹.,:/-])\\d{3}[ -]\\d{3}(?![ -]?\\d)(?![.,:/%]\\d)"), // 123 456 / 123-456 (not a phone number)
    Regex("(?<![\\d$£€₹.,:/#-])\\d{4,8}(?![\\d%])(?![.,:/]\\d)(?!\\s?(?:am|pm|AM|PM)\\b)"), // 482913
    Regex("\\b(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,8}\\b"), // X7K2P9
  )
  private val labelled = Regex(
    "(?i)\\b(temporary password|temp password|one[- ]time password|password|passcode|pin)(\\s*(?:is|:|=|-)\\s*)([^\\s,.;]{3,64})"
  )
  private val url = Regex("(?i)\\bhttps?://\\S+")

  data class Result(val text: String, val codes: List<String>) {
    val hasCode: Boolean get() = codes.isNotEmpty()
  }

  /** Redacts codes in [text]. [Result.codes] are the raw values (keep them in memory only: [CodeShelf]). */
  fun redact(text: String): Result {
    val found = ArrayList<String>()
    var out = text

    // 1) "password: hunter2" / "PIN is 4821": the value after the label, whatever its shape.
    out = labelled.replace(out) { m ->
      val value = m.groupValues[3]
      if (value.startsWith("[")) m.value else { found += value; m.groupValues[1] + m.groupValues[2] + SECRET }
    }

    // 2) Number-like tokens near a code keyword.
    if (keywordSpans(out).isNotEmpty()) {
      for (rx in candidates) {
        val spans = keywordSpans(out)
        out = rx.replace(out) { m ->
          val near = spans.any { k -> distance(k, m.range) <= PROXIMITY }
          if (near && !insidePlaceholder(out, m.range)) { found += m.value; CODE } else m.value
        }
      }
    }

    // 3) Magic sign-in links: the URL itself is the credential.
    if (linkKeyword.containsMatchIn(out) || (keywordSpans(out).isNotEmpty() && Regex("(?i)\\b(sign|log)[- ]?in\\b").containsMatchIn(out))) {
      out = url.replace(out) { m -> found += m.value; LINK }
    }
    return Result(out, found)
  }

  /** True when [text] still looks like it holds a code. The LLM gate refuses to send such text. */
  fun looksLikeCode(text: String): Boolean = redact(text).hasCode

  private fun distance(a: IntRange, b: IntRange): Int = when {
    b.first > a.last -> b.first - a.last
    a.first > b.last -> a.first - b.last
    else -> 0
  }

  private fun insidePlaceholder(s: String, r: IntRange): Boolean =
    r.first > 0 && s[r.first - 1] == '[' // never re-redact our own placeholders
}

/**
 * The only place a raw code lives: memory, keyed by item id, gone after [ttlMs] or process death. Never
 * persisted, never serialized. The card offers "Show code" while it is here.
 */
class CodeShelf(private val clock: Clock, private val ttlMs: Long = 10 * 60_000L) {
  private data class Entry(val codes: List<String>, val until: Long)
  private val map = HashMap<String, Entry>()

  @Synchronized fun put(itemId: String, codes: List<String>) {
    if (codes.isEmpty()) return
    val prev = map[itemId]?.codes.orEmpty()
    map[itemId] = Entry((prev + codes).distinct(), clock.now() + ttlMs)
  }

  /** The newest code for [itemId] while still fresh, else null. */
  @Synchronized fun reveal(itemId: String): String? {
    val e = map[itemId] ?: return null
    if (e.until <= clock.now()) { map.remove(itemId); return null }
    return e.codes.last()
  }

  @Synchronized fun has(itemId: String): Boolean = reveal(itemId) != null

  @Synchronized fun purge() { val now = clock.now(); map.entries.removeAll { it.value.until <= now } }

  @Synchronized fun forget(itemId: String) { map.remove(itemId) }

  override fun toString() = "CodeShelf(${map.size} entries)" // never print codes
}
