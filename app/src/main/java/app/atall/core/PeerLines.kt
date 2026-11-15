package app.atall.core

import app.atall.agents.text.Reaction
import app.atall.agents.text.Reactions

/**
 * Pure rules for agent lines landing in the thread: who they answer (attribution), which user message a tapback
 * belongs to, and when a "may have answered in Messages" hint is stale.
 */
object PeerLines {
    /** An uncorrelated reply this soon after the user @mentioned the agent still counts as its direct answer. */
    const val INFER_WINDOW_MS = 30 * 60_000L

    /** Direct sends use "d:<message id prefix>:<peer>:<attempt>". */
    fun isDirectCid(cid: String?) = cid != null && cid.startsWith(Router.DIRECT)

    /** Juno's asks use "<runId>~a<n>" (runId starts with "r"). */
    fun isJunoCid(cid: String?) = cid != null && JUNO_CID.matches(cid)

    fun runIdOf(askId: String) = askId.substringBefore("~a")

    private val JUNO_CID = Regex("^r[^~\\s]+~a\\d+$")

    /**
     * The muted one-liner for an agent's status text (not an answer): "Hark: Looks like your signal dropped. …".
     * One line, trimmed; the agent keeps its slot and the thread keeps waiting for the real reply.
     */
    fun statusLine(name: String, text: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return "$name: " + if (one.length > STATUS_MAX) one.take(STATUS_MAX - 1).trimEnd() + "…" else one
    }

    const val STATUS_MAX = 120

    /**
     * Attribution of a line from [peerId] that is not one of Juno's asks.
     * - correlated to a direct send: DIRECT.
     * - not correlated, but the user's newest message to this agent was a direct @mention within [INFER_WINDOW_MS]
     *   and this agent hasn't answered since: DIRECT (the transport lost the correlation, e.g. the reply landed
     *   while the chat was open, or the draft was sent late). Without this, the first reply of a drafted send
     *   showed no label while later ones did.
     * - otherwise UNPROMPTED.
     */
    fun attribution(peerId: String, correlationId: String?, at: Long, thread: List<ThreadMessage>): Attribution {
        if (isDirectCid(correlationId)) return Attribution.DIRECT
        if (isJunoCid(correlationId)) return Attribution.JUNO
        val ask = thread.lastOrNull { it.author == ThreadMessage.USER && peerId in it.sentTo && it.at <= at + SKEW_MS } ?: return Attribution.UNPROMPTED
        if (at - ask.at > INFER_WINDOW_MS) return Attribution.UNPROMPTED
        val answeredSince = thread.any { it.author == peerId && it.kind == MessageKind.TEXT && it.at > ask.at && it.at <= at }
        return if (answeredSince) Attribution.UNPROMPTED else Attribution.DIRECT
    }

    /**
     * The user message a tapback from [peerId] belongs to:
     *  1. the message the transport matched ([Reaction.targetCorrelationId]): a direct send's message, or the request
     *     that started Juno's run;
     *  2. the newest user message whose text the reaction quotes;
     *  3. the newest user message sent to that agent (directly or through a Juno run that asked it).
     * Null when nothing fits (the reaction is dropped, never shown as text).
     */
    fun reactionTarget(peerId: String, r: Reaction, thread: List<ThreadMessage>): ThreadMessage? {
        val users = thread.filter { it.author == ThreadMessage.USER }
        r.targetCorrelationId?.let { cid ->
            when {
                isDirectCid(cid) -> {
                    val prefix = cid.removePrefix(Router.DIRECT).substringBefore(':')
                    users.lastOrNull { it.id.startsWith(prefix) }?.let { return it }
                }
                isJunoCid(cid) -> {
                    val run = runIdOf(cid)
                    users.firstOrNull { it.runId == run }?.let { return it }
                }
            }
        }
        users.lastOrNull { Reactions.quotes(r.quoted, it.text) || Reactions.quotes(r.quoted, Mentions.plain(it.text)) }?.let { return it }
        val askedRuns = thread.filter { it.kind == MessageKind.SUBTASK && it.peerId == peerId }.mapNotNull { it.runId }.toSet()
        return users.lastOrNull { peerId in it.sentTo || (it.runId != null && it.runId in askedRuns) }
    }

    /** Add or remove [peerId]'s chip on [target]. Idempotent. */
    fun applyReaction(target: ThreadMessage, peerId: String, r: Reaction): ThreadMessage {
        val chip = ReactionChip(r.emoji, peerId)
        val next = if (r.removed) target.reactions - chip
        else (target.reactions.filter { it.by != peerId } + chip) // one reaction per agent per message, like Messages
        return if (next == target.reactions) target else target.copy(reactions = next)
    }

    /**
     * "Nova may have answered in Messages" hints that a later line from Nova makes stale. A hint is stale once that
     * agent has a line in the thread sent after the request the hint is about ([ThreadMessage.detail] holds the
     * request's send time).
     */
    fun staleMissedHints(peerId: String, thread: List<ThreadMessage>): List<ThreadMessage> {
        val lastFromPeer = thread.filter { it.author == peerId && it.kind == MessageKind.TEXT }.maxOfOrNull { it.at } ?: return emptyList()
        return thread.filter {
            it.kind == MessageKind.NOTICE && it.peerId == peerId && it.sourceId?.startsWith(MISSED_PREFIX) == true &&
                lastFromPeer >= (it.detail?.toLongOrNull() ?: Long.MAX_VALUE)
        }
    }

    /** Whether a "may have answered" hint for a request sent at [sentAt] is already answered by a line in the thread. */
    fun answeredSince(peerId: String, sentAt: Long, thread: List<ThreadMessage>): Boolean =
        thread.any { it.author == peerId && it.kind == MessageKind.TEXT && it.at >= sentAt - SKEW_MS }

    const val MISSED_PREFIX = "closed:"
    private const val SKEW_MS = 5_000L

    /**
     * Other agents an agent's line @mentions ("@Hark is that open Sundays?"): peer ids, for "Ask Hark about this?".
     * An agent's @mention never sends anything by itself. Emails, Juno and the author itself don't count.
     */
    fun suggestions(text: String, authorId: String, agents: List<AgentProfile>): List<String> =
        Mentions.find(text, Mentions.nameMap(agents)).map { it.target }
            .filter { it != Main.AUTHOR && it != authorId && agents.any { a -> a.id == it && !a.paused } }.distinct()
}
