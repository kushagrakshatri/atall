package app.atall.core

import app.atall.agents.text.Agent
import app.atall.agents.text.AgentId
import app.atall.agents.text.AgentMessage
import app.atall.agents.text.DraftRequest
import app.atall.agents.text.NeedsUserReason
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import app.atall.agents.text.testing.FakeAgent
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.InboxDecision
import app.atall.api.Inbox
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.PeerDirectory
import app.atall.api.PeerInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RouterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var thread: ThreadStore
    private lateinit var registry: AgentRegistry
    private val agents = HashMap<String, Agent>()
    private val turns = ArrayList<MainTurn>()
    private var coordinator: Coordinator = object : Coordinator {
        override suspend fun onTurn(turn: MainTurn, context: MainContext) {
            turns += turn
            val id = context.sink.start("thinking")
            context.sink.update(id, "answer to ${turn.text}")
        }
    }
    private var held = false

    private fun router(): Router {
        thread = ThreadStore(File(tmp.root, "t.jsonl"))
        registry = AgentRegistry(File(tmp.root, "a.json"), clock = { 0 })
        val peers = object : PeerDirectory {
            override fun peers() = registry.all().map { it.toPeer() }
            override fun agent(peerId: String) = registry.get(peerId)?.takeIf { !it.paused }?.let { agents[it.id] }
        }
        val inbox = object : Inbox {
            override suspend fun onUnsolicited(peer: PeerInfo, message: AgentMessage) = if (held) InboxDecision.HOLD else InboxDecision.SHOW_IN_THREAD
        }
        return Router(thread, registry, peers, { coordinator }, { null }, inbox = { inbox })
    }

    @Test fun unaddressedGoesToMain() = runTest {
        val r = router()
        r.send("plan dinner")
        assertEquals(listOf("plan dinner"), turns.map { it.text })
        val msgs = thread.all()
        assertEquals(SendStatus.SENT, msgs[0].status)
        assertEquals("answer to plan dinner", msgs[1].text)
        assertEquals(Main.AUTHOR, msgs[1].author)
        assertEquals(false, msgs[1].streaming)
    }

    @Test fun directMentionSendsAndReplyShowsAsDirect() = runTest {
        val r = router()
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        val fake = FakeAgent(AgentId(nova.id), "Nova") { listOf("pong") }
        agents[nova.id] = fake
        r.send("@Nova ping")
        // The agent gets the user's words with a context header (no thread yet, no one else asked).
        assertEquals("From Atall (your user's thread):\nThe user: ping", fake.sent.single().second)
        assertTrue(fake.sent.single().first.startsWith(Router.DIRECT))
        assertTrue(turns.isEmpty())
        // The fake answers synchronously on its flow; deliver it like the app's collector does.
        val reply = fake.history(10).last()
        r.onPeerMessage(nova.id, AgentMessage(reply.id, reply.agentId, reply.text, reply.at, reply.at, reply.correlation))
        val shown = thread.all().last()
        assertEquals("pong", shown.text)
        assertEquals(nova.id, shown.author)
        assertTrue(shown.direct)
        assertEquals(Attribution.DIRECT, shown.attribution)
        // Same message again (notification re-post / backfill) is not duplicated.
        r.backfill(nova.id, fake.history(10))
        assertEquals(1, thread.all().count { it.text == "pong" })
    }

    /** m05: an agent's status text on a direct @mention is a muted line, never its answer; the answer still lands as one. */
    @Test fun directStatusTextIsAMutedLineNotAnAnswer() = runTest {
        val r = router()
        val hark = registry.add("Hark", ChannelType.RCS, "+14085550103")
        val fake = FakeAgent(AgentId(hark.id), "Hark") { emptyList() }
        agents[hark.id] = fake
        r.send("@Hark resolve the costs")
        val cid = fake.sent.single().first
        val corr = app.atall.agents.text.Correlation(cid, app.atall.agents.text.Confidence.HIGH, app.atall.agents.text.CorrelationReason.ONLY_OPEN_REQUEST, 0)
        r.onPeerMessage(hark.id, AgentMessage("s1", AgentId(hark.id), "Looks like your signal dropped. Feel free to keep texting, and I'll pick everything up once you're back online.",
            System.currentTimeMillis(), System.currentTimeMillis(), corr))
        val status = thread.all().last()
        assertEquals(MessageKind.NOTICE, status.kind)
        assertTrue(status.text, status.text.startsWith("Hark: Looks like your signal dropped"))
        assertTrue(thread.all().none { it.author == hark.id })
        r.onPeerMessage(hark.id, AgentMessage("a1", AgentId(hark.id), "Van is $812 with fees.", System.currentTimeMillis(), System.currentTimeMillis(), corr))
        val answer = thread.all().single { it.sourceId == "a1" }
        assertEquals(hark.id, answer.author)
        assertEquals(Attribution.DIRECT, answer.attribution)
    }

    @Test fun noLiveActionBecomesTapToSend() = runTest {
        val r = router()
        val p = registry.add("Scout", ChannelType.RCS, "+14085550102")
        var allowDraftSeen: Boolean? = null
        agents[p.id] = object : Agent by FakeAgent(AgentId(p.id)) {
            override suspend fun send(text: String, correlationId: String, options: SendOptions): SendResult {
                allowDraftSeen = options.allowDraft
                return if (!options.allowDraft) SendResult.NeedsUser(correlationId, NeedsUserReason.DRAFTS_DISABLED, DraftRequest("+14085550102", text))
                else SendResult.Drafted(correlationId, 1, app.atall.agents.text.DraftReason.NO_LIVE_ACTION, text)
            }
        }
        r.send("@Scout book it")
        val tap = thread.all().single { it.kind == MessageKind.NEEDS_TAP }
        assertEquals("Tap to send to Scout", tap.text)
        assertEquals("From Atall (your user's thread):\nThe user: book it", tap.draftText)
        assertEquals(false, allowDraftSeen)
        assertTrue(r.tapToSend(tap.id))
        assertEquals(true, allowDraftSeen)
        assertEquals(SendStatus.DRAFTED, thread.get(tap.id)!!.status)
    }

    @Test fun pausedOrUnimplementedIsNotSent() = runTest {
        val r = router()
        val p = registry.add("Nova", ChannelType.RCS, "+14085550101")
        registry.update(p.copy(paused = true))
        r.send("@Nova hi")
        val u = thread.all().first()
        assertEquals(SendStatus.NOT_SENT, u.status)
        assertEquals("Nova is paused.", u.statusNote)
    }

    @Test fun unsolicitedGoesThroughInbox() = runTest {
        val r = router()
        val p = registry.add("Nova", ChannelType.RCS, "+14085550101")
        r.onPeerMessage(p.id, AgentMessage("m1", AgentId(p.id), "report ready", 5, 5, null))
        assertEquals(false, thread.all().single().direct)
        assertEquals(Attribution.UNPROMPTED, thread.all().single().attribution)
        held = true
        r.onPeerMessage(p.id, AgentMessage("m2", AgentId(p.id), "spam", 6, 6, null))
        assertEquals(1, thread.all().size)
        r.onPeerMessage(p.id, AgentMessage("m3", AgentId(p.id), "old", 1, 1, null, historical = true))
        assertEquals(1, thread.all().size)
    }

    @Test fun plainJunoAsksToSignInWhenSignedOut() = runTest {
        coordinator = PlainJuno()
        val r = router()
        r.send("hello")
        val last = thread.all().last()
        assertEquals(MessageKind.NEEDS_SIGN_IN, last.kind)
        assertEquals("Sign in with ChatGPT to talk to Juno.", last.text)
    }

    @Test fun brokenCoordinatorLeavesOneLine() = runTest {
        coordinator = object : Coordinator {
            override suspend fun onTurn(turn: MainTurn, context: MainContext) = error("boom")
        }
        val r = router()
        r.send("hello")
        assertEquals(listOf("hello", "Juno couldn't answer just now."), thread.all().map { it.text })
    }

    @Test fun tapbackBecomesAChipNotText() = runTest {
        val r = router()
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        agents[nova.id] = FakeAgent(AgentId(nova.id), "Nova") { emptyList() }
        r.send("@Nova how are my projects going")
        val reaction = app.atall.agents.text.Reactions.parse("👍 to “how are my projects going”")
        val t = System.currentTimeMillis() + 1_000
        r.onPeerMessage(nova.id, AgentMessage("m1", AgentId(nova.id), "👍 to “how are my projects going”", t, t, null, reaction = reaction))
        r.onPeerMessage(nova.id, AgentMessage("m2", AgentId(nova.id), "Two on track", t + 1, t + 1, null))
        val msgs = thread.all()
        assertEquals(listOf(ReactionChip("👍", nova.id)), msgs.first().reactions)
        // The real reply is its own line, attributed as the direct answer (it lost its correlation in transit).
        assertEquals(listOf("@Nova how are my projects going", "Two on track"), msgs.map { it.text })
        assertEquals(Attribution.DIRECT, msgs.last().attribution)
    }

    @Test fun missedHintIsDroppedOnceTheAgentReplied() = runTest {
        val r = router()
        val nova = registry.add("Nova", ChannelType.RCS, "+14085550101")
        agents[nova.id] = FakeAgent(AgentId(nova.id), "Nova") { emptyList() }
        r.send("@Nova where's the game?")
        val req = app.atall.agents.text.OutstandingRequest("d:x:${nova.id}:0", AgentId(nova.id), "q", "q", 10,
            app.atall.agents.text.Via.DRAFT, app.atall.agents.text.RequestState.WAITING, 20, threadOpenedAt = 11)
        r.onRequestClosed(app.atall.agents.text.RequestClosed(req, app.atall.agents.text.RequestClosed.Outcome.LIKELY_MISSED))
        assertEquals("Nova may have answered in Messages", thread.all().last().text)
        r.onPeerMessage(nova.id, AgentMessage("m1", AgentId(nova.id), "Watson Park at 3", 30, 30, null))
        assertTrue(thread.all().none { it.kind == MessageKind.NOTICE })
        // And a closure for a request that was already answered never shows a hint.
        r.onRequestClosed(app.atall.agents.text.RequestClosed(req.copy(correlationId = "d:y:${nova.id}:0"), app.atall.agents.text.RequestClosed.Outcome.LIKELY_MISSED))
        assertTrue(thread.all().none { it.kind == MessageKind.NOTICE })
    }

    @Test fun atAllWithNobodyToAskFallsBackToPlainChat() = runTest {
        val r = router()
        r.send("@all what's up")
        assertEquals(listOf("what's up"), turns.map { it.text })
    }
}
