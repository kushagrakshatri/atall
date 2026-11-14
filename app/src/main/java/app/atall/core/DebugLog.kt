package app.atall.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's own debug log: run state transitions, sends and transport fallbacks, replies, merge/map/talk steps,
 * model call errors. Kept on the phone (the last [max] lines, in a file so it survives a restart) for Settings →
 * "Copy debug log", and mirrored to [sinks] (Sentry breadcrumbs). Everything is redacted on the way in ([Redact]):
 * phone numbers and anything that looks like a token or secret never reach the file. Message text is kept apart in
 * [Entry.text], so "Send message text with debug logs" off drops it at export time.
 *
 * Pure Kotlin (no Android); tested in DebugLogTest.
 */
class DebugLog(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    val max: Int = 500,
) {
    @Serializable
    data class Entry(val at: Long, val tag: String, val event: String, val runId: String? = null, val detail: String = "", val text: String? = null)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var sinceCompact = 0
    private val sinks = mutableListOf<(Entry) -> Unit>()

    init { load() }

    fun addSink(sink: (Entry) -> Unit) { synchronized(lock) { sinks += sink } }

    fun add(tag: String, event: String, runId: String? = null, detail: String = "", text: String? = null) {
        val e = Entry(clock(), tag, event, runId, Redact.text(detail).take(MAX_DETAIL), text?.let { Redact.text(it).take(MAX_TEXT) })
        val out: List<(Entry) -> Unit>
        synchronized(lock) {
            entries.addLast(e)
            while (entries.size > max) entries.removeFirst()
            persist(e)
            out = sinks.toList()
        }
        out.forEach { runCatching { it(e) } }
    }

    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    /** The log as text to paste: newest last, times in UTC. Without [includeText], message text shows as its length only. */
    fun export(includeText: Boolean, header: String = ""): String = buildString {
        if (header.isNotBlank()) append(header.trim()).append("\n")
        entries().forEach { append(render(it, includeText)).append("\n") }
    }

    private fun persist(e: Entry) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(JSON.encodeToString(Entry.serializer(), e) + "\n")
            if (++sinceCompact >= max) { sinceCompact = 0; f.writeText(entries.joinToString("") { JSON.encodeToString(Entry.serializer(), it) + "\n" }) }
        }
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (!f.isFile) return
            f.readLines().takeLast(max).forEach { line -> runCatching { entries.addLast(JSON.decodeFromString(Entry.serializer(), line)) } }
        }
    }

    companion object {
        const val MAX_DETAIL = 400
        const val MAX_TEXT = 1_500
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun render(e: Entry, includeText: Boolean): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.at))
            val head = listOfNotNull(time, e.tag, e.runId, e.event, e.detail.ifBlank { null }).joinToString(" ")
            val t = e.text ?: return head
            return if (includeText) "$head | ${t.replace("\n", "⏎")}" else "$head | [text, ${t.length} chars]"
        }
    }
}

/** Scrubs phone numbers, emails' local parts stay, and anything that looks like a token, key, code or secret. */
object Redact {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+") to "Bearer [secret]",
        Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}") to "[secret]",
        Regex("https?://[A-Za-z0-9]{16,}(:[A-Za-z0-9]+)?@[^\\s/]+(/\\S*)?") to "[secret-url]",
        Regex("(?i)\\b(access_token|refresh_token|id_token|api[_-]?key|apikey|secret|password|passwd|token|dsn|authorization|client_secret)(\\s*[:=]\\s*|\"\\s*:\\s*\")[^\\s,\"}&]+") to "$1$2[secret]",
        Regex("\\b(sk|pk|rk)-[A-Za-z0-9_-]{10,}") to "[secret]",
        Regex("\\b(xox[abprs]|ghp|gho|ghs|github_pat|AKIA|AIza)[-_A-Za-z0-9]{10,}") to "[secret]",
        Regex("(?i)\\b(code|otp|pin|passcode)(\\s*(is|:)?\\s*)\\d{4,8}\\b") to "$1$2[code]",
        // Long opaque strings with letters and digits mixed (keys, hashes, session ids).
        Regex("\\b(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}\\b") to "[secret]",
        // Phone numbers: +1 408 555 0101, (408) 555-0101, 4085550101, +44 20 7946 0958.
        Regex("(?<![\\w~])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\w~])") to "\u0000",
    )

    fun text(s: String): String {
        var out = s
        for ((re, rep) in rules) {
            out = if (rep == "\u0000") re.replace(out) { m -> if (m.value.count { it.isDigit() } in 9..15 && !looksLikeDate(m.value)) "[phone]" else m.value }
            else re.replace(out, rep)
        }
        return out
    }

    private fun looksLikeDate(v: String) = Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(v.trim())
}

/** Where debug events go besides the phone's own log (Sentry in the app; nothing in tests). */
interface Telemetry {
    /** One step of a run (or a transport line) as a breadcrumb. [includeText]: the user's "Send message text" switch. */
    fun breadcrumb(e: DebugLog.Entry, includeText: Boolean) {}
    /** An event worth a report: a stuck run, a model error, a finished run (its steps ride along as breadcrumbs). */
    fun capture(message: String, level: Level, runId: String?, extras: Map<String, String> = emptyMap()) {}
    enum class Level { INFO, WARNING, ERROR }
    companion object { val NONE = object : Telemetry {} }
}
