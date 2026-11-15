package app.atall

import android.content.Context
import android.os.Build
import app.atall.core.DebugLog
import app.atall.core.RelayCopy
import app.atall.relay.EventsPoller
import app.atall.relay.RelayAgent
import app.atall.relay.RelayClient
import app.atall.relay.RelayEvent
import app.atall.relay.RelayException
import app.atall.relay.TaskCard
import app.atall.relay.android.KeystoreDeviceKey
import app.atall.relay.android.PrefsRelayStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

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
 * The phone side of the Atall relay (C1): pairing with the owner code, the connected agents, revoking, and a debug test
 * task. Not wired into Juno yet (C2). Signing uses a non-exportable Android Keystore key.
 */
class Relay(context: Context, private val scope: CoroutineScope, private val log: DebugLog) {
    private val key = KeystoreDeviceKey()
    private val store = PrefsRelayStore(context)
    val client = RelayClient(key = key, store = store)

    private val _ui = MutableStateFlow(RelayUi(paired = store.paired))
    val ui: StateFlow<RelayUi> = _ui
    private val _test = MutableStateFlow<TestTaskUi?>(null)
    val test: StateFlow<TestTaskUi?> = _test
    private var testJob: Job? = null

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
        scope.launch {
            _ui.value = _ui.value.copy(loading = true)
            _ui.value = try {
                RelayUi(paired = true, agents = client.agents().sortedBy { RelayCopy.agentName(it.agentId, it.clientName).lowercase() })
            } catch (e: Throwable) {
                log.add("relay", "agents.error", null, describe(e))
                _ui.value.copy(loading = false, problem = problemLine(e), reconnect = e is RelayException && e.unauthorized)
            }
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
            refreshAgents()
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
            log.add("relay", "test.sent", null, "task=$taskId ref=$ref")
            _test.value = _test.value?.copy(status = "Sent to all · waiting")
            val poller = EventsPoller(client, store) { page ->
                val mine = page.filter { it.taskId == taskId }
                mine.forEach { log.add("relay", "event", null, "type=${it.type} agent=${it.agentId} verified=${it.verified} task=$taskId") }
                if (mine.isNotEmpty()) _test.value = _test.value?.let { t ->
                    val events = t.events + mine
                    val results = events.count { it.type == RelayEvent.RESULT }
                    t.copy(events = events, status = "Sent to all · $results result${if (results == 1) "" else "s"}")
                }
            }
            val until = System.currentTimeMillis() + 10 * 60_000
            while (isActive && System.currentTimeMillis() < until) {
                try { poller.pollOnce() } catch (e: Throwable) {
                    log.add("relay", "poll.error", null, describe(e))
                    _test.value = _test.value?.copy(status = problemLine(e) ?: "Couldn't check for results")
                }
                delay(3_000)
            }
            _test.value = _test.value?.copy(running = false)
        }
    }

    fun closeTest() { testJob?.cancel(); testJob = null; _test.value = null }

    private fun problemLine(e: Throwable): String? = when {
        e is RelayException && e.unauthorized -> "The relay didn't accept this phone. Check the phone's clock, or connect again."
        e is IOException -> "Couldn't reach the relay."
        else -> "Something went wrong."
    }

    private fun describe(e: Throwable) = when (e) {
        is RelayException -> "status=${e.status} error=${e.error}"
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }
}
