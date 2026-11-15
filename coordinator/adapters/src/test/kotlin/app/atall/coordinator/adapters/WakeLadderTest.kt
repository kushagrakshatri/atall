package app.atall.coordinator.adapters

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C3: the wake dispatcher (best path per agent, backup when it can't be used) and the §8 fallback ladder. */
class WakeLadderTest {
  private val dir: File = Files.createTempDirectory("atall-c3").toFile()
  @After fun down() { dir.deleteRecursively() }

  private var now = 1_000_000L
  private val MIN = 60_000L

  /** Records every sink call; each path answers what [answers] says (default SENT). */
  private inner class Sinks : WakeSinks {
    val calls = CopyOnWriteArrayList<String>()
    val answers = HashMap<WakeKind, WakeOutcome>()
    private fun a(k: WakeKind) = answers[k] ?: WakeOutcome.SENT
    override suspend fun text(task: ConnectorTask, text: String) = a(WakeKind.TEXT).also { calls += "text ${task.relayAgentId} $text" }
    override suspend fun webhook(task: ConnectorTask) = a(WakeKind.WEBHOOK).also { calls += "webhook ${task.relayAgentId} #${task.ref}" }
    override suspend fun events(task: ConnectorTask, first: Boolean) = a(WakeKind.EVENTS).also { calls += "events ${task.relayAgentId} first=$first" }
    override suspend fun tap(task: ConnectorTask, prompt: String, full: String?) = a(WakeKind.TAP).also { calls += "tap ${task.relayAgentId} $prompt" }
  }

  private val textRoutes = setOf("p-hark", "p-kdot")

  /** These tests exercise the C3 "Atall #ref" text wake on Hark (C3.1 made Full text Hark's default; Text stays pickable). */
  private fun c3Wakes(relayId: String): WakeConfig =
    if (relayId == "hark") WakeConfig(WakeKind.TEXT, backup = WakeKind.TAP) else WakeDefaults.forAgent(relayId)
  private fun task(cid: String, peer: String, relay: String, ref: String = cid.takeLast(4).padStart(4, 'x')) =
    ConnectorTask(cid, peer, relay, ref, "tsk_$cid", postedAt = now, deadlineAt = now + 60 * MIN)

  private fun ladder(ledger: ConnectorLedger, sinks: Sinks, texted: MutableList<String>, tapped: MutableList<String> = CopyOnWriteArrayList(), textOk: Boolean = true): FallbackLadder {
    val cfg: (String) -> WakeConfig = ::c3Wakes
    val d = WakeDispatcher(cfg, sinks) { it in textRoutes }
    return FallbackLadder(
      ledger, d,
      policyFor = { t -> LadderPolicy.of(cfg(t.relayAgentId), softTimeoutMs = if (t.relayAgentId == "duet") 15 * MIN else 6 * MIN) },
      hasTextRoute = { it in textRoutes },
      byText = { t, why -> texted += "${t.cid} $why"; textOk },
      tapFull = { t, why -> tapped += "${t.cid} $why"; true },
      clock = { now },
    )
  }

  @Test fun eachAgentIsWokenByItsBestPath() = runBlocking {
    val sinks = Sinks()
    val d = WakeDispatcher(::c3Wakes, sinks) { it in textRoutes }
    assertEquals(WakeKind.TEXT, d.wake(task("r~a0", "p-hark", "hark", "k3f9"), 1).kind)
    assertEquals(WakeKind.EVENTS, d.wake(task("r~a1", "p-kdot", "kdot"), 1).kind)
    assertEquals(WakeKind.WEBHOOK, d.wake(task("r~a2", "p-grok", "grok", "g7h8"), 1).kind)
    assertEquals(WakeKind.SCHEDULE, d.wake(task("r~a3", "p-duet", "duet"), 1).kind)
    assertEquals(WakeKind.SCHEDULE, d.wake(task("r~a4", "p-muse", "muse"), 1).kind)
    // The nudge goes by the backup path: a tap for Duet and Muse, a text for kDot.
    assertEquals(WakeKind.TAP, d.wake(task("r~a3", "p-duet", "duet"), 2).kind)
    assertEquals(WakeKind.TEXT, d.wake(task("r~a1", "p-kdot", "kdot"), 2).kind)
    // The wake text carries no content: only "Atall #ref".
    assertTrue(sinks.calls.contains("text hark Atall #k3f9"))
    assertTrue(sinks.calls.contains("tap duet Check your Atall inbox for #${task("r~a3", "p-duet", "duet").ref}"))
  }

  @Test fun anUnusablePathFallsToTheBackup() = runBlocking {
    val sinks = Sinks()
    sinks.answers[WakeKind.WEBHOOK] = WakeOutcome.UNAVAILABLE // no webhook set for Grok yet
    sinks.answers[WakeKind.EVENTS] = WakeOutcome.UNAVAILABLE  // kDot hasn't subscribed
    val d = WakeDispatcher(::c3Wakes, sinks) { it in textRoutes }
    val g = d.wake(task("r~a0", "p-grok", "grok"), 1)
    assertEquals(WakeKind.TAP, g.kind)
    assertEquals(listOf(WakeKind.WEBHOOK, WakeKind.TAP), g.tried.map { it.first })
    val k = d.wake(task("r~a1", "p-kdot", "kdot"), 1)
    assertEquals(WakeKind.TEXT, k.kind)
    assertEquals(WakeOutcome.SENT, k.outcome)
    // A text route the agent doesn't have is never used.
    val custom = WakeDispatcher({ WakeConfig(WakeKind.TEXT) }, sinks) { false }
    assertEquals(WakeOutcome.UNAVAILABLE, custom.wake(task("r~a2", "p-grok", "grok"), 1).outcome)
  }

  @Test fun ladderWakesNudgesOnceThenFallsBackByText() = runBlocking {
    val ledger = ConnectorLedger(File(dir, "l.json"))
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, sinks, texted)
    ledger.put(task("run~a0", "p-hark", "hark", "h1k2"))

    l.tick()
    assertEquals(listOf("text hark Atall #h1k2"), sinks.calls.toList())
    assertEquals(1, ledger.get("run~a0")!!.wakes)
    l.tick() // nothing new yet
    assertEquals(1, sinks.calls.size)

    now += 3 * MIN // §8: nudge after max(2 × latency, 3 min); Hark's backup is a tap
    l.tick()
    assertEquals("tap hark Check your Atall inbox for #h1k2", sinks.calls.last())
    now += 2 * MIN
    l.tick()
    assertEquals("only two wakes", 2, sinks.calls.size)
    assertTrue(texted.isEmpty())

    now += 1 * MIN // 6 min with no claim: Hark's soft timeout → the same ask by text
    l.tick()
    assertEquals(listOf("run~a0 no_claim"), texted.toList())
    val t = ledger.get("run~a0")!!
    assertEquals("no_claim:text", t.fallback)
    assertTrue("fell-back tasks stop fast polling", ledger.open(now).isEmpty())
    now += 10 * MIN
    l.tick()
    assertEquals("never twice", 1, texted.size)
  }

  @Test fun aClaimStopsTheNudgeAndProgressKeepsItAlive() = runBlocking {
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, sinks, texted)
    ledger.put(task("run~a1", "p-kdot", "kdot"))
    l.tick()
    assertEquals(listOf("events kdot first=true"), sinks.calls.toList())
    now += 1 * MIN
    ledger.update("run~a1") { it.copy(activityAt = now) } // kDot claimed (the pump records it)
    now += 4 * MIN
    l.tick()
    assertEquals("no nudge after a claim", 1, sinks.calls.size)
    now += 3 * MIN
    ledger.update("run~a1") { it.copy(activityAt = now) } // progress
    now += 5 * MIN
    l.tick()
    assertTrue("progress within the soft timeout keeps it on the connector", texted.isEmpty())
    now += 1 * MIN
    l.tick()
    assertEquals(listOf("run~a1 stalled"), texted.toList())
  }

  @Test fun agentsOnTheirOwnScheduleAreNotMovedByTheClock() = runBlocking {
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val tapped = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, sinks, texted, tapped)
    ledger.put(task("run~a2", "p-duet", "duet", "d1d2").copy(deadlineAt = now + 240 * MIN))
    l.tick()
    assertTrue("SCHEDULE sends nothing", sinks.calls.isEmpty())
    assertEquals("schedule:sent", ledger.get("run~a2")!!.wakeVia)
    now += 3 * 60 * MIN // Duet's 3 h window
    l.tick()
    assertTrue(sinks.calls.isEmpty())
    now += 30 * MIN // window + 30 min: one tap nudge
    l.tick()
    assertEquals(listOf("tap duet Check your Atall inbox for #d1d2"), sinks.calls.toList())
    assertTrue(texted.isEmpty() && tapped.isEmpty())
  }

  @Test fun connectorOnlyAgentsGetTheWholeAskAsATapAtTheirSoftTimeout() = runBlocking {
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val tapped = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, sinks, texted, tapped)
    ledger.put(task("run~a3", "p-grok", "grok"))
    l.tick()
    assertEquals(1, sinks.calls.count { it.startsWith("webhook grok") })
    now += 3 * MIN
    l.tick() // nudge: Grok's backup is a tap
    now += 3 * MIN
    l.tick()
    assertTrue(texted.isEmpty())
    assertEquals(listOf("run~a3 no_claim"), tapped.toList())
    assertEquals("no_claim:tap", ledger.get("run~a3")!!.fallback)
  }

  @Test fun relayOutageSendsTextingAgentsAsksByTextAfterAMinute() = runBlocking {
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val tapped = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, sinks, texted, tapped)
    ledger.put(task("run~a0", "p-hark", "hark"))
    ledger.put(task("run~a1", "p-grok", "grok"))
    l.tick()
    ledger.update("run~a0") { it.copy(activityAt = now) } // Hark had claimed before the relay died
    val downSince = now + 30_000
    now += 60_000
    l.tick(relayDownSince = downSince)
    assertTrue("30 s of failures isn't an outage yet", texted.isEmpty())
    now += 31_000
    l.tick(relayDownSince = downSince)
    assertEquals(listOf("run~a0 relay_down"), texted.toList())
    assertEquals("relay_down:text", ledger.get("run~a0")!!.fallback)
    // Grok has no text route: during the outage nothing is woken or tapped (it couldn't read the card or answer).
    assertNull(ledger.get("run~a1")!!.fellBackAt)
    val wakesBefore = sinks.calls.size
    now += 2 * MIN
    l.tick(relayDownSince = downSince)
    assertEquals(wakesBefore, sinks.calls.size)
    assertTrue(tapped.isEmpty())
  }

  @Test fun aTextThatNeedsATapBecomesTheTapRung() = runBlocking {
    val ledger = ConnectorLedger(null)
    val tapped = CopyOnWriteArrayList<String>()
    val l = ladder(ledger, Sinks(), CopyOnWriteArrayList(), tapped, textOk = false)
    ledger.put(task("run~a0", "p-hark", "hark"))
    l.tick(relayDownSince = now - 2 * MIN)
    assertEquals(listOf("run~a0 relay_down"), tapped.toList())
    assertEquals("relay_down:tap", ledger.get("run~a0")!!.fallback)
  }

  @Test fun theLedgerKeepsWakeCountsAcrossProcessDeath() = runBlocking {
    val f = File(dir, "l2.json")
    val sinks = Sinks()
    ConnectorLedger(f).put(task("run~a9", "p-hark", "hark"))
    val l1 = ladder(ConnectorLedger(f), sinks, CopyOnWriteArrayList())
    l1.tick()
    // New process, same file: no second wake before the nudge time.
    val l2 = ladder(ConnectorLedger(f), sinks, CopyOnWriteArrayList())
    l2.tick()
    assertEquals(1, sinks.calls.size)
  }

  @Test fun theSettingsPickerOnlyOffersRealPaths() {
    assertEquals(listOf(WakeKind.EVENTS, WakeKind.FULL_TEXT, WakeKind.TEXT, WakeKind.TAP, WakeKind.OFF), WakeDefaults.choices("kdot", hasTextRoute = true))
    assertEquals(listOf(WakeKind.EVENTS, WakeKind.FULL_TEXT, WakeKind.TAP, WakeKind.OFF), WakeDefaults.choices("kdot", hasTextRoute = false))
    assertEquals(listOf(WakeKind.FULL_TEXT, WakeKind.TEXT, WakeKind.TAP, WakeKind.SCHEDULE, WakeKind.OFF), WakeDefaults.choices("hark", hasTextRoute = true))
    assertEquals(WakeKind.FULL_TEXT, WakeDefaults.choices("hark", hasTextRoute = false).first())
    assertEquals(listOf(WakeKind.WEBHOOK, WakeKind.TAP, WakeKind.SCHEDULE, WakeKind.OFF), WakeDefaults.choices("grok", hasTextRoute = false))
    assertEquals(listOf(WakeKind.SCHEDULE, WakeKind.TAP, WakeKind.OFF), WakeDefaults.choices("duet", hasTextRoute = false))
    assertEquals(180 * MIN, WakeDefaults.forAgent("duet").windowMs)
  }

  @Test fun fullTextIsHarksDefaultAndNeverWakes() = runBlocking {
    assertEquals(WakeConfig(WakeKind.FULL_TEXT), WakeDefaults.forAgent("hark"))
    val sinks = Sinks()
    val d = WakeDispatcher({ WakeDefaults.forAgent(it) }, sinks) { it in textRoutes }
    val a = d.wake(task("r~a0", "p-hark", "hark", "k3f9"), 1)
    assertEquals(WakeOutcome.UNAVAILABLE, a.outcome)
    assertTrue("no \"Atall #ref\" nudge, no tap", sinks.calls.isEmpty())
    val p = LadderPolicy.of(WakeDefaults.forAgent("hark"), softTimeoutMs = 6 * MIN)
    assertEquals(0, p.maxWakes)
    assertEquals(0L, p.fallbackAfterMs)
  }

  @Test fun aCardLeftOverFromBeforeTheSwitchGoesByTextAtOnceUnwoken() = runBlocking {
    // A Hark card posted under C3's Text wake; the user is now on C3.1 (Full text).
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val l = FallbackLadder(
      ledger, WakeDispatcher({ WakeDefaults.forAgent(it) }, sinks) { it in textRoutes },
      policyFor = { t -> LadderPolicy.of(WakeDefaults.forAgent(t.relayAgentId), softTimeoutMs = 6 * MIN) },
      hasTextRoute = { it in textRoutes },
      byText = { t, why -> texted += "${t.cid} $why"; true },
      clock = { now },
    )
    ledger.put(task("run~a0", "p-hark", "hark", "h1k2"))
    l.tick()
    assertEquals(listOf("run~a0 no_claim"), texted.toList())
    assertTrue(sinks.calls.isEmpty())
    assertEquals(0, ledger.get("run~a0")!!.wakes)
    now += 10 * MIN
    l.tick()
    assertEquals(1, texted.size)
  }

  @Test fun aPausedAgentsOpenCardIsNeitherWokenNorFallenBack() = runBlocking {
    // kDot paused (his ChatGPT Pro ended) with a card still open from earlier.
    val ledger = ConnectorLedger(null)
    val sinks = Sinks()
    val texted = CopyOnWriteArrayList<String>()
    val tapped = CopyOnWriteArrayList<String>()
    var paused = true
    val l = FallbackLadder(
      ledger, WakeDispatcher(::c3Wakes, sinks) { it in textRoutes },
      policyFor = { t -> LadderPolicy.of(c3Wakes(t.relayAgentId), softTimeoutMs = 6 * MIN) },
      hasTextRoute = { it in textRoutes },
      byText = { t, why -> texted += "${t.cid} $why"; true },
      tapFull = { t, why -> tapped += "${t.cid} $why"; true },
      clock = { now },
      skip = { t -> paused && t.peerId == "p-kdot" },
    )
    ledger.put(task("run~a1", "p-kdot", "kdot"))
    l.tick()
    now += 10 * MIN
    l.tick(relayDownSince = now - 2 * MIN)
    assertTrue(sinks.calls.isEmpty() && texted.isEmpty() && tapped.isEmpty())
    assertEquals(0, ledger.get("run~a1")!!.wakes)
    paused = false // unpaused: the ladder picks it up again
    l.tick()
    assertEquals(listOf("run~a1 no_claim"), texted.toList())
  }
}
