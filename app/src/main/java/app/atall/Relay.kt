package app.atall

import android.content.Context
import android.os.Build
import app.atall.core.AgentProfile
import app.atall.core.AgentRegistry
import app.atall.core.ConnectorAccess
import app.atall.core.ConnectorRouting
import app.atall.core.DebugLog
import app.atall.core.RelayCopy
import app.atall.coordinator.PeerSignal
import app.atall.api.PeerDirectory
import app.atall.coordinator.PeerSend
import app.atall.coordinator.adapters.LadderNote
import app.atall.coordinator.adapters.ApprovalRequest
import app.atall.coordinator.adapters.CardPeer
import app.atall.coordinator.adapters.ConnectorTask
import app.atall.coordinator.adapters.FallbackLadder
import app.atall.coordinator.adapters.LadderPolicy
import app.atall.coordinator.adapters.WakeCopy
import app.atall.coordinator.adapters.WakeDispatcher
import app.atall.coordinator.adapters.WakeOutcome
import app.atall.coordinator.adapters.ConnectorAgent
import app.atall.coordinator.adapters.ConnectorDirectory
import app.atall.coordinator.adapters.ConnectorLedger
import app.atall.coordinator.adapters.ConnectorPump
import app.atall.relay.EventsPoller
import app.atall.relay.RelayAgent
import app.atall.relay.RelayClient
import app.atall.relay.RelayEvent
import app.atall.relay.RelayException
import app.atall.relay.TaskCard
import app.atall.relay.android.KeystoreDeviceKey
import app.atall.relay.android.PrefsRelayStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** Settings → Agents, relay part. */
data class RelayUi(
    val paired: Boolean = false,
    val agents: List<RelayAgent> = emptyList(),
    val loading: Boolean = false,
    /** A short, plain problem line, or null. */
    val problem: String? = null,
    /** The relay refused this phone (revoked, or paired with another relay): offer "Connect again". */
    val reconnect: Boolean = false,
)

/** The debug "Send test task to all" run. */
data class TestTaskUi(
    val ref: String,
    val status: String,
    val events: List<RelayEvent> = emptyList(),
    val running: Boolean = true,
)

/**
 * The phone side of the Atall relay: pairing with the owner code, the connected agents, revoking, the debug test task,
 * and (C2) the connector Juno uses. One events poller feeds everything: Juno's asks and direct @mentions (through
 * [ConnectorPump]) and the debug test task. It polls every few seconds while a connector task is open, else slowly.
 * Signing uses a non-exportable Android Keystore key.
 */
class Relay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: DebugLog,
    dataDir: File,
    private val registry: AgentRegistry,
    /** C3: per-agent wake settings (Settings → Agents → Wake-up). */
    val wake: WakeSettings = WakeSettings(context),
    private val peerDirectory: () -> PeerDirectory? = { null },
) {
    private val key = KeystoreDeviceKey()
    private val store = PrefsRelayStore(context)
    val client = RelayClient(key = key, store = store)
    val ledger = ConnectorLedger(File(dataDir, "connector-tasks.json"))
    val directory = ConnectorDirectory(client)

    private val _ui = MutableStateFlow(RelayUi(paired = store.paired))
    val ui: StateFlow<RelayUi> = _ui
    private val _test = MutableStateFlow<TestTaskUi?>(null)
    val test: StateFlow<TestTaskUi?> = _test
    private var testJob: Job? = null
    @Volatile private var testTaskId: String? = null
    private val connectorOnlyRuns = ConcurrentHashMap.newKeySet<String>()
    private val poller = EventsPoller(client, store) { page -> onEvents(page) }
    private var pump: ConnectorPump? = null
    private var loop: Job? = null
    /** Wakes the loop early (a task was just posted). */
    private val kick = MutableStateFlow(0L)
    /** Called after the connected agents change, so the registry and the thread roster follow. */
    var onAgentsChanged: () -> Unit = {}

    // ---- C3: wake-ups, the fallback ladder, approvals --------------------------------------------------------------
    /** When the relay started failing (null while it answers). A minute of this is an outage (§8). */
    @Volatile var downSince: Long? = null; private set
    /** An agent asked for approval on one of our tasks. */
    var onApproval: (ApprovalRequest) -> Unit = {}
    /** The ladder's text rung: sends the ask for this task by text (Graph wires it to Juno). */
    var fallbackText: suspend (ConnectorTask, String) -> Boolean = { _, _ -> false }
    /** The whole ask's text for the tap rung. */
    var fullAsk: suspend (cid: String) -> String? = { null }
    var hasTextRoute: (peerId: String) -> Boolean = { false }
    /** A wake or nudge reached nobody (Graph sends a webhook agent's to Sentry). */
    var onWakeProblem: (LadderNote, runId: String?) -> Unit = { _, _ -> }
    private val sinks by lazy {
        AndroidWakeSinks(context, wake, client, object : PeerDirectory {
            override fun peers(): List<app.atall.api.PeerInfo> = peerDirectory()?.peers().orEmpty()
            override fun agent(peerId: String): app.atall.agents.text.Agent? = peerDirectory()?.agent(peerId)
        }, subscribed = { id -> directory.agents.orEmpty().any { it.agentId.equals(id, true) && it.events == true } },
            names = { id -> registry.get(id)?.name ?: id }, log = log)
    }
    private val ladder by lazy {
        FallbackLadder(
            ledger, WakeDispatcher({ wake.config(it) }, sinks) { hasTextRoute(it) },
            policyFor = { t ->
                val cfg = wake.config(t.relayAgentId)
                val p = LadderPolicy.of(cfg, ConnectorRouting.timeouts(t.relayAgentId).softMs)
                // Direct @mentions: wake-ups only (their text fallback is the Router's own retry).
                if ("~a" in t.cid) p else p.copy(fallbackAfterMs = null)
            },
            hasTextRoute = { hasTextRoute(it) },
            byText = { t, why -> fallbackText(t, why) },
            tapFull = { t, _ -> val full = fullAsk(t.cid); full != null && sinks.tap(t, WakeCopy.prompt(t.ref), full) == WakeOutcome.TAP },
            note = { n ->
                val runId = n.cid.substringBefore('~').takeIf { "~" in n.cid }
                // A wake that reached nobody is an error line (Sentry breadcrumb at ERROR) and, for a webhook agent, an event.
                log.add("wake", n.step.substringBefore(' ') + if (n.failed) ".error" else "", runId, "#${n.ref} ${n.relayAgentId}: ${n.step}")
                if (n.failed) runCatching { onWakeProblem(n, runId) }
            },
            // A paused agent (kDot after his ChatGPT Pro ended) gets no wake, nudge, text or tap for its open cards.
            skip = { t -> registry.get(t.peerId)?.paused != false },
        )
    }

    /**
     * What Juno and the Router use: the connector route for an agent, when there is one right now. C3.1: an agent set to
     * Full text (Hark by default) has none, so its asks go by text at once ([app.atall.core.FullTextRouting]).
     */
    val access: ConnectorAccess by lazy { app.atall.core.FullTextRouting(connected) { wake.config(it) } }

    /** Connected on the relay (paired, live connection), whatever the agent's route. */
    private val connected = object : ConnectorAccess {
        override fun relayIdFor(p: AgentProfile): String? {
            val ids = directory.agents?.map { it.agentId } ?: return null
            return if (directory.paired) ConnectorRouting.relayIdOf(p, ids) else null
        }

        override fun peer(p: AgentProfile): CardPeer? {
            val relayId = relayIdFor(p) ?: return null
            val agent = ConnectorAgent(p.id, relayId, client, ledger, ConnectorRouting.timeouts(relayId).hardMs)
            return object : CardPeer by agent {
                override suspend fun sendCard(correlationId: String, title: String, goal: String): PeerSend {
                    // Debug "Pretend the relay is down": the post fails the way an unreachable relay does.
                    if (wake.simulateOutage) return PeerSend.Failed(ConnectorAgent.RELAY_UNREACHABLE)
                    return agent.sendCard(correlationId, title, goal).also { kick.value = System.currentTimeMillis() }
                }
            }
        }

        override fun connectorOnly(runId: String): Boolean = runId in connectorOnlyRuns
    }

    /** Story 1 (debug): this run never falls back to text. */
    fun markConnectorOnly(runId: String) { connectorOnlyRuns += runId }

    /**
     * Starts the events loop. [toJuno] takes signals for Juno's asks, [toDirect] answers to direct @mentions. Also keeps
     * the connected-agents list fresh (every minute), which is what routing reads.
     */
    fun start(toJuno: suspend (PeerSignal) -> Unit, toDirect: suspend (String, PeerSignal.Message) -> Unit) {
        if (loop != null) return
        pump = ConnectorPump(ledger, toJuno, toDirect, note = { n -> log.add("connector", "event", null, "#${n.cursor} ${n.type} ${n.agentId}: ${n.outcome}") },
            onApproval = { r -> runCatching { onApproval(r) } })
        loop = scope.launch {
            var lastAgents = 0L
            var backoff = FAST_MS
            while (isActive) {
                val now = System.currentTimeMillis()
                var wait = SLOW_MS
                if (store.paired) {
                    if (now - lastAgents >= AGENTS_EVERY_MS || directory.agents == null) { refreshAgentsNow(); lastAgents = now }
                    val busy = ledger.open(now).isNotEmpty() || testJob?.isActive == true
                    ConnectorService.keep(context, busy) { waitingNames(now) }
                    wait = try {
                        if (wake.simulateOutage) throw IOException("simulated relay outage (debug)")
                        val got = poller.pollOnce()
                        backoff = FAST_MS
                        downSince = null
                        when { got.isNotEmpty() -> 0L; busy -> FAST_MS; else -> SLOW_MS }
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                        if (downSince == null) downSince = System.currentTimeMillis()
                        log.add("connector", "poll.error", null, describe(e))
                        // While tasks are open, keep checking often enough for the ladder's one-minute outage rule.
                        backoff = (backoff * 2).coerceAtMost(if (busy) 15_000L else SLOW_MS)
                        backoff
                    }
                    // C3: wake new tasks, nudge, and fall back by text (§8). Never lets a failure stop the loop.
                    try { ladder.tick(downSince) } catch (e: CancellationException) { throw e } catch (e: Throwable) { log.add("wake", "ladder.error", null, describe(e)) }
                }
                if (wait > 0) {
                    val k = kick.value
                    withTimeoutOrNull(wait) { kick.first { it != k } }
                }
            }
        }
    }

    private fun waitingNames(now: Long): List<String> = ledger.open(now).mapNotNull { registry.get(it.peerId)?.name }.distinct()

    private suspend fun onEvents(page: List<RelayEvent>) {
        val test = testTaskId
        if (test != null) {
            val mine = page.filter { it.taskId == test }
            mine.forEach { log.add("relay", "event", null, "type=${it.type} agent=${it.agentId} verified=${it.verified} task=$test") }
            if (mine.isNotEmpty()) _test.value = _test.value?.let { t ->
                val events = t.events + mine
                val results = events.count { it.type == RelayEvent.RESULT }
                t.copy(events = events, status = "Sent to all · $results result${if (results == 1) "" else "s"}")
            }
        }
        pump?.onEvents(page.filter { it.taskId == null || it.taskId != test })
    }

    /** Pairs this phone. Returns null when it worked, else a short line to show under the code field. */
    suspend fun pair(ownerCode: String): String? {
        if (ownerCode.isBlank()) return "Enter your owner code."
        return try {
            val device = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").ifBlank { "Android phone" }
            val p = client.pair(ownerCode, device.take(100))
            log.add("relay", "paired", null, "device=${p.deviceId} hw=${key.hardwareBacked()}")
            _ui.value = RelayUi(paired = true)
            refreshAgents()
            null
        } catch (e: Throwable) {
            log.add("relay", "pair.error", null, describe(e))
            when {
                e is RelayException && e.rateLimited -> "Too many tries. Wait 10 minutes."
                e is RelayException && (e.status == 401 || e.status == 403) -> "That code didn't work."
                e is IOException -> "Couldn't reach the relay. Check your connection."
                else -> "Couldn't connect. Try again in a bit."
            }
        }
    }

    fun refreshAgents() {
        if (!store.paired) return
        scope.launch { refreshAgentsNow() }
    }

    private suspend fun refreshAgentsNow() {
        _ui.value = _ui.value.copy(loading = true)
        val before = directory.agents?.map { it.agentId }?.toSet()
        val list = directory.refresh()
        val e = directory.lastError
        _ui.value = if (e == null && list != null) {
            RelayUi(paired = true, agents = list.sortedBy { RelayCopy.agentName(it.agentId, it.clientName).lowercase() })
        } else {
            log.add("relay", "agents.error", null, e?.let(::describe) ?: "no list")
            _ui.value.copy(loading = false, problem = e?.let(::problemLine), reconnect = e is RelayException && e.unauthorized)
        }
        val after = directory.agents?.map { it.agentId }?.toSet()
        if (after != before) {
            log.add("connector", "agents", null, "connected=" + (after?.sorted()?.joinToString(",") ?: "unknown"))
            onAgentsChanged()
        }
    }

    /** Cuts one agent connection off. The list refreshes after. */
    fun revoke(agent: RelayAgent) {
        scope.launch {
            try {
                client.revoke(agent.connId)
                log.add("relay", "revoked", null, "agent=${agent.agentId} conn=${agent.connId}")
                _ui.value = _ui.value.copy(agents = _ui.value.agents.filterNot { it.connId == agent.connId }, problem = null)
            } catch (e: Throwable) {
                log.add("relay", "revoke.error", null, describe(e))
                _ui.value = _ui.value.copy(problem = "Couldn't revoke ${RelayCopy.agentName(agent.agentId, agent.clientName)}. " + (problemLine(e) ?: ""))
            }
            refreshAgentsNow()
        }
    }

    /** Debug: posts a signed test task to all agents and shows this task's events as they come in (for 10 minutes). */
    fun sendTestTask() {
        if (!store.paired || testJob?.isActive == true) return
        val ref = TaskCard.newRef()
        _test.value = TestTaskUi(ref, "Sending…")
        testJob = scope.launch {
            val card = TaskCard(
                ref = ref,
                title = "Atall connection test",
                goal = "This is a connection test from the Atall app. Post a one-line result saying which agent you are. Nothing else is needed.",
                to = listOf(TaskCard.ALL),
                deadline = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString(),
            )
            val taskId = try {
                client.postTask(card).taskId
            } catch (e: Throwable) {
                log.add("relay", "test.error", null, describe(e))
                _test.value = TestTaskUi(ref, "Couldn't send. ${problemLine(e) ?: ""}".trim(), running = false)
                return@launch
            }
            testTaskId = taskId
            kick.value = System.currentTimeMillis()
            log.add("relay", "test.sent", null, "task=$taskId ref=$ref")
            _test.value = _test.value?.copy(status = "Sent to all · waiting")
            // The events loop polls fast while this runs and shows this task's events.
            delay(10 * 60_000)
            _test.value = _test.value?.copy(running = false)
        }
    }

    fun closeTest() { testJob?.cancel(); testJob = null; testTaskId = null; _test.value = null }

    private fun problemLine(e: Throwable): String? = when {
        e is RelayException && e.unauthorized -> "The relay didn't accept this phone. Check the phone's clock, or connect again."
        e is IOException -> "Couldn't reach the relay."
        else -> "Something went wrong."
    }

    private fun describe(e: Throwable) = when (e) {
        is RelayException -> "status=${e.status} error=${e.error}"
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    companion object {
        /** While a connector task is open. */
        const val FAST_MS = 3_000L
        /** Otherwise: late results, presence. */
        const val SLOW_MS = 60_000L
        const val AGENTS_EVERY_MS = 60_000L
    }
}
