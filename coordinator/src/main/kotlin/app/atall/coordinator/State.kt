package app.atall.coordinator

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RunMode {
  /** Unaddressed message (or @Juno): Juno plans, asks, waits, merges. */
  COORDINATE,
  /** @all: the same question blind to everyone, then an Agreement Map. */
  ALL,
  /** "Talk it over": blind first answers, then up to two reveal rounds relayed by Juno, then Juno wraps up. */
  ROOM,
}

@Serializable
enum class Phase {
  /** Next step is a model call (or processing the calls it returned). */
  PLANNING,
  /** Waiting on agents; no model call until the wait resolves. */
  WAITING,
  /** Waiting on the user: an approval or a question. Nothing happens until they answer. */
  AWAITING_USER,
  /** @all: answers are in; claims, clusters and the verdict are being computed. */
  MERGING,
  DONE,
  FAILED,
  CANCELLED,
  ;
  val terminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

@Serializable
enum class AskPurpose {
  TASK, BLIND, CHALLENGE, ROOM,
  /** Editor (P1): the one repair ask after a cross-check found an answer that breaks another agent's fact. Not blind. */
  REPAIR,
  /** An agent @mentioned another in its answer: Juno relays the question with no tap (within caps). Not blind. */
  MENTION,
}

@Serializable
enum class AskStatus {
  /** Another ask to the same texting agent is in flight (one at a time, design.md rule 1). */
  QUEUED,
  /** Persisted before the send call; resent (idempotently) after a crash. */
  SENDING,
  PENDING,
  /** Messages opened with a draft; waiting for the user's tap there. Replies still count. */
  DRAFTED,
  /** Needs the user's tap ("Tap to send to kDot"). */
  NEEDS_TAP,
  /** Money/account ask held for the user's yes. */
  AWAITING_APPROVAL,
  /** At least one reply part; more may follow until the quiet window passes. */
  REPLYING,
  ANSWERED,
  TIMED_OUT,
  /** The transport thinks the reply was shown inside Messages without a notification: "look in Messages". */
  LIKELY_MISSED,
  DECLINED,
  FAILED,
  CANCELLED,
  ;
  /** Nothing more is expected (a late reply can still revive it). */
  val settled: Boolean get() = this in setOf(ANSWERED, TIMED_OUT, LIKELY_MISSED, DECLINED, FAILED, CANCELLED)
  /** Counts as occupying the agent (one in-flight ask per texting agent). */
  val inFlight: Boolean get() = this in setOf(SENDING, PENDING, DRAFTED, NEEDS_TAP, REPLYING)
}

@Serializable
data class ReplyPart(
  val messageId: String,
  val text: String,
  val at: Long,
  val certainty: Certainty = Certainty.HIGH,
  val late: Boolean = false,
  val userInterjected: Boolean = false,
  /** How this part came in; null on parts stored before C2 (they came by text). */
  val via: Via? = null,
  /** A connector part whose relay countersignature verified. */
  val verified: Boolean = false,
  /** The agent's structured claims (connector results). */
  val claims: List<SourcedClaim> = emptyList(),
) {
  /** The provenance to show: a verified connector answer, or a text. */
  val provenance: Via get() = if (via == Via.CONNECTOR && verified) Via.CONNECTOR else Via.TEXT
}

@Serializable
data class AskState(
  /** Also the correlation id handed to the peer: "<runId>~a<n>". */
  val id: String,
  val agentId: String,
  val text: String,
  val purpose: AskPurpose = AskPurpose.TASK,
  /** The model call that created it (resume dedupe). */
  val callId: String? = null,
  val why: String? = null,
  val status: AskStatus,
  val createdAt: Long,
  val sentAt: Long? = null,
  val softDeadline: Long? = null,
  val hardDeadline: Long? = null,
  val replies: List<ReplyPart> = emptyList(),
  /**
   * The agent's own status texts about this ask ("Looks like your signal dropped", "Let me check"): shown as a muted
   * line, never an answer. They don't change [status]; the ask keeps waiting until its soft timeout.
   */
  val statusNotes: List<ReplyPart> = emptyList(),
  val lastReplyAt: Long = 0,
  val settledAt: Long? = null,
  val gateReason: String? = null,
  val draftText: String? = null,
  val draftNumber: String? = null,
  val failReason: String? = null,
  /** A reply part arrived after the run had already answered the user. */
  val afterMerge: Boolean = false,
  val followedUp: Boolean = false,
  /** For CHALLENGE asks: the dispute it is about. */
  val disputeId: String? = null,
  /** Talk asks: the round it belongs to (0 = the blind first answers). */
  val roomRound: Int? = null,
  /** Talk relays: every room entry with seq <= this was considered for this agent (the cursor; see [RoomState]). */
  val relayThrough: Int? = null,
  /** Talk relays: the other agents' words quoted in [text] (and Juno's note). Checked by the send gate as relayed text. */
  val quoted: List<String> = emptyList(),
  /** Talk relays held for the user's "Share with X?": the agents whose answers are in [text] and need the tap. */
  val shareFrom: List<String> = emptyList(),
  /** The same relay without [shareFrom]'s answers (null when nothing would be left): sent if the user says no. */
  val textWithoutShared: String? = null,
  val quotedWithoutShared: List<String> = emptyList(),
  /** The user approved this exact text with a tap: it goes out as theirs. */
  val approved: Boolean = false,
  /** The transport the ask went out on (null before it was sent, and on asks stored before C2). */
  val via: Via? = null,
  /**
   * Editor: the agent's measured reply time is slow, so the run never waits on it; its answer folds in as a review
   * when it lands (the avatar shows a moon until then).
   */
  val background: Boolean = false,
  /** MENTION relays: how many @mention hops led here (1 = an agent's answer mentioned this agent). */
  val mentionDepth: Int = 0,
  /** MENTION relays: the agent whose answer @mentioned this one. */
  val mentionFrom: String? = null,
) {
  val replyText: String get() = replies.joinToString("\n") { it.text }
  /** Every structured claim the agent sent for this ask. */
  val claims: List<SourcedClaim> get() = replies.flatMap { it.claims }
  /** Every part came over the connector with a verified countersignature. */
  val verified: Boolean get() = replies.isNotEmpty() && replies.all { it.provenance == Via.CONNECTOR }
  val uncertain: Boolean get() = replies.any { it.certainty == Certainty.LOW }
  val late: Boolean get() = replies.any { it.late }
}

@Serializable
enum class PendingKind { WAIT, ASK_USER, APPROVAL, CHALLENGE_WAIT, ROOM_ROUND }

/** A tool call whose output is not known yet. The run continues once every pending call has an [output]. */
@Serializable
data class PendingCall(
  val callId: String,
  val kind: PendingKind,
  val askIds: List<String> = emptyList(),
  val until: Long? = null,
  val question: String? = null,
  val output: String? = null,
  val startedAt: Long = 0,
)

/** A model tool call that was returned but not fully processed yet (crash between the model call and its effects). */
@Serializable
data class CallRecord(val callId: String, val name: String, val arguments: String)

@Serializable
data class FollowUp(val agentId: String, val text: String, val at: Long)

@Serializable
data class RunState(
  val runId: String,
  val mode: RunMode,
  val userText: String,
  val mustAsk: List<String> = emptyList(),
  val createdAt: Long,
  val phase: Phase,
  val transcript: List<JsonObject> = emptyList(),
  val asks: List<AskState> = emptyList(),
  val rounds: Int = 0,
  val modelCalls: Int = 0,
  val pending: List<PendingCall> = emptyList(),
  val unprocessed: List<CallRecord> = emptyList(),
  val answer: String? = null,
  val answeredAt: Long? = null,
  /** True when the answer was assembled without the model (it failed or looped). */
  val fallback: Boolean = false,
  val followUps: List<FollowUp> = emptyList(),
  /** User messages that arrived mid-run, not yet shown to the model. */
  val interjections: List<String> = emptyList(),
  val nudgedMustAsk: Boolean = false,
  val error: String? = null,
  val errorKind: LlmException.Kind? = null,
  val agreement: AgreementMap? = null,
  /** A talk ("Talk it over"): its entries, rounds and outcome. Set for [RunMode.ROOM]. */
  val room: RoomState? = null,
  /** "Juno as editor" (@all with [JunoConfig.editor]): versions, claims table, cross-check, Your call, reviews. */
  val editor: EditorState? = null,
  val version: Long = 0,
) {
  fun ask(id: String) = asks.firstOrNull { it.id == id }
  val openAsks: List<AskState> get() = asks.filter { !it.status.settled }
  /** Still needs attention: not terminal, or answered but an agent may still reply (late follow-up). */
  val live: Boolean get() = !phase.terminal || (phase == Phase.DONE && (openAsks.isNotEmpty() || asks.any { it.afterMerge && it.status.settled && !it.followedUp && it.replies.isNotEmpty() }))
}

/** Where runs are persisted. Room in the app; JSON files or memory in tests. Every mutation is saved before effects. */
interface RunStore {
  suspend fun save(state: RunState)
  suspend fun load(runId: String): RunState?
  /** Runs that are [RunState.live]. Called on start-up to resume. */
  suspend fun live(): List<RunState>
}

object RunCodec {
  fun encode(s: RunState): String = CoordJson.encodeToString(RunState.serializer(), s)
  fun decode(raw: String): RunState = CoordJson.decodeFromString(RunState.serializer(), raw)
}

/** Keeps serialized JSON (not objects), so every save proves the state round-trips. */
class InMemoryRunStore : RunStore {
  val raw = ConcurrentHashMap<String, String>()
  override suspend fun save(state: RunState) { raw[state.runId] = RunCodec.encode(state) }
  override suspend fun load(runId: String) = raw[runId]?.let(RunCodec::decode)
  override suspend fun live() = raw.values.map(RunCodec::decode).filter { it.live }.sortedBy { it.createdAt }
}

/** One JSON file per run, written atomically (temp file + rename). Good enough for tests and a Room-less prototype. */
class FileRunStore(private val dir: File) : RunStore {
  init { dir.mkdirs() }
  private fun f(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_.~-]"), "_") + ".json")
  override suspend fun save(state: RunState) {
    val tmp = File(dir, ".${state.runId.hashCode()}.tmp")
    tmp.writeText(RunCodec.encode(state))
    if (!tmp.renameTo(f(state.runId))) { f(state.runId).delete(); tmp.renameTo(f(state.runId)) }
  }
  override suspend fun load(runId: String) = f(runId).takeIf { it.exists() }?.readText()?.let(RunCodec::decode)
  override suspend fun live() = (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray())
    .map { RunCodec.decode(it.readText()) }.filter { it.live }.sortedBy { it.createdAt }
}
