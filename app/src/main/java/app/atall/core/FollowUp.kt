package app.atall.core

import app.atall.api.ThreadLine
import app.atall.coordinator.SendGate

/**
 * A message that is mostly an @mention or a bare follow-up ("try to bring in @all", "@Grok thoughts?", "you too")
 * means nothing to an agent on its own. Device bug, v0.1.6: after Juno's dinner-plan answer, "try to bring in @all"
 * reached every agent as just "try to bring in". Such a message is expanded with what is being asked: the user's
 * intent ("weigh in on this plan"), Juno's last answer (the plan) and its open question, and the user's earlier request.
 *
 * The intent line is Juno's words (it may go anywhere a sub-task goes). The context block is quoted thread history:
 * sentences that would read as an order or a go-ahead are left out, so it can never carry the user's OK.
 */
object FollowUp {
    /** Juno's answer is quoted whole up to this; longer, it is cut at a sentence end. */
    const val MAX_PLAN = 1_200
    private const val WINDOW_MS = 6 * 3_600_000L

    private val FOLLOW = Regex(
        "\\b(bring (?:them |everyone |others )?in|weigh in|chime in|thoughts|what do (?:you|y'all|you all) think|opinions?|your take|" +
            "second opinion|sanity[- ]check|check (?:this|that|it)|double[- ]check|look (?:at|over) (?:this|that|it)|agree|you too|same question|" +
            "what about you|anyone else|ask (?:them|everyone|the others)|loop in|get (?:their|everyone's) take|try)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CHECK = Regex("\\b(check|verify|sanity|double[- ]check|look (?:at|over))\\b", RegexOption.IGNORE_CASE)

    data class Expansion(
        /** One line, Juno's words: what the agents are asked to do. */
        val intent: String,
        /** Quoted context: the user's words, the earlier request, Juno's plan, its open question. */
        val context: String,
    ) {
        /** Both, for Juno's own model (the run's question). */
        val full: String get() = intent + "\n" + context
    }

    /** The words left once mentions are gone, e.g. "try to bring in". */
    fun words(raw: String, names: Collection<String>): String =
        ContextPack.stripMentions(Markdown.plain(raw), "", emptyList())
            .replace(Regex("(?<![\\p{L}\\p{N}_.])@[\\p{L}\\p{N}_-]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

    /** Mostly an @mention or a bare follow-up: four words or fewer, or a short follow-up phrase. */
    fun isThin(words: String): Boolean {
        val n = words.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        return n <= 4 || (n <= 10 && FOLLOW.containsMatchIn(words))
    }

    /**
     * The expansion for [raw] (the user's line as typed) given the thread turns before it ([recent], oldest first), or
     * null when the line stands on its own or there is nothing before it to refer to.
     */
    fun expand(raw: String, recent: List<ThreadLine>, userName: String, names: Collection<String> = emptyList()): Expansion? {
        val w = words(raw, names)
        if (!isThin(w)) return null
        val newest = recent.maxOfOrNull { it.at } ?: return null
        val live = recent.filter { it.text.isNotBlank() && newest - it.at <= WINDOW_MS }
        val answerIdx = live.indexOfLast { it.author == Main.AUTHOR }
        val answer = live.getOrNull(answerIdx)?.let { clean(it.text, userName) }?.takeIf { it.isNotBlank() }
        val earlier = (if (answerIdx >= 0) live.subList(0, answerIdx) else live)
            .lastOrNull { it.author == ThreadMessage.USER && !isThin(words(it.text, names)) }
            ?.let { squash(Markdown.plain(ContextPack.stripMentions(it.text, "", names))) }
        if (answer == null && earlier == null) return null
        val user = userName.trim().ifBlank { "The user" }
        val question = answer?.let { openQuestion(it) }
        val intent = when {
            answer != null && CHECK.containsMatchIn(w) -> "Check ${Main.NAME}'s plan below for anything wrong or missing."
            answer != null -> "Weigh in on ${Main.NAME}'s plan below: agree, push back, or add what's missing."
            else -> "Help with $user's request below."
        }
        val context = buildList {
            if (w.isNotBlank()) add("$user's words: \"$w\"")
            earlier?.let { add("$user asked earlier: \"$it\"") }
            answer?.let { add("${Main.NAME}'s plan: ${cap(it)}") }
            question?.let { add("Open question: $it") }
        }.joinToString("\n")
        return Expansion(intent, context)
    }

    /** The last question in Juno's answer ("Want me to hold it?"). */
    fun openQuestion(answer: String): String? = SendGate.sentences(answer).lastOrNull { it.endsWith("?") }

    /** Juno's answer as plain prose, minus any sentence that would read as an order or a go-ahead when relayed. */
    private fun clean(text: String, userName: String): String =
        SendGate.sentences(squash(Markdown.plain(text))).filter { SendGate.relayHit(it, userName) == null }.joinToString(" ")

    private fun cap(s: String): String {
        if (s.length <= MAX_PLAN) return s
        val cut = s.take(MAX_PLAN)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
        return if (end > MAX_PLAN / 2) cut.substring(0, end + 1) else cut.substringBeforeLast(' ')
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
