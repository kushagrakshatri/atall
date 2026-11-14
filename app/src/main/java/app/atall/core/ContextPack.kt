package app.atall.core

import app.atall.api.ThreadLine

/**
 * The compact context an agent receives with every message from Atall, so it can act without asking "which deets?".
 * One text message: a header, what is being asked, the user's words, a few trimmed recent turns, and who else is
 * being asked. Pure; the result never exceeds [MAX_CHARS].
 *
 * ```
 * From Atall (Kush's thread):
 * Juno needs: Your pick of dates and a budget for the Thanksgiving trip.
 * Kush asked: "can you all decide on the trip for Thanksgiving"
 * Recent:
 * - Kush: thinking Tahoe or Big Sur
 * - Juno: Want me to check what Nova and Scout know?
 * Also asking: Scout
 * ```
 */
object ContextPack {
    const val MAX_CHARS = 700
    const val MAX_RECENT = 6
    const val RECENT_LINE_CHARS = 140
    const val REQUEST_CHARS = 200
    const val NEED_CHARS = 300
    /** Turns older than this (relative to the newest) are not "recent". */
    const val RECENT_WINDOW_MS = 6 * 3_600_000L

    enum class Kind {
        /** The user @mentioned the agent: it gets the user's words. */
        DIRECT,
        /** Juno delegated: it gets Juno's rewritten sub-task first. */
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

    fun header(userName: String): String =
        if (userName.isBlank()) "From Atall (your user's thread):" else "From Atall (${possessive(userName.trim())} thread):"

    fun pack(i: Input): String {
        val user = i.userName.trim().ifBlank { "The user" }
        val request = squash(stripMentions(i.request, i.recipientName, i.names.values)).let { trim(it, REQUEST_CHARS) }
        val need = i.need?.let { squash(stripMentions(it, i.recipientName, i.names.values)) }?.let { trim(it, NEED_CHARS) }
        val head = header(i.userName)

        if (i.kind == Kind.CHALLENGE) return fit(listOfNotNull(head, need?.let { "$MAIN_NAME needs: $it" }))

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
        val tail = buildList {
            if (i.others.isNotEmpty()) {
                val who = joinNames(i.others.distinct())
                add(if (i.kind == Kind.BLIND) "Also asking: $who (answer on your own)" else "Also asking: $who")
            }
        }
        val recent = recentLines(i, user)
        // Drop the oldest recent turns until the whole thing fits in one text.
        for (n in recent.size downTo 0) {
            val block = if (n == 0) emptyList() else listOf("Recent:") + recent.takeLast(n)
            val text = (core + block + tail).joinToString("\n")
            if (text.length <= MAX_CHARS) return text
        }
        return fit(core + tail)
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
                "- $who: " + trim(squash(stripMentions(l.text, i.recipientName, i.names.values)), RECENT_LINE_CHARS)
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

    private fun fit(lines: List<String>): String {
        val text = lines.joinToString("\n")
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1).trimEnd() + "…"
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun trim(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun possessive(name: String) = if (name.endsWith("s", ignoreCase = true)) "$name'" else "$name's"

    fun joinNames(ns: List<String>): String = when (ns.size) {
        0 -> ""
        1 -> ns[0]
        2 -> "${ns[0]} and ${ns[1]}"
        else -> ns.dropLast(1).joinToString(", ") + " and " + ns.last()
    }

    private const val MAIN_NAME = Main.NAME
}
