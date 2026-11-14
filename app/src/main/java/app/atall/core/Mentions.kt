package app.atall.core

/** One @mention found in a message. [range] covers the "@Name" text. */
data class Mention(val target: String, val range: IntRange)

/** Where a user message goes. */
sealed interface Route {
    /** No mention, or @Juno (with or without peers): the main agent handles it. */
    data class ToMain(val text: String, val mentionedPeers: List<String>) : Route
    /** Only peers were mentioned: each gets [text] directly, the main agent stays quiet. */
    data class Direct(val peerIds: List<String>, val text: String) : Route
    /** `@all` / `@everyone`: the same question to every active agent, blind, then the Agreement Map. */
    data class All(val text: String) : Route
    /**
     * "Talk it over": the agents answer on their own first, then see each other's answers and reply, then Juno wraps
     * up. [peerIds] empty means every active agent.
     */
    data class Talk(val text: String, val peerIds: List<String> = emptyList()) : Route
}

/**
 * Finds @mentions of known names (case-insensitive, whole word: the next char isn't a letter, digit, '_' or '-')
 * and decides the route. Names are matched against [Main.NAME] and every agent name; unknown "@words" stay text.
 */
object Mentions {
    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    /** [names] maps a lowercased name to its target id (a peer id, or [Main.AUTHOR]). */
    fun find(text: String, names: Map<String, String>): List<Mention> {
        val out = ArrayList<Mention>()
        var i = 0
        while (i < text.length) {
            val at = text.indexOf('@', i)
            if (at < 0) break
            // "@" must start a word (so emails like a@b.com don't count).
            if (at > 0 && isNameChar(text[at - 1])) { i = at + 1; continue }
            var end = at + 1
            while (end < text.length && isNameChar(text[end])) end++
            // Allow trailing punctuation like "@Nova," or "@Nova." to still match "Nova".
            var word = text.substring(at + 1, end)
            while (word.isNotEmpty() && (word.last() == '.' || word.last() == '-')) word = word.dropLast(1)
            val id = names[word.lowercase()]
            if (id != null) out += Mention(id, at..(at + word.length))
            i = maxOf(end, at + 1)
        }
        return out
    }

    fun nameMap(agents: List<AgentProfile>): Map<String, String> =
        agents.associate { it.name.lowercase() to it.id } + (Main.NAME.lowercase() to Main.AUTHOR)

    /** The user's own words asking for a talk ("talk it over", "talk this over"). */
    private val TALK_WORDS = Regex("\\btalk (?:it|this|that) over\\b", RegexOption.IGNORE_CASE)
    fun asksToTalk(text: String): Boolean = TALK_WORDS.containsMatchIn(text)

    /**
     * Where [text] goes with the "Talk it over" chip on ([talk]) or the words in it: a [Route.Talk] when at least two
     * agents would take part (`@all`, two or more @mentions, or none named and two or more active agents);
     * otherwise the ordinary route.
     */
    fun route(text: String, agents: List<AgentProfile>, talk: Boolean): Route {
        val plain = route(text, agents)
        if (!talk && !asksToTalk(text)) return plain
        val active = agents.filter { !it.paused }
        val names = nameMap(agents)
        val mentioned = find(text, names).map { it.target }.filter { it != Main.AUTHOR }.distinct()
        val all = allMentions(text, names).isNotEmpty()
        val who = when {
            all -> emptyList()
            mentioned.size >= 2 -> mentioned
            mentioned.isEmpty() -> emptyList()
            else -> return plain
        }
        if (who.isEmpty() && active.size < 2) return plain
        val body = remove(text, allMentions(text, names) + find(text, names))
        return if (body.isBlank()) plain else Route.Talk(body, who)
    }

    fun route(text: String, agents: List<AgentProfile>): Route {
        val names = nameMap(agents)
        val all = allMentions(text, names)
        if (all.isNotEmpty()) {
            val rest = find(text, names).filter { it.target == Main.AUTHOR }
            val body = remove(text, all + rest)
            if (body.isNotBlank()) return Route.All(body)
        }
        val mentions = find(text, names)
        val peers = mentions.map { it.target }.filter { it != Main.AUTHOR }.distinct()
        val main = mentions.any { it.target == Main.AUTHOR }
        return if (peers.isEmpty() || main) Route.ToMain(text.trim(), peers)
        else Route.Direct(peers, stripLeading(text, mentions))
    }

    /**
     * The text a peer receives for a direct message: leading mentions ("@Nova @Scout what's up") are dropped,
     * mentions later in the sentence keep the name without the "@". Falls back to the whole text if nothing's left.
     */
    fun stripLeading(text: String, mentions: List<Mention>): String {
        val sb = StringBuilder()
        var pos = 0
        var leading = true
        for (m in mentions.sortedBy { it.range.first }) {
            val before = text.substring(pos, m.range.first)
            if (leading && before.isNotBlank()) leading = false
            if (!leading) sb.append(before).append(text.substring(m.range.first + 1, m.range.last + 1))
            pos = m.range.last + 1
        }
        sb.append(text.substring(pos))
        val out = sb.toString().trim().trimStart(',', ':', ' ').trim()
        return out.ifEmpty { text.trim() }
    }

    /** `@all` / `@everyone` tokens, unless an agent is actually called that. */
    fun allMentions(text: String, names: Map<String, String>): List<Mention> {
        val out = ArrayList<Mention>()
        for (m in ALL.findAll(text)) {
            val word = m.groupValues[1]
            if (names.containsKey(word.lowercase())) continue
            out += Mention(ALL_TARGET, m.range.first..(m.range.first + word.length))
        }
        return out
    }

    /** The text with [mentions] removed entirely (and the punctuation right after a leading one). */
    fun remove(text: String, mentions: List<Mention>): String {
        val sb = StringBuilder()
        var pos = 0
        for (m in mentions.sortedBy { it.range.first }) {
            if (m.range.first < pos) continue
            sb.append(text, pos, m.range.first)
            pos = m.range.last + 1
            if (sb.isBlank()) while (pos < text.length && (text[pos] == ',' || text[pos] == ':')) pos++
        }
        sb.append(text.substring(pos))
        return sb.toString().replace(Regex("[ \t]{2,}"), " ").trim()
    }

    /** The text with every "@" of a mention dropped ("@Nova where" -> "Nova where"), for matching quotes. */
    fun plain(text: String): String = text.replace(Regex("(?<![\\p{L}\\p{N}_.])@(?=\\p{L})"), "")

    const val ALL_TARGET = "*all"
    private val ALL = Regex("(?<![\\p{L}\\p{N}_.@-])@(all|everyone)(?![\\p{L}\\p{N}_-])", RegexOption.IGNORE_CASE)

    /**
     * The "@prefix" being typed right before [cursor], for the picker. Returns the prefix (without '@') and
     * where the '@' is, or null when the cursor isn't in a mention token.
     */
    fun activeToken(text: String, cursor: Int): Pair<String, Int>? {
        if (cursor < 1 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0 && isNameChar(text[i])) i--
        if (i < 0 || text[i] != '@') return null
        if (i > 0 && isNameChar(text[i - 1])) return null
        return text.substring(i + 1, cursor) to i
    }

    /** Filter for the picker: prefix matches first, then names containing the query. */
    fun filter(query: String, names: List<String>): List<String> {
        val q = query.lowercase()
        if (q.isEmpty()) return names
        val prefix = names.filter { it.lowercase().startsWith(q) }
        val contains = names.filter { it !in prefix && it.lowercase().contains(q) }
        return prefix + contains
    }
}
