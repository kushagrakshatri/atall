package app.atall.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Canonical JSON for relay countersignatures: object keys sorted (UTF-16 code-unit order, like JS `sort()`), no
 * whitespace, strings and numbers written exactly as JavaScript's `JSON.stringify` writes them. That is RFC 8785
 * (JCS), which is what a Deno relay doing `JSON.stringify` over sorted keys produces.
 */
object CanonicalJson {
    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sortedWith(Comparator { a, b -> a.compareTo(b) }).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out); out.append(':'); write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> string(e.content, out)
                e.content == "true" || e.content == "false" -> out.append(e.content)
                else -> out.append(number(e.content))
            }
        }
    }

    /** JSON.stringify string escaping: only `"`, `\`, control characters and lone surrogates are escaped. */
    fun string(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(hex4(c))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { out.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> out.append("\\u").append(hex4(c))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun hex4(c: Char) = c.code.toString(16).padStart(4, '0')

    /** A JSON number literal as ECMAScript's Number::toString writes it (1.0 -> 1, 1e2 -> 100, 1e21 -> 1e+21). */
    fun number(literal: String): String {
        if (Regex("-?(0|[1-9][0-9]{0,14})").matches(literal)) return if (literal == "-0") "0" else literal
        val d = literal.toDouble()
        require(!d.isNaN() && !d.isInfinite()) { "not a finite JSON number: $literal" }
        if (d == 0.0) return "0"
        val neg = d < 0
        // Shortest round-tripping digits as digits × 10^(n − k). (Double.toString isn't shortest before JDK 19.)
        val exact = BigDecimal(Math.abs(d))
        val bd = (1..17).asSequence().map { exact.round(java.math.MathContext(it, java.math.RoundingMode.HALF_EVEN)) }
            .first { it.toDouble() == Math.abs(d) }.stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale()
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mant = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mant + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
            }
        }
        return if (neg) "-$body" else body
    }
}
