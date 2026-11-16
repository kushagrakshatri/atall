package app.atall.coordinator

import kotlinx.serialization.Serializable

/**
 * The self-filled tail every ask from Juno ends with (collab plan step 1). The agent declares its own angle, per ask,
 * from its current self: nothing about what an agent is good at is hard-coded anywhere.
 */
@Serializable
data class Tail(
  /** What it adds that another assistant probably isn't. */
  val angle: String? = null,
  /** Up to 3 things it knows about the user from its own memory that bear on this (with how it knows, if it said). */
  val facts: List<String> = emptyList(),
  /** What it would need to be surer. */
  val cantSee: String? = null,
  /** One thing it could do or check next. */
  val next: String? = null,
) {
  val isEmpty: Boolean get() = angle == null && facts.isEmpty() && cantSee == null && next == null
}

/**
 * An agent's reply split into the answer (what the thread may show) and its tail (never shown raw). [hadBody] is false
 * when the reply was only a tail (then [body] says it in plain words).
 */
data class TailedReply(val body: String, val tail: Tail, val hadBody: Boolean = true)

object AskTail {
  const val MAX_FACTS = 3

  /**
   * The lines appended to an ask. Plain text (SMS shows Markdown raw), short, and free of action words so the send gate
   * never trips on it. [userName] blank means "the user".
   */
  fun text(userName: String): String {
    val who = userName.trim().ifBlank { "the user" }
    return listOf(
      "After your answer, add four short lines in your own words:",
      "Angle: what you're adding that another assistant probably isn't",
      "From memory: up to 3 things you know about $who that bear on this, and how you know",
      "Can't see: what you'd need to be surer",
      "Next: one thing you could check or do next, if asked",
    ).joinToString("\n")
  }

  private enum class Field { ANGLE, FACTS, CANT_SEE, NEXT }

  // Lenient label matching: case, Markdown bold/italics, bullets, numbering, and the usual wordings agents use.
  private val labels: List<Pair<Field, Regex>> = listOf(
    Field.ANGLE to "(?:my\\s+)?angle|what\\s+i(?:'m|\\s+am)\\s+adding|my\\s+take",
    Field.FACTS to "from\\s+(?:my\\s+)?memory|what\\s+i\\s+know(?:\\s+about\\s+[\\p{L}' ]{1,20})?|memory|facts?\\s+about\\s+[\\p{L}' ]{1,20}",
    Field.CANT_SEE to "can(?:'|’)?t\\s+see|cannot\\s+see|can\\s+not\\s+see|what\\s+i\\s+can(?:'|’)?t\\s+see|blind\\s+spots?|i\\s+can(?:'|’)?t\\s+see",
    Field.NEXT to "next(?:\\s+step)?|could\\s+also|one\\s+next\\s+step|i\\s+could\\s+also",
  ).map { (f, p) -> f to Regex("^\\s*(?:[-*•]+\\s*|\\d+[.)]\\s*)?[*_]{0,2}(?:$p)[*_]{0,2}\\s*(?:[:：—–-]|\\*\\*\\s*:|:\\s*\\*\\*)[*_]{0,2}\\s*(.*)$", RegexOption.IGNORE_CASE) }

  private val none = Regex("^(?:none|nothing|n/?a|no(?:thing)?\\s+(?:relevant|specific|in\\s+memory)?|-+|—|nothing\\s+relevant)\\.?$", RegexOption.IGNORE_CASE)

  /**
   * Splits [reply] into its answer and its tail. Never throws; a reply without a tail is all body. The tail starts at the
   * first labelled line that has at least one more labelled line after it, or at a labelled line that ends the reply,
   * so an answer that happens to say "Next: Sunday" in its middle stays body.
   */
  fun parse(reply: String): TailedReply {
    val lines = reply.replace("\r\n", "\n").lines()
    val hits = lines.mapIndexedNotNull { i, l -> labels.firstNotNullOfOrNull { (f, r) -> r.find(l)?.let { Triple(i, f, it.groupValues[1].trim()) } } }
    if (hits.isEmpty()) return TailedReply(reply.trim(), Tail())
    // The tail is the trailing block of labelled lines plus the short lines under them. Walk back from the last label
    // while the lines in between are short (a list of facts, a wrapped sentence); a long paragraph ends the walk.
    val afterLast = lines.drop(hits.last().first + 1).filter { it.isNotBlank() }
    if (afterLast.any { !isContinuation(it) } || afterLast.size > MAX_FACTS + 2) return TailedReply(reply.trim(), Tail())
    var start = hits.last().first
    for (h in hits.reversed().drop(1)) {
      val between = (h.first + 1 until start).map { lines[it] }.filter { it.isNotBlank() }
      if (between.all { isContinuation(it) } && between.size <= MAX_FACTS + 2) start = h.first else break
    }
    val tailHits = hits.filter { it.first >= start }
    // One labelled line alone counts only when it ends the reply ("Next: Sunday" in the middle of an answer stays body).
    if (tailHits.size == 1 && afterLast.isNotEmpty() && tailHits[0].second != Field.FACTS) return TailedReply(reply.trim(), Tail())
    var angle: String? = null; var cant: String? = null; var next: String? = null
    val facts = mutableListOf<String>()
    tailHits.forEachIndexed { k, (i, f, first) ->
      val end = tailHits.getOrNull(k + 1)?.first ?: lines.size
      val cont = (i + 1 until end).map { lines[it].trim() }.filter { it.isNotEmpty() }.map { clean(it) }
      when (f) {
        Field.FACTS -> {
          val items = (splitFacts(clean(first)) + cont.flatMap { splitFacts(it) }).filter { it.isNotBlank() && !none.matches(it) }
          facts += items
        }
        else -> {
          val v = (listOf(clean(first)) + cont).filter { it.isNotBlank() }.joinToString(" ").trim().takeIf { it.isNotEmpty() && !none.matches(it) }
          when (f) { Field.ANGLE -> angle = angle ?: v; Field.CANT_SEE -> cant = cant ?: v; Field.NEXT -> next = next ?: v; else -> {} }
        }
      }
    }
    val body = lines.take(start).joinToString("\n").trim().trimEnd('-', '—', ' ', '\n')
      // Some agents put a heading before the tail ("A few notes:" / "---").
      .replace(Regex("\\n+\\s*(?:-{3,}|_{3,}|\\*{3,})\\s*$"), "").trim()
    val tail = Tail(angle, facts.distinctBy { it.lowercase() }.take(MAX_FACTS), cant, next)
    // A reply that is only a tail: show what it says in plain words, never the labels.
    val shown = body.ifEmpty { listOfNotNull(tail.angle, tail.facts.joinToString("; ").ifEmpty { null }, tail.next).joinToString(". ").ifEmpty { reply.trim() } }
    return TailedReply(shown, tail, body.isNotEmpty())
  }

  /** The answer part only: what the thread shows. */
  fun strip(reply: String): String = parse(reply).body

  private fun isContinuation(l: String) = l.trim().length <= 200

  private fun clean(s: String) = s.trim().replace(Regex("^(?:[-*•]+|\\d+[.)])\\s*"), "").replace(Regex("[*_]{2}"), "").trim()

  /** "a; b; c" or "1) a 2) b" on one line become separate facts; a plain sentence stays one. */
  private fun splitFacts(s: String): List<String> {
    if (s.isBlank()) return emptyList()
    val numbered = Regex("(?:^|\\s)\\(?\\d[.)]\\s+").split(s).map { it.trim() }.filter { it.isNotEmpty() }
    if (numbered.size > 1) return numbered
    return s.split(';').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.map { it }
  }
}
