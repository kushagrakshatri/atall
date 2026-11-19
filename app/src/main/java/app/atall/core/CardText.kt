package app.atall.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which real mark an agent wears. Never a letter: an agent with no known mark gets a neutral one. */
enum class AgentLogo {
    /** xAI's Grok mark, tinted ink. */
    GROK,
    /** The Hark mark, tinted ink. */
    HARK,
    /** The Meta AI colour ring (Muse), in its own colours. */
    META,
    /** Claude's mark (Duet), tinted ink. */
    CLAUDE,
    /** OpenAI's mark (kDot / ChatGPT), tinted ink. */
    OPENAI,
    /** No known mark: a neutral shape in the agent's hue. */
    NONE;

    /** Marks drawn in one colour, which follow the ink (and flip in dark mode). */
    val tinted: Boolean get() = this != META && this != NONE

    companion object {
        /** By relay id first (kdot, grok, hark, duet, muse), then by name or the product it runs on. */
        fun of(name: String, relayId: String? = null): AgentLogo {
            relayId?.lowercase(Locale.ROOT)?.let { id -> byKey(id)?.let { return it } }
            val n = name.trim().lowercase(Locale.ROOT).removePrefix("@")
            byKey(n)?.let { return it }
            return when {
                n.contains("grok") -> GROK
                n.contains("hark") -> HARK
                n.contains("muse") || n.contains("meta") -> META
                n.contains("claude") || n.contains("duet") -> CLAUDE
                n.contains("chatgpt") || n.contains("openai") || n.contains("kdot") || n == "gpt" -> OPENAI
                else -> NONE
            }
        }

        private fun byKey(k: String): AgentLogo? = when (k) {
            "grok", "xai" -> GROK
            "hark" -> HARK
            "muse", "meta", "metaai", "meta-ai" -> META
            "duet", "claude", "anthropic" -> CLAUDE
            "kdot", "chatgpt", "openai", "gpt" -> OPENAI
            else -> null
        }
    }
}

/**
 * Text helpers for the answer card, kept free of Compose so they are unit-tested: credit tiles glued to a line's last
 * word, the late-change highlight, and the "Changed just now" caption.
 */
object CardText {
    /** The placeholder character Compose's inline content stands on. */
    const val TILE = '\uFFFC'

    sealed interface Piece {
        /** The line's words up to (and including the space before) its last word. */
        data class Words(val text: String) : Piece
        /**
         * The line's last word and all its credit tiles, laid out as ONE inline object: no text engine can break a line
         * inside it, so a tile never wraps away from its word and never starts a line on its own.
         */
        data class Tail(val word: String, val agentIds: List<String>) : Piece
    }

    /** One line, its last word glued to its credit tiles. A line with no credits is just its words. */
    fun glued(text: String, credits: List<String>): List<Piece> {
        val t = text.trim()
        val ids = credits.distinct()
        if (ids.isEmpty() || t.isEmpty()) return listOf(Piece.Words(t))
        val cut = t.lastIndexOf(' ')
        val head = if (cut >= 0) t.substring(0, cut + 1) else ""
        return listOfNotNull(head.takeIf { it.isNotEmpty() }?.let { Piece.Words(it) }, Piece.Tail(t.substring(cut + 1), ids))
    }

    /** The glued line as one string, the tail as one [TILE] (what the Text lays out around the inline object). */
    fun flat(pieces: List<Piece>): String = pieces.joinToString("") { when (it) { is Piece.Words -> it.text; is Piece.Tail -> TILE.toString() } }

    /** Emphasis markers the card doesn't draw ("**", "__", a lone "*" around words). */
    fun plainLine(text: String): String = text.replace("**", "").replace("__", "").replace(Regex("(?<![\\w*])\\*(?=\\S)([^*]+?)(?<=\\S)\\*(?![\\w*])"), "$1").trim()

    /**
     * Character ranges of [current] that are new compared with [previous]: word-level, adjacent new words merged with
     * the space between them. Empty when nothing changed or there is no previous text.
     */
    fun changedRanges(previous: String?, current: String): List<IntRange> {
        if (previous == null || previous == current) return emptyList()
        val a = tokens(previous).map { norm(it.second) }
        val b = tokens(current)
        val bw = b.map { norm(it.second) }
        // LCS on words.
        val n = a.size; val m = bw.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i] == bw[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val kept = BooleanArray(m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == bw[j] -> { kept[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        val out = mutableListOf<IntRange>()
        var start = -1; var end = -1
        for (k in 0 until m) {
            val (pos, w) = b[k]
            if (!kept[k]) {
                if (start >= 0 && current.substring(end + 1, pos).none { it == '\n' }) end = pos + w.length - 1
                else { if (start >= 0) out += start..end; start = pos; end = pos + w.length - 1 }
            } else if (start >= 0) { out += start..end; start = -1 }
        }
        if (start >= 0) out += start..end
        // Trailing punctuation outside the highlight reads better ("damage cover" + ".").
        return out.map { r ->
            var e = r.last
            while (e > r.first && current[e] in ".,;:!?") e--
            r.first..e
        }
    }

    /** The ranges that fall inside the line starting at [offset] with [length] chars, shifted to the line. */
    fun rangesInLine(ranges: List<IntRange>, offset: Int, length: Int): List<IntRange> = ranges.mapNotNull { r ->
        val s = maxOf(r.first, offset); val e = minOf(r.last, offset + length - 1)
        if (s > e) null else (s - offset)..(e - offset)
    }

    /** "Changed just now" for two minutes, then "Changed 5 min ago", then the time. */
    fun changedLabel(changedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val ago = (now - changedAt).coerceAtLeast(0)
        return when {
            ago < 2 * 60_000 -> "Changed just now"
            ago < 60 * 60_000 -> "Changed ${ago / 60_000} min ago"
            else -> "Changed at " + Instant.ofEpochMilli(changedAt).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        }
    }

    private fun tokens(s: String): List<Pair<Int, String>> = Regex("\\S+").findAll(s).map { it.range.first to it.value }.toList()
    private fun norm(w: String) = w.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() && it != '$' && it != '%' }
}
