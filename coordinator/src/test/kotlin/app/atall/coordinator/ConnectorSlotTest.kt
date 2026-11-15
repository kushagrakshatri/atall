package app.atall.coordinator

import app.atall.coordinator.testing.Behavior
import app.atall.coordinator.testing.FakeLlm
import app.atall.coordinator.testing.FakePeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 2026-11-15 2:23 PM @all: Muse's card stayed "Queued" and never reached the relay. Muse's card from the 12:58 run
 * (task 3bvdd, INPUT_REQUIRED: Muse had asked a question) was still open, and the one-text-per-agent rule counted it, so
 * the new ask waited up to Muse's 2 h hard timeout. Cards correlate by task id, so only texts hold the slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorSlotTest {
  /** [inner]'s sends, reported the way the connector reports a posted card. */
  private class Card(private val inner: FakePeer, private val via: Via) : Peer {
    override val id = inner.id
    override suspend fun send(text: String, correlationId: String): PeerSend =
      when (val r = inner.send(text, correlationId)) { is PeerSend.Sent -> PeerSend.Sent(r.at, via); else -> r }
    override suspend fun close(correlationId: String) = inner.close(correlationId)
  }

  private fun rig(scope: TestScope, via: Via): Triple<Juno, FakePeer, FakeLlm> {
    val clock = { scope.testScheduler.currentTime }
    val nova = FakePeer("nova", scope.backgroundScope, clock) { Behavior.Silent }
    val scout = FakePeer("scout", scope.backgroundScope, clock) { Behavior.Silent }
    val llm = FakeLlm()
    var n = 0
    val juno = Juno(testRoster, mapOf("nova" to Card(nova, via), "scout" to scout), llm, InMemoryRunStore(), InMemoryScoreLog(), clock, JunoConfig(), newId = { "${++n}" })
    nova.sink = juno::ingest; scout.sink = juno::ingest
    llm.on("juno") { _, k -> if (k == 0) FakeLlm.tools(askAgent("Nova", "Find a table for 4?"), waitCall(max = 30)) else FakeLlm.tools(replyCall("ok")) }
    llm.on("blind") { _, _ -> FakeLlm.text("Saturday") }
    return Triple(juno, nova, llm)
  }

  @Test fun anOpenCardFromAnEarlierRunDoesNotQueueTheNextAsk() = runTest {
    val (juno, nova, _) = rig(this, Via.CONNECTOR)
    // The 12:58 run: Nova gets a card and never answers; the run moves on, its ask stays open for a late answer.
    val first = juno.startCoordinated("bring in Nova")
    async { juno.drive(first) }
    advanceTimeBy(1_000); runCurrent()
    assertEquals(AskStatus.PENDING, juno.state(first)!!.asks.single { it.agentId == "nova" }.status)
    // The 2:23 PM @all: Nova's ask goes out at once.
    val all = juno.startAll("Saturday dinner?")
    async { juno.drive(all) }
    advanceTimeBy(1_000); runCurrent()
    val ask = juno.state(all)!!.asks.single { it.agentId == "nova" }
    assertEquals(AskStatus.PENDING, ask.status)
    assertEquals(Via.CONNECTOR, ask.via)
    assertEquals(2, nova.sent.size)
  }

  @Test fun anOpenTextStillQueuesTheNextTextAsk() = runTest {
    val (juno, nova, _) = rig(this, Via.TEXT)
    val first = juno.startCoordinated("bring in Nova")
    async { juno.drive(first) }
    advanceTimeBy(1_000); runCurrent()
    val all = juno.startAll("Saturday dinner?")
    assertEquals(AskStatus.QUEUED, juno.state(all)!!.asks.single { it.agentId == "nova" }.status)
    assertEquals(1, nova.sent.size)
  }

  @Test fun aPausedAgentIsLeftOutOfAtAll() = runTest {
    val (juno, _, _) = rig(this, Via.CONNECTOR)
    // testRoster: Hermes is paused (as kDot is now). @all asks only the active agents and Juno.
    val all = juno.startAll("Saturday dinner?")
    assertEquals(setOf("juno", "scout", "nova"), juno.state(all)!!.asks.map { it.agentId }.toSet())
    assertIs<RunState>(juno.state(all))
  }
}
