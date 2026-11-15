package app.atall.relay

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End to end against a real relay (skipped unless ATALL_RELAY_URL and ATALL_OWNER_CODE are set). Pairs a software key,
 * reads agents, posts a phone-signed task, asks a test hook (ATALL_AGENT_HOOK, e.g. a local relay's
 * `/__agent?agent=kdot&task=`) to make agents answer, then polls, verifies countersignatures, acks and revokes.
 * Never point this at the production relay: it pairs a device and posts a task to everyone.
 */
class LiveRelayTest {
    private val url = System.getenv("ATALL_RELAY_URL")
    private val code = System.getenv("ATALL_OWNER_CODE")
    private val hook = System.getenv("ATALL_AGENT_HOOK")

    @Test fun pairPostPollVerifyAckRevoke() = runBlocking<Unit> {
        assumeTrue("set ATALL_RELAY_URL and ATALL_OWNER_CODE to run", !url.isNullOrBlank() && !code.isNullOrBlank())
        val store = InMemoryRelayStore()
        val client = RelayClient(url!!, SoftwareDeviceKey(), store)
        val p = client.pair(code!!, "JVM interop test")
        assertTrue(p.deviceId.startsWith("dev_"))

        val ref = TaskCard.newRef()
        val created = client.postTask(TaskCard(ref = ref, title = "Interop", goal = "Say which agent you are.", deadline = java.time.Instant.now().plusSeconds(900).toString(), output = "claims"))
        assertEquals(ref, created.ref)

        val answering = listOf("kdot", "duet")
        if (!hook.isNullOrBlank()) answering.forEach { a ->
            OkHttpClient().newCall(Request.Builder().url("$hook".replace("{agent}", a).replace("{task}", ref)).build()).execute().use { assertTrue(it.isSuccessful) }
        }

        val seen = mutableListOf<RelayEvent>()
        val poller = EventsPoller(client, store) { seen += it }
        repeat(5) { poller.pollOnce() }
        println("events: " + seen.joinToString { "${it.cursor}:${it.type}:${it.agentId}:${it.verified}" })
        assertTrue("every event carries a valid relay_sig", seen.isNotEmpty() && seen.all { it.verified })
        if (!hook.isNullOrBlank()) {
            val results = seen.filter { it.type == RelayEvent.RESULT && it.taskId == created.taskId }.map { it.agentId }.toSet()
            assertEquals(answering.toSet(), results)
        }
        assertTrue(store.cursorJson != null)

        val agents = client.agents()
        println("agents: " + agents.joinToString { "${it.agentId}/${it.connId}/${it.lastTool}/${it.lastSeen}" })
        agents.firstOrNull()?.let { a ->
            client.revoke(a.connId)
            assertTrue(client.agents().none { it.connId == a.connId })
        }
    }
}
