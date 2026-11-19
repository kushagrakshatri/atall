package app.atall.coordinator

import kotlinx.serialization.Serializable

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
  /** A talk ("Talk it over"): its live rows and controls, then "Where they landed" and the rounds. */
  val talk: TalkView? = null,
  /** One avatar row under Juno's answer instead of ask cards and waiting lines (editor and delegating runs). */
  val presence: PresenceView? = null,
  /** Editor: the one caption under the answer ("First take · others adding", "Updated · Hark knew …"). */
  val caption: String? = null,
  /** Editor: a real split as one deciding question or an if/then rule, with its choices. */
  val yourCall: YourCall? = null,
  /** Editor: Kush acted on the answer (only a late fix or change still edits it, in place). */
  val frozen: Boolean = false,
  /** Editor: the answer card (lines with credits, the disagreement line, Your call, actions, the late-change highlight). */
  val card: AnswerCard? = null,
)

// ------------------------------------------------------------------ the avatar row (fast-coord ux.md §7)

@Serializable
enum class PresenceState {
  /** Asked and working: a ring. */
  WORKING,
  /** Asked in the background (slow by measured reply time): a moon. Never holds anything up. */
  LATER,
  /** Its answer is folded in: a check. */
  IN,
  /** Its answer only agreed: a check, no edit. */
  AGREED,
  /** Needs Kush (tap to send, an OK). */
  NEEDS_YOU,
  /** Didn't answer (shown faded while live; dropped once the run settles). */
  NO_ANSWER,
}

/** One avatar. [text] is the agent's answer without its tail (the detail sheet's only per-agent text). */
@Serializable
data class PresenceAgent(val agentId: String, val name: String, val state: PresenceState, val text: String? = null, val note: String? = null)

@Serializable
data class PresenceView(
  val runId: String,
  val live: Boolean,
  val agents: List<PresenceAgent>,
  /** The comparison, one tap down (its disagreements keep "Talk it over"). */
  val map: AgreementMap? = null,
  val accessibility: String = "",
)

// ------------------------------------------------------------------ "Talk it over" (the UI never says "room")

@Serializable
enum class TalkRowState { WAITING, STILL_WAITING, REPLIED, NEEDS_TAP, NEEDS_OK, LEFT_OUT, DIDNT_REPLY }

/** One agent in the live card: "Waiting on kDot", "Still waiting on kDot", a reply preview, "kDot needs a tap". */
@Serializable
data class TalkRow(val agentId: String, val name: String, val state: TalkRowState, val line: String)

@Serializable
data class TalkLine(val seq: Int, val kind: EntryKind, val agentId: String, val name: String, val text: String, val late: Boolean = false, val passed: Boolean = false)

@Serializable
data class TalkRoundView(val n: Int, val caption: String, val lines: List<TalkLine>, val time: String?)

/** A row of "Where they landed": the struck-through first position only when it changed. */
@Serializable
data class LandedRow(val agentId: String, val name: String, val first: String?, val final: String, val tag: String?, val reason: String)

@Serializable
data class TalkAgent(val agentId: String, val name: String)

/** "Ask Hark about this?": an agent @mentioned another; nothing is sent until the user taps (and then sends). */
@Serializable
data class AskSuggestion(val agentId: String, val name: String, val fromName: String, val quote: String)

/** "What Juno sent": the exact text each agent got. */
@Serializable
data class SentRelay(val round: Int, val agentId: String, val name: String, val text: String)

@Serializable
data class TalkView(
  val runId: String,
  val live: Boolean,
  /** "Hark and kDot are talking it over" / "Hark and kDot talked it over" / "… disagreed, so they talked it over". */
  val label: String,
  /** Live "1:41" (mono, ticking); done "1m 48s". */
  val timer: String,
  /** The open round has waited past the soft deadline: rows turn warning, Wrap up now and Leave X out show. */
  val slow: Boolean,
  /** The live card's caption: "First answers · on their own" / "Replies to each other" / "… · last round". */
  val caption: String?,
  val rows: List<TalkRow>,
  val rounds: List<TalkRoundView>,
  /** Shown only when someone changed position or they still disagree. */
  val landed: List<LandedRow>,
  val canStop: Boolean,
  val canWrapUp: Boolean,
  val leaveOut: List<TalkAgent>,
  val suggestions: List<AskSuggestion>,
  /** Up to two things the user can tap to say next; under "Your call" when [yourCall]. */
  val choices: List<String>,
  val yourCall: Boolean,
  /** "wrapped up early" · "Stopped" · "Stopped after first answers". */
  val endNote: String?,
  val sent: List<SentRelay>,
  val accessibility: String,
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
  /** Provenance of the reply: a verified connector answer or a text; null with no reply yet. */
  val via: Via? = null,
  /** Structured claims that came with it (connector). */
  val claims: Int = 0,
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
          AskStatus.AWAITING_APPROVAL -> add(NeedsUserLine(NeedsUserKind.APPROVE, approvalLine(a, name), a.agentId, a.id, null, a.text))
          AskStatus.LIKELY_MISSED -> add(NeedsUserLine(NeedsUserKind.LOOK_IN_MESSAGES, "${name(a.agentId)} may have answered in Messages", a.agentId, a.id, null))
          else -> {}
        }
      }
      s.pending.filter { it.kind == PendingKind.ASK_USER && it.output == null }.forEach {
        add(NeedsUserLine(NeedsUserKind.ANSWER_QUESTION, it.question ?: "", null, null, it.callId))
      }
    }
    val map = s.agreement ?: if (s.mode == RunMode.ALL) collecting(s, roster) else null
    val presence = presence(s, roster, now)
    return RunView(s.runId, s.mode, s.phase, strip, s.answer, folded, needs, s.followUps, map, s.error, s.errorKind, talk(s, roster, now, config),
      presence = presence, caption = s.editor?.caption, yourCall = s.editor?.yourCall, frozen = s.editor?.frozen == true,
      card = if (s.room == null && s.mode != RunMode.ROOM) Cards.build(s, roster, presence) else null)
  }

  /**
   * The avatar row for an editor run or a delegating run (never a talk). Paused agents are never asked, so never here.
   * One avatar per agent (its newest ask): check = in, ring = working, moon = background, faded = no answer (live only).
   */
  fun presence(s: RunState, roster: Roster, now: Long): PresenceView? {
    if (s.room != null || s.mode == RunMode.ROOM) return null
    if (s.editor == null && s.mode != RunMode.COORDINATE) return null
    val asks = s.asks.filter { it.agentId != roster.main.id && roster.byId(it.agentId)?.paused != true }
    if (asks.isEmpty()) return null
    val live = !s.phase.terminal
    val e = s.editor
    val agents = asks.groupBy { it.agentId }.mapNotNull { (id, list) ->
      val withReply = list.lastOrNull { it.replies.isNotEmpty() }
      val last = list.last()
      val open = list.any { !it.status.settled }
      val state = when {
        list.any { it.status == AskStatus.NEEDS_TAP || it.status == AskStatus.AWAITING_APPROVAL } -> PresenceState.NEEDS_YOU
        withReply != null && e != null && id in e.agreedOnly && !open -> PresenceState.AGREED
        withReply != null && !open -> PresenceState.IN
        open && (last.background || (s.phase.terminal && list.all { it.background || it.purpose == AskPurpose.BLIND })) -> PresenceState.LATER
        open -> if (withReply != null) PresenceState.IN else PresenceState.WORKING
        withReply != null -> PresenceState.IN
        else -> PresenceState.NO_ANSWER
      }
      if (state == PresenceState.NO_ANSWER && !live) return@mapNotNull null
      val note = when {
        withReply == null -> null
        e?.reviews?.any { it.agentId == id } == true -> "Review"
        withReply.purpose == AskPurpose.REPAIR -> "After a cross-check"
        withReply.purpose == AskPurpose.MENTION -> withReply.mentionFrom?.let { "Answering ${roster.nameOf(it)}" }
        else -> withReply.sentAt?.let { short(withReply.replies.first().at - it) }
      }
      PresenceAgent(id, roster.nameOf(id), state, withReply?.let { AskTail.strip(it.replyText) }, note)
    }
    if (agents.isEmpty()) return null
    val a11y = agents.joinToString(". ") { a -> a.name + ": " + when (a.state) {
      PresenceState.WORKING -> "working"; PresenceState.LATER -> "later"; PresenceState.IN, PresenceState.AGREED -> "in"
      PresenceState.NEEDS_YOU -> "needs you"; PresenceState.NO_ANSWER -> "no answer"
    } } + ". Double-tap for each answer."
    return PresenceView(s.runId, live, agents, if (s.mode == RunMode.ALL) (s.agreement ?: collecting(s, roster)) else null, a11y)
  }

  /** The talk's view, or null when the run isn't one. Copy follows ux.md §11 (no "room", "round 2 of 2", "debate"). */
  fun talk(s: RunState, roster: Roster, now: Long, config: JunoConfig): TalkView? {
    val r = s.room ?: return null
    val name = roster::nameOf
    val people = r.participants.filter { it != roster.main.id }
    val live = !s.phase.terminal
    val who = names(people.map(name))
    val label = when {
      live -> "$who ${if (people.size == 1) "is" else "are"} talking it over"
      r.trigger == TalkTrigger.CLASH -> "$who disagreed, so they talked it over"
      else -> "$who talked it over"
    }
    val end = if (live) now else (s.answeredAt ?: s.room.rounds.mapNotNull { it.closedAt }.maxOrNull() ?: now)
    val elapsed = (end - r.startedAt).coerceAtLeast(0)
    val round = r.currentRound
    val roundAsks = round?.askIds?.mapNotNull { s.ask(it) }.orEmpty()
    val openFor = round?.let { now - it.openedAt } ?: 0
    val waiting = roundAsks.filter { !it.status.settled && it.status != AskStatus.AWAITING_APPROVAL && it.status != AskStatus.NEEDS_TAP }
    val slow = live && r.stage == RoomStage.ROUND_OPEN && waiting.isNotEmpty() && openFor >= config.talk.slowAfterMs
    val rows = if (!live || r.stage != RoomStage.ROUND_OPEN) emptyList() else roundAsks.map { a ->
      val n = name(a.agentId)
      when {
        a.agentId in r.leftOut -> TalkRow(a.agentId, n, TalkRowState.LEFT_OUT, "Left out")
        a.replies.isNotEmpty() -> TalkRow(a.agentId, n, TalkRowState.REPLIED, preview(a.replyText))
        a.status == AskStatus.NEEDS_TAP -> TalkRow(a.agentId, n, TalkRowState.NEEDS_TAP, "$n needs a tap")
        a.status == AskStatus.AWAITING_APPROVAL -> TalkRow(a.agentId, n, TalkRowState.NEEDS_OK, "Waiting for your OK")
        a.status.settled -> TalkRow(a.agentId, n, TalkRowState.DIDNT_REPLY, "$n didn't reply")
        slow -> TalkRow(a.agentId, n, TalkRowState.STILL_WAITING, "Still waiting on $n")
        else -> TalkRow(a.agentId, n, TalkRowState.WAITING, "Waiting on $n")
      }
    }
    val caption = when {
      round == null || !live -> null
      round.n == 0 -> "First answers · on their own"
      round.n >= config.talk.maxRounds -> "Replies to each other · last round"
      else -> "Replies to each other"
    }
    val rounds = r.entries.groupBy { if (it.round == 0) 0 else 1 }.toSortedMap().map { (k, es) ->
      val rr = r.rounds.filter { if (k == 0) it.n == 0 else it.n >= 1 }
      val t = rr.mapNotNull { x -> x.closedAt?.let { it - x.openedAt } }.sum().takeIf { it > 0 }?.let(::short)
      TalkRoundView(k, if (k == 0) "First answers · on their own" else "Replies to each other",
        es.map { e ->
          val n = when (e.kind) { EntryKind.USER -> "You"; else -> name(e.authorId) }
          val left = e.kind == EntryKind.AGENT && e.authorId in r.leftOut && e.round == 0
          TalkLine(e.seq, e.kind, e.authorId, n, e.text, e.late, e.passed).let { if (left) it.copy(late = it.late) else it }
        }, t)
    }
    val stillSplit = !live && s.agreement?.disputed.orEmpty().isNotEmpty()
    val landed = if (live || (r.landed.none { it.changed } && !stillSplit)) emptyList() else r.landed.map { l ->
      LandedRow(l.agentId, name(l.agentId), l.first.takeIf { l.changed && it.isNotBlank() && it != l.final }, l.final,
        when { l.wentAlongWith != null -> "Went along with ${name(l.wentAlongWith)}"; l.changed -> "Changed its mind"; else -> null }, l.reason)
    }
    val leaveOut = if (slow) waiting.filter { it.agentId !in r.leftOut }.map { TalkAgent(it.agentId, name(it.agentId)) }.distinctBy { it.agentId } else emptyList()
    val spokeAfter = r.entries.any { it.kind == EntryKind.AGENT && it.round >= 1 }
    val endNote = when {
      s.phase == Phase.CANCELLED || r.endReason == EndReason.USER_STOP -> if (spokeAfter) "Stopped" else "Stopped after first answers"
      r.endReason == EndReason.WRAPPED_EARLY && !live -> "wrapped up early"
      else -> null
    }
    val suggestions = if (live) emptyList() else r.suggestions.map { AskSuggestion(it.toAgent, name(it.toAgent), name(it.fromAgent), it.quote) }
    val sent = s.asks.filter { it.purpose == AskPurpose.ROOM && it.status != AskStatus.CANCELLED }.map { SentRelay(it.roomRound ?: 0, it.agentId, name(it.agentId), it.text) }
    val timer = if (live) clock(elapsed) else short(elapsed)
    val a11y = if (live) "$label, ${spoken(elapsed)}." + (if (slow) " Still waiting on ${names(waiting.map { name(it.agentId) })}." else "")
      else "${roster.main.name}: $label in ${spoken(elapsed)}." + landed.filter { it.first != null }.joinToString("") { " ${it.name} changed its mind, from ${it.first} to ${it.final}." } + " Double-tap to show the talk."
    return TalkView(s.runId, live, label, timer, slow, caption, rows, rounds, landed, live, slow, leaveOut, suggestions,
      if (live) emptyList() else r.choices, r.yourCall, endNote, sent, a11y)
  }

  /** The approval line: a talk relay holding a private agent's answer, a relay the send gate held, or Juno's ask. */
  fun approvalLine(a: AskState, name: (String) -> String): String = when {
    a.purpose == AskPurpose.ROOM && a.shareFrom.isNotEmpty() -> "Share ${names(a.shareFrom.map(name))}'s answer with ${name(a.agentId)}?"
    a.purpose == AskPurpose.ROOM -> "Okay to pass this on to ${name(a.agentId)}?"
    a.purpose == AskPurpose.REPAIR && a.shareFrom.isNotEmpty() -> "Share a note from ${names(a.shareFrom.map(name))} with ${name(a.agentId)}?"
    else -> "Okay to have ${name(a.agentId)} do this?"
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
    raw = s.asks.filter { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }.map { RawAnswer(it.agentId, roster.nameOf(it.agentId), AskTail.strip(it.replyText), it.uncertain, it.late) },
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
      StripLine(a.id, a.agentId, roster.nameOf(a.agentId), a.text, AskTail.strip(a.replyText).takeIf { it.isNotBlank() }?.let(::preview),
        duration(a, now), statusOf(a, runDone), a.uncertain, a.late,
        via = if (a.replies.isEmpty()) null else if (a.verified) Via.CONNECTOR else Via.TEXT, claims = a.claims.size)
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
    if (s.mode == RunMode.ROOM && s.room != null) {
      val t = talk(s, roster, now, config)!!
      val people = s.room.participants.filter { it != roster.main.id }
      val parts = nameParts(people.map { StripAgent(it, roster.nameOf(it), AgentStatus.ASKING, null) })
      val tail = when {
        !runDone -> " ${if (people.size == 1) "is" else "are"} talking it over"
        s.room.trigger == TalkTrigger.CLASH -> " disagreed, so they talked it over"
        else -> " talked it over"
      }
      val tPhase = if (!runDone) StripPhase.LIVE else StripPhase.DONE
      val tNotes = listOfNotNull(t.endNote) + if (runDone) failed.map { "${it.name} didn't answer" } else emptyList()
      return DelegationStrip(tPhase, agents, parts + LabelPart(tail), t.timer, (if (runDone) (s.answeredAt ?: now) else now) - s.room.startedAt, t.slow,
        if (t.slow) "Still waiting on " + names(t.rows.filter { it.state == TalkRowState.STILL_WAITING }.map { it.name }) else null, tNotes, lines, t.accessibility)
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
  fun spoken(ms: Long): String { val s = ms / 1000; return if (s < 60) "$s seconds" else "${s / 60} minutes ${s % 60} seconds" }

  fun preview(text: String): String {
    val lines = text.trim().lines().filter { it.isNotBlank() }.take(2).joinToString("\n")
    return if (lines.length > 160) lines.take(157).trimEnd() + "…" else lines
  }
}
