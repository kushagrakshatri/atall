package app.atall.agents.text

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A registered texting agent. [name] is the display name and the primary match against the Messages
 * conversation title ("qBit", "Wren"); [aliases] are other titles Messages may show (a contact name);
 * [number] is its texting number (E.164 preferred), needed for the draft fallback and for number titles.
 */
@Serializable
data class AgentConfig(
  val id: AgentId,
  val name: String,
  val number: String = "",
  val aliases: List<String> = emptyList(),
) {
  init { require(name.isNotBlank()) { "blank agent name" } }

  /** Normalized copy: trimmed name, E.164-ish number, distinct non-blank aliases. */
  fun normalized(): AgentConfig = copy(
    name = name.trim(),
    number = Rules.normalizeNumber(number),
    aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Rules.fold(it) },
  )
}

/** Pure matching and key rules (ported from the Route C spike's AgentRules, hardened). */
object Rules {
  const val MESSAGES_PACKAGE = "com.google.android.apps.messaging"

  /** Lowercase, whitespace collapsed; for title comparison only. */
  fun fold(s: String?): String = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT) ?: ""

  /** "+1 (408) 555-0101" -> "+14085550101"; "" when it holds no digits. Keeps a leading "+". */
  fun normalizeNumber(s: String?): String {
    if (s == null) return ""
    val digits = s.filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    return (if (s.trim().startsWith("+")) "+" else "") + digits
  }

  private fun digits(s: String?) = s?.filter { it in '0'..'9' } ?: ""

  /** The last 10 digits, so "+1 650..." and "(650)..." compare equal. */
  internal fun tail(number: String?): String = digits(number).let { if (it.length > 10) it.takeLast(10) else it }

  /** A title that is only a phone number or short code ("+1 408-555-0101", "22395"). */
  internal fun looksLikeNumber(title: String?): Boolean =
    title != null && title.trim().matches(Regex("[+0-9()\\-.\\s]{3,}")) && digits(title).length >= 3

  /**
   * Exact match on name or alias (folded), or on the number when the title is a number:
   * last-10-digit equality for full numbers, exact digits for short codes (< 7 digits). Never a substring.
   */
  fun matches(agent: AgentConfig, title: String?): Boolean {
    val t = fold(title)
    if (t.isEmpty()) return false
    if (t == fold(agent.name)) return true
    if (agent.aliases.any { fold(it) == t }) return true
    if (agent.number.isNotEmpty() && looksLikeNumber(title)) {
      val a = digits(agent.number)
      val b = digits(title)
      return if (a.length < 7 || b.length < 7) a == b else tail(a) == tail(b)
    }
    return false
  }

  fun find(agents: List<AgentConfig>, title: String?): AgentConfig? = agents.firstOrNull { matches(it, title) }

  fun sha(text: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return d.take(8).joinToString("") { "%02x".format(it) }
  }

  /** Dedupe key of a timestamped line (Messages re-posts recent history with every notification). */
  fun messageId(agent: AgentId, time: Long, text: String, self: Boolean = false): String =
    (if (self) "self|" else "in|") + agent.value + "|" + time + "|" + sha(text.trim())

  /** Folded text for echo comparison: our sent text vs the user's line Messages shows back. */
  fun sameText(a: String, b: String): Boolean = fold(a) == fold(b)
}
