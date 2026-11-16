package app.atall.coordinator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// "Juno as editor" (new-product/collab/plan.md): blind answers with a self-filled tail, a claims table with sources,
// one cross-check, a Your call card for a real split, slow agents as reviewers, and a surplus record per run.

// ------------------------------------------------------------------ claims table

@Serializable
enum class RowKind {
  /** A claim from an answer (extracted by the claims call). */
  CLAIM,
  /** A fact about the user an agent gave from its own memory (its tail). */
  FACT,
}

/**
 * One row of the claims table. [sources] every agent that stated it; [blindSources] those that stated it on their own,
 * before seeing any other answer. Only blind agreement raises confidence ([confirmed]).
 */
@Serializable
data class ClaimRow(
  val id: String,
  val kind: RowKind,
  val text: String,
  val sources: List<String>,
  val blindSources: List<String>,
  /** A side of a disagreement (the dispute's id). */
  val disputeId: String? = null,
) {
  val confirmed: Boolean get() = blindSources.distinct().size >= 2
  val single: Boolean get() = sources.distinct().size == 1
}

@Serializable
data class ClaimsTable(val rows: List<ClaimRow> = emptyList(), val disputes: List<Dispute> = emptyList()) {
  /** What only one agent (not Juno) knew, memory facts first. Sides of a disagreement are not "only one knew". */
  fun onlyOne(main: String): List<ClaimRow> = rows.filter { it.single && it.sources[0] != main && it.disputeId == null }
    .sortedBy { if (it.kind == RowKind.FACT) 0 else 1 }

  /** Rows two or more agents gave (agreement, confirmed or not). */
  fun overlap(): List<ClaimRow> = rows.filter { it.sources.distinct().size >= 2 && it.disputeId == null }

  fun confirmed(): List<ClaimRow> = overlap().filter { it.confirmed }

  /** Every agent that is a source of anything. */
  fun agents(): Set<String> = rows.flatMap { it.sources }.toSet()

  /** Whether [agentId] adds nothing beyond what others already said: no single-source row, no side of a dispute. */
  fun onlyAgrees(agentId: String): Boolean {
    val mine = rows.filter { agentId in it.sources }
    return mine.isNotEmpty() && mine.all { it.sources.distinct().size >= 2 && it.disputeId == null } &&
      disputes.none { d -> d.sides.any { agentId in it.agentIds } }
  }

  /** The table as plain lines for Juno's model (names, never labels; the merge isn't blind). */
  fun lines(names: (String) -> String, main: String): String = buildString {
    fun who(ids: List<String>) = Views.names(ids.distinct().map { if (it == main) "${names(it)} (you)" else names(it) })
    val only = onlyOne(main)
    if (only.isNotEmpty()) {
      append("Only one agent knew:\n")
      only.forEach { r -> append("- Only ").append(names(r.sources[0])).append(if (r.kind == RowKind.FACT) " (from its memory of the user): " else ": ")
        .append(r.text).append(if (r.blindSources.isEmpty()) " [said after seeing others]" else "").append("\n") }
    }
    if (disputes.isNotEmpty()) {
      append("They disagree:\n")
      disputes.forEach { d -> append("- ").append(d.topic).append(": ").append(d.sides.joinToString(" vs ") { s -> "${s.statement} (${who(s.agentIds)})" }).append("\n") }
    }
    val ov = overlap()
    if (ov.isNotEmpty()) {
      append("Overlap (one line at most in the answer):\n")
      ov.forEach { r ->
        val late = r.sources.distinct() - r.blindSources.toSet()
        append("- ").append(r.text).append(" — ").append(who(r.sources))
        append(if (r.confirmed) ", each on its own: confirmed" else ", not independent")
        if (late.isNotEmpty() && r.confirmed) append(" (").append(who(late)).append(" only after seeing others)")
        append("\n")
      }
    }
    rows.filter { it.single && it.sources[0] == main && it.disputeId == null }.takeIf { it.isNotEmpty() }?.let { mine ->
      append("Only in your own first take:\n"); mine.forEach { append("- ").append(it.text).append("\n") }
    }
  }.trim()
}

/** One agent's tail, and whether it was written blind (an answer to the question itself, not a repair). */
data class SourcedTail(val agentId: String, val tail: Tail, val blind: Boolean)

object ClaimsTables {
  /** Two facts are the same when their words mostly overlap. */
  const val FACT_MATCH = 0.5

  /**
   * The table from the comparison [map] (built from blind answers only) and every agent's tail. Facts from several
   * agents merge into one row; a fact echoed in a repair reply adds a source but not a blind one.
   */
  fun build(map: AgreementMap?, tails: List<SourcedTail>): ClaimsTable {
    val rows = mutableListOf<ClaimRow>()
    map?.agreed?.forEach { rows += ClaimRow("c-${it.id}", RowKind.CLAIM, it.statement, it.agentIds, it.agentIds) }
    map?.unique?.forEach { rows += ClaimRow("c-${it.id}", RowKind.CLAIM, it.statement, it.agentIds, it.agentIds) }
    map?.disputed?.forEach { d -> d.sides.forEachIndexed { i, s -> rows += ClaimRow("c-${d.id}-$i", RowKind.CLAIM, s.statement, s.agentIds, s.agentIds, d.id) } }
    var n = 0
    for (t in tails) for (f in t.tail.facts) {
      val i = rows.indexOfFirst { it.kind == RowKind.FACT && Agreement.similarity(it.text, f) >= FACT_MATCH }
      if (i < 0) rows += ClaimRow("f${n++}", RowKind.FACT, f, listOf(t.agentId), if (t.blind) listOf(t.agentId) else emptyList())
      else {
        val r = rows[i]
        rows[i] = r.copy(sources = (r.sources + t.agentId).distinct(), blindSources = if (t.blind) (r.blindSources + t.agentId).distinct() else r.blindSources)
      }
    }
    return ClaimsTable(rows, map?.disputed.orEmpty())
  }
}

// ------------------------------------------------------------------ the answer without the model

object Combine {
  /**
   * The editor's answer when the model fails: what only one agent knew first, then the real disagreement, then the
   * overlap in one line. Never longer than four short lines.
   */
  fun fallback(table: ClaimsTable, names: (String) -> String, main: String, firstTake: String?): String {
    val lines = mutableListOf<String>()
    table.onlyOne(main).groupBy { it.sources[0] }.entries.take(2).forEach { (agent, rows) ->
      lines += "Only ${names(agent)} knew: " + rows.take(2).joinToString("; ") { it.text.trim().trimEnd('.') } + "."
    }
    table.disputes.firstOrNull()?.let { d ->
      val who = Views.names(d.sides.flatMap { it.agentIds }.distinct().map(names))
      lines += "$who split on ${d.topic}: " + d.sides.take(2).joinToString(" vs ") { it.value } + "."
    }
    val conf = table.confirmed()
    if (conf.isNotEmpty()) lines += "They agree: ${conf[0].text.trim().trimEnd('.')}" + (if (conf.size > 1) ", and ${conf.size - 1} more." else ".")
    if (lines.isEmpty()) return firstTake?.trim().orEmpty()
    return lines.joinToString("\n")
  }

  /** "Updated · Hark knew you're busy Saturday". Blank model captions become "{Agent} added to this". */
  fun caption(agentNames: List<String>, modelCaption: String?): String {
    val c = modelCaption?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let { if (it.length > 60) it.take(57).trimEnd() + "…" else it }
    return "Updated · " + (c ?: "${Views.names(agentNames)} added to this")
  }

  const val FIRST_TAKE = "First take · others adding"
}

// ------------------------------------------------------------------ folds (one model call per arrival that adds something)

@Serializable
enum class FoldKind { FIRST, NONE, ADD, FIX, CHANGE }

@Serializable
data class AnswerVersion(val n: Int, val text: String, val caption: String?, val at: Long, val kind: FoldKind, val agents: List<String> = emptyList())

data class FoldResult(val kind: FoldKind, val answer: String, val caption: String?)

object Fold {
  val schema: JsonObject = Schema.obj(
    "kind" to Schema.string("What the new answers change", listOf("none", "add", "fix", "change")),
    "answer" to Schema.string("The full answer the user reads now"),
    "caption" to Schema.string("At most 6 words on what changed, starting with the agent's name; empty when kind is none"),
  )

  fun parse(raw: String, current: String?): FoldResult? {
    val o = parseJsonObject(raw)
    if (o == null) {
      // Plain text instead of JSON: take it as the new answer.
      val t = raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") } ?: return null
      return FoldResult(FoldKind.ADD, t, null)
    }
    val kind = when (o.str("kind")?.trim()?.lowercase()) { "none" -> FoldKind.NONE; "fix" -> FoldKind.FIX; "change" -> FoldKind.CHANGE; else -> FoldKind.ADD }
    val answer = o.str("answer")?.trim().orEmpty().ifEmpty { current.orEmpty() }
    if (answer.isEmpty()) return null
    return FoldResult(if (kind != FoldKind.NONE && answer == current) FoldKind.NONE else kind, answer, o.str("caption")?.trim()?.ifEmpty { null })
  }
}

// ------------------------------------------------------------------ cross-check (P1)

@Serializable
data class Conflict(val answerAgent: String, val factAgent: String, val fact: String, val problem: String)

object CrossCheck {
  val schema: JsonObject = Schema.obj(
    "conflicts" to Schema.array("Real conflicts only; empty when none", Schema.obj(
      "answer" to Schema.string("The answer's label, e.g. B"),
      "fact" to Schema.string("The fact's label, e.g. F2"),
      "problem" to Schema.string("What the answer gets wrong for that fact, under 15 words"),
    )),
  )

  data class Input(val text: String, val answerLabels: Map<String, String>, val factLabels: Map<String, Pair<String, String>>)

  /**
   * Answers (labelled A, B…) and every memory fact (F1, F2… with its source's answer label). Null when there is
   * nothing to check: fewer than two agents, or no fact from an agent other than an answer's author.
   */
  fun input(question: String, answers: List<Pair<String, String>>, facts: List<Pair<String, String>>): Input? {
    if (answers.size < 2 || facts.isEmpty()) return null
    val aLabels = answers.mapIndexed { i, (agent, _) -> Agreement.label(i) to agent }.toMap()
    val byAgent = aLabels.entries.associate { (l, a) -> a to l }
    if (facts.none { (agent, _) -> answers.any { it.first != agent } }) return null
    val fLabels = facts.mapIndexed { i, f -> "F${i + 1}" to f }.toMap()
    val text = buildString {
      append("Question: ").append(question).append("\n")
      answers.forEachIndexed { i, (_, body) -> append("\nAnswer ").append(Agreement.label(i)).append(":\n").append(body.trim()).append("\n") }
      append("\nFacts about the user from the assistants' own memory:\n")
      fLabels.forEach { (l, f) -> append(l).append(" (from ").append(byAgent[f.first]?.let { "the author of answer $it" } ?: "another assistant").append("): ").append(f.second).append("\n") }
    }
    return Input(text, aLabels, fLabels)
  }

  /** The first real conflict: an answer against a fact that came from a different agent. */
  fun parse(raw: String, input: Input): Conflict? {
    val o = parseJsonObject(raw) ?: return null
    return (o["conflicts"] as? JsonArray).orEmpty().firstNotNullOfOrNull { el ->
      val c = el as? JsonObject ?: return@firstNotNullOfOrNull null
      val a = input.answerLabels[c.str("answer")?.trim()?.uppercase()] ?: return@firstNotNullOfOrNull null
      val f = input.factLabels[c.str("fact")?.trim()?.uppercase()] ?: return@firstNotNullOfOrNull null
      if (f.first == a) null else Conflict(a, f.first, f.second, c.str("problem")?.trim().orEmpty())
    }
  }
}

/** Who gets the one repair ask: chosen by measured latency, never by a list of who's good at what. */
object Repair {
  /**
   * [conflictAgent] when its measured reply time is fast enough (it keeps its own idea and fixes it); otherwise the
   * fastest measured candidate under [fastMs]; otherwise nobody (Juno adjusts the answer itself and says so).
   * [medianMs]: each candidate's measured median reply time (history, or this run's own reply when there is none yet).
   */
  fun pick(conflictAgent: String, candidates: List<String>, medianMs: Map<String, Long?>, fastMs: Long): String? {
    fun fast(a: String) = medianMs[a]?.let { it <= fastMs } == true
    if (conflictAgent in candidates && fast(conflictAgent)) return conflictAgent
    return candidates.filter(::fast).minByOrNull { medianMs[it]!! }
  }
}

// ------------------------------------------------------------------ Your call (P3/P4)

@Serializable
enum class CallKind { QUESTION, CONDITIONAL }

@Serializable
data class CallRule(val condition: String, val pick: String)

/** A real split as one deciding question ([CallKind.QUESTION]) or "if X, do A; if Y, do B" ([CallKind.CONDITIONAL]). */
@Serializable
data class YourCall(val kind: CallKind, val question: String? = null, val choices: List<String> = emptyList(), val rules: List<CallRule> = emptyList(), val disputeId: String? = null) {
  /** The card's text: the question, or one line per rule. */
  val text: String get() = when (kind) {
    CallKind.QUESTION -> question.orEmpty()
    CallKind.CONDITIONAL -> rules.joinToString("\n") { "If ${it.condition.trim().removePrefix("if ").removePrefix("If ").trimEnd('.', ',')}: ${it.pick.trim().trimEnd('.')}" }
  }
}

object Crux {
  val schema: JsonObject = Schema.obj(
    "kind" to Schema.string("question: one thing only the user knows decides it; conditional: it depends on a condition; none: the split doesn't change what to do", listOf("question", "conditional", "none")),
    "question" to Schema.string("For question: the one deciding question, under 12 words; else empty"),
    "options" to Schema.stringArray("For question: the two answers as short options, 1-4 words each; else empty"),
    "rules" to Schema.array("For conditional: exactly two rules; else empty", Schema.obj(
      "if" to Schema.string("A concrete condition about time, money, people or place, under 8 words"),
      "then" to Schema.string("The pick under that condition, under 6 words"),
    )),
  )

  fun input(question: String, d: Dispute, names: (String) -> String): String = buildString {
    append("Question: ").append(question).append("\nThey split on: ").append(d.topic).append("\n")
    d.sides.forEach { s -> append("- ").append(s.statement).append(" (").append(Views.names(s.agentIds.map(names))).append(")")
      s.evidence.values.firstOrNull()?.let { append(" because ").append(it) }; append("\n") }
  }

  fun parse(raw: String, d: Dispute): YourCall? {
    val o = parseJsonObject(raw) ?: return null
    return when (o.str("kind")?.trim()?.lowercase()) {
      "question" -> {
        val q = o.str("question")?.trim().orEmpty()
        val opts = o.strings("options").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        if (q.isEmpty() || opts.size < 2) null else YourCall(CallKind.QUESTION, q, opts, disputeId = d.id)
      }
      "conditional" -> {
        val rules = (o["rules"] as? JsonArray).orEmpty().mapNotNull { el ->
          val r = el as? JsonObject ?: return@mapNotNull null
          val c = r.str("if")?.trim().orEmpty(); val p = r.str("then")?.trim().orEmpty()
          if (c.isEmpty() || p.isEmpty()) null else CallRule(c, p)
        }.take(2)
        if (rules.size < 2) null else YourCall(CallKind.CONDITIONAL, choices = rules.map { it.pick }, rules = rules, disputeId = d.id)
      }
      else -> null
    }
  }

  /** The card from the model's reply: none when it says the split doesn't matter; the plain question when it failed. */
  fun decide(raw: String?, d: Dispute): YourCall? {
    val o = raw?.let { parseJsonObject(it) } ?: return fallback(d)
    if (o.str("kind")?.trim()?.lowercase() == "none") return null
    return parse(raw, d) ?: fallback(d)
  }

  /** Without the model: the two sides as one question. */
  fun fallback(d: Dispute): YourCall? {
    val vs = d.sides.map { it.value.trim() }.filter { it.isNotEmpty() }.distinct().take(2)
    if (vs.size < 2) return null
    return YourCall(CallKind.QUESTION, "Which way: ${vs[0]} or ${vs[1]}?", vs, disputeId = d.id)
  }
}

// ------------------------------------------------------------------ slow reviewers (P8)

@Serializable
data class Review(val agentId: String, val askId: String, val kind: FoldKind, val at: Long, val asTurn: Boolean = false)

// ------------------------------------------------------------------ surplus (the north-star record, text-free)

@Serializable
enum class SurplusKind {
  /** The answer holds things only one agent knew, from two or more different agents. */
  COMBINED,
  /** An answer was checked against a fact another agent knew, and conflicted. */
  CROSS_CHECK,
  /** The repair ask came back: one agent's pick fixed for another agent's fact. */
  REPAIR,
  /** A real split became a Your call card. */
  SPLIT_CARD,
  /** A slow agent's review changed the answer built from the others. */
  REVIEW_CHANGE,
}

@Serializable
data class SurplusPart(val kind: SurplusKind, val agents: List<String>)

object Surplus {
  /** Which parts of the run needed two or more agents. Agent ids only, no text. */
  fun of(s: RunState, main: String): List<SurplusPart> {
    val e = s.editor ?: return emptyList()
    val t = e.table ?: ClaimsTable()
    val out = mutableListOf<SurplusPart>()
    val onlyAgents = t.onlyOne(main).map { it.sources[0] }.distinct()
    if (onlyAgents.size >= 2) out += SurplusPart(SurplusKind.COMBINED, onlyAgents)
    e.conflict?.let { out += SurplusPart(SurplusKind.CROSS_CHECK, listOf(it.answerAgent, it.factAgent)) }
    val repair = e.repairAskId?.let { s.ask(it) }
    if (repair != null && repair.replies.isNotEmpty() && e.conflict != null) out += SurplusPart(SurplusKind.REPAIR, listOf(repair.agentId, e.conflict.factAgent).distinct())
    e.yourCall?.disputeId?.let { id -> t.disputes.firstOrNull { it.id == id }?.let { d -> out += SurplusPart(SurplusKind.SPLIT_CARD, d.sides.flatMap { it.agentIds }.distinct()) } }
    e.reviews.filter { it.kind == FoldKind.ADD || it.kind == FoldKind.FIX || it.kind == FoldKind.CHANGE }.forEach { r ->
      val others = e.versions.flatMap { it.agents }.filter { it != r.agentId }.distinct()
      if (others.isNotEmpty()) out += SurplusPart(SurplusKind.REVIEW_CHANGE, listOf(r.agentId) + others)
    }
    return out.filter { it.agents.distinct().size >= 2 }
  }
}

/** Who an editor run asks. */
@Serializable
enum class EditorScope {
  /** `@all`: every active agent. */
  ALL,
  /** The user @mentioned two or more agents: only those. */
  SOME,
  /** An unaddressed message (or @Juno): Juno's first take decides who, if anyone, is worth asking. */
  PICK,
}

/**
 * Juno's first take on an unaddressed message, and who (if anyone) is worth asking. One model call, so the answer still
 * shows within seconds. Zero agents is the normal case for chat and for what Juno fully knows.
 */
object Pick {
  fun schema(names: List<String>): JsonObject = Schema.obj(
    "answer" to Schema.string("Your answer now, in your voice"),
    "ask" to Schema.array("Agents whose own accounts, memory or tools would change this answer; empty when you can answer fully",
      if (names.isEmpty()) Schema.string("Agent name") else Schema.string("Agent name", names)),
  )

  data class Result(val answer: String, val ask: List<String>)

  /** Plain text instead of JSON is the answer with nobody asked. Unknown names are dropped; at most [max]. */
  fun parse(raw: String?, roster: Roster, max: Int): Result? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return null
    val o = parseJsonObject(t) ?: return if (t.startsWith("{")) null else Result(t, emptyList())
    val answer = o.str("answer")?.trim().orEmpty()
    val ask = o.strings("ask").mapNotNull { roster.resolve(it) }
      .filter { it.kind != AgentKind.MAIN && !it.paused }.map { it.id }.distinct().take(max)
    if (answer.isEmpty() && ask.isEmpty()) return null
    return Result(answer, ask)
  }
}

/** Per-run state of the editor (an @all run in [JunoConfig.editor] mode). */
@Serializable
data class EditorState(
  val scope: EditorScope = EditorScope.ALL,
  /** PICK: Juno's first take decided who to ask (asks are only created then). */
  val picked: Boolean = false,
  val versions: List<AnswerVersion> = emptyList(),
  /** The one caption under the answer: "First take · others adding", "Updated · Hark knew …". */
  val caption: String? = null,
  /** Ask ids already folded into the answer. */
  val folded: List<String> = emptyList(),
  /** Agents whose reply only agreed: a ✓ on the avatar, no edit. */
  val agreedOnly: List<String> = emptyList(),
  /** Kush tapped something under the answer or replied: no more edits; late changes become a new turn. */
  val frozenAt: Long? = null,
  /** When the fast answers were in (or the wait ended) and the final combine ran. */
  val settledAt: Long? = null,
  val table: ClaimsTable? = null,
  val crossChecked: Boolean = false,
  val conflict: Conflict? = null,
  val repairAskId: String? = null,
  val yourCall: YourCall? = null,
  val reviews: List<Review> = emptyList(),
  val surplus: List<SurplusPart> = emptyList(),
  /** Juno's model calls for this run. */
  val calls: Int = 0,
  val v1At: Long? = null,
  val summaryLogged: Boolean = false,
) {
  val frozen: Boolean get() = frozenAt != null
}

// ------------------------------------------------------------------ per-run summary (text-free, safe for Sentry)

@Serializable
data class RunSummary(
  val runId: String,
  val mode: String,
  val agentsAsked: List<String>,
  val agentsAnswered: List<String>,
  val lateAgents: List<String>,
  val backgroundAgents: List<String>,
  val junoCalls: Int,
  val tV1Ms: Long?,
  val tSettleMs: Long?,
  val versions: Int,
  val folds: Map<String, Int>,
  val agreedOnly: Int,
  val onlyOne: Int,
  val confirmed: Int,
  val disputes: Int,
  val conflict: Boolean,
  val repair: String,
  val yourCall: String,
  val surplus: List<String>,
  val surplusAgents: Int,
  val answerWords: Int,
  val frozen: Boolean,
  val fallback: Boolean,
) {
  /** Flat string extras for telemetry. Ids, counts and enums only: never any text. */
  fun extras(): Map<String, String> = mapOf(
    "mode" to mode, "agents_asked" to agentsAsked.joinToString(","), "agents_answered" to agentsAnswered.joinToString(","),
    "late_agents" to lateAgents.joinToString(","), "background_agents" to backgroundAgents.joinToString(","),
    "juno_calls" to junoCalls.toString(), "t_v1_ms" to (tV1Ms?.toString() ?: ""), "t_settle_ms" to (tSettleMs?.toString() ?: ""),
    "versions" to versions.toString(), "folds" to folds.entries.joinToString(",") { "${it.key}:${it.value}" },
    "agreed_only" to agreedOnly.toString(), "only_one" to onlyOne.toString(), "confirmed" to confirmed.toString(),
    "disputes" to disputes.toString(), "conflict" to conflict.toString(), "repair" to repair, "your_call" to yourCall,
    "surplus" to surplus.joinToString(","), "surplus_agents" to surplusAgents.toString(), "answer_words" to answerWords.toString(),
    "frozen" to frozen.toString(), "fallback" to fallback.toString(),
  )

  companion object {
    fun of(s: RunState, main: String): RunSummary {
      val e = s.editor ?: EditorState()
      val peers = s.asks.filter { it.agentId != main }
      val repair = e.repairAskId?.let { s.ask(it) }
      return RunSummary(
        runId = s.runId, mode = if (e.scope == EditorScope.ALL) s.mode.name else e.scope.name,
        agentsAsked = peers.map { it.agentId }.distinct(),
        agentsAnswered = peers.filter { it.replies.isNotEmpty() }.map { it.agentId }.distinct(),
        lateAgents = peers.filter { it.afterMerge && it.replies.isNotEmpty() }.map { it.agentId }.distinct(),
        backgroundAgents = peers.filter { it.background }.map { it.agentId }.distinct(),
        junoCalls = e.calls + s.modelCalls,
        tV1Ms = e.v1At?.let { it - s.createdAt }, tSettleMs = e.settledAt?.let { it - s.createdAt },
        versions = e.versions.size,
        folds = e.versions.groupingBy { it.kind.name.lowercase() }.eachCount(),
        agreedOnly = e.agreedOnly.size,
        onlyOne = e.table?.onlyOne(main)?.size ?: 0,
        confirmed = e.table?.confirmed()?.size ?: 0,
        disputes = e.table?.disputes?.size ?: 0,
        conflict = e.conflict != null,
        repair = when { repair == null -> "none"; repair.replies.isNotEmpty() -> "answered"; repair.status == AskStatus.AWAITING_APPROVAL -> "needs_tap"; repair.status == AskStatus.DECLINED -> "not_shared"; else -> repair.status.name.lowercase() },
        yourCall = e.yourCall?.kind?.name?.lowercase() ?: "none",
        surplus = e.surplus.map { it.kind.name.lowercase() },
        surplusAgents = e.surplus.flatMap { it.agents }.distinct().size,
        answerWords = s.answer?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
        frozen = e.frozen, fallback = s.fallback,
      )
    }
  }
}
