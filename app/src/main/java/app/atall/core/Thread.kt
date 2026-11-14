package app.atall.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
enum class MessageKind {
    /** Ordinary text from the user, the main agent or a peer. */
    TEXT,
    /** The peer couldn't be reached silently: "Tap to send to {Agent}" opens a prefilled draft. */
    NEEDS_TAP,
    /** "Sign in with ChatGPT to talk to Juno." with a Sign in action. */
    NEEDS_SIGN_IN,
    /** A quiet one-line status ("Nova didn't answer"). */
    NOTICE,
}

@Serializable
enum class SendStatus { SENDING, SENT, DRAFTED, NOT_SENT }

/**
 * One thread line. [author] is "user", [Main.AUTHOR] or a peer id. Stored as snapshots in an append-only log;
 * the newest snapshot of an id wins, so an edit (streaming text, a status change) is just another line.
 */
@Serializable
data class ThreadMessage(
    val id: String,
    val at: Long,
    val author: String,
    val text: String,
    val kind: MessageKind = MessageKind.TEXT,
    /** User lines: delivery state. NEEDS_TAP lines: DRAFTED once tapped. */
    val status: SendStatus? = null,
    /** Short reason shown with NOT_SENT ("Wait a moment, then retry."). */
    val statusNote: String? = null,
    /** A peer's answer to a direct @mention ("replied directly"). */
    val direct: Boolean = false,
    /** For NEEDS_TAP / NOTICE: the peer it is about. */
    val peerId: String? = null,
    /** For NEEDS_TAP: what to send and under which correlation id. */
    val draftText: String? = null,
    val correlationId: String? = null,
    /** Dedupe key of a peer message (the transport's message id). */
    val sourceId: String? = null,
    val streaming: Boolean = false,
) {
    companion object {
        const val USER = "user"
        fun newId() = UUID.randomUUID().toString()
    }
}

/**
 * The single thread, persisted in app-private storage as JSON lines (one snapshot per line, append-only,
 * compacted when it grows). Thread-safe. A torn last line (crash mid-write) is skipped on load.
 */
class ThreadStore(private val file: File, private val maxMessages: Int = 5_000) {
    private val lock = Any()
    private val byId = LinkedHashMap<String, ThreadMessage>()
    private val sources = HashSet<String>()
    private var lines = 0
    private val _messages = MutableStateFlow<List<ThreadMessage>>(emptyList())
    val messages: StateFlow<List<ThreadMessage>> = _messages.asStateFlow()

    init {
        synchronized(lock) {
            if (file.isFile) file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { Json.decodeFromString(ThreadMessage.serializer(), line) }.getOrNull()?.let { put(it) }
                lines++
            }
            publish()
        }
    }

    fun all(): List<ThreadMessage> = messages.value
    fun get(id: String): ThreadMessage? = synchronized(lock) { byId[id] }
    fun hasSource(sourceId: String): Boolean = synchronized(lock) { sourceId in sources }

    /** Insert or replace by id. Returns false when [m] carries a [ThreadMessage.sourceId] already in the thread. */
    fun upsert(m: ThreadMessage): Boolean = synchronized(lock) {
        val existing = byId[m.id]
        if (existing == null && m.sourceId != null && m.sourceId in sources) return false
        if (existing == m) return true
        put(m)
        append(m)
        publish()
        true
    }

    fun update(id: String, change: (ThreadMessage) -> ThreadMessage): ThreadMessage? = synchronized(lock) {
        val cur = byId[id] ?: return null
        val next = change(cur)
        upsert(next)
        next
    }

    fun clear() = synchronized(lock) {
        byId.clear(); sources.clear(); lines = 0
        file.delete()
        publish()
    }

    private fun put(m: ThreadMessage) {
        byId[m.id] = m
        m.sourceId?.let { sources += it }
    }

    private fun append(m: ThreadMessage) {
        file.absoluteFile.parentFile?.mkdirs()
        file.appendText(Json.encodeToString(ThreadMessage.serializer(), m) + "\n")
        lines++
        if (lines > byId.size * 2 + 200 || byId.size > maxMessages) compact()
    }

    private fun compact() {
        val keep = sorted().takeLast(maxMessages)
        byId.clear(); sources.clear()
        keep.forEach { put(it) }
        AtomicFile.write(file, keep.joinToString("") { Json.encodeToString(ThreadMessage.serializer(), it) + "\n" })
        lines = keep.size
    }

    private fun sorted() = byId.values.sortedWith(compareBy<ThreadMessage> { it.at }.thenBy { it.id })

    private fun publish() { _messages.value = sorted() }
}
