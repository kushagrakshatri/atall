package app.atall.agents.text.testing

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.AgentMessage
import app.atall.agents.text.Clock
import app.atall.agents.text.Confidence
import app.atall.agents.text.Correlation
import app.atall.agents.text.CorrelationReason
import app.atall.agents.text.Direction
import app.atall.agents.text.DraftLauncher
import app.atall.agents.text.DraftOutcome
import app.atall.agents.text.DraftRequest
import app.atall.agents.text.FailReason
import app.atall.agents.text.FireResult
import app.atall.agents.text.LogEntry
import app.atall.agents.text.NotificationSnapshot
import app.atall.agents.text.OutstandingRequest
import app.atall.agents.text.RemovalReason
import app.atall.agents.text.ReplyHandle
import app.atall.agents.text.RequestState
import app.atall.agents.text.Rules
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import app.atall.agents.text.SnapshotLine
import app.atall.agents.text.TextAgentHub
import app.atall.agents.text.Via
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ManualClock(start: Long = 1_700_000_000_000L) : Clock {
  private val t = AtomicLong(start)
  override fun now() = t.get()
  fun advance(ms: Long) { t.addAndGet(ms) }
  fun set(ms: Long) { t.set(ms) }
}

/**
 * A simulated Google Messages, faithful to what the Route C spike observed on a Pixel 9 Pro:
 *  - each incoming text (re)posts the conversation notification with every unread line as MessagingStyle
 *    history (the whole history again, so the hub must dedupe);
 *  - a notification reply (RemoteInput) adds the user's line to the thread and re-posts with it appended
 *    (sender null), so the hub sees its own echo;
 *  - an open chat gets no notification (suppressed) and opening it cancels the notification (APP_CANCEL);
 *  - [revokeActions] makes old reply actions throw CanceledException.
 * Wire [deliver]/[removed] to the hub (see [attach]). Never touches a real phone.
 */
class FakeMessagesApp(private val clock: ManualClock) {
  inner class Conversation(val title: String, val number: String) {
    val key = "0|${Rules.MESSAGES_PACKAGE}|${title.hashCode()}|null|10101"
    val unread = ArrayList<SnapshotLine>()
    val thread: MutableList<SnapshotLine> = java.util.Collections.synchronizedList(ArrayList())
    var chatOpen = false
    var showing = false
    var generation = 0
  }

  private inner class Handle(val conv: Conversation, val gen: Int) : ReplyHandle {
    override fun fire(text: String): FireResult {
      if (gen < revokedBelow) return FireResult.CANCELED
      if (failNextFire) { failNextFire = false; return FireResult.ERROR }
      fired += conv.title to text
      val line = SnapshotLine(text, clock.now(), null)
      conv.thread += line
      if (echoReplies && conv.showing) { conv.unread += line; post(conv) }
      return FireResult.OK
    }
  }

  val conversations = LinkedHashMap<String, Conversation>()
  /** (conversation title, text) for every reply-action send. */
  val fired: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())
  var echoReplies = true
  /** Post plain-text notifications without MessagingStyle (older/odd formats). */
  var plainText = false
  var failNextFire = false
  private var revokedBelow = 0
  private var nextGen = 1

  var deliver: (NotificationSnapshot) -> Unit = {}
  var removed: (String, RemovalReason) -> Unit = { _, _ -> }

  fun attach(hub: TextAgentHub) {
    deliver = hub::onPosted
    removed = hub::onRemoved
  }

  fun conversation(title: String, number: String = "") = conversations.getOrPut(title) { Conversation(title, number) }

  /** The agent texts the phone. Returns the line's timestamp. [sentAt] lets RCS lag be simulated (old stamp, late arrival). */
  fun agentTexts(title: String, text: String, sentAt: Long = clock.now()): Long {
    val c = conversation(title)
    val line = SnapshotLine(text, sentAt, title)
    c.thread += line
    if (c.chatOpen) return sentAt // suppressed: shown in the open chat, no notification
    c.unread += line
    post(c)
    return sentAt
  }

  /** The user types into the thread in Messages directly. */
  fun userTypes(title: String, text: String) {
    val c = conversation(title)
    val line = SnapshotLine(text, clock.now(), null)
    c.thread += line
    if (c.showing) { c.unread += line; post(c) }
  }

  fun openChat(title: String) {
    val c = conversation(title)
    c.chatOpen = true
    c.unread.clear()
    if (c.showing) { c.showing = false; removed(c.key, RemovalReason.APP_CANCEL) }
  }

  fun closeChat(title: String) { conversation(title).chatOpen = false }

  fun dismiss(title: String) {
    val c = conversation(title)
    if (c.showing) { c.showing = false; removed(c.key, RemovalReason.USER_DISMISS) }
  }

  /** Messages revokes every reply action handed out so far. */
  fun revokeActions() { revokedBelow = nextGen }

  /** The notifications still showing, as a fresh listener would read them (getActiveNotifications). */
  fun active(): List<NotificationSnapshot> = conversations.values.filter { it.showing }.map { snapshot(it) }

  private fun post(c: Conversation) {
    c.showing = true
    deliver(snapshot(c))
  }

  private fun snapshot(c: Conversation): NotificationSnapshot {
    val gen = nextGen++
    c.generation = gen
    val handle = Handle(c, gen)
    return if (plainText) {
      NotificationSnapshot(Rules.MESSAGES_PACKAGE, c.key, clock.now(), title = c.title, text = c.unread.lastOrNull { it.sender != null }?.text, reply = handle)
    } else {
      NotificationSnapshot(
        packageName = Rules.MESSAGES_PACKAGE, key = c.key, postTime = clock.now(), conversationTitle = c.title,
        title = c.title, selfName = "You", lines = c.unread.toList(), reply = handle,
      )
    }
  }

  /** A [DraftLauncher] standing in for ACTION_SENDTO: records drafts and opens the chat. */
  inner class Drafts(var outcome: DraftOutcome = DraftOutcome.OPENED) : DraftLauncher {
    val opened = ArrayList<DraftRequest>()
    override fun open(request: DraftRequest): DraftOutcome {
      if (outcome != DraftOutcome.OPENED) return outcome
      opened += request
      conversations.values.firstOrNull { Rules.normalizeNumber(it.number) == request.number }?.let { openChat(it.title) }
      return outcome
    }

    /** The user taps Send on the draft (optionally after editing it). */
    fun userTapsSend(request: DraftRequest, edited: String = request.text) {
      val c = conversations.values.first { Rules.normalizeNumber(it.number) == request.number }
      c.thread += SnapshotLine(edited, clock.now(), null)
    }
  }
}

/**
 * An in-memory [Agent] for coordinator tests that need no notification plumbing. [respond] scripts the
 * agent: given the text it returns the reply parts (empty for no reply), emitted at once, correlated HIGH.
 */
class FakeAgent(
  override val id: AgentId,
  override val displayName: String = id.value,
  private val clock: Clock = Clock { System.currentTimeMillis() },
  private val respond: (String) -> List<String> = { listOf("ok: $it") },
) : Agent {
  private val flow = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 256)
  private val log = ArrayList<LogEntry>()
  private val open = LinkedHashMap<String, OutstandingRequest>()
  private val used = HashSet<String>()
  val sent = ArrayList<Pair<String, String>>()

  override val incoming: Flow<AgentMessage> = flow.asSharedFlow()

  /** Push an unsolicited message. */
  fun push(text: String, correlation: Correlation? = null) {
    val now = clock.now()
    val m = AgentMessage("fake|$id|${log.size}", id, text, now, now, correlation)
    log += LogEntry(m.id, id, Direction.IN, text, now, correlation = correlation)
    flow.tryEmit(m)
  }

  override suspend fun send(text: String, correlationId: String, options: SendOptions): SendResult {
    if (text.isBlank()) return SendResult.Failed(correlationId, FailReason.EMPTY)
    if (!used.add(correlationId)) return SendResult.Duplicate(correlationId, clock.now())
    val now = clock.now()
    sent += correlationId to text
    log += LogEntry("out|$correlationId", id, Direction.OUT, text, now, Via.ACTION, correlationId = correlationId)
    if (options.expectReply) open[correlationId] = OutstandingRequest(correlationId, id, text, text, now, Via.ACTION, RequestState.WAITING, now + 60_000)
    respond(text).forEachIndexed { i, part ->
      push(part, Correlation(correlationId, Confidence.HIGH, CorrelationReason.ONLY_OPEN_REQUEST, i))
    }
    return SendResult.Sent(correlationId, now, 0, text)
  }

  override suspend fun history(limit: Int) = log.takeLast(limit)
  override suspend fun outstanding() = open.values.toList()
  override suspend fun close(correlationId: String) { open.remove(correlationId) }
}
