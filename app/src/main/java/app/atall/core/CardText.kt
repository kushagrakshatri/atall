package app.atall.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which app icon an agent wears. Each of Kush's agents is its own product with its own app icon (Grok Bot, Muse from
 * Meta, Claude Duet), not its parent brand's mark; kDot lives inside ChatGPT, so it wears ChatGPT's icon. Picked from
 * the agent's app identity (its relay id, or its Play package), never from its display name, so a renamed agent keeps
 * its icon. Never a letter: an agent with no known app gets a neutral spark.
 */
enum class AgentLogo(
    /** The Play package of the app this agent is (null for Hark, which has no app icon of its own here). */
    val pkg: String?,
    /** Its id on the Atall relay. */
    val relayId: String?,
) {
    /** kDot: ChatGPT's app icon (it lives inside ChatGPT). */
    CHATGPT("com.openai.chatgpt", "kdot"),
    /** Grok Bot's app icon (a white 3D face on black). */
    GROK_BOT("ai.x.grok.bot", "grok"),
    /** Muse from Meta's app icon (a blue brush M on white). */
    MUSE("com.facebook.aura", "muse"),
    /** Claude Duet's app icon (a terracotta blob with loops on cream). */
    CLAUDE_DUET("com.anthropic.aibo", "duet"),
    /** The Hark mark (vector, tinted ink). */
    HARK(null, "hark"),
    /** No known app: a neutral spark in the agent's hue. */
    NONE(null, null);

    /** A full-bleed app-icon bitmap (never tinted). */
    val bitmap: Boolean get() = pkg != null
    /** A one-colour vector mark that follows the ink. */
    val tinted: Boolean get() = this == HARK

    companion object {
        /** The relay ids of the agents whose app is known. */
        val KNOWN_RELAY_IDS: List<String> = entries.mapNotNull { it.relayId }

        /** By app identity: a relay id ("grok") or a Play package ("ai.x.grok.bot"). Anything else is [NONE]. */
        fun of(identity: String?): AgentLogo {
            val k = identity?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null } ?: return NONE
            return entries.firstOrNull { it != NONE && (it.relayId == k || it.pkg == k) } ?: NONE
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
