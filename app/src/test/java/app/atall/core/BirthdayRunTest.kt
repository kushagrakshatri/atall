@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.Clock
import app.atall.agents.text.Confidence
import app.atall.agents.text.Correlation
import app.atall.agents.text.CorrelationReason
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.coordinator.AgreementMap
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.MapStatus
import app.atall.coordinator.Phase
import app.atall.coordinator.TalkView
import app.atall.coordinator.testing.FakeLlm
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The 2026-11-14 device test of v0.1.3-m03, word for word: "@all what should I get Mugdha for her birthday" to Hark and
 * kDot. Hark tapbacks 🥳 first, then answers at 17 s; kDot answers at 10 s; Juno answers blind. On the phone the run
 * stopped at "Collecting answers": no merged answer, no Agreement Map, no talk.
 */
// Pinned to the pre-collab projection (editorUi = false, auto talk on): the collab UI is covered by CollabBridgeTest.
class BirthdayRunTest {
    @get:Rule val tmp = TemporaryFolder()
    private val T0 = 1_763_000_000_000L
    private var ids = 0

    companion object {
        const val QUESTION = "@all what should I get Mugdha for her birthday"
        /** Hark's text from Sentry run r6cca6eef (2026-11-14 15:59 PST), word for word. */
        const val SIGNAL_DROPPED = "Looks like your signal dropped. Feel free to keep texting, and I'll pick everything up once you're back online."
        const val JUNO_BLIND = "Get Mugdha a small gift tied to something she loves, a handwritten birthday note, and a plan to spend time together.\n\n" +
            "If she’s mentioned something she wants, choose that over a surprise.\nIf you don’t know her wishlist, pick a book, treat, or hobby item you’ve seen her enjoy.\n" +
            "In the note, include one specific memory and something you appreciate about her.\nFor the outing, choose something that fits your relationship: coffee, dinner, a workshop, or a show.\n" +
            "Avoid perfume, clothing, or expensive jewelry unless you know her preferences.\n\nWhat’s your budget, how do you know Mugdha, and what’s one thing she’s into?"
        const val KDOT = "My pick: a thoughtful experience together, paired with a handwritten note. If Mugdha loves trying places, a dinner reservation somewhere she’s mentioned would feel more personal than a generic gift.\n\n" +
            "I don’t know her tastes or your budget yet, though. What’s Mugdha to you, and what does she love? That’ll help me pick something that actually feels like her."
        const val HARK = "I'd lean toward something she'll actually use: a WHOOP upgrade, since she kept the last one, a spa day for two, or a small fine-jewelry piece. When's her birthday, and roughly what budget? Then I'll pull real picks."

        /**
         * Claims shaped the way the model words them for answers like these: every answer gets its own topic wording, so
         * topic-by-topic nothing lines up (before M0.4 the map had no dispute and no talk started). The pick fields are
         * what M0.4's schema adds.
         */
        fun realisticClaims(req: LlmRequest): String {
            val input = FakeLlm.userTexts(req).single()
            val labels = Regex("Answer ([A-Z]):\\n([\\s\\S]*?)(?=\\nAnswer [A-Z]:|$)").findAll(input).map { it.groupValues[1] to it.groupValues[2] }.toList()
            fun claimsFor(text: String): String = when {
                text.contains("WHOOP") -> """[{"topic":"gift recommendation","value":"WHOOP upgrade, spa day for two, or small fine jewelry","statement":"Get something she'll use: a WHOOP upgrade, a spa day for two, or a small fine-jewelry piece.","evidence":"she kept the last WHOOP"}]"""
                text.contains("experience together") -> """[{"topic":"best gift type","value":"an experience together","statement":"A thoughtful experience together beats a generic gift.","evidence":""},{"topic":"add a note","value":"handwritten note","statement":"Pair it with a handwritten note.","evidence":""}]"""
                else -> """[{"topic":"what to give","value":"small gift tied to her interests","statement":"A small gift tied to something she loves.","evidence":""},{"topic":"add a note","value":"handwritten note","statement":"Add a handwritten birthday note.","evidence":""}]"""
            }
            val decision = """"decision":"what gift to get Mugdha""""
            fun pick(text: String) = when {
                text.contains("WHOOP") -> "a physical gift she will use"
                text.contains("experience together") -> "an experience instead of an object"
                else -> "a small physical gift"
            }
            fun asks(text: String) = when {
                text.contains("WHOOP") -> """["When is her birthday?","What's your budget?"]"""
                text.contains("experience together") -> """["What is Mugdha to you?","What does she love?"]"""
                else -> """["What's your budget?","How do you know Mugdha?","What's one thing she's into?"]"""
            }
            return "{" + decision + ",\"answers\":[" + labels.joinToString(",") { (l, t) ->
                """{"label":"$l","pick":"${pick(t)}","pick_kind":"${if (t.contains("experience together")) "experience" else "physical item"}","asks_user":${asks(t)},"claims":${claimsFor(t)}}"""
            } + "]}"
        }
    }

    private class FakeTelemetry : Telemetry {
        val events = mutableListOf<Triple<String, Telemetry.Level, String?>>()
        val crumbs = mutableListOf<DebugLog.Entry>()
        override fun breadcrumb(e: DebugLog.Entry, includeText: Boolean) { crumbs += e }
        override fun capture(message: String, level: Telemetry.Level, runId: String?, extras: Map<String, String>) { events += Triple(message, level, runId) }
    }

    private inner class Rig(val scope: TestScope) {
        val telemetry = FakeTelemetry()
        val log = DebugLog(null, clock = { T0 + scope.testScheduler.currentTime }).also { l -> l.addSink { telemetry.breadcrumb(it, true) } }
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val hark = registry.add("Hark", ChannelType.RCS, "+14085550111")
        val kdot = registry.add("kDot", ChannelType.RCS, "+14085550112")
        val agents: Map<String, FakeAgent> = listOf(hark, kdot).associate { p -> p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, { emptyList() }) }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, config = app.atall.coordinator.JunoConfig(autoTalk = true), editorUi = false, userName = { "Kush" }, clock = clock,
            log = log, telemetry = telemetry)
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) {} } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true })
        init {
            bridge.start()
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
        fun lines() = thread.all()
        fun cid(p: AgentProfile) = agents.getValue(p.id).sent.first().first
        fun corr(p: AgentProfile, part: Int = 0) = Correlation(cid(p), Confidence.HIGH, CorrelationReason.ONLY_OPEN_REQUEST, part)
    }

    /** Plays the device transcript: kDot at 10 s, Hark's 🥳 tapback at 6 s and its answer at 17 s. Juno's blind answer is scripted. */
    private suspend fun TestScope.play(r: Rig) {
        r.llm.on("blind") { _, _ -> FakeLlm.text(JUNO_BLIND) }
        r.router.send(QUESTION)
        advanceTimeBy(6_000); runCurrent()
        val packedToHark = r.agents.getValue(r.hark.id).sent.single().second
        r.agents.getValue(r.hark.id).push("🥳 to “$packedToHark”", r.corr(r.hark))
        advanceTimeBy(4_000); runCurrent()
        r.agents.getValue(r.kdot.id).push(KDOT, r.corr(r.kdot))
        advanceTimeBy(7_000); runCurrent()
        r.agents.getValue(r.hark.id).push(HARK, r.corr(r.hark, 1))
        runCurrent()
    }

    private fun map(r: Rig): AgreementMap? = r.lines().lastOrNull { it.kind == MessageKind.AGREEMENT }?.detail?.let { Json.decodeFromString(AgreementMap.serializer(), it) }

    /**
     * The stall: once every answer is in, the run's next step is the merge, whose first model call is the claims call
     * (strict json_schema, never exercised on the SIWC route before). No model call had a time limit (the SIWC stream
     * only gives up after OkHttp's 10-minute read timeout, and SIWC strips max_output_tokens), so a call that stalls
     * leaves the run in MERGING with no map: exactly "Collecting answers" with every participant answered. Before the
     * fix this test sat in MERGING forever. Now every model call has a 90 s limit and the merge always lands.
     */
    @Test fun runGetsPastCollectingEvenWhenTheClaimsCallHangs() = runTest {
        val r = Rig(this)
        r.llm.on("claims") { _, _ -> kotlinx.coroutines.awaitCancellation() }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("unused") }
        r.llm.on("merge") { _, _ -> FakeLlm.text("Here's where they landed.") }
        play(r)
        advanceTimeBy(6 * 60_000); runCurrent()
        val st = r.bridge.juno.state(r.lines().first().runId!!)!!
        assertEquals(Phase.DONE, st.phase)
        val m = map(r)
        assertNotNull(m)
        assertEquals(MapStatus.READY, m!!.status)
        assertTrue(r.lines().any { it.id.startsWith("answer:") })
    }

    @Test fun deviceTranscriptMergesMapsAndStartsTheTalk() = runTest {
        val r = Rig(this)
        r.llm.on("claims") { req, _ -> FakeLlm.text(realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Most lean toward a gift she'll use; kDot would make it an experience.") }
        r.llm.on("merge") { req, _ ->
            val input = FakeLlm.userTexts(req).single()
            // The merge sees every agent's specifics and what they already know.
            assertTrue(input, input.contains("WHOOP") && input.contains("she kept the last one"))
            assertTrue(input, input.contains("experience together"))
            FakeLlm.text("Upgrade her **WHOOP** (she kept the last one), or make it a spa day for two with a handwritten note.\n\nWhen's her birthday, and roughly what budget?")
        }
        r.llm.on("room") { _, _ -> FakeLlm.text("") } // the moderator says nothing: the default floor opens
        play(r)
        advanceTimeBy(60_000); runCurrent()

        val l = r.lines()
        // Juno's own blind answer never stands alone in the thread.
        assertFalse(l.any { !it.hidden && it.detail == "My own answer" })
        assertFalse(l.any { !it.hidden && it.text.startsWith("Get Mugdha a small gift") })
        // One merged answer with Hark's specifics and one short question at the end; nothing an agent already knows.
        val answer = l.single { it.id.startsWith("answer:") && !it.hidden }
        assertTrue(answer.text, answer.text.contains("WHOOP"))
        assertEquals(1, Regex("\\?").findAll(answer.text.substringAfterLast("\n")).count())
        // The Agreement Map shows the real disagreement: specific products vs an experience.
        val m = map(r)!!
        assertTrue(m.disputed.isNotEmpty())
        val d = m.disputed.first()
        assertTrue(d.sides.any { r.kdot.id in it.agentIds } && d.sides.any { r.hark.id in it.agentIds })
        // The talk starts by itself (pinned legacy config). Since 2026-11-16 there's no share tap by default: Hark's
        // answer goes to kDot in the reveal with no tap.
        val talk = l.single { it.kind == MessageKind.TALK }
        val tv = Json { ignoreUnknownKeys = true; explicitNulls = false }.decodeFromString(TalkView.serializer(), talk.detail!!)
        assertTrue(tv.label, tv.label.contains("talking it over") || tv.label.contains("talked it over"))
        assertTrue(l.none { it.kind == MessageKind.NEEDS_APPROVAL })
    }

    @Test fun mergeAsksNothingAnAgentAlreadyKnows() = runTest {
        val r = Rig(this)
        var mergeInput = ""
        r.llm.on("claims") { req, _ -> FakeLlm.text(realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Split.") }
        r.llm.on("merge") { req, _ -> mergeInput = FakeLlm.userTexts(req).single(); FakeLlm.text("Get her a WHOOP upgrade.\n\nWhat's your budget?") }
        r.llm.on("room") { _, _ -> FakeLlm.text("") }
        play(r)
        advanceTimeBy(60_000); runCurrent()
        // The merge is told which questions are still open (budget, timing, her tastes) and that Hark knows her.
        assertTrue(mergeInput, mergeInput.contains("Questions the agents asked Kush"))
        assertTrue(mergeInput, mergeInput.contains("When is her birthday?"))
        assertTrue(mergeInput, mergeInput.contains("one short question"))
    }


    /** The reaction never completes an ask: even when the transport closes Hark's request after the 🥳 and the answer arrives uncorrelated. */
    @Test fun tapbackThenTransportCloseThenUncorrelatedAnswerStillMerges() = runTest {
        val r = Rig(this)
        r.llm.on("blind") { _, _ -> FakeLlm.text(JUNO_BLIND) }
        r.llm.on("claims") { req, _ -> FakeLlm.text(realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Split.") }
        r.llm.on("merge") { _, _ -> FakeLlm.text("Upgrade her WHOOP.\n\nWhat's your budget?") }
        r.llm.on("room") { _, _ -> FakeLlm.text("") }
        r.router.send(QUESTION)
        advanceTimeBy(6_000); runCurrent()
        val harkCid = r.cid(r.hark)
        r.agents.getValue(r.hark.id).push("🥳 to “${r.agents.getValue(r.hark.id).sent.single().second}”", r.corr(r.hark))
        r.bridge.onRequestClosed(app.atall.agents.text.RequestClosed(
            app.atall.agents.text.OutstandingRequest(harkCid, AgentId(r.hark.id), "", "", T0, app.atall.agents.text.Via.ACTION, app.atall.agents.text.RequestState.WAITING, T0 + 60_000),
            app.atall.agents.text.RequestClosed.Outcome.COMPLETED))
        advanceTimeBy(4_000); runCurrent()
        r.agents.getValue(r.kdot.id).push(KDOT, r.corr(r.kdot))
        advanceTimeBy(7_000); runCurrent()
        r.agents.getValue(r.hark.id).push(HARK) // uncorrelated
        advanceTimeBy(60_000); runCurrent()
        val st = r.bridge.juno.state(r.lines().first().runId!!)!!
        // Merged and mapped; the talk is now running (it waits on Hark's turn and the share tap).
        assertEquals(app.atall.coordinator.RunMode.ROOM, st.mode)
        assertNotNull(map(r)?.verdict)
        assertTrue(r.log.entries().any { it.event == "transport.closed" && it.detail.contains("COMPLETED while PENDING") })
        assertTrue(r.lines().any { it.id.startsWith("answer:") && it.text.contains("WHOOP") })
    }

    /** The debug log has every step, and a run that stops moving with every reply in is reported once. */
    @Test fun debugLogRecordsTheRunAndAStuckRunIsReported() = runTest {
        val r = Rig(this)
        r.llm.on("claims") { _, _ -> kotlinx.coroutines.awaitCancellation() } // longer than the stuck window, shorter than the 90 s limit
        r.llm.on("verdict") { _, _ -> FakeLlm.text("unused") }
        r.llm.on("merge") { _, _ -> FakeLlm.text("Upgrade her WHOOP.") }
        play(r)
        val runId = r.lines().first().runId!!
        // Replies settle at ~37 s and the merge hangs on the claims call; the bridge's 30 s sweep sees no change.
        advanceTimeBy(108_000); runCurrent() // t = 125 s, before the 90 s model limit ends the claims call
        assertEquals(1, r.telemetry.events.count { it.first.startsWith("Run stuck") && it.third == runId })
        assertFalse(r.bridge.checkStuck(runId)) // once per run
        advanceTimeBy(60_000); runCurrent() // the claims call times out; the run finishes anyway
        val events = r.log.entries().map { it.event + " " + it.detail }
        assertTrue(events.joinToString("\n"), events.any { it.startsWith("run.phase WAITING -> MERGING") })
        assertTrue(events.any { it.startsWith("run.stuck") })
        assertTrue(events.any { it.startsWith("model.error claims timeout") })
        assertTrue(events.any { it.startsWith("run.phase MERGING -> DONE") })
        assertTrue(r.log.entries().any { it.event == "reply" && it.text?.contains("WHOOP") == true })
        assertTrue(r.telemetry.crumbs.size >= r.log.entries().size)
        assertTrue(r.telemetry.events.any { it.first == "Run DONE" && it.third == runId })
    }

    /**
     * m05 bug 1 (Sentry run r6cca6eef, same pattern on the @all birthday test): Hark's "signal dropped" text is an
     * agent status, not an answer. It shows as a small muted line under Hark's sub-task, Hark keeps its slot, and the
     * merge uses Hark's real answer when it lands.
     */
    @Test fun harksSignalDroppedTextIsAStatusLineNotItsAnswer() = runTest {
        val r = Rig(this)
        var mergeInput = ""
        r.llm.on("blind") { _, _ -> FakeLlm.text(JUNO_BLIND) }
        r.llm.on("claims") { req, _ -> FakeLlm.text(realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Split.") }
        r.llm.on("merge") { req, _ -> mergeInput = FakeLlm.userTexts(req).single(); FakeLlm.text("Upgrade her WHOOP.\n\nWhat's your budget?") }
        r.llm.on("room") { _, _ -> FakeLlm.text("") }
        r.router.send(QUESTION)
        advanceTimeBy(6_000); runCurrent()
        r.agents.getValue(r.hark.id).push(SIGNAL_DROPPED, r.corr(r.hark))
        advanceTimeBy(4_000); runCurrent()
        r.agents.getValue(r.kdot.id).push(KDOT, r.corr(r.kdot))
        // Long past the 20 s quiet window that used to settle Hark's "answer": the run is still waiting on Hark.
        advanceTimeBy(60_000); runCurrent()
        val runId = r.lines().first().runId!!
        val st = r.bridge.juno.state(runId)!!
        assertEquals(Phase.WAITING, st.phase)
        val harkAsk = st.asks.single { it.agentId == r.hark.id }
        assertEquals(app.atall.coordinator.AskStatus.PENDING, harkAsk.status)
        assertTrue(harkAsk.replies.isEmpty())
        // The thread: a muted status line, never an agent reply.
        val status = r.lines().single { it.kind == MessageKind.NOTICE && it.peerId == r.hark.id && !it.hidden }
        assertTrue(status.text, status.text.startsWith("Hark: Looks like your signal dropped"))
        assertFalse(r.lines().any { it.author == r.hark.id && it.text.contains("signal dropped") })
        assertTrue(r.log.entries().any { it.event == "reply.status" })
        // Hark's real answer, 90 s in, is the one merged.
        r.agents.getValue(r.hark.id).push(HARK, r.corr(r.hark))
        advanceTimeBy(60_000); runCurrent()
        assertTrue(mergeInput, mergeInput.contains("WHOOP"))
        assertFalse(mergeInput, mergeInput.contains("signal dropped"))
        assertTrue(r.lines().any { it.id.startsWith("answer:") && it.text.contains("WHOOP") })
    }

    /** Hark never answers after its status text: the @all merges at the soft timeout without it, and nothing quotes it. */
    @Test fun statusTextAloneNeverReachesTheMerge() = runTest {
        val r = Rig(this)
        var mergeInput = ""
        r.llm.on("blind") { _, _ -> FakeLlm.text(JUNO_BLIND) }
        r.llm.on("claims") { req, _ -> FakeLlm.text(realisticClaims(req)) }
        r.llm.on("verdict") { _, _ -> FakeLlm.text("Only kDot answered.") }
        r.llm.on("merge") { req, _ -> mergeInput = FakeLlm.userTexts(req).single(); FakeLlm.text("Plan an experience together.") }
        r.llm.on("room") { _, _ -> FakeLlm.text("") }
        r.router.send(QUESTION)
        advanceTimeBy(6_000); runCurrent()
        r.agents.getValue(r.hark.id).push(SIGNAL_DROPPED, r.corr(r.hark))
        advanceTimeBy(4_000); runCurrent()
        r.agents.getValue(r.kdot.id).push(KDOT, r.corr(r.kdot))
        advanceTimeBy(150_000); runCurrent()
        assertEquals("", mergeInput) // still collecting before the 180 s soft timeout
        advanceTimeBy(60_000); runCurrent()
        assertTrue(mergeInput.isNotEmpty())
        assertFalse(mergeInput, mergeInput.contains("signal dropped"))
        assertFalse(r.lines().any { !it.hidden && it.author == r.hark.id })
    }
}
