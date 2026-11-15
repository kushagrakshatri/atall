package app.atall.agents.text

import java.util.Locale

/**
 * Texts an agent sends that are about the conversation, not an answer to it: its own connection/signal errors
 * ("Looks like your signal dropped…"), holding replies ("Let me check", "One sec"), typing placeholders and
 * delivery failures. Like a tapback, such a line must never count as the agent's answer: it doesn't close the
 * agent's slot, and the thread shows it as a small muted status line.
 *
 * The test is deliberately narrow (m05, the 2026-11-14 device run where Hark's "signal dropped" text was merged as
 * its answer): the text is short and every sentence in it is a status sentence or status filler ("Feel free to keep
 * texting", "I'll get back to you"), with at least one real status sentence. "On it! Get her a Kindle." is an
 * answer; "On it, one sec." is not.
 */
object NonAnswers {
  enum class Kind { CONNECTION, HOLDING, TYPING, DELIVERY_FAILED }

  /** Longer than this is treated as an answer whatever it says. */
  const val MAX_LENGTH = 280

  private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

  private val connection = listOf(
    re("""\bsignal (dropped|was lost|is weak|went out)\b"""),
    re("""\b(lost|dropped|losing|lose) (the |your |my |our )?(connection|signal|service|network)\b"""),
    re("""\b(connection|network|signal) (was |is |has |got )?(lost|dropped|interrupted|unstable|down|issue|issues|error|problem|problems)\b"""),
    re("""\b(you('re| are| seem| appear)|i('m| am)|we('re| are)) (to be )?(offline|disconnected)\b"""),
    re("""\bonce (you('re| are)|i('m| am)|we('re| are)) back online\b"""),
    re("""\b(network|connection|server|service|internal|unexpected) error\b"""),
    re("""\b(an )?error (occurred|has occurred|happened)\b"""),
    re("""\bsomething went wrong\b"""),
    re("""\b(couldn'?t|could not|can'?t|cannot|unable to) (connect|reach (the )?(server|service|network))\b"""),
    re("""\bhaving (some )?trouble (connecting|reaching|loading)\b"""),
    re("""\b(service|server|assistant|agent|i('m| am)|this (number|service|line)) (is )?(temporarily |currently )?(unavailable|down|offline)\b"""),
    re("""^(please )?try again (later|in a (bit|moment|few minutes))$"""),
    re("""\b(request|connection|session) (has )?timed out\b"""),
  )
  private val delivery = listOf(
    re("""\b(message )?(not delivered|undeliverable|failed to send|failed to deliver|could ?n[o']?t be delivered|was not delivered|delivery failed)\b"""),
    re("""\b(unable|couldn'?t|could not) (to )?(deliver|send) (your |this |the )?message\b"""),
    re("""\bmessage (failed|blocked|bounced)\b"""),
  )
  private val holding = listOf(
    re("""\blet me (check|look|see|think|find|dig|pull|get back|work|research|confirm)\b"""),
    re("""\b(give|gimme) me (a|one) (sec|second|moment|minute|min|bit)\b"""),
    re("""^(just )?(one|a|1) (sec|second|moment|minute|min)\b"""),
    re("""\b(hold on|hang on|hang tight|bear with me|stand by|be right back|brb)\b"""),
    re("""\b(i('m| am)|we('re| are)) (looking into (it|this|that)|checking( (on )?(it|this|that|now))?$|working on (it|this|that)|digging (in|into (it|this|that))|researching (it|this|that)|on it|on (this|that) now|pulling (it|this|that) up)"""),
    re("""^(i('m| am) )?thinking( about it| it over)?$"""),
    re("""^(ok(ay)?[, ]+)?(on it|working on it|looking into it|checking( now)?|thinking|got it,? (one|a) (sec|moment))\b"""),
    re("""\b(still )?working on (it|this|that|your (request|question))\b"""),
    re("""\bget(ting)? back to you\b"""),
  )
  private val typing = listOf(
    re("""^(\S+ )?(is )?typing\b"""),
    re("""^(…|\.{2,}|thinking…|thinking\.{2,})$"""),
  )

  /** Sentences that only carry status or politeness: allowed beside a status sentence, never one on their own. */
  private val filler = listOf(
    re("""\bfeel free to\b"""),
    re("""\bkeep texting\b"""),
    re("""\bi('ll| will) (get back|follow up|circle back|pick (it|this|that|everything|things) (back )?up|reply|respond|answer|text you|let you know|be (right )?back|send)"""),
    re("""\b(back online|reconnect(ed|s)?|when (you('re| are)|i('m| am)) back)\b"""),
    re("""^(ok(ay)?|sure|sounds good|got it|thanks|thank you|no worries|sorry|apologies|hmm+|hi|hey|hello)\b"""),
    re("""\b(sorry|apologies) (about|for) (that|the (delay|wait|trouble|inconvenience))\b"""),
    re("""\bplease (wait|resend|send (it|that|this) again|try again)\b"""),
    re("""\b(one|a) (sec|second|moment|minute)\b"""),
  )

  private val sentenceSplit = Regex("""(?<=[.!?…])\s+|\n+""")
  private val wordChar = Regex("""[\p{L}\p{N}]""")

  /** The kind of non-answer [text] is, or null when it may be a real answer. */
  fun classify(text: String): Kind? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_LENGTH) return null
    val lower = t.lowercase(Locale.ROOT)
    typing.firstOrNull { it.containsMatchIn(lower) }?.let { if (t.length <= 40) return Kind.TYPING }
    val rawSentences = t.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
    var kind: Kind? = null
    for (raw in rawSentences) {
      val s = raw.trimEnd('.', '!', '?', '…').trim()
      if (s.isEmpty() || !wordChar.containsMatchIn(s)) continue // emoji or punctuation only
      // A question back to the user ("Let me check — what's your budget?") is a reply that needs them.
      val question = raw.endsWith("?") && connection.none { it.containsMatchIn(s) } && delivery.none { it.containsMatchIn(s) }
      if (question) return null
      val k = when {
        delivery.any { it.containsMatchIn(s) } -> Kind.DELIVERY_FAILED
        connection.any { it.containsMatchIn(s) } -> Kind.CONNECTION
        holding.any { it.containsMatchIn(s) } && !hasSubstance(s) -> Kind.HOLDING
        else -> null
      }
      if (k != null) { if (kind == null || rank(k) > rank(kind)) kind = k; continue }
      if (filler.any { it.containsMatchIn(s) } && !hasSubstance(s)) continue
      return null // a sentence that says something else: treat the whole text as an answer
    }
    return kind
  }

  fun isNonAnswer(text: String): Boolean = classify(text) != null

  /** A short label for the muted status line ("Connection problem"). */
  fun label(kind: Kind): String = when (kind) {
    Kind.CONNECTION -> "connection problem"
    Kind.HOLDING -> "still working"
    Kind.TYPING -> "typing"
    Kind.DELIVERY_FAILED -> "delivery failed"
  }

  private fun rank(k: Kind) = when (k) { Kind.TYPING -> 0; Kind.HOLDING -> 1; Kind.CONNECTION -> 2; Kind.DELIVERY_FAILED -> 3 }

  /** Numbers, prices, links or a colon list: the sentence carries content even if it opens with "let me check". */
  private val substance = Regex("""[$€£₹]\s?\d|\d{2,}|https?://|\w:\s+\w""")
  private fun hasSubstance(s: String) = substance.containsMatchIn(s) || s.split(Regex("\\s+")).size > 18
}
