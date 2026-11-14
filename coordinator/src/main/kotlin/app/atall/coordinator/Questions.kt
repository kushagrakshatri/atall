package app.atall.coordinator

/**
 * The questions agents ask the user, folded into one: two answers asking "What's your budget?" and "roughly what
 * budget?" are one question. Concepts are coarse on purpose (budget, timing, tastes, relationship); anything else is
 * kept word for word.
 */
object Questions {
  private val CONCEPTS = listOf(
    "budget" to Regex("\\b(budget|spend|price|cost|afford)\\b", RegexOption.IGNORE_CASE),
    "timing" to Regex("\\b(when|date|deadline|by what day)\\b", RegexOption.IGNORE_CASE),
    "tastes" to Regex("\\b(love|loves|into|tastes?|likes?|hobb(y|ies)|interests?|enjoy)\\b", RegexOption.IGNORE_CASE),
    "relationship" to Regex("\\b(know (her|him|them)|to you|relationship|how do you know)\\b", RegexOption.IGNORE_CASE),
  )

  private val PRIORITY = mapOf("budget" to 0, "timing" to 1)

  fun concept(q: String): String = CONCEPTS.firstOrNull { it.second.containsMatchIn(q) }?.first ?: Agreement.normalize(q)

  /** "What's your budget?" -> "what's your budget" (one clause, no question mark). Null when blank. */
  fun topic(q: String): String? {
    val one = q.trim().split(Regex("(?<=\\?)\\s+")).first().trim().trimEnd('?', '.', ' ')
    if (one.isEmpty()) return null
    return one.replaceFirstChar { it.lowercase() }
  }

  /** At most [max] distinct questions, joined into one: "What's your budget, and when is her birthday?" */
  fun combine(questions: List<String>, max: Int = 2): String? {
    val seen = LinkedHashMap<String, String>()
    for (q in questions) {
      val t = topic(q) ?: continue
      val c = concept(t)
      if (c !in seen) seen[c] = t
    }
    // Only the user can set a budget or a date; tastes and who someone is are often known to an agent already.
    val picked = seen.entries.sortedBy { PRIORITY[it.key] ?: 2 }.map { it.value }.take(max)
    if (picked.isEmpty()) return null
    val joined = if (picked.size == 1) picked[0] else picked.dropLast(1).joinToString(", ") + ", and " + picked.last()
    return joined.replaceFirstChar { it.uppercase() } + "?"
  }
}
