package app.atall.core

/**
 * The small Markdown subset models write (bold, italics, inline code, links, headings, bullet and numbered lists,
 * quotes, rules, code fences), parsed into styled lines for the thread and flattened to plain text for agents.
 * Pure Kotlin, no Compose: the UI maps [Line]s to an AnnotatedString. Unclosed markers stay as typed (a streaming
 * "**Dea" shows raw until its closing "**" arrives).
 */
object Markdown {
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    /** One rendered line: [prefix] is the list marker ("• ", "2. ") with its indent; blank [spans] is an empty line. */
    data class Line(val spans: List<Span>, val prefix: String = "", val heading: Boolean = false) {
        val text: String get() = prefix + spans.joinToString("") { it.text }
    }

    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~).*$")

    private val INLINE = Regex(
        "\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*" + // 1 bold
            "|(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])" + // 2 bold
            "|(?<![\\p{L}\\p{N}*])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![\\p{L}\\p{N}*])" + // 3 italic
            "|(?<![\\p{L}\\p{N}_])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\p{L}\\p{N}_])" + // 4 italic
            "|`([^`\\n]+)`" + // 5 code
            "|\\[([^\\]\\n]+)\\]\\((\\S+?)\\)" + // 6 link text, 7 url
            "|~~(?=\\S)(.+?)(?<=\\S)~~", // 8 strikethrough: shown as plain text
    )

    /** Parse [text] into lines. Runs of blank lines collapse to one; leading and trailing blank lines are dropped. */
    fun parse(text: String): List<Line> {
        val out = ArrayList<Line>()
        var inFence = false
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (FENCE.matches(raw)) { inFence = !inFence; continue }
            val line = when {
                inFence -> Line(listOf(Span(raw, code = true)))
                raw.isBlank() -> Line(emptyList())
                RULE.matches(raw) -> continue
                else -> HEADING.matchEntire(raw)?.let { Line(inline(it.groupValues[1], bold = true), heading = true) }
                    ?: BULLET.matchEntire(raw)?.let { Line(inline(it.groupValues[2]), indent(it.groupValues[1]) + "• ") }
                    ?: NUMBERED.matchEntire(raw)?.let { Line(inline(it.groupValues[3]), indent(it.groupValues[1]) + it.groupValues[2] + ". ") }
                    ?: QUOTE.matchEntire(raw)?.let { Line(inline(it.groupValues[1])) }
                    ?: Line(inline(raw.trimEnd()))
            }
            if (line.spans.isEmpty() && line.prefix.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    /**
     * Plain text for agents (they read SMS): no asterisks, underscores, backticks, heading hashes or link syntax.
     * Lists keep a "- " / "1. " marker so they still read as lists; a link becomes "text (url)".
     */
    fun plain(text: String): String = parse(text).joinToString("\n") { l ->
        val prefix = if (l.prefix.trimStart().startsWith("•")) l.prefix.replace("•", "-") else l.prefix
        prefix + l.spans.joinToString("") { it.text }
    }.trim()

    private fun indent(ws: String): String {
        val width = ws.replace("\t", "    ").length
        return "  ".repeat((width / 2).coerceAtMost(4))
    }

    private fun inline(s: String, bold: Boolean = false, italic: Boolean = false): List<Span> {
        val out = ArrayList<Span>()
        var pos = 0
        fun plainUpTo(end: Int) { if (end > pos) out += Span(s.substring(pos, end), bold, italic) }
        for (m in INLINE.findAll(s)) {
            plainUpTo(m.range.first)
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out += inline(g[1], bold = true, italic = italic)
                g[2].isNotEmpty() -> out += inline(g[2], bold = true, italic = italic)
                g[3].isNotEmpty() -> out += inline(g[3], bold = bold, italic = true)
                g[4].isNotEmpty() -> out += inline(g[4], bold = bold, italic = true)
                g[5].isNotEmpty() -> out += Span(g[5], bold, italic, code = true)
                g[6].isNotEmpty() -> {
                    out += inline(g[6], bold, italic)
                    if (!sameLink(g[6], g[7])) out += Span(" (${g[7]})", bold, italic)
                }
                g[8].isNotEmpty() -> out += inline(g[8], bold, italic)
            }
            pos = m.range.last + 1
        }
        plainUpTo(s.length)
        return merge(out)
    }

    private fun sameLink(text: String, url: String) =
        text.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') == url.removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans) {
            if (sp.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.bold == sp.bold && last.italic == sp.italic && last.code == sp.code) out[out.lastIndex] = last.copy(text = last.text + sp.text)
            else out += sp
        }
        return out
    }
}
