package app.atall.core

import app.atall.api.ThreadLine

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
    }

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
    )

    /** Every text from Atall starts with this; a tapback quoting it reacts to one of our texts. */
    const val HEADER_PREFIX = "From Atall ("

    fun header(userName: String): String =
        if (userName.isBlank()) "From Atall (your user's thread):" else "From Atall (${possessive(userName.trim())} thread):"

    fun pack(i: Input): String {
        val user = i.userName.trim().ifBlank { "The user" }
        val head = header(i.userName)
        val tail = buildList {
            if (i.others.isNotEmpty()) {
                val who = joinNames(i.others.distinct())
                add(if (i.kind == Kind.BLIND) "Also asking: $who (answer on your own)" else "Also asking: $who")
            }
        }

        if (i.kind == Kind.DELEGATED || i.kind == Kind.CHALLENGE) {
            // Juno's sub-task is self-contained: no "needs:" label, no repeat of the user's words, no recent turns.
            // It goes in whole, never shortened.
            val need = i.need?.let { prose(stripMentions(Markdown.plain(it), i.recipientName, i.names.values)) }.orEmpty()
                .ifBlank { prose(stripMentions(Markdown.plain(i.request), i.recipientName, i.names.values)) }
            return (listOf(head) + listOfNotNull(need.ifBlank { null }) + tail).joinToString("\n")
        }

        val request = squash(stripMentions(Markdown.plain(i.request), i.recipientName, i.names.values))
        val need = i.need?.let { squash(stripMentions(Markdown.plain(it), i.recipientName, i.names.values)) }
        val core = buildList {
            add(head)
            when (i.kind) {
                Kind.DIRECT -> add("$user: $request")
                else -> {
                    need?.let { add("$MAIN_NAME needs: $it") }
                    if (request.isNotEmpty() && (need == null || squash(need) != request)) add("$user asked: \"$request\"")
                }
            }
        }
        val recent = recentLines(i, user)
        // Keep the newest whole turns that fit; drop the rest, oldest first. A turn is never cut partway.
        for (n in recent.size downTo 0) {
            val block = if (n == 0) emptyList() else listOf("Recent:") + recent.takeLast(n)
            val text = (core + block + tail).joinToString("\n")
            if (n == 0 || text.length <= MAX_CHARS) return text
        }
        return (core + tail).joinToString("\n")
    }

    private fun recentLines(i: Input, user: String): List<String> {
        val newest = i.recent.maxOfOrNull { it.at } ?: return emptyList()
        return i.recent
            .filter { it.text.isNotBlank() && newest - it.at <= RECENT_WINDOW_MS }
            // Blind asks must not see other agents' answers.
            .filter { i.kind != Kind.BLIND || it.author == ThreadMessage.USER || it.author == Main.AUTHOR }
            .takeLast(MAX_RECENT)
            .map { l ->
                val who = when (l.author) {
                    ThreadMessage.USER -> user
                    Main.AUTHOR -> MAIN_NAME
                    i.recipientId -> "You"
                    else -> i.names[l.author] ?: "Another agent"
                }
                "- $who: " + squash(stripMentions(Markdown.plain(l.text), i.recipientName, i.names.values))
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
