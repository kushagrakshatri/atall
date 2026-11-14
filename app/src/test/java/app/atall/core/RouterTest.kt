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
        assertEquals("ping", fake.sent.single().second)
        assertTrue(fake.sent.single().first.startsWith(Router.DIRECT))
        assertTrue(turns.isEmpty())
        // The fake answers synchronously on its flow; deliver it like the app's collector does.
        val reply = fake.history(10).last()
        r.onPeerMessage(nova.id, AgentMessage(reply.id, reply.agentId, reply.text, reply.at, reply.at, reply.correlation))
        val shown = thread.all().last()
        assertEquals("pong", shown.text)
        assertEquals(nova.id, shown.author)
        assertTrue(shown.direct)
        // Same message again (notification re-post / backfill) is not duplicated.
        r.backfill(nova.id, fake.history(10))
        assertEquals(1, thread.all().count { it.text == "pong" })
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
        assertEquals("book it", tap.draftText)
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
}
