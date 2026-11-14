package app.atall.coordinator

/**
 * Everything the thread renders for one run, derived purely from [RunState] + roster + clock, so the UI can be
 * rebuilt after a crash from what's persisted (design.md §3.2). Copy follows design-system.md's fixed vocabulary.
 */
data class RunView(
  val runId: String,
  val mode: RunMode,
  val phase: Phase,
  val strip: DelegationStrip?,
  /** The merged answer in Juno's voice, once posted. */
  val answer: String?,
  /** Raw replies under the answer: "From kDot and Hark ›". */
  val folded: FoldedReplies?,
  /** One-line, one-action states ("Tap to send to kDot", approvals, "look in Messages", Juno's question). */
  val needsUser: List<NeedsUserLine>,
  val followUps: List<FollowUp>,
  val agreement: AgreementMap?,
  val error: String?,
  val errorKind: LlmException.Kind?,
)

enum class StripPhase { LIVE, DONE, FAILED }

/** Per-agent status dot. */
enum class AgentStatus { ASKING, REPLIED, NEEDS_TAP, NEEDS_APPROVAL, DIDNT_ANSWER, CHECK_MESSAGES, SKIPPED }

data class StripAgent(val agentId: String, val name: String, val status: AgentStatus, val durationMs: Long?)

/** A label piece; pieces with [agentId] render bold in the agent's hue. */
data class LabelPart(val text: String, val agentId: String? = null)

data class StripLine(
  val askId: String,
  val agentId: String,
  val name: String,
  /** Exactly what was sent. */
  val request: String,
  /** One or two lines of the raw reply, or null. */
  val replyPreview: String?,
  val durationMs: Long?,
  val status: AgentStatus,
  /** The transport wasn't sure the reply belongs to this ask (user typed in that chat). */
  val uncertain: Boolean,
  val late: Boolean,
)

/** design-system.md §7.3. */
data class DelegationStrip(
  val phase: StripPhase,
  val agents: List<StripAgent>,
  val label: List<LabelPart>,
  /** Live: "0:09" (mono, ticking). Done: "18s". */
  val timer: String,
  val elapsedMs: Long,
  /** Live and over 45 s: timer turns warning, tap shows [slowHint] ("Still waiting on kDot") + "Stop waiting". */
  val slow: Boolean,
  val slowHint: String?,
  /** Done with gaps: "kDot didn't answer". */
  val notes: List<String>,
  /** Expanded card lines. */
  val lines: List<StripLine>,
  /** TalkBack text for the whole strip. */
  val accessibility: String,
) {
  val labelText: String get() = label.joinToString("") { it.text }
}

data class FoldedReplies(val label: String, val replies: List<StripLine>)

enum class NeedsUserKind { TAP_TO_SEND, APPROVE, LOOK_IN_MESSAGES, ANSWER_QUESTION }

data class NeedsUserLine(val kind: NeedsUserKind, val text: String, val agentId: String?, val askId: String?, val callId: String?, val detail: String? = null)

object Views {
  fun build(s: RunState, roster: Roster, now: Long, config: JunoConfig): RunView {
    val name = roster::nameOf
    val strip = strip(s, roster, now, config)
    val folded = if (s.mode == RunMode.COORDINATE && s.answer != null && s.asks.any { it.replies.isNotEmpty() }) {
      val lines = strip?.lines.orEmpty().filter { it.replyPreview != null }
      FoldedReplies("From " + names(lines.map { it.name }.distinct()), lines)
    } else null
    val needs = buildList {
      s.asks.forEach { a ->
        when (a.status) {
          AskStatus.NEEDS_TAP -> add(NeedsUserLine(NeedsUserKind.TAP_TO_SEND, "Tap to send to ${name(a.agentId)}", a.agentId, a.id, null, a.draftText ?: a.text))
          AskStatus.AWAITING_APPROVAL -> add(NeedsUserLine(NeedsUserKind.APPROVE, "Okay to have ${name(a.agentId)} do this?", a.agentId, a.id, null, a.text))
          AskStatus.LIKELY_MISSED -> add(NeedsUserLine(NeedsUserKind.LOOK_IN_MESSAGES, "${name(a.agentId)} may have answered in Messages", a.agentId, a.id, null))
          else -> {}
        }
      }
      s.pending.filter { it.kind == PendingKind.ASK_USER && it.output == null }.forEach {
        add(NeedsUserLine(NeedsUserKind.ANSWER_QUESTION, it.question ?: "", null, null, it.callId))
      }
    }
    val map = s.agreement ?: if (s.mode == RunMode.ALL) collecting(s, roster) else null
    return RunView(s.runId, s.mode, s.phase, strip, s.answer, folded, needs, s.followUps, map, s.error, s.errorKind)
  }

  /** @all participants: who answered (and how fast), who's still out, who didn't answer. */
  fun participants(s: RunState, roster: Roster): List<Participant> = s.asks.filter { it.purpose == AskPurpose.BLIND }.map { a ->
    val st = when {
      a.replies.isNotEmpty() -> ParticipantState.ANSWERED
      a.status == AskStatus.NEEDS_TAP -> ParticipantState.NEEDS_TAP
      a.status.settled -> ParticipantState.DIDNT_ANSWER
      else -> ParticipantState.WAITING
    }
    Participant(a.agentId, roster.nameOf(a.agentId), st, a.replies.firstOrNull()?.let { r -> a.sentAt?.let { (r.at - it).coerceAtLeast(0) } })
  }

  /** The partial map shown while @all answers are still arriving: participants and raw answers, no claims yet. */
  fun collecting(s: RunState, roster: Roster): AgreementMap = AgreementMap(
    s.userText, MapStatus.COLLECTING, participants(s, roster),
    raw = s.asks.filter { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }.map { RawAnswer(it.agentId, roster.nameOf(it.agentId), it.replyText, it.uncertain, it.late) },
  )

  fun strip(s: RunState, roster: Roster, now: Long, config: JunoConfig): DelegationStrip? {
    val asks = s.asks.filter { it.agentId != roster.main.id }
    if (asks.isEmpty()) return null
    val byAgent = asks.groupBy { it.agentId }
    val runDone = s.phase.terminal
    val start = asks.mapNotNull { it.sentAt ?: it.createdAt }.minOrNull() ?: s.createdAt
    val end = if (runDone) (s.answeredAt ?: asks.mapNotNull { it.settledAt ?: (if (it.replies.isNotEmpty()) it.lastReplyAt else null) }.maxOrNull() ?: now) else now
    val elapsed = (end - start).coerceAtLeast(0)

    val agents = byAgent.map { (id, list) ->
      val last = list.last()
      StripAgent(id, roster.nameOf(id), statusOf(last, runDone), duration(last, now))
    }
    val lines = asks.map { a ->
      StripLine(a.id, a.agentId, roster.nameOf(a.agentId), a.text, a.replyText.takeIf { it.isNotBlank() }?.let(::preview),
        duration(a, now), statusOf(a, runDone), a.uncertain, a.late)
    }
    val live = !runDone && agents.any { it.status in setOf(AgentStatus.ASKING, AgentStatus.NEEDS_TAP, AgentStatus.NEEDS_APPROVAL) }
    val replied = agents.filter { it.status == AgentStatus.REPLIED }
    val failed = agents.filter { it.status == AgentStatus.DIDNT_ANSWER || it.status == AgentStatus.CHECK_MESSAGES }
    val waiting = agents.filter { it.status in setOf(AgentStatus.ASKING, AgentStatus.NEEDS_TAP, AgentStatus.NEEDS_APPROVAL) }
    val all = agents.filter { it.status != AgentStatus.SKIPPED }.ifEmpty { agents }

    val phase = when {
      live -> StripPhase.LIVE
      replied.isEmpty() && failed.isNotEmpty() -> StripPhase.FAILED
      else -> StripPhase.DONE
    }
    val label = when (phase) {
      StripPhase.LIVE -> if (replied.isEmpty()) listOf(LabelPart("Asking ")) + nameParts(waiting)
        else listOf(LabelPart("Asked ")) + nameParts(replied) + LabelPart(" · waiting on ") + nameParts(waiting)
      StripPhase.DONE -> listOf(LabelPart("Asked ")) + nameParts(all)
      StripPhase.FAILED -> nameParts(failed) + LabelPart(" didn't answer")
    }
    val slow = phase == StripPhase.LIVE && elapsed > config.slowAfterMs
    val notes = if (phase == StripPhase.DONE) failed.map { if (it.status == AgentStatus.CHECK_MESSAGES) "${it.name} may have answered in Messages" else "${it.name} didn't answer" } else emptyList()
    val timer = if (phase == StripPhase.LIVE) clock(elapsed) else short(elapsed)
    val a11y = when (phase) {
      StripPhase.LIVE -> "${roster.main.name} is ${label.joinToString("") { it.text }.replaceFirstChar { it.lowercase() }}, ${elapsed / 1000} seconds so far."
      StripPhase.DONE -> "${roster.main.name} ${label.joinToString("") { it.text }.replaceFirstChar { it.lowercase() }}, done in ${spoken(elapsed)}. Double-tap to show details."
      StripPhase.FAILED -> label.joinToString("") { it.text } + ". Double-tap to retry."
    }
    return DelegationStrip(phase, agents, label, timer, elapsed, slow, if (slow) "Still waiting on " + names(waiting.map { it.name }) else null, notes, lines, a11y)
  }

  private fun statusOf(a: AskState, runDone: Boolean): AgentStatus = when (a.status) {
    AskStatus.ANSWERED, AskStatus.REPLYING -> AgentStatus.REPLIED
    AskStatus.NEEDS_TAP -> AgentStatus.NEEDS_TAP
    AskStatus.AWAITING_APPROVAL -> AgentStatus.NEEDS_APPROVAL
    AskStatus.TIMED_OUT, AskStatus.FAILED -> AgentStatus.DIDNT_ANSWER
    AskStatus.LIKELY_MISSED -> AgentStatus.CHECK_MESSAGES
    AskStatus.DECLINED, AskStatus.CANCELLED -> AgentStatus.SKIPPED
    // After the answer is posted an unanswered ask reads as "didn't answer" (a late reply becomes a follow-up).
    AskStatus.QUEUED, AskStatus.SENDING, AskStatus.PENDING, AskStatus.DRAFTED -> if (runDone) AgentStatus.DIDNT_ANSWER else AgentStatus.ASKING
  }

  private fun duration(a: AskState, now: Long): Long? {
    val from = a.sentAt ?: return null
    return when {
      a.replies.isNotEmpty() -> a.replies.first().at - from
      a.status.settled -> (a.settledAt ?: now) - from
      else -> now - from
    }.coerceAtLeast(0)
  }

  private fun nameParts(agents: List<StripAgent>): List<LabelPart> {
    val ns = agents.map { LabelPart(it.name, it.agentId) }
    return when {
      ns.size <= 1 -> ns
      ns.size == 2 -> listOf(ns[0], LabelPart(" and "), ns[1])
      else -> listOf(ns[0], LabelPart(", "), ns[1], LabelPart(" and ${ns.size - 2} more"))
    }
  }

  fun names(ns: List<String>): String = when {
    ns.isEmpty() -> ""
    ns.size == 1 -> ns[0]
    ns.size == 2 -> "${ns[0]} and ${ns[1]}"
    else -> "${ns[0]}, ${ns[1]} and ${ns.size - 2} more"
  }

  fun clock(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
  fun short(ms: Long): String { val s = ms / 1000; return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s" }
  private fun spoken(ms: Long): String { val s = ms / 1000; return if (s < 60) "$s seconds" else "${s / 60} minutes ${s % 60} seconds" }

  fun preview(text: String): String {
    val lines = text.trim().lines().filter { it.isNotBlank() }.take(2).joinToString("\n")
    return if (lines.length > 160) lines.take(157).trimEnd() + "…" else lines
  }
}
