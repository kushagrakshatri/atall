package app.atall.coordinator

import kotlinx.serialization.Serializable

/** What kind of agent a roster entry is. [MAIN] is Juno itself (the coordinator's brain), never a peer. */
@Serializable
enum class AgentKind { MAIN, TEXT, API }

/**
 * One agent in the thread. [goodAt] are short plain tags ("bookings", "calendar", "research") that Juno uses to
 * plan who to ask; they are also shown to the model in the roster. Timeouts default per kind (see [JunoConfig]).
 */
@Serializable
data class AgentProfile(
  val id: String,
  val name: String,
  val kind: AgentKind,
  val aliases: List<String> = emptyList(),
  val goodAt: List<String> = emptyList(),
  val paused: Boolean = false,
  val softTimeoutMs: Long? = null,
  val hardTimeoutMs: Long? = null,
) {
  init { require(id.isNotBlank() && name.isNotBlank()) { "agent needs an id and a name" } }
  val handles: List<String> get() = listOf(name) + aliases
}

/** The agents the user has set up. Exactly one [AgentKind.MAIN]. Names and aliases match case-insensitively. */
class Roster(val agents: List<AgentProfile>) {
  init {
    require(agents.count { it.kind == AgentKind.MAIN } == 1) { "roster needs exactly one main agent" }
    require(agents.map { it.id }.toSet().size == agents.size) { "duplicate agent ids" }
  }

  val main: AgentProfile get() = agents.first { it.kind == AgentKind.MAIN }
  val peers: List<AgentProfile> get() = agents.filter { it.kind != AgentKind.MAIN }
  val activePeers: List<AgentProfile> get() = peers.filter { !it.paused }

  fun byId(id: String): AgentProfile? = agents.firstOrNull { it.id == id }

  /** By name or alias, case-insensitive, ignoring a leading "@". */
  fun resolve(handle: String): AgentProfile? {
    val h = handle.trim().removePrefix("@").lowercase()
    if (h.isEmpty()) return null
    return agents.firstOrNull { a -> a.handles.any { it.lowercase() == h } }
  }

  fun nameOf(id: String): String = byId(id)?.name ?: id
}

/**
 * The coordinator's view of a peer agent. Deliberately smaller than text-agents' `Agent`: the app adapts its texting
 * agents (and later API agents) to this. Replies do not come back through here; the app's event pump feeds them to
 * [Juno.ingest] as [PeerSignal]s, so a reply that arrives while no run is in memory is still recorded.
 */
interface Peer {
  val id: String

  /**
   * Hand [text] to the agent. [correlationId] is the ask id; it must be idempotent: re-sending an id that was
   * already accepted must not text twice (return [PeerSend.Duplicate]). Juno relies on this after process death.
   */
  suspend fun send(text: String, correlationId: String): PeerSend

  /** Stop attributing replies to [correlationId]. */
  suspend fun close(correlationId: String) {}
}

sealed interface PeerSend {
  /** Handed to the transport. Not proof of delivery. */
  data class Sent(val at: Long) : PeerSend

  /** Messages was opened with the text typed in; nothing goes until the user taps Send there. */
  data class Drafted(val at: Long) : PeerSend

  /** Could not send or open a draft by itself (cold thread, app in background). Show "Tap to send to {agent}". */
  data class NeedsUser(val draftText: String, val number: String? = null, val reason: String = "no_live_reply") : PeerSend

  data class RateLimited(val retryAfterMs: Long) : PeerSend

  /** This correlation id was already accepted earlier (e.g. a resend after process death). Treated as sent. */
  data class Duplicate(val firstAt: Long) : PeerSend

  data class Failed(val reason: String) : PeerSend
}

/** How sure the transport is that a reply belongs to an ask (text-agents' Confidence). */
@Serializable
enum class Certainty { HIGH, MEDIUM, LOW }

/** Why the transport stopped tracking a request (text-agents' RequestClosed.Outcome). */
@Serializable
enum class CloseOutcome { COMPLETED, EXPIRED, LIKELY_MISSED, CLOSED_BY_CALLER }

/** Everything the app's event pump feeds into [Juno.ingest]. */
sealed interface PeerSignal {
  val agentId: String

  /**
   * A message from a peer. [correlationId] null means the transport could not attribute it: it is unsolicited.
   * [late] means the transport had already closed the request when the reply showed up (RCS delivered late).
   * [userInterjected] means the user typed in that agent's chat themselves after the ask (certainty is LOW then).
   */
  data class Message(
    override val agentId: String,
    val messageId: String,
    val text: String,
    val at: Long,
    val correlationId: String? = null,
    val certainty: Certainty = Certainty.HIGH,
    val part: Int = 0,
    val late: Boolean = false,
    val userInterjected: Boolean = false,
  ) : PeerSignal

  /** The transport closed a request. */
  data class Closed(override val agentId: String, val correlationId: String, val outcome: CloseOutcome, val at: Long) : PeerSignal

  /** A drafted or tap-to-send ask is now in the thread (the user tapped Send; our line was seen). */
  data class DraftSent(override val agentId: String, val correlationId: String, val at: Long) : PeerSignal
}
