@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.agents.text

import app.atall.agents.text.testing.FakeAgent
import app.atall.agents.text.testing.FakeMessagesApp
import app.atall.agents.text.testing.ManualClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAgentAndConcurrencyTest {
  @Test fun fakeAgentScriptsCorrelatedReplies() = runTest {
    val agent: Agent = FakeAgent(AgentId("echo"), respond = { listOf("part1: $it", "part2") })
    val got = mutableListOf<AgentMessage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.incoming.toList(got) }
    assertTrue(agent.send("hi", "c1") is SendResult.Sent)
    assertTrue(agent.send("hi", "c1") is SendResult.Duplicate)
    assertEquals(listOf("part1: hi", "part2"), got.map { it.text })
    assertEquals(listOf(0, 1), got.map { it.correlation!!.part })
    assertEquals(3, agent.history().size)
    agent.close("c1")
    assertTrue(agent.outstanding().isEmpty())
  }

  /** The listener thread posting while coordinator threads send: no lost or duplicated log lines, no exceptions. */
  @Test fun concurrentPostsAndSendsStayConsistent() {
    val clock = ManualClock()
    val phone = FakeMessagesApp(clock)
    val store = InMemoryStore()
    val hub = TextAgentHub(store, phone.Drafts(), clock,
      TextAgentConfig(rateLimits = RateLimiter.Limits(minIntervalMs = 0, perAgentPerMinute = 10_000, perAgentPerHour = 10_000, globalPerMinute = 10_000), maxOutstandingPerAgent = 10_000),
      java.util.Random(1), Dispatchers.Unconfined)
    phone.conversation("Wren", "+14085550101")
    val agent = hub.register(AgentConfig(AgentId("wren"), "Wren", "+14085550101"))
    phone.attach(hub)
    phone.echoReplies = false // the fake phone itself is single-threaded; only the hub is under test
    phone.agentTexts("Wren", "start")
    val pool = Executors.newFixedThreadPool(4)
    val done = CountDownLatch(4)
    val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    pool.execute {
      try { repeat(300) { i -> clock.advance(1); phone.agentTexts("Wren", "m$i") } } catch (t: Throwable) { errors += t } finally { done.countDown() }
    }
    repeat(3) { w ->
      pool.execute {
        try { runBlocking { repeat(100) { i -> agent.send("s$w-$i", "c$w-$i") } } } catch (t: Throwable) { errors += t } finally { done.countDown() }
      }
    }
    assertTrue(done.await(60, TimeUnit.SECONDS))
    pool.shutdown()
    assertTrue(errors.toString(), errors.isEmpty())
    val log = store.log(null, 10_000)
    assertEquals(301, log.count { it.dir == Direction.IN })
    assertEquals(300, log.count { it.dir == Direction.OUT })
    assertEquals(log.size, log.map { it.id }.toSet().size)
  }
}
