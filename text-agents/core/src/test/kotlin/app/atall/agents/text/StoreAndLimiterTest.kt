package app.atall.agents.text

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAndLimiterTest {
  private val a = AgentId("wren")
  private val b = AgentId("qbit")

  private fun tmp(): File = Files.createTempDirectory("ta").toFile()

  private fun entry(i: Int, agent: AgentId = a) = LogEntry("id$i", agent, Direction.IN, "t$i", i.toLong())

  @Test fun fileStoreRoundTripsEverything() {
    val dir = tmp()
    val s = FileTextAgentStore(dir)
    val cfg = AgentConfig(a, "Wren", "+14085550101", listOf("Wren AI"))
    s.saveAgents(listOf(cfg))
    s.append(entry(1))
    s.append(LogEntry("o1", a, Direction.OUT, "q", 2, Via.DRAFT, correlationId = "c1"))
    s.append(entry(3, b).copy(correlation = Correlation("c1", Confidence.HIGH, CorrelationReason.TAG, 0)))
    val r = OutstandingRequest("c1", a, "q", "q", 2, Via.DRAFT, RequestState.AWAITING_USER_SEND, 99, "abcd")
    s.saveRequests(listOf(r))
    s.addKey("k1", 1); s.addKey("k2", 2); s.addKey("k1", 3)
    s.saveMeta(mapOf("wm:wren" to 5L))

    val t = FileTextAgentStore(dir)
    assertEquals(listOf(cfg), t.agents())
    assertEquals(3, t.log(null, 10).size)
    assertEquals(listOf("id1", "o1"), t.log(a, 10).map { it.id })
    assertEquals(Via.DRAFT, t.log(a, 10)[1].via)
    assertEquals(CorrelationReason.TAG, t.log(b, 1)[0].correlation!!.reason)
    assertEquals(listOf(r), t.requests())
    assertEquals(listOf("k2", "k1"), t.keys().keys.toList())
    assertEquals(3L, t.keys()["k1"])
    assertEquals(5L, t.meta()["wm:wren"])
  }

  @Test fun logIsTrimmedAndLimitReturnsNewestOldestFirst() {
    val s = FileTextAgentStore(tmp())
    repeat(TextAgentStore.LOG_MAX + 1) { s.append(entry(it)) }
    val all = s.log(null, 100_000)
    assertEquals(TextAgentStore.LOG_KEEP, all.size)
    assertEquals("id${TextAgentStore.LOG_MAX}", all.last().id)
    assertEquals(listOf("id${TextAgentStore.LOG_MAX - 1}", "id${TextAgentStore.LOG_MAX}"), s.log(a, 2).map { it.id })
  }

  @Test fun keysAreCompactedKeepingTheNewest() {
    val s = FileTextAgentStore(tmp())
    repeat(2 * TextAgentStore.KEYS_MAX + 1) { s.addKey("k$it", it.toLong()) }
    val k = s.keys()
    assertEquals(TextAgentStore.KEYS_MAX, k.size)
    assertTrue(k.containsKey("k${2 * TextAgentStore.KEYS_MAX}"))
  }

  @Test fun survivesTornAndCorruptFiles() {
    val dir = tmp()
    File(dir, "log.jsonl").writeText("{\"id\":\"x\",\"agentId\":\"wren\",\"dir\":\"IN\",\"text\":\"ok\",\"at\":1}\n{\"id\":\"torn")
    File(dir, "agents.json").writeText("not json")
    File(dir, "keys.txt").writeText("1\tgood\nbad line\n2\t")
    val s = FileTextAgentStore(dir)
    assertTrue(s.agents().isEmpty())
    s.append(entry(2))
    assertEquals(listOf("x", "id2"), s.log(null, 10).map { it.id })
    assertEquals(setOf("good"), s.keys().keys)
    assertTrue(s.requests().isEmpty())
    assertTrue(s.meta().isEmpty())
  }

  @Test fun inMemoryStoreTrimsLikeTheFileStore() {
    val s = InMemoryStore()
    repeat(TextAgentStore.LOG_MAX + 1) { s.append(entry(it)) }
    assertEquals(TextAgentStore.LOG_KEEP, s.log(null, 100_000).size)
    repeat(TextAgentStore.KEYS_MAX + 5) { s.addKey("k$it", 0) }
    assertEquals(TextAgentStore.KEYS_MAX, s.keys().size)
  }

  // ------------------------------------------------------------------ rate limiting

  @Test fun minIntervalPerAgent() {
    val r = RateLimiter(RateLimiter.Limits(minIntervalMs = 3_000))
    assertNull(r.check(a, 0)); r.record(a, 0)
    val blocked = r.check(a, 1_000)!!
    assertEquals(RateScope.AGENT_INTERVAL, blocked.first)
    assertEquals(2_000L, blocked.second)
    assertNull(r.check(b, 1_000))
    assertNull(r.check(a, 3_000))
  }

  @Test fun perMinutePerHourAndGlobal() {
    val r = RateLimiter(RateLimiter.Limits(minIntervalMs = 0, perAgentPerMinute = 3, perAgentPerHour = 5, globalPerMinute = 4))
    for (t in listOf(0L, 1L, 2L)) { assertNull(r.check(a, t)); r.record(a, t) }
    assertEquals(RateScope.AGENT_MINUTE, r.check(a, 3)!!.first)
    assertEquals(59_997L, r.check(a, 3)!!.second)
    assertNull(r.check(b, 3)); r.record(b, 3)
    assertEquals(RateScope.GLOBAL_MINUTE, r.check(b, 4)!!.first)
    for (t in listOf(60_000L, 60_001L)) { assertNull(r.check(a, t)); r.record(a, t) }
    val hour = r.check(a, 120_000)
    assertNotNull(hour)
    assertEquals(RateScope.AGENT_HOUR, hour!!.first)
    assertNull(r.check(a, 3_600_000))
  }
}
