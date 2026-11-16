package app.atall.coordinator

import app.atall.coordinator.testing.FakeLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.9.1: every ask is handed to its transport exactly once. In v0.1.9 an ask stayed SENDING while its send call ran,
 * and a second flush (the drive loop and the bridge's 30 s sweep) sent it again: every connector ask was posted twice.
 */
class SendOnceTest {
  /** A peer whose sends block until [gate] opens, counting calls per ask. */
  private class SlowPeer(override val id: String, val gate: CompletableDeferred<Unit>) : Peer {
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val entered = AtomicInteger()
    override suspend fun send(text: String, correlationId: String): PeerSend {
      calls.computeIfAbsent(correlationId) { AtomicInteger() }.incrementAndGet()
      entered.incrementAndGet()
      gate.await()
      return PeerSend.Sent(System.currentTimeMillis(), Via.CONNECTOR)
    }
  }

  @Test fun concurrentFlushesSendEachAskOnce() = runBlocking(Dispatchers.Default) {
    val gate = CompletableDeferred<Unit>()
    val scout = SlowPeer("scout", gate)
    val nova = SlowPeer("nova", gate)
    var n = 0
    val juno = Juno(testRoster, mapOf("scout" to scout, "nova" to nova), FakeLlm(), InMemoryRunStore(), config = JunoConfig(), newId = { "${++n}" })
    val runId = juno.startAll("Saturday plans?", testRoster.activePeers)
    // First flush: both sends start and block on the gate.
    val first = async { juno.sweep() }
    withTimeout(5_000) { while (scout.entered.get() < 1 || nova.entered.get() < 1) delay(5) }
    // Second and third flushes while the first is still sending (the sweep and the drive loop raced like this).
    val second = async { juno.sweep() }
    val third = async { juno.sweep() }
    // They return without sending (in v0.1.9 they sent again and blocked on the gate here).
    val returned = withTimeoutOrNull(3_000) { second.await(); third.await(); true } ?: false
    gate.complete(Unit)
    first.await(); second.await(); third.await()
    juno.sweep()
    assertTrue("later flushes returned while the first was sending", returned)
    assertEquals(1, scout.calls.size); assertEquals(1, scout.calls.values.sumOf { it.get() })
    assertEquals(1, nova.calls.size); assertEquals(1, nova.calls.values.sumOf { it.get() })
    val st = juno.state(runId)!!
    assertEquals(setOf(AskStatus.PENDING), st.asks.filter { it.agentId != testRoster.main.id }.map { it.status }.toSet())
  }

  @Test fun anAskThatFailedToSendCanBeFlushedAgainLater() = runBlocking(Dispatchers.Default) {
    // The in-flight mark is cleared after each flush, so a re-queued ask (SENDING again) is sent by the next flush.
    val gate = CompletableDeferred(Unit)
    val scout = SlowPeer("scout", gate)
    var n = 0
    val juno = Juno(testRoster, mapOf("scout" to scout), FakeLlm(), InMemoryRunStore(), config = JunoConfig(), newId = { "${++n}" })
    juno.startAll("hi", listOf(testRoster.byId("scout")!!))
    juno.sweep(); juno.sweep()
    assertEquals(1, scout.calls.values.sumOf { it.get() })
  }
}
