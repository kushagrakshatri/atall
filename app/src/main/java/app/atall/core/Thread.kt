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
    /** The head of a Juno turn that delegates: the strip ("Asking Nova and Scout", "Asked Nova · 18s"). */
    RUN,
    /** What Juno asked one agent (the rewritten sub-task), grouped under the [RUN]. [peerId] is the agent. */
    SUBTASK,
    /** "Okay to have Nova do this?" with Yes / No: a money/account ask held by the approval gate. */
    NEEDS_APPROVAL,
    /** The @all Agreement Map; [ThreadMessage.detail] holds it as JSON. */
    AGREEMENT,
    /** "Talk it over": the live card, then "Where they landed" and the rounds; [ThreadMessage.detail] holds the view as JSON. */
    TALK,
}

/** Why an agent's line is in the thread. Every agent line carries one, so the label is always the same kind. */
@Serializable
enum class Attribution {
    /** It answers the user's own @mention: "replied directly". */
    DIRECT,
    /** It answers one of Juno's asks; shown under that Juno turn. */
    JUNO,
    /** Nobody asked: "messaged you". */
    UNPROMPTED,
}

/** A tapback an agent put on one of the user's messages ("👍" from Nova). */
@Serializable
data class ReactionChip(val emoji: String, val by: String)

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
    /** Agent lines: why it is here. Null on lines written before 0.1.1 ([direct] covers those). */
    val attribution: Attribution? = null,
    /** The Juno run this line belongs to (the user's request, the strip, sub-tasks, replies, the answer). */
    val runId: String? = null,
    /** SUBTASK / reply lines: the ask (also the transport correlation id). */
    val askId: String? = null,
    /** User lines: tapbacks agents put on it. */
    val reactions: List<ReactionChip> = emptyList(),
    /** User lines: the agents it was sent to directly (by @mention). */
    val sentTo: List<String> = emptyList(),
    /** Secondary text: the strip's timer and notes, a sub-task's status, the Agreement Map JSON. */
    val detail: String? = null,
    /** Dropped from the thread (a "may have answered in Messages" hint once the reply arrived). Kept in the log. */
    val hidden: Boolean = false,
    /** Juno's wrap-up after a talk: up to two things the user can tap to say next ("Go with Rooh"). */
    val choices: List<String> = emptyList(),
    /** [choices] are a split or cost money: shown under "Your call". */
    val yourCall: Boolean = false,
    /** Agent lines: other agents it @mentioned ("Ask Hark about this?"): peer ids. Nothing is sent until the user does. */
    val suggest: List<String> = emptyList(),
    /**
     * Agent answers (to Juno or a direct mention): CONNECTOR when it came over the Atall connector with a verified
     * relay countersignature for that agent's connection (the "Verified" badge). Null on everything else.
     */
    val via: app.atall.coordinator.Via? = null,
) {
    /** The attribution to render; lines from before 0.1.1 only have [direct]. */
    val shownAttribution: Attribution?
        get() = attribution ?: if (direct) Attribution.DIRECT else null

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

    /** Every visible message, in display order (Juno turns grouped, see [ThreadLayout]). */
    fun all(): List<ThreadMessage> = messages.value

    /** Every visible message in time order. */
    fun chronological(): List<ThreadMessage> = synchronized(lock) { sorted().filter { !it.hidden } }
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

    private fun publish() { _messages.value = ThreadLayout.arrange(sorted().filter { !it.hidden }) }
}

/**
 * Display order. Messages sort by time, except that a Juno run's lines (the user's request, the strip, sub-tasks,
 * agent replies, questions, the answer) stay together where the run started: an unrelated line written while the
 * run was waiting shows after the whole group instead of splitting it.
 */
object ThreadLayout {
    fun arrange(sorted: List<ThreadMessage>): List<ThreadMessage> {
        if (sorted.none { it.runId != null }) return sorted
        val groups = sorted.filter { it.runId != null }.groupBy { it.runId!! }
        val out = ArrayList<ThreadMessage>(sorted.size)
        val placed = HashSet<String>()
        for (m in sorted) {
            val run = m.runId
            if (run == null) { out += m; continue }
            if (!placed.add(run)) continue
            out += groups.getValue(run).sortedWith(compareBy<ThreadMessage> { it.at }.thenBy { rank(it) }.thenBy { it.id })
        }
        return out
    }

    /** Within one millisecond: request, strip, sub-task, reply, everything else. */
    private fun rank(m: ThreadMessage) = when {
        m.author == ThreadMessage.USER -> 0
        m.kind == MessageKind.RUN -> 1
        m.kind == MessageKind.SUBTASK -> 2
        else -> 3
    }
}
