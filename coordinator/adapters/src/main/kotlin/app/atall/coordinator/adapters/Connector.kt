package app.atall.coordinator.adapters

// C2: Juno's asks over the Atall connector. relay-client -> coordinator Peer (ConnectorAgent) and event pump.

import app.atall.coordinator.ClaimSource
import app.atall.coordinator.Peer
import app.atall.coordinator.PeerSend
import app.atall.coordinator.PeerSignal
import app.atall.coordinator.SourcedClaim
import app.atall.coordinator.Via
import app.atall.relay.RelayAgent
import app.atall.relay.RelayClient
import app.atall.relay.RelayEvent
import app.atall.relay.RelayException
import app.atall.relay.TaskCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One task card posted for one of Juno's asks (or a direct @mention). [cid] is the ask's correlation id, [peerId] the
 * agent in the user's roster, [relayAgentId] its id on the relay (kdot, grok, hark, duet, muse). Events find their ask
 * by [taskId], or by ([relayAgentId], [ref]) when the post went through but its answer was lost (crash, timeout).
 */
@Serializable
data class ConnectorTask(
  val cid: String,
  val peerId: String,
  val relayAgentId: String,
  val ref: String,
  val taskId: String? = null,
  val postedAt: Long,
  val deadlineAt: Long,
  /** A result arrived, or Juno stopped waiting. Open tasks keep the events poller fast. */
  val doneAt: Long? = null,
  /** C3: wake-ups sent for this task so far, the last one's time and how it went (the [WakeKind] name). */
  val wakes: Int = 0,
  val wokeAt: Long? = null,
  val wakeVia: String? = null,
  /** C3: the agent's last sign of life on this task (claim, progress, question, approval request). */
  val activityAt: Long? = null,
  /** C3 fallback ladder (protocol.md §8): when the same ask went by text (or a tap) instead, and why. */
  val fellBackAt: Long? = null,
  val fallback: String? = null,
  /**
   * The relay answered 409 for our own ref: the card is on the relay (an earlier or concurrent post of this ask went
   * through) but its task id wasn't returned. Woken and fallen back like a posted task; the pump fills [taskId] in.
   */
  val onRelay: Boolean = false,
) {
  /** On the relay (posted, or known to be there): the ladder may wake it and fall back for it. */
  val posted: Boolean get() = taskId != null || onRelay

  /** Waiting on the connector: polled fast. A task that fell back still takes a late result (slow poll). */
  fun open(now: Long) = doneAt == null && fellBackAt == null && now < deadlineAt
  /** Still worth waking or falling back for. */
  fun live(now: Long) = doneAt == null && now < deadlineAt
}

/** Where [ConnectorTask]s are kept. A JSON file in the app (survives process death); memory in tests. */
class ConnectorLedger(private val file: File? = null, private val keepMs: Long = 7 * 24 * 3600_000L) {
  private val lock = Any()
  private var tasks: List<ConnectorTask> = load()

  fun all(): List<ConnectorTask> = synchronized(lock) { tasks }
  fun get(cid: String): ConnectorTask? = synchronized(lock) { tasks.lastOrNull { it.cid == cid } }
  fun byTaskId(taskId: String): ConnectorTask? = synchronized(lock) { tasks.lastOrNull { it.taskId == taskId } }
  fun byRef(relayAgentId: String, ref: String): ConnectorTask? = synchronized(lock) { tasks.lastOrNull { it.ref == ref && it.relayAgentId == relayAgentId } }
  fun open(now: Long): List<ConnectorTask> = synchronized(lock) { tasks.filter { it.open(now) } }

  fun put(t: ConnectorTask) = synchronized(lock) {
    val now = t.postedAt
    tasks = tasks.filter { it.cid != t.cid && now - it.postedAt < keepMs } + t
    save()
  }

  fun remove(cid: String) = synchronized(lock) { tasks = tasks.filter { it.cid != cid }; save() }

  /**
   * Drops this post's own entry for [cid] after a failed post, but never one that is on the relay (another post of
   * the same ask got a task id, or a 409 showed the card is there): that would orphan the task and skip its wake.
   */
  fun dropUnposted(cid: String, ref: String) = synchronized(lock) {
    val before = tasks.size
    tasks = tasks.filter { !(it.cid == cid && it.ref == ref && !it.posted) }
    if (tasks.size != before) save()
  }

  /** Posts in flight, by ask id: a second send of the same ask while one is posting waits for it (one POST per ask). */
  internal val posting = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<PeerSend>>()

  fun live(now: Long): List<ConnectorTask> = synchronized(lock) { tasks.filter { it.live(now) } }

  /** Changes the newest task for [cid] (no-op when there is none). Returns the updated task. */
  fun update(cid: String, f: (ConnectorTask) -> ConnectorTask): ConnectorTask? = synchronized(lock) {
    val cur = tasks.lastOrNull { it.cid == cid } ?: return@synchronized null
    val next = f(cur)
    tasks = tasks.map { if (it === cur) next else it }
    save()
    next
  }

  fun markDone(cid: String, at: Long) = synchronized(lock) {
    tasks = tasks.map { if (it.cid == cid && it.doneAt == null) it.copy(doneAt = at) else it }
    save()
  }

  private fun load(): List<ConnectorTask> = try {
    if (file?.isFile == true) JSON.decodeFromString(ListSerializer(ConnectorTask.serializer()), file.readText()) else emptyList()
  } catch (_: Exception) { emptyList() }

  private fun save() {
    val f = file ?: return
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".${f.name}.tmp")
    tmp.writeText(JSON.encodeToString(ListSerializer(ConnectorTask.serializer()), tasks))
    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
  }

  private companion object { val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false } }
}

/** A peer that takes a task card (a short title and the full text) rather than one text. */
interface CardPeer : Peer {
  val relayAgentId: String
  suspend fun sendCard(correlationId: String, title: String, goal: String): PeerSend
}

/**
 * One agent reached over the Atall connector: [send] posts a phone-signed task card addressed to [relayAgentId] only,
 * asking for claims. Idempotent by correlation id (a resend after process death returns [PeerSend.Duplicate]). The
 * answer comes back as relay events through [ConnectorPump], never through here.
 */
class ConnectorAgent(
  override val id: String,
  override val relayAgentId: String,
  private val client: RelayClient,
  private val ledger: ConnectorLedger,
  /** How long the task stays open on the relay: the agent's hard timeout. */
  private val deadlineMs: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) : CardPeer {
  override suspend fun send(text: String, correlationId: String): PeerSend = sendCard(correlationId, ConnectorText.title(text), text)

  /**
   * Posts the card: [title] is a short line, [goal] the full self-contained text the agent works from. Exactly one POST
   * per ask: a send of the same [correlationId] while one is in flight waits for it and returns [PeerSend.Duplicate]
   * (v0.1.9 bug: two flushes posted every ask twice; Grok's second post got a 409 and failed the ask, dropping its wake).
   */
  override suspend fun sendCard(correlationId: String, title: String, goal: String): PeerSend {
    while (true) {
      val mine = kotlinx.coroutines.CompletableDeferred<PeerSend>()
      val other = ledger.posting.putIfAbsent(correlationId, mine)
      if (other != null) {
        val r = try { other.await() } catch (e: CancellationException) {
          // The other post was cancelled, not us: post ourselves.
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          continue
        } catch (_: Exception) { continue }
        return when (r) {
          is PeerSend.Sent -> PeerSend.Duplicate(r.at)
          else -> r
        }
      }
      try {
        val r = post(correlationId, title, goal)
        mine.complete(r)
        return r
      } catch (e: Throwable) {
        mine.completeExceptionally(e)
        throw e
      } finally {
        ledger.posting.remove(correlationId, mine)
      }
    }
  }

  private suspend fun post(correlationId: String, title: String, goal: String): PeerSend {
    val prior = ledger.get(correlationId)
    if (prior != null && prior.posted) return PeerSend.Duplicate(prior.postedAt)
    val now = clock()
    val ref = prior?.ref ?: TaskCard.newRef()
    val deadline = now + deadlineMs
    ledger.put(ConnectorTask(correlationId, id, relayAgentId, ref, null, now, deadline, onRelay = prior?.onRelay == true))
    val card = TaskCard(
      ref = ref,
      title = title.ifBlank { "Task from Juno" }.take(200),
      goal = goal.take(GOAL_MAX),
      to = listOf(relayAgentId),
      deadline = Instant.ofEpochMilli(deadline).truncatedTo(ChronoUnit.SECONDS).toString(),
      output = "claims",
    )
    return try {
      val created = client.postTask(card)
      ledger.put(ConnectorTask(correlationId, id, relayAgentId, created.ref, created.taskId, now, deadline))
      PeerSend.Sent(now, Via.CONNECTOR)
    } catch (e: CancellationException) {
      throw e
    } catch (e: RelayException) {
      when {
        // 409 "ref … is already used by a live task" for our own ref: the card is on the relay (an earlier post of this
        // ask went through and its answer was lost, or another post of it won). Sent, never FAILED, and the ledger
        // entry stays so the pump finds its events and the ladder wakes it.
        e.status == 409 -> {
          val cur = ledger.get(correlationId)
          if (cur?.taskId != null) PeerSend.Duplicate(cur.postedAt)
          else {
            if (ledger.update(correlationId) { it.copy(onRelay = true) } == null) {
              ledger.put(ConnectorTask(correlationId, id, relayAgentId, ref, null, now, deadline, onRelay = true))
            }
            PeerSend.Sent(now, Via.CONNECTOR)
          }
        }
        e.rateLimited -> { ledger.dropUnposted(correlationId, ref); PeerSend.RateLimited(30_000) }
        e.unauthorized -> { ledger.dropUnposted(correlationId, ref); PeerSend.Failed(RELAY_REFUSED) }
        // The relay itself is down (5xx): same as unreachable.
        e.status >= 500 -> { ledger.dropUnposted(correlationId, ref); PeerSend.Failed(RELAY_UNREACHABLE) }
        else -> { ledger.dropUnposted(correlationId, ref); PeerSend.Failed("relay_error_${e.status}") }
      }
    } catch (_: IOException) {
      ledger.dropUnposted(correlationId, ref)
      PeerSend.Failed(RELAY_UNREACHABLE)
    }
  }

  /** Juno stopped waiting (hard timeout): no need to poll fast for it any more. A late result is still taken. */
  override suspend fun close(correlationId: String) = ledger.markDone(correlationId, clock())

  companion object {
    const val RELAY_UNREACHABLE = "relay_unreachable"
    const val RELAY_REFUSED = "relay_refused"
    const val GOAL_MAX = 4000
    /** Failures that mean the connector isn't usable right now: the text route may take the ask instead. */
    val UNAVAILABLE = setOf(RELAY_UNREACHABLE, RELAY_REFUSED)
  }
}

/**
 * A peer reached over the connector when that route exists, else by text. The connector is tried first; when the relay
 * can't be reached or refuses this phone, the same ask goes by text (when the agent has a text route).
 */
class RoutedPeer(override val id: String, private val connector: Peer?, private val text: Peer?) : Peer {
  /** What the last [send] used, for the debug log. */
  @Volatile var lastRoute: Via? = null; private set

  override suspend fun send(text: String, correlationId: String): PeerSend {
    val c = connector
    if (c != null) {
      val r = c.send(text, correlationId)
      if (!(r is PeerSend.Failed && r.reason in ConnectorAgent.UNAVAILABLE && this.text != null)) { lastRoute = Via.CONNECTOR; return r }
    }
    val t = this.text ?: return PeerSend.Failed("agent_unavailable")
    lastRoute = Via.TEXT
    return t.send(text, correlationId)
  }

  override suspend fun close(correlationId: String) {
    connector?.close(correlationId)
    text?.close(correlationId)
  }
}

/** Short task-card titles from the text Juno wrote. */
object ConnectorText {
  fun title(text: String): String {
    val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("From Atall", ignoreCase = true) } ?: text.trim()
    val one = line.replace(Regex("\\s+"), " ")
    return if (one.length <= 80) one else one.take(79).trimEnd() + "…"
  }
}

/** What the pump did with one relay event (for the debug log). */
data class PumpNote(val cursor: String, val type: String, val agentId: String, val outcome: String)

/**
 * Turns relay events into what Juno understands: a result is a final, verified [PeerSignal.Message] with its claims; a
 * progress line, a question or an approval request is a status note (never an answer, m05). Events whose relay
 * countersignature doesn't verify, or that belong to no task this phone posted (the debug test task), are dropped.
 * Messages for Juno's asks go to [toJuno]; replies to direct @mentions to [toDirect].
 */
class ConnectorPump(
  private val ledger: ConnectorLedger,
  private val toJuno: suspend (PeerSignal) -> Unit,
  private val toDirect: suspend (peerId: String, PeerSignal.Message) -> Unit = { _, _ -> },
  private val clock: () -> Long = System::currentTimeMillis,
  private val isRunAsk: (String) -> Boolean = { "~a" in it },
  private val note: (PumpNote) -> Unit = {},
  /** C3: any verified event for one of our tasks (claim, progress, question, approval, result): the ladder's clock. */
  private val onActivity: (ConnectorTask, RelayEvent) -> Unit = { _, _ -> },
  /** C3: a verified approval request on one of our tasks, for the fingerprint gate ([ApprovalGate]). */
  private val onApproval: (ApprovalRequest) -> Unit = {},
) {
  suspend fun onEvents(events: List<RelayEvent>) { for (e in events) onEvent(e) }

  suspend fun onEvent(e: RelayEvent) {
    val cursor = e.cursor.contentOrNull ?: ""
    fun log(outcome: String) = note(PumpNote(cursor, e.type, e.agentId, outcome))
    if (e.type == RelayEvent.PRESENCE || e.type == RelayEvent.MANIFEST) return log("presence")
    val payload = e.payload as? JsonObject ?: JsonObject(emptyMap())
    val ref = payload.str("ref")
    val task = e.taskId?.let { ledger.byTaskId(it) } ?: ref?.let { ledger.byRef(e.agentId, it) } ?: return log("not ours")
    if (task.relayAgentId != e.agentId) return log("wrong agent")
    if (!e.verified) return log("dropped: countersignature didn't verify")
    // A card known only by its ref (409 on our own ref): its first verified event gives the task id (for wakes).
    if (task.taskId == null && e.taskId != null) ledger.update(task.cid) { if (it.taskId == null && it.ref == task.ref) it.copy(taskId = e.taskId) else it }
    ledger.update(task.cid) { it.copy(activityAt = maxOf(it.activityAt ?: 0L, clock())) }
    onActivity(task, e)
    if (e.type == RelayEvent.APPROVAL_REQUEST) ApprovalRequest.from(e, task.peerId, clock())?.let(onApproval)
    val signal = toSignal(e, payload, task, cursor) ?: return log("ignored")
    if (signal.final) ledger.markDone(task.cid, clock())
    if (isRunAsk(task.cid)) toJuno(signal) else toDirect(task.peerId, signal)
    log(if (signal.final) "result claims=${signal.claims.size}" else "status ${signal.status}")
  }

  private fun toSignal(e: RelayEvent, p: JsonObject, task: ConnectorTask, cursor: String): PeerSignal.Message? {
    val at = e.at?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: clock()
    fun status(kind: String, text: String) = PeerSignal.Message(
      task.peerId, "relay:$cursor", text, at, task.cid, status = kind, via = Via.CONNECTOR, verified = true,
    )
    return when (e.type) {
      // Only progress with something to say. The claim ("grok started working on #ref") and empty updates add nothing.
      RelayEvent.PROGRESS -> if (p["claimed"].bool()) null else p.str("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { status("progress", it) }
      RelayEvent.QUESTION -> status("question", "Asks: " + (p.str("text") ?: "a question"))
      RelayEvent.APPROVAL_REQUEST -> status("approval", "Wants your OK: " + (p.str("action") ?: "an action"))
      RelayEvent.RESULT -> {
        val summary = p.str("summary").orEmpty().trim()
        val claims = (p["claims"] as? JsonArray).orEmpty().mapNotNull { claimOf(it as? JsonObject ?: return@mapNotNull null) }
        val text = summary.ifEmpty { claims.joinToString("\n") { "• " + it.text } }.ifEmpty { if (p.str("status") == "declined") "Declined." else "Done." }
        PeerSignal.Message(
          task.peerId, "relay:res:" + (p.str("result_id") ?: cursor), text, at, task.cid,
          via = Via.CONNECTOR, verified = true, final = true, claims = claims,
        )
      }
      else -> null
    }
  }

  private fun claimOf(o: JsonObject): SourcedClaim? {
    val text = o.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val sources = (o["sources"] as? JsonArray).orEmpty().mapNotNull { s ->
      val so = s as? JsonObject ?: return@mapNotNull null
      so.str("url")?.let { ClaimSource(it, so.str("title")) }
    }
    return SourcedClaim(text, (o["confidence"] as? JsonPrimitive)?.doubleOrNull, o.str("kind"), sources)
  }

  private fun kotlinx.serialization.json.JsonElement?.bool() = (this as? JsonPrimitive)?.contentOrNull == "true"
  private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

/**
 * Which agents the connector can reach now: the phone is paired and the agent has a live (not revoked) connection on
 * the relay. [agents] is null until the first successful refresh; then a connector route exists only for listed agents.
 */
class ConnectorDirectory(private val client: RelayClient) {
  @Volatile var agents: List<RelayAgent>? = null; private set
  @Volatile var lastError: Throwable? = null; private set

  val paired: Boolean get() = client.paired

  /** Re-reads GET /phone/agents. Keeps the last list when the relay can't be reached; clears it on 401 (revoked). */
  suspend fun refresh(): List<RelayAgent>? {
    if (!client.paired) { agents = null; return null }
    return try {
      client.agents().also { agents = it; lastError = null }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      lastError = e
      if (e is RelayException && e.unauthorized) agents = emptyList()
      agents
    }
  }

  fun connected(relayAgentId: String?): Boolean =
    relayAgentId != null && client.paired && agents.orEmpty().any { it.agentId.equals(relayAgentId, ignoreCase = true) }
}
