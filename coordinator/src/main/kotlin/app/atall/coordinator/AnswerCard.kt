package app.atall.coordinator

import kotlinx.serialization.Serializable

// The answer card (design-system/mocks/answer-card, approved by Kush 2026-11-19): the decision first in 2-3 short
// lines, a small credit tile with the agent's logo at the end of each line that came from an agent, one disagreement
// line only when an agent really dissents (it opens "Your call"), action chips under it, and late changes edited in
// place with the changed words softly highlighted and "Changed just now". No system or relay words ever reach it.

/** One line of the answer and the agents it came from (their credit tiles, glued to its last word). */
@Serializable
data class CardLine(val text: String, val credits: List<String> = emptyList())

/** "<Agent>: <one line>": an agent that still sees it differently. Tapping it opens the Your call sheet. */
@Serializable
data class DissentNote(val agentId: String, val line: String)

/** One agent's take in the Your call sheet (its logo, its name in plain ink, its answer in a sentence or two). */
@Serializable
data class CardTake(val agentId: String, val name: String, val text: String)

/** A "Your call" option: an if/then rule ([condition] small, [pick] bold) or a plain answer to the deciding question. */
@Serializable
data class CallOption(val pick: String, val condition: String? = null)

@Serializable
data class CallSheet(val title: String, val subtitle: String, val takes: List<CardTake>, val options: List<CallOption>)

@Serializable
data class AnswerCard(
  val runId: String,
  val lines: List<CardLine>,
  /** Agents are still being asked: the take shows muted, with the row of agent tiles (pending ones faded). */
  val thinking: Boolean = false,
  val dissent: DissentNote? = null,
  val dissentName: String? = null,
  val sheet: CallSheet? = null,
  /** Up to two next steps; the first is the primary (filled) chip. Sent as Kush's own message when tapped. */
  val actions: List<String> = emptyList(),
  /** A late change (after the answer settled): the answer before it, for the soft highlight on what changed. */
  val previous: String? = null,
  val changedAt: Long? = null,
) {
  /** The answer as plain text: what the thread line's text, copy and TalkBack read. */
  val plain: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Credit markers in Juno's answer: a line ends with `{Grok}` or `{Grok, Hark}` (agent names). Stored in the run's
 * answer so the next fold keeps them; never shown raw. A marker in the middle of a line ends that line there.
 */
object Credits {
  private val MARK = Regex("\\{\\s*([^{}\\n]{1,80}?)\\s*\\}")

  /** The answer split into lines, each with the agent ids it credits (unknown names and Juno itself are dropped). */
  fun parse(answer: String, resolve: (String) -> String?): List<CardLine> {
    val out = mutableListOf<CardLine>()
    for (raw in answer.replace("\r", "").split('\n')) {
      var line = raw.trim().removePrefix("- ").removePrefix("• ").trim()
      if (line.isEmpty()) continue
      var pos = 0
      var text = StringBuilder()
      var credits = mutableListOf<String>()
      fun flush() {
        val t = text.toString().replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\s+([.,!?;:…])"), "$1").trim()
        if (t.isNotEmpty()) out += CardLine(t, credits.distinct())
        else if (credits.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last().copy(credits = (out.last().credits + credits).distinct())
        text = StringBuilder(); credits = mutableListOf()
      }
      for (m in MARK.findAll(line)) {
        // A marker after a sentence with more text following ends that line there; one mid-sentence moves to its end.
        text.append(line.substring(pos, m.range.first))
        names(m.groupValues[1]).mapNotNull(resolve).forEach { credits += it }
        pos = m.range.last + 1
        val rest = line.substring(pos)
        if (rest.isNotBlank() && Regex("[.!?…:;]\\s*$").containsMatchIn(text.toString().trimEnd())) flush()
      }
      text.append(line.substring(pos))
      flush()
    }
    return out
  }

  /** The answer with every marker taken out (same lines). */
  fun plain(answer: String): String = parse(answer) { null }.joinToString("\n") { it.text }

  /** "{Grok}" for [names]: what the fallback writes. */
  fun mark(names: List<String>): String = if (names.isEmpty()) "" else " {" + names.distinct().joinToString(", ") + "}"

  private fun names(inner: String): List<String> = inner.split(Regex("\\s*(,|&|\\band\\b|/)\\s*")).map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
}

/**
 * Words that belong to the machinery, not to an answer: "Only Grok knew", "Changed:", run states ("stalled in
 * MERGING"), relay talk. The editor prompt forbids them; this takes out whatever slips through.
 */
object AnswerWords {
  private val STATES = "QUEUED|SENDING|PENDING|DRAFTED|REPLYING|ANSWERED|TIMED_OUT|LIKELY_MISSED|NEEDS_TAP|AWAITING_APPROVAL|AWAITING_USER|" +
    "PLANNING|WAITING|MERGING|SETTLED|DONE|FAILED|CANCELLED|COORDINATE|ROOM"
  /** A sentence with any of these is dropped whole. */
  private val SYSTEM_SENTENCE = Regex(
    "\\b(?:$STATES)\\b|\\bstalled\\b|\\brelay(?:ed|ing)?\\b|\\brun ?id\\b|\\bclaims table\\b|" +
      "\\b(?:first|blind) (?:take|answers?)\\b|\\bask id\\b|\\bwebhook\\b|\\btask #\\w+\\b",
  )
  private val LEAD = Regex("^\\s*(?:\\*\\*\\s*(?:Changed|Update|Updated|Fix|Fixed|Correction|Note)\\s*:\\s*\\*\\*|(?:Changed|Update|Updated|Fix|Fixed|Correction|Note)\\s*:)\\s*", RegexOption.IGNORE_CASE)
  private val ONLY_KNEW = Regex("\\b(?i:only) @?\\p{Lu}[\\p{L}\\p{N}._-]* (?i:knew|knows|mentioned|pointed out)(?:\\s*:\\s*|\\s+that\\s+|\\s+)")
  private val LATE = Regex("\\*{0,2}[^.\\n]*\\b(?:late reply|late answer)\\b[^.\\n]*\\.\\*{0,2}\\s*", RegexOption.IGNORE_CASE)

  /** The answer with system wording taken out, credit markers kept. Never returns blank when [text] has content. */
  fun clean(text: String, agentNames: Collection<String> = emptyList()): String {
    val names = agentNames.filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }
    val knewNamed = if (names.isEmpty()) null else Regex("\\b(?:As )?@?(?:$names) (?:knew|knows|pointed out|flagged|noted) (?:that )?", RegexOption.IGNORE_CASE)
    val lines = text.replace("\r", "").split('\n').map { raw ->
      var l = raw.replace(LEAD, "").replace(LATE, "")
      val before = l
      l = ONLY_KNEW.replace(l) { "" }
      if (knewNamed != null) l = knewNamed.replace(l) { "" }
      // A sentence that now starts mid-phrase ("you're 25, so…") gets its capital back.
      if (l != before) l = l.replace(Regex("([.!?]\\*{0,2}[ \\t]+)(\\p{Ll})")) { it.groupValues[1] + it.groupValues[2].uppercase() }
      l = l.replace(LEAD, "")
      // Sentences that are only machinery go; keep the rest of the line.
      val sentences = Regex("(?<=[.!?])\\s+").split(l)
      l = sentences.filterNot { SYSTEM_SENTENCE.containsMatchIn(it.replace(Regex("\\{[^}]*\\}"), "")) }.joinToString(" ")
      capitalizeFirst(l.trim())
    }
    val out = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return out.ifEmpty { text.trim().replace(LEAD, "") }
  }

  /** Whether [text] still holds a word the user should never see (for tests and a last check). */
  fun leaks(text: String): Boolean {
    val t = text.replace(Regex("\\{[^}]*\\}"), "")
    return SYSTEM_SENTENCE.containsMatchIn(t) || ONLY_KNEW.containsMatchIn(t) || LEAD.containsMatchIn(t.trim()) ||
      Regex("late reply changes", RegexOption.IGNORE_CASE).containsMatchIn(t)
  }

  private fun capitalizeFirst(s: String): String {
    val i = s.indexOfFirst { it.isLetter() }
    if (i < 0) return s
    return s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
  }
}

object Cards {
  /** At most this many answer lines are shown as the decision (the model is asked for 2-3). */
  const val MAX_LINES = 4

  /**
   * The card for an editor run, or null when the run has no answer yet. [presence] gives each agent's own answer
   * (the takes in the sheet). A dissent shows only when it is real: the run's comparison holds a disagreement and the
   * dissenting agent is on one of its sides.
   */
  fun build(s: RunState, roster: Roster, presence: PresenceView?): AnswerCard? {
    val e = s.editor ?: return null
    val answer = s.answer ?: return null
    val main = roster.main.id
    // Plain chat (nobody asked) stays an ordinary reply; the card is for answers the agents fed.
    if (s.asks.none { it.agentId != main }) return null
    fun resolve(n: String): String? = roster.resolve(n)?.takeIf { it.id != main && !it.paused }?.id
    val lines = Credits.parse(answer, ::resolve)
    if (lines.isEmpty()) return null
    // Still settling: the take shows muted until the fast answers are folded in (slow agents later edit it in place).
    val thinking = !s.phase.terminal && presence?.live == true
    val disputes = e.table?.disputes.orEmpty()
    val dissent = e.dissent?.takeIf { d -> real(d, disputes, main) }
    val sheet = if (dissent != null || e.yourCall != null) sheet(s, roster, disputes, e.yourCall, dissent) else null
    val last = e.versions.lastOrNull()
    val late = last != null && e.settledAt != null && last.at > e.settledAt && last.kind != FoldKind.NONE && last.kind != FoldKind.FIRST && e.versions.size >= 2
    val previous = if (late) Credits.plain(e.versions[e.versions.size - 2].text) else null
    return AnswerCard(
      s.runId, lines, thinking,
      dissent = dissent, dissentName = dissent?.let { roster.nameOf(it.agentId) },
      sheet = sheet?.takeIf { dissent != null },
      actions = if (thinking) emptyList() else e.actions.take(2),
      previous = previous?.takeIf { it != Credits.plain(answer) }, changedAt = if (late) last!!.at else null,
    )
  }

  /** A dissent is real only when the comparison found a disagreement and the agent is on one of its sides. */
  fun real(d: DissentNote, disputes: List<Dispute>, main: String): Boolean =
    d.agentId != main && d.line.isNotBlank() && disputes.any { x -> x.sides.size >= 2 && x.sides.any { d.agentId in it.agentIds } &&
      x.sides.map { it.agentIds.toSet() }.distinct().size >= 2 }

  /** Without the model: the side with the fewest agents (not Juno) dissents, in its own statement. */
  fun fallbackDissent(disputes: List<Dispute>, main: String): DissentNote? {
    val d = disputes.firstOrNull { it.sides.size >= 2 } ?: return null
    val side = d.sides.drop(1).lastOrNull { s -> s.agentIds.any { it != main } } ?: return null
    val agent = side.agentIds.first { it != main }
    return DissentNote(agent, oneLine(side.statement))
  }

  private fun sheet(s: RunState, roster: Roster, disputes: List<Dispute>, call: YourCall?, dissent: DissentNote?): CallSheet? {
    val d = disputes.firstOrNull { x -> call?.disputeId == x.id } ?: disputes.firstOrNull { x -> dissent != null && x.sides.any { dissent.agentId in it.agentIds } }
      ?: disputes.firstOrNull() ?: return null
    val main = roster.main.id
    val agents = d.sides.flatMap { it.agentIds }.distinct().filter { it != main }
    val takes = agents.mapNotNull { id ->
      val text = s.asks.lastOrNull { it.agentId == id && it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }?.let { AskTail.strip(it.replyText) }
        ?: d.sides.firstOrNull { id in it.agentIds }?.statement
      text?.takeIf { it.isNotBlank() }?.let { CardTake(id, roster.nameOf(id), short(AnswerWords.clean(Credits.plain(it)))) }
    }
    val values = d.sides.map { it.value.trim() }.filter { it.isNotEmpty() }.distinct().take(2)
    val title = when {
      call?.kind == CallKind.QUESTION && !call.question.isNullOrBlank() -> call.question!!.trim()
      values.size == 2 -> capitalize("${values[0]} or ${values[1]}?")
      else -> capitalize(d.topic.trim().trimEnd('?', '.') + "?")
    }
    val who = d.sides.flatMap { it.agentIds }.distinct().filter { it != main }.map(roster::nameOf)
    val subtitle = when (who.size) { 0 -> ""; 1 -> "${who[0]} sees it differently."; else -> "${Views.names(who)} see it differently." }
    val options = when (call?.kind) {
      CallKind.CONDITIONAL -> call.rules.map { CallOption(it.pick.trim().trimEnd('.'), "If " + it.condition.trim().removePrefix("if ").removePrefix("If ").trimEnd('.', ',')) }
      CallKind.QUESTION -> call.choices.map { CallOption(it.trim()) }
      null -> emptyList()
    }
    return CallSheet(title, subtitle, takes, options)
  }

  /** A sentence or two: the take in the sheet stays short. */
  fun short(text: String, max: Int = 220): String {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.length <= max) return t
    val cut = Regex("(?<=[.!?])\\s").findAll(t).map { it.range.first }.filter { it in 40..max }.lastOrNull()
    return if (cut != null) t.substring(0, cut).trim() else t.take(max - 1).trimEnd() + "…"
  }

  fun oneLine(text: String): String {
    val t = Credits.plain(text).replace(Regex("\\s+"), " ").trim().trimEnd('.')
    val first = Regex("(?<=[.!?])\\s").find(t)?.let { t.substring(0, it.range.first).trimEnd('.') } ?: t
    return if (first.length > 90) first.take(89).trimEnd() + "…" else first
  }

  private fun capitalize(s: String) = s.replaceFirstChar { it.uppercaseChar() }
}
