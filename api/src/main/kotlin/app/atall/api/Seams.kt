package app.atall.api

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentMessage
import app.atall.chatgpt.ResponsesClient

/**
 * How an agent is reached. Channel-agnostic on purpose: the registry, the add-agent screen and routing only
 * see this enum and the [Agent] interface. Adapters for the non-RCS channels live in `app-agents` and plug in
 * through [ChannelAdapter].
 */
enum class ChannelType(
    /** True when this build can send to and hear from agents on the channel. */
    val implemented: Boolean,
) {
    /** SMS/RCS through Google Messages notifications and reply actions (text-agents). */
    RCS(true),
    /** WhatsApp notifications and reply actions. */
    WHATSAPP(false),
    /** An agent app's own notifications (it posts, we reply through its action). */
    APP_NOTIFICATIONS(false),
}

/** A registered peer agent, as other modules see it. Never the main agent. */
data class PeerInfo(
    val id: String,
    val name: String,
    val channel: ChannelType,
    /** Channel address: a phone number for RCS, a contact name for WhatsApp, a package name for app notifications. */
    val address: String,
    val aliases: List<String> = emptyList(),
    val paused: Boolean = false,
)

/** Peers the main agent may talk to. The returned [Agent] is the same interface for every channel. */
interface PeerDirectory {
    fun peers(): List<PeerInfo>
    /** Null when the peer is unknown, paused, or its channel has no adapter in this build. */
    fun agent(peerId: String): Agent?
}

/** Builds [Agent]s for a channel. RCS is built in; `app-agents` registers WhatsApp and app-notification adapters. */
interface ChannelAdapter {
    val channel: ChannelType
    /** Add or update the peer; idempotent. Throws IllegalArgumentException when it would clash with another peer. */
    fun register(peer: PeerInfo): Agent
    fun unregister(peerId: String)
    /** The registered peer's [Agent], or null. */
    fun agent(peerId: String): Agent?
}

/** One line of the thread as other modules see it (oldest first in lists). */
data class ThreadLine(
    val id: String,
    val at: Long,
    /** "user", "main", or a peer id. */
    val author: String,
    val text: String,
)

/** A user message the main agent must handle: no mention, or an explicit `@Juno` (possibly with peer mentions). */
data class MainTurn(
    val messageId: String,
    val text: String,
    val at: Long,
    /** Peer ids the user @mentioned alongside the main agent; hard constraints for the coordinator. */
    val mentionedPeers: List<String>,
)

/** Where the main agent writes. Text is shown as the main agent's voice. */
interface ThreadSink {
    /** Starts a main-agent message and returns its id. */
    suspend fun start(text: String = ""): String
    /** Replaces the text of a main-agent message (streaming). */
    suspend fun update(id: String, text: String)
    /** A one-line "needs you" state (sign in, notification access), with an action the UI knows. */
    suspend fun needsUser(kind: NeedsUserKind, text: String)
}

/** SIGN_IN shows a Sign in action; NOTICE is a plain one-line status (an error, "still waiting"). */
enum class NeedsUserKind { SIGN_IN, NOTIFICATION_ACCESS, NOTICE }

/** What the main agent can use. [responses] is null when there is no usable ChatGPT sign-in. */
class MainContext(
    val history: List<ThreadLine>,
    val peers: PeerDirectory,
    val sink: ThreadSink,
    val responses: ResponsesClient?,
    val model: String,
    val mainName: String,
)

/**
 * The main agent's brain. The app ships a plain-chat fallback; `app.atall.coordinator` replaces it with the
 * ask/wait/merge loop (design.md §3.4). Called on a background dispatcher; may suspend for minutes.
 */
interface Coordinator {
    suspend fun onTurn(turn: MainTurn, context: MainContext)
    /** A peer message arrived. Return true when the coordinator took it as an answer to its own ask. */
    suspend fun onPeerMessage(peer: PeerInfo, message: AgentMessage): Boolean = false
}

/** What to do with a message a peer sent. `app.atall.inbox` can triage unsolicited messages. */
enum class InboxDecision { SHOW_IN_THREAD, HOLD }

interface Inbox {
    /** Called for each peer message that no direct ask or coordinator claimed. */
    suspend fun onUnsolicited(peer: PeerInfo, message: AgentMessage): InboxDecision
}

/** Default: every unsolicited message shows in the thread. */
object ShowEverything : Inbox {
    override suspend fun onUnsolicited(peer: PeerInfo, message: AgentMessage) = InboxDecision.SHOW_IN_THREAD
}
