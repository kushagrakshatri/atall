package app.atall.agents.text

import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A tapback/reaction an agent (or the user) put on a message. Google Messages shows it in the notification as a
 * plain line ("👍 to “how are my projects going”", "Reacted 👍 to “…”"); it is not an answer and must never be
 * attributed to a request or rendered as agent text.
 *
 * [quoted] is the text Messages quoted (possibly truncated with "…"). [targetCorrelationId] is filled by the hub when
 * the quote matches one of our own sent lines. [removed] is "Removed 👍 from “…”".
 */
@Serializable
data class Reaction(
  val emoji: String,
  val quoted: String,
  val removed: Boolean = false,
  val targetCorrelationId: String? = null,
)

/**
 * Pure parsing of reaction lines. Whole-line matches only, so a sentence that merely contains "to “x”" is text.
 *
 * The quoted part may span several lines (Messages quotes a multi-line text as is), may be truncated ("…" / "..."),
 * and may lack its closing quote. The emoji may be any single emoji: skin tones, ZWJ sequences, flags, keycaps.
 * The part before the quote ("👍 to", "Nova reacted 👍 to") is always on one line.
 */
object Reactions {
  private const val OPEN = "[\"“”‘'«]"
  private const val CLOSE = "[\"”“’'»]"
  /** An emoji: a keycap ("1️⃣"), or a run of non-letter, non-digit, non-space, non-quote code points (skin tones,
   *  ZWJ sequences, variation selectors, flags and tag sequences all fit). [looksLikeEmoji] rejects punctuation. */
  private const val EMOJI = "((?:[0-9#*]\\x{FE0F}?\\x{20E3})|[^\\p{L}\\p{N}\\s\"“”‘’'«»]{1,40})"
  /** Quoted text: any characters including newlines, lazily, then an optional closing quote or ellipsis. */
  private const val QUOTE = "$OPEN((?s:.+?))(?:$CLOSE|…|\\.\\.\\.)?"
  /** Longest line we treat as a possible reaction: a quote of our longest text (2,000) plus the reaction words. */
  const val MAX_LENGTH = 2_400

  private val patterns: List<Pair<Regex, Boolean>> = listOf(
    // "👍 to “how are my projects going”"
    Regex("^$EMOJI[ \\t]+to[ \\t]+$QUOTE$") to false,
    // "Reacted 👍 to “…”", "Reacted with 👍 to your message “…”", "Nova reacted 👍 to “…”"
    Regex("^(?:[^\\n]{1,40}?[ \\t]+)?[Rr]eacted[ \\t]+(?:with[ \\t]+)?$EMOJI[ \\t]+to[ \\t]+(?:your message[ \\t]*:?[ \\t]*)?$QUOTE$") to false,
    // "Removed 👍 from “…”", "Removed a 👍 from “…”"
    Regex("^(?:[^\\n]{1,40}?[ \\t]+)?[Rr]emoved[ \\t]+(?:a[ \\t]+|the[ \\t]+)?$EMOJI[ \\t]+(?:reaction[ \\t]+)?from[ \\t]+$QUOTE$") to true,
  )

  /** "🧳 to From Atall …" with no quote marks at all: a reaction only when the rest quotes something we sent. */
  private val loose = Regex("^$EMOJI[ \\t]+to[ \\t]+((?s:.+))$")

  /** iMessage-style tapbacks that arrive as SMS text ("Liked “…”"). */
  private val tapbacks = linkedMapOf(
    "liked" to "👍", "loved" to "❤️", "disliked" to "👎", "laughed at" to "😂", "emphasized" to "‼️", "questioned" to "❓",
  )
  private val tapback = Regex("^(Liked|Loved|Disliked|Laughed at|Emphasized|Questioned)[ \\t]+$QUOTE$")
  private val tapbackRemoved = Regex("^Removed an? (like|heart|dislike|laugh|exclamation|question mark) from[ \\t]+$QUOTE$")
  private val removedNames = mapOf("like" to "👍", "heart" to "❤️", "dislike" to "👎", "laugh" to "😂", "exclamation" to "‼️", "question mark" to "❓")

  /** The reaction in [text], or null when it is ordinary text. */
  fun parse(text: String): Reaction? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_LENGTH) return null
    for ((re, removed) in patterns) {
      val m = re.matchEntire(t) ?: continue
      val emoji = m.groupValues[1].trim()
      val quoted = clean(m.groupValues[2])
      if (!looksLikeEmoji(emoji) || quoted.isEmpty()) continue
      return Reaction(emoji, quoted, removed)
    }
    tapback.matchEntire(t)?.let { m ->
      val quoted = clean(m.groupValues[2])
      if (quoted.isNotEmpty()) return Reaction(tapbacks.getValue(m.groupValues[1].lowercase(Locale.ROOT)), quoted)
    }
    tapbackRemoved.matchEntire(t)?.let { m ->
      val quoted = clean(m.groupValues[2])
      if (quoted.isNotEmpty()) return Reaction(removedNames.getValue(m.groupValues[1].lowercase(Locale.ROOT)), quoted, removed = true)
    }
    return null
  }

  /**
   * "🧳 to <text>" without quote marks. Not a reaction by itself ("🎉 to everyone who came!" is text): the caller
   * accepts it only when [Reaction.quoted] [quotes] one of the texts it sent to that agent.
   */
  fun parseUnquoted(text: String): Reaction? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_LENGTH) return null
    val m = loose.matchEntire(t) ?: return null
    val emoji = m.groupValues[1].trim()
    val quoted = clean(m.groupValues[2])
    if (!looksLikeEmoji(emoji) || fold(quoted).length < 12) return null
    return Reaction(emoji, quoted)
  }

  /**
   * Whether [quoted] (as Messages showed it, maybe truncated) refers to [sent]. Compares folded text; a truncated
   * quote matches the start of the sent text. Our packed context header may have been quoted, or only the user's words.
   */
  fun quotes(quoted: String, sent: String): Boolean {
    val q = fold(quoted)
    val s = fold(sent)
    if (q.isEmpty() || s.isEmpty()) return false
    if (q == s || s.startsWith(q)) return true
    // Messages sometimes quotes only the first line of a multi-line text.
    val firstLine = fold(sent.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
    if (firstLine.isNotEmpty() && (q == firstLine || firstLine.startsWith(q))) return true
    // Or the quote is long enough to be distinctive and appears inside (a header line came first).
    return q.length >= 12 && s.contains(q)
  }

  private fun clean(s: String) = s.trim().removeSuffix("…").removeSuffix("...").trim()

  private fun fold(s: String) = s.trim().removeSuffix("…").removeSuffix("...").replace(Regex("\\s+"), " ")
    .replace('’', '\'').replace('‘', '\'').lowercase(Locale.ROOT).trim()

  /** At least one code point that is a symbol (So/Sk) or a pictographic range; rejects punctuation like "-" or "->". */
  private fun looksLikeEmoji(s: String): Boolean = s.codePoints().anyMatch { cp ->
    val type = Character.getType(cp)
    type == Character.OTHER_SYMBOL.toInt() || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0x203C || cp == 0x2049 ||
      cp == 0x20E3
  }
}
