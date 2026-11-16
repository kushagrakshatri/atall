package app.atall.core

import app.atall.api.ThreadLine
import app.atall.coordinator.AskTail
import app.atall.coordinator.Provenance
import app.atall.coordinator.Relay
import app.atall.coordinator.SendGate
import app.atall.coordinator.Segment

/**
 * What an agent receives from Atall: one plain text (no Markdown, which SMS shows raw). Nothing is ever cut off
 * with "…": Juno's sub-task and the user's words always go in whole. Only a direct @mention's (and an @all
 * question's) recent turns are trimmed to keep the text near [MAX_CHARS], and only by dropping whole turns,
 * oldest first.
 *
 * Juno's delegations (and rechecks) carry only the header and Juno's rewritten sub-task, which is already
 * self-contained, plus one line naming who else is asked:
 * ```
 * From Atall (Kush's thread):
 * Your pick of dates and a budget for the Thanksgiving trip. Don't book anything yet.
 * Also asking: Scout
 * ```
 * A direct @mention (and an @all question, which is the user's raw line too) carries the context the raw line lacks:
 * ```
 * From Atall (Kush's thread):
 * Kush: i think Nova and Scout know the deets
 * Recent:
 * - Kush: can you all decide on the trip for Thanksgiving
 * - Juno: Happy to. Tahoe or the coast?
 * Also asking: Scout
 * ```
 */
object ContextPack {
    /** Budget for the recent-turns context. The sub-task and the user's words may take a text past it. */
    const val MAX_CHARS = 700
    const val MAX_RECENT = 6
    /** Turns older than this (relative to the newest) are not "recent". */
    const val RECENT_WINDOW_MS = 6 * 3_600_000L

    enum class Kind {
        /** The user @mentioned the agent: it gets the user's words. */
        DIRECT,
        /** Juno delegated: it gets Juno's rewritten sub-task only (it is self-contained). */
        DELEGATED,
        /** @all: the same question to everyone, answered independently (no other agents' lines are shown). */
        BLIND,
        /** A one-claim recheck: only Juno's text (it already carries the claim and the other side's reason). */
        CHALLENGE,
        /**
         * "Talk it over": the other agents' answers, anonymised and quoted, framed as information and not as the
         * user's words, with the question restated. Juno writes the body; the whole text stays under [ROOM_MAX_CHARS].
         */
        ROOM,
    }

    /** Every relay in a talk, header included, stays under this. */
    const val ROOM_MAX_CHARS = 1_800

    /** The text an agent receives, and who wrote each part of it (for the send gate). */
    data class Packed(val text: String, val segments: List<Segment>)

    data class Input(
        val kind: Kind,
        /** Display name of the user ("Kush"); blank means "the user". */
        val userName: String,
        val recipientId: String,
        val recipientName: String,
        /** The user's message as typed (mentions included). */
        val request: String,
        /** Juno's sub-task for this agent (DELEGATED / BLIND / CHALLENGE). */
        val need: String? = null,
        /** Recent thread turns, oldest first, without the request itself. Authors: "user", "main" or a peer id. */
        val recent: List<ThreadLine> = emptyList(),
        /** Peer id -> display name, for authors and mentions. */
        val names: Map<String, String> = emptyMap(),
        /** Other agents asked the same thing at the same time. */
        val others: List<String> = emptyList(),
        /** The user approved [need] with a tap ("Yes, send it" / "Share"): it goes out as theirs. */
        val approved: Boolean = false,
        /** ROOM: the other agents' words quoted in [need] (and Juno's note), checked as relayed text. */
        val quoted: List<String> = emptyList(),
        /**
         * What a bare follow-up refers to ([FollowUp]): the user's words, Juno's plan, its open question. Goes in whole
         * (never budget-trimmed) as quoted context, and replaces the Recent block it already covers.
         */
        val context: String? = null,
        /**
         * Juno's asks (not the user's direct @mentions) end with the self-filled tail ([AskTail]): the agent's angle, up
         * to 3 facts about the user from its own memory, what it can't see, one next step. Never counted against the
         * recent-turns budget.
         */
        val tail: Boolean = false,
    )

    /** Every text from Atall starts with this; a tapback quoting it reacts to one of our texts. */
    const val HEADER_PREFIX = "From Atall ("

    fun header(userName: String): String =
        if (userName.isBlank()) "From Atall (your user's thread):" else "From Atall (${possessive(userName.trim())} thread):"

    fun pack(i: Input): String = packed(i).text

    /**
     * The text for the agent plus its provenance. Only the user's own words (or text they approved with a tap) may
     * carry buy / book / "go ahead": recent turns from Juno or other agents that would read as one are left out, and
     * the transport refuses a text whose other parts do ([SendGate]).
     */
    fun packed(i: Input): Packed {
        if (!i.tail || i.kind == Kind.DIRECT) return core(i, 0)
        val t = AskTail.text(i.userName)
        val p = core(i, t.length + 1)
        return Packed(p.text + "\n" + t, p.segments + Segment(t, Provenance.ATALL))
    }

    private fun core(i: Input, reserve: Int): Packed {
        val user = i.userName.trim().ifBlank { "The user" }
        val head = header(i.userName)
        val tail = buildList {
            if (i.others.isNotEmpty()) {
                val who = joinNames(i.others.distinct())
                add(if (i.kind == Kind.BLIND) "Also asking: $who (answer on your own)" else "Also asking: $who")
            }
        }
        val atall = { t: String -> Segment(t, Provenance.ATALL) }

        if (i.kind == Kind.ROOM) {
            val body = prose(i.need.orEmpty())
            var text = (listOf(head, body)).joinToString("\n")
            if (text.length > ROOM_MAX_CHARS - reserve) text = Relay.trim(text, ROOM_MAX_CHARS - reserve)
            val prov = if (i.approved) Provenance.USER_APPROVED else Provenance.PEER_RELAY
            return Packed(text, listOf(atall(text)) + i.quoted.map { Segment(it, prov) })
        }

        if (i.kind == Kind.DELEGATED || i.kind == Kind.CHALLENGE) {
            // Juno's sub-task is self-contained: no "needs:" label, no repeat of the user's words, no recent turns.
            // It goes in whole, never shortened.
            val need = i.need?.let { prose(stripMentions(Markdown.plain(it), i.recipientName, i.names.values)) }.orEmpty()
                .ifBlank { prose(stripMentions(Markdown.plain(i.request), i.recipientName, i.names.values)) }
            val prov = when {
                i.approved -> Provenance.USER_APPROVED
                i.kind == Kind.CHALLENGE -> Provenance.PEER_RELAY
                else -> Provenance.JUNO_REWRITE
            }
            val ctx = i.context?.let { prose(it) }?.ifBlank { null }
            val text = (listOf(head) + listOfNotNull(need.ifBlank { null }, ctx) + tail).joinToString("\n")
            return Packed(text, listOf(atall(head)) + listOfNotNull(need.ifBlank { null }?.let { Segment(it, prov) }, ctx?.let(atall)) + tail.map(atall))
        }

        val request = squash(stripMentions(Markdown.plain(i.request), i.recipientName, i.names.values))
        val need = i.need?.let { squash(stripMentions(Markdown.plain(it), i.recipientName, i.names.values)) }
        val core = buildList {
            add(atall(head))
            when (i.kind) {
                Kind.DIRECT -> add(Segment("$user: $request", Provenance.USER_VERBATIM))
                else -> {
                    need?.let { add(Segment("$MAIN_NAME needs: $it", if (i.approved) Provenance.USER_APPROVED else Provenance.JUNO_REWRITE)) }
                    if (request.isNotEmpty() && (need == null || squash(need) != request)) add(Segment("$user asked: \"$request\"", Provenance.USER_VERBATIM))
                }
            }
        }
        val ctx = i.context?.let { prose(it) }?.ifBlank { null }
        if (ctx != null) {
            val segs = core + atall(ctx) + tail.map(atall)
            return Packed(segs.joinToString("\n") { it.text }, segs)
        }
        val recent = recentLines(i, user)
        // Keep the newest whole turns that fit; drop the rest, oldest first. A turn is never cut partway.
        for (n in recent.size downTo 0) {
            val block = if (n == 0) emptyList() else listOf(atall("Recent:")) + recent.takeLast(n)
            val segs = core + block + tail.map(atall)
            val text = segs.joinToString("\n") { it.text }
            if (n == 0 || text.length <= MAX_CHARS) return Packed(text, segs)
        }
        val segs = core + tail.map(atall)
        return Packed(segs.joinToString("\n") { it.text }, segs)
    }

    /**
     * Recent turns with who wrote them. A turn from Juno or another agent that would read as the user's go-ahead
     * ("yes, book it") is dropped before it can travel under the user's number.
     */
    private fun recentLines(i: Input, user: String): List<Segment> {
        val newest = i.recent.maxOfOrNull { it.at } ?: return emptyList()
        return i.recent
            .filter { it.text.isNotBlank() && newest - it.at <= RECENT_WINDOW_MS }
            // Blind asks must not see other agents' answers.
            .filter { i.kind != Kind.BLIND || it.author == ThreadMessage.USER || it.author == Main.AUTHOR }
            .takeLast(MAX_RECENT)
            .mapNotNull { l ->
                val who = when (l.author) {
                    ThreadMessage.USER -> user
                    Main.AUTHOR -> MAIN_NAME
                    i.recipientId -> "You"
                    else -> i.names[l.author] ?: "Another agent"
                }
                val content = squash(stripMentions(Markdown.plain(l.text), i.recipientName, i.names.values))
                // Quoted, labelled context: Juno's and agents' past turns are checked like relayed words, and one that
                // reads as a go-ahead or an order is left out.
                if (l.author != ThreadMessage.USER && SendGate.relayHit(content, i.userName) != null) return@mapNotNull null
                Segment("- $who: $content", if (l.author == ThreadMessage.USER) Provenance.USER_VERBATIM else Provenance.ATALL)
            }
    }

    /**
     * A leading "@Recipient" (and the punctuation after it) is dropped; every other "@Name" keeps the name without
     * the "@" so the agent reads plain prose ("i think Nova and Scout know the deets").
     */
    fun stripMentions(text: String, recipient: String, known: Collection<String>): String {
        var t = text.trim()
        val lead = Regex("^@" + Regex.escape(recipient) + "(?![\\p{L}\\p{N}_-])[\\s,:;]*", RegexOption.IGNORE_CASE)
        t = lead.replace(t, "")
        // "@all" / "@everyone" address the fan-out, not the agent: drop them.
        t = Regex("(?<![\\p{L}\\p{N}_.])@(all|everyone)(?![\\p{L}\\p{N}_-])[,:]?", RegexOption.IGNORE_CASE).replace(t, "")
            .replace(Regex("[ \\t]{2,}"), " ").trim()
        val names = (known + recipient + MAIN_NAME).filter { it.isNotBlank() }
        for (n in names.sortedByDescending { it.length }) {
            t = Regex("(?<![\\p{L}\\p{N}_.])@(" + Regex.escape(n) + ")(?![\\p{L}\\p{N}_-])", RegexOption.IGNORE_CASE).replace(t) { it.groupValues[1] }
        }
        return t.trim().ifEmpty { text.trim() }
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Spaces squashed within each line; blank lines dropped (a sub-task may be a short list). */
    private fun prose(s: String) = s.lines().map { squash(it) }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun possessive(name: String) = if (name.endsWith("s", ignoreCase = true)) "$name'" else "$name's"

    fun joinNames(ns: List<String>): String = when (ns.size) {
        0 -> ""
        1 -> ns[0]
        2 -> "${ns[0]} and ${ns[1]}"
        else -> ns.dropLast(1).joinToString(", ") + " and " + ns.last()
    }

    private const val MAIN_NAME = Main.NAME
}
