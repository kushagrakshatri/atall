package app.atall.coordinator.replay

import app.atall.coordinator.AgentKind
import app.atall.coordinator.AgentProfile
import app.atall.coordinator.AskPurpose
import app.atall.coordinator.AskStatus
import app.atall.coordinator.AskTail
import app.atall.coordinator.DriveResult
import app.atall.coordinator.EditorState
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.InMemoryScoreLog
import app.atall.coordinator.Juno
import app.atall.coordinator.JunoConfig
import app.atall.coordinator.Llm
import app.atall.coordinator.Peer
import app.atall.coordinator.Phase
import app.atall.coordinator.Roster
import app.atall.coordinator.RunCodec
import app.atall.coordinator.RunMode
import app.atall.coordinator.RunState
import app.atall.coordinator.SurplusKind
import java.io.File

/**
 * The offline replay harness (measure.md §3, JVM test scope only). It re-runs Juno's editor over saved runs using only
 * Juno's model interface ([Llm]): the recorded blind answers are reused as recorded and never regenerated, no agent
 * is reachable (every peer is null, so nothing is ever sent), and no agent is simulated. A pattern that would need a
 * new agent reply (the repair answer, a late review that never came) is simply not replayed.
 *
 * Then a [Judge] (an outside grader when configured, else Juno's model) compares Juno's replayed answer with the best
 * single blind answer, names hidden and both orders (Beat-the-Best, M1).
 */
class Replay(
  private val model: Llm,
  private val judge: Judge = Judges.default(model),
  /** Agent id -> display name (the saved runs carry ids only). */
  private val names: Map<String, String> = emptyMap(),
  private val config: JunoConfig = JunoConfig(editor = true),
) {
  data class Case(
    val runId: String,
    val question: String,
    val agents: List<String>,
    val replayedAnswer: String?,
    val recordedAnswer: String?,
    val bestSingleAgent: String?,
    val vsBest: PairResult?,
    val surplus: List<SurplusKind>,
    val onlyOne: Int,
    val disputes: Int,
    val conflict: Boolean,
    val yourCall: Boolean,
  )

  data class Report(val cases: List<Case>, val skipped: Map<String, String>, val beatTheBest: WinRate.Report, val judge: String) {
    /** One line in the scorecard style of measure.md §1. */
    fun line(): String {
      val b = beatTheBest
      fun pct(d: Double?) = d?.let { "${Math.round(it * 100)}%" } ?: "n/a"
      val usr = cases.count { it.surplus.isNotEmpty() }
      return "runs ${cases.size} (skipped ${skipped.size}) · BtB ${pct(b.rate)} [${pct(b.low)}–${pct(b.high)}] LC ${pct(b.lengthControlled)} (W${b.wins}/L${b.losses}/T${b.ties}) · " +
        "surplus ${usr}/${cases.size} · judge ${judge}"
    }
  }

  /** Every `*.json` run in [dir] (the phone's runs/ export, or the FileRunStore format). */
  suspend fun run(dir: File): Report = runAll((dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
    runCatching { RunCodec.decode(f.readText()) }.getOrNull()
  })

  suspend fun runAll(runs: List<RunState>): Report {
    val cases = mutableListOf<Case>()
    val skipped = linkedMapOf<String, String>()
    for (r in runs) {
      val why = replayable(r)
      if (why != null) { skipped[r.runId] = why; continue }
      cases += replay(r)
    }
    return Report(cases, skipped, WinRate.of(cases.mapNotNull { it.vsBest }), judge.name)
  }

  /** Why a run can't be replayed from what was logged, or null. */
  fun replayable(r: RunState): String? = when {
    r.mode != RunMode.ALL -> "not an @all run"
    r.asks.count { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() && it.agentId != MAIN } < 2 -> "fewer than two recorded agent answers"
    else -> null
  }

  private suspend fun replay(r: RunState): Case {
    val main = r.asks.firstOrNull { it.purpose == AskPurpose.BLIND && it.replies.any { p -> p.messageId.startsWith("main-") } }?.agentId ?: MAIN
    val agents = r.asks.map { it.agentId }.filter { it != main }.distinct()
    val roster = Roster(listOf(AgentProfile(main, "Juno", AgentKind.MAIN)) + agents.map { AgentProfile(it, nameOf(it), AgentKind.TEXT) })
    // Only the blind round, exactly as recorded, every ask settled: the run resumes at the combine step.
    val blind = r.asks.filter { it.purpose == AskPurpose.BLIND && it.replies.isNotEmpty() }
      .map { it.copy(status = AskStatus.ANSWERED, afterMerge = false, followedUp = false, background = false) }
    val start = r.copy(asks = blind, phase = Phase.MERGING, pending = emptyList(), answer = null, answeredAt = null, agreement = null,
      followUps = emptyList(), editor = EditorState(), room = null, fallback = false, version = 0)
    val store = InMemoryRunStore().also { it.save(start) }
    val noPeers: (String) -> Peer? = { null }
    val juno = Juno({ roster }, noPeers, model, store, InMemoryScoreLog(), config = config)
    juno.recover()
    juno.drive(r.runId) as? DriveResult.Done
    val out = juno.state(r.runId)
    val answer = out?.answer
    val e = out?.editor
    // Best single blind answer (B*): a round-robin among the agents' own answers with the same judge.
    val singles = blind.filter { it.agentId != main }.map { it.agentId to AskTail.strip(it.replyText) }
    val hide = roster.agents.map { it.name } + agents
    var best = singles.firstOrNull()
    for (c in singles.drop(1)) {
      if (best == null) break
      if (PairJudging.judge(judge, r.userText, c.second, best.second, hide).outcome == Preference.FIRST) best = c
    }
    val vs = if (answer != null && best != null) PairJudging.judge(judge, r.userText, answer, best.second, hide) else null
    return Case(r.runId, r.userText, agents, answer, r.answer, best?.first, vs, e?.surplus.orEmpty().map { it.kind },
      e?.table?.onlyOne(main)?.size ?: 0, e?.table?.disputes?.size ?: 0, e?.conflict != null, e?.yourCall != null)
  }

  private fun nameOf(id: String) = names[id] ?: id.replaceFirstChar { it.uppercase() }

  companion object { const val MAIN = "main" }
}
