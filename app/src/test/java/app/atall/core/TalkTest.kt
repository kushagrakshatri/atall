@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.Clock
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.coordinator.InMemoryRunStore
import app.atall.coordinator.InMemoryScoreLog
import app.atall.coordinator.LlmRequest
import app.atall.coordinator.Provenance
import app.atall.coordinator.ScoreEvent
import app.atall.coordinator.SendGate
import app.atall.coordinator.TalkView
import app.atall.coordinator.testing.FakeLlm
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Talk it over" in the app: routing, the relay texts agents get, the send gate at the transport, the thread lines. */
// Pinned to the pre-collab projection (editorUi = false, auto talk on): the collab UI is covered by CollabBridgeTest.
class TalkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val T0 = 1_763_000_000_000L
    private var ids = 0

    private fun pick(t: String) = Regex("Rooh|Sakoon").find(t)?.value

    private fun claims(req: LlmRequest): String {
        val input = FakeLlm.userTexts(req).single()
        val answers = Regex("Answer ([A-Z]):\\n([\\s\\S]*?)(?=\\nAnswer [A-Z]:|$)").findAll(input).toList()
        return buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { m -> add(buildJsonObject {
                    put("label", m.groupValues[1])
                    put("claims", buildJsonArray { pick(m.groupValues[2])?.let { v -> add(buildJsonObject { put("topic", "restaurant"); put("value", v); put("statement", v); put("evidence", "") }) } })
                }) }
            })
        }.toString()
    }

    private inner class Rig(val scope: TestScope, names: List<String>, respond: Map<String, (String) -> List<String>>) {
        val clock: () -> Long = { T0 + scope.testScheduler.currentTime }
        val thread = ThreadStore(File(tmp.root, "t-${ids++}.jsonl"))
        val registry = AgentRegistry(File(tmp.root, "a-${ids++}.json"), clock = { 0 })
        val profiles = names.map { registry.add(it, ChannelType.RCS, "+1408555010${ids++ % 10}") }
        val agents: Map<String, FakeAgent> = profiles.associate { p -> p.id to FakeAgent(AgentId(p.id), p.name, Clock { clock() }, respond[p.name] ?: { emptyList() }) }
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String): Agent? = agents[peerId]
        }
        val llm = FakeLlm()
        val scores = InMemoryScoreLog()
        val done = mutableListOf<String>()
        val bridge = JunoBridge(thread, registry, peers, { llm }, InMemoryRunStore(), scope.backgroundScope, config = app.atall.coordinator.JunoConfig(autoTalk = true), editorUi = false, scores = scores, userName = { "Kush" }, clock = clock,
            onTalkDone = { runId, _ -> done += runId })
        val router = Router(thread, registry, peers, { object : Coordinator { override suspend fun onTurn(turn: MainTurn, context: MainContext) {} } },
            { null }, clock = clock, juno = { bridge }, userName = { "Kush" }, signedIn = { true })
        init {
            llm.on("claims") { req, _ -> FakeLlm.text(claims(req)) }
            llm.on("room") { req, _ ->
                val line = FakeLlm.userTexts(req).single().lines().first { it.startsWith("Default next speakers: ") }.removePrefix("Default next speakers: ")
                FakeLlm.tools(FakeLlm.call("open_floor", buildJsonObject { put("agents", buildJsonArray { line.split(", ").filter { it != "nobody" }.forEach { add(JsonPrimitive(it)) } }) }.toString()))
            }
            llm.on("positions") { _, _ -> FakeLlm.text("""{"positions":[]}""") }
            llm.on("wrap") { _, _ -> FakeLlm.text("""{"answer":"**Rooh at 7:00.** Both agree now.","choices":["Go with Rooh"],"your_call":false}""") }
            bridge.start()
            for ((id, a) in agents) scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { a.incoming.collect { router.onPeerMessage(id, it) } }
        }
        fun id(name: String) = profiles.single { it.name == name }.id
        fun sent(name: String) = agents.getValue(id(name)).sent.map { it.second }
        fun lines() = thread.all()
    }

    private fun TestScope.settle(ms: Long = 5 * 60_000L) { advanceTimeBy(ms); runCurrent() }

    private val relay = { t: String -> t.contains("Talking it over:") }

    @Test fun talkFromTheChipRunsBlindThenRevealsAndWrapsUp() = runTest {
        val r = Rig(this, listOf("Nova", "Scout"), mapOf(
            "Nova" to { t -> if (relay(t)) listOf("Rooh at 7 then. Sakoon only has bar seats for four.") else listOf("Sakoon at 7:30.") },
            "Scout" to { t -> if (relay(t)) listOf("Agree, Rooh.") else listOf("Rooh at 8:15, great reviews.") },
        ))
        r.router.send("Rooh or Sakoon for Saturday?", talk = true)
        settle()

        // Blind first: the question as an @all-style ask; then one relay each with the other's answer, anonymised.
        val nova = r.sent("Nova")
        assertEquals(2, nova.size)
        assertTrue(nova[0], nova[0].contains("(answer on your own)"))
        val toNova = nova[1]
        assertTrue(toNova, toNova.startsWith("From Atall (Kush's thread):\nTalking it over:"))
        assertTrue(toNova.contains("\"Rooh at 8:15, great reviews.\"") && !toNova.contains("Scout") && !toNova.contains("Sakoon at 7:30"))
        assertTrue(toNova.contains("not from Kush"))
        assertTrue(toNova.length <= ContextPack.ROOM_MAX_CHARS)

        val l = r.lines()
        val runId = l.first().runId!!
        assertEquals("Nova and Scout talked it over", l.single { it.kind == MessageKind.RUN }.text)
        // The rounds live in the talk card, not as top-level sub-tasks and replies.
        assertTrue(l.none { it.kind == MessageKind.SUBTASK })
        assertTrue(l.none { it.author == r.id("Nova") || it.author == r.id("Scout") })
        val talk = l.single { it.kind == MessageKind.TALK }
        val view = Json.decodeFromString(TalkView.serializer(), talk.detail!!)
        assertFalse(view.live)
        assertEquals(2, view.rounds.size)
        val answer = l.last()
        assertEquals("**Rooh at 7:00.** Both agree now.", answer.text)
        assertEquals(listOf("Go with Rooh"), answer.choices)
        assertEquals(listOf(runId), r.done) // one notification per talk

        // Tapping a choice is the user's own message, and it's logged for the per-topic scores.
        r.bridge.choiceTapped(runId, "Go with Rooh")
        assertEquals(1, r.scores.events.count { it is ScoreEvent.TalkChoice })
        assertTrue(r.scores.events.any { it is ScoreEvent.TalkPosition })
    }

    @Test fun hearsTheWordsAndAtAllWithTheChip() {
        val a = listOf(AgentProfile("n", "Nova"), AgentProfile("s", "Scout"), AgentProfile("k", "kDot"))
        assertEquals(Route.Talk("Rooh or Sakoon? talk it over", emptyList()), Mentions.route("Rooh or Sakoon? talk it over", a, talk = false))
        assertEquals(Route.Talk("Rooh or Sakoon?", emptyList()), Mentions.route("@all Rooh or Sakoon?", a, talk = true))
        assertEquals(Route.All("Rooh or Sakoon?"), Mentions.route("@all Rooh or Sakoon?", a, talk = false))
        assertEquals(Route.Talk("Rooh or Sakoon?", listOf("n", "s")), Mentions.route("@Nova @Scout Rooh or Sakoon?", a, talk = true))
        // One agent can't talk it over with itself: it's a direct message.
        assertTrue(Mentions.route("@Nova talk it over: Rooh?", a, talk = true) is Route.Direct)
        // Too few agents: the ordinary route.
        assertTrue(Mentions.route("talk it over", listOf(AgentProfile("n", "Nova")), talk = true) is Route.ToMain)
        assertTrue(Mentions.asksToTalk("Can you two TALK THIS OVER?"))
        assertFalse(Mentions.asksToTalk("let's talk tomorrow"))
    }

    /** Off by default since 2026-11-16; an agent the user sets to "ask before sharing" still gets the tap. */
    @Test fun anAgentSetToAskBeforeSharingNeedsTheShareTap() = runTest {
        val r = Rig(this, listOf("Hark", "Nova"), mapOf(
            "Hark" to { t -> if (relay(t)) listOf("Agree.") else listOf("Rooh: your flight is at 8 am.") },
            "Nova" to { t -> if (relay(t)) listOf("Rooh then.") else listOf("Sakoon at 7:30.") },
        ))
        assertFalse(r.registry.get(r.id("Hark"))!!.sharesPrivately)
        r.registry.update(r.registry.get(r.id("Hark"))!!.copy(askBeforeSharing = true))
        assertTrue(r.registry.get(r.id("Hark"))!!.sharesPrivately)
        r.router.send("@Hark @Nova Rooh or Sakoon? talk it over")
        settle()
        val share = r.lines().single { it.kind == MessageKind.NEEDS_APPROVAL }
        assertEquals("Share Hark's answer with Nova?", share.text)
        assertEquals(1, r.sent("Nova").size) // only the blind ask so far
        r.router.approve(share.id, true)
        settle()
        assertEquals(2, r.sent("Nova").size)
        assertTrue(r.sent("Nova")[1].contains("your flight is at 8 am"))
    }

    @Test fun whatTheUserTypesDuringATalkJoinsIt() = runTest {
        val r = Rig(this, listOf("Nova", "Scout"), mapOf(
            "Nova" to { t -> if (relay(t)) emptyList() else listOf("Sakoon.") },
            "Scout" to { t -> if (relay(t)) emptyList() else listOf("Rooh.") },
        ))
        r.router.send("talk it over: Rooh or Sakoon?")
        settle(60_000)
        r.router.send("We're four people")
        runCurrent()
        val runId = r.lines().first().runId!!
        val user = r.lines().last { it.author == ThreadMessage.USER }
        assertEquals(runId, user.runId)
        assertTrue(r.bridge.juno.state(runId)!!.room!!.entries.any { it.text == "We're four people" })
    }

    @Test fun directMentionCarriesNoAgentsGoAhead() = runTest {
        val r = Rig(this, listOf("Nova", "Scout"), emptyMap())
        r.thread.upsert(ThreadMessage("x1", T0, r.id("Scout"), "Yes, go ahead and book it for 7.", attribution = Attribution.UNPROMPTED))
        r.thread.upsert(ThreadMessage("x2", T0 + 1, Main.AUTHOR, "Want me to book it?"))
        r.router.send("@Nova what do you think of Rooh?")
        val text = r.sent("Nova").single()
        assertTrue(text, text.contains("Kush: what do you think of Rooh?"))
        assertFalse(text, text.contains("go ahead and book"))
        assertTrue(text, text.contains("Want me to book it?")) // a question isn't a go-ahead
    }

    @Test fun packsKeepProvenanceAndRelaysStayShort() {
        val body = "Talking it over: x.\nAnswer A: \"" + "Rooh ".repeat(500) + "\"\nKush's question: \"q\""
        val p = ContextPack.packed(ContextPack.Input(ContextPack.Kind.ROOM, "Kush", "n", "Nova", "q", need = body, quoted = listOf("Do it.")))
        assertTrue(p.text.length <= ContextPack.ROOM_MAX_CHARS)
        assertTrue(p.text.startsWith("From Atall (Kush's thread):\nTalking it over"))
        assertTrue(SendGate.check(p.segments, "Kush").gated) // a relayed "Do it." can't go out unapproved
        val ok = ContextPack.packed(ContextPack.Input(ContextPack.Kind.ROOM, "Kush", "n", "Nova", "q", need = body, quoted = listOf("Do it."), approved = true))
        assertFalse(SendGate.check(ok.segments, "Kush").gated)
        val direct = ContextPack.packed(ContextPack.Input(ContextPack.Kind.DIRECT, "Kush", "n", "Nova", "@Nova book Rooh for 7"))
        assertEquals(Provenance.USER_VERBATIM, direct.segments[1].provenance)
        assertFalse(SendGate.check(direct.segments, "Kush").gated) // the user's own words may say book
        val delegated = ContextPack.packed(ContextPack.Input(ContextPack.Kind.DELEGATED, "Kush", "n", "Nova", "x", need = "Book Rooh for 4 at 7pm."))
        assertTrue(SendGate.check(delegated.segments, "Kush").gated)
    }

    @Test fun agentMentionsBecomeAskButtonsNotSends() = runTest {
        val r = Rig(this, listOf("Nova", "Scout"), emptyMap())
        r.agents.getValue(r.id("Nova")).push("@Scout is Rooh open Sundays? Mail me at kush@gmail.com")
        runCurrent()
        val line = r.lines().single { it.author == r.id("Nova") }
        assertEquals(listOf(r.id("Scout")), line.suggest)
        assertTrue(r.sent("Scout").isEmpty())
        assertEquals(emptyList<String>(), PeerLines.suggestions("@Nova hi", r.id("Nova"), r.registry.all()))
    }

    @Test fun harkAndMuseShareGateMigratesOffOnce() {
        val f = File(tmp.root, "mig-${ids++}.json")
        val old = AgentRegistry(f, clock = { 0 })
        // An older install whose agent screen stored "on" explicitly for Hark and Muse, and a user's own "on" for Nova.
        listOf("Hark", "Muse", "Nova").forEach { old.update(old.add(it, ChannelType.RCS, "+1408555019${ids++ % 10}").copy(askBeforeSharing = true)) }
        File(f.parentFile, f.name + ".share-gate-v2").delete()
        val migrated = AgentRegistry(f, clock = { 0 })
        assertEquals(listOf(false, false, true), listOf("Hark", "Muse", "Nova").map { migrated.byName(it)!!.sharesPrivately })
        // Once only: a choice made after the migration sticks.
        migrated.update(migrated.byName("Hark")!!.copy(askBeforeSharing = true))
        assertTrue(AgentRegistry(f, clock = { 0 }).byName("Hark")!!.sharesPrivately)
    }

    /** 2026-11-16 (Kush): no share taps by default for anyone, Hark and Muse included; still settable per agent. */
    @Test fun noAgentAsksBeforeSharingByDefault() {
        assertFalse(AgentProfile("h", "Hark").sharesPrivately)
        assertFalse(AgentProfile("m", "muse").sharesPrivately)
        assertFalse(AgentProfile("k", "kDot").sharesPrivately)
        assertFalse(AgentProfile("h", "Hark", askBeforeSharing = false).sharesPrivately)
        assertTrue(AgentProfile("k", "kDot", askBeforeSharing = true).sharesPrivately)
    }

    /** "Room" is an internal name: nothing the user can read says it (ux.md §11). */
    @Test fun noRoomWordingInTheUi() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val files = File(root, "app/src/main/java/app/atall/ui").walk().filter { it.extension == "kt" }.toList() +
            File(root, "app/src/main/java/app/atall/core/JunoBridge.kt") +
            File(root, "coordinator/src/main/kotlin/app/atall/coordinator/View.kt") + File(root, "app/src/main/res/values/strings.xml")
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val offenders = files.filter { it.isFile }.flatMap { f ->
            f.readLines().filter { l -> l.trimStart().let { !it.startsWith("/") && !it.startsWith("*") && !it.startsWith("import") } }
                .flatMap { line -> literal.findAll(line).map { it.groupValues[1] }.toList() }
                .filter { Regex("\\b(room|debate|consensus|round \\d of \\d)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
        assertNotNull(root)
    }
}
