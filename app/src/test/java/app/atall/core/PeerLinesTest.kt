package app.atall.core

import app.atall.agents.text.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attribution, reaction targets and stale hints. Placeholder agents (Nova = p1, Scout = p2). */
class PeerLinesTest {
    private val t0 = 1_763_000_000_000L
    private fun user(id: String, text: String, at: Long, sentTo: List<String> = emptyList(), runId: String? = null) =
        ThreadMessage(id, at, ThreadMessage.USER, text, status = SendStatus.SENT, sentTo = sentTo, runId = runId)
    private fun peer(id: String, author: String, at: Long) = ThreadMessage(id, at, author, "text $id")

    @Test fun correlatedLinesAreAttributedByTheirCorrelation() {
        assertEquals(Attribution.DIRECT, PeerLines.attribution("p1", "d:abc:p1:0", t0, emptyList()))
        assertEquals(Attribution.JUNO, PeerLines.attribution("p1", "rab12cd~a1", t0, emptyList()))
        assertEquals(Attribution.UNPROMPTED, PeerLines.attribution("p1", null, t0, emptyList()))
    }

    @Test fun firstUncorrelatedReplyAfterAMentionIsStillDirect() {
        // The pixel test: a drafted send whose first reply lost its correlation showed no "replied directly".
        val thread = listOf(user("u1", "@Nova @Scout which deets?", t0, sentTo = listOf("p1", "p2")))
        assertEquals(Attribution.DIRECT, PeerLines.attribution("p1", null, t0 + 40_000, thread))
        // Once Nova has answered, a further unprompted line from Nova is just a message.
        val after = thread + peer("n1", "p1", t0 + 40_000)
        assertEquals(Attribution.UNPROMPTED, PeerLines.attribution("p1", null, t0 + 600_000, after))
        // Scout still owes its answer.
        assertEquals(Attribution.DIRECT, PeerLines.attribution("p2", null, t0 + 60_000, after))
        // Too long after the mention.
        assertEquals(Attribution.UNPROMPTED, PeerLines.attribution("p2", null, t0 + PeerLines.INFER_WINDOW_MS + 60_000, after))
        // An agent the user didn't mention.
        assertEquals(Attribution.UNPROMPTED, PeerLines.attribution("p3", null, t0 + 1_000, after))
    }

    @Test fun reactionGoesOnTheMessageItReactsTo() {
        val thread = listOf(
            user("aaaaaaaa-1111-2222", "how are my projects going", t0, sentTo = listOf("p1")),
            user("bbbbbbbb-3333-4444", "and dinner?", t0 + 10_000, sentTo = listOf("p1")),
            user("cccccccc-5555", "plan the weekend", t0 + 20_000, runId = "rrun1"),
            ThreadMessage("ask:rrun1~a0", t0 + 20_001, Main.AUTHOR, "free Saturday?", kind = MessageKind.SUBTASK, peerId = "p2", runId = "rrun1"),
        )
        // 1. By the transport's match (direct cid carries the message id prefix).
        val byCid = Reaction("👍", "From Atall (Kush's thread):", targetCorrelationId = Router.directCid("aaaaaaaa-1111-2222", "p1", "0"))
        assertEquals("aaaaaaaa-1111-2222", PeerLines.reactionTarget("p1", byCid, thread)!!.id)
        // ...or Juno's ask id -> the request that started the run.
        assertEquals("cccccccc-5555", PeerLines.reactionTarget("p2", Reaction("❤️", "x", targetCorrelationId = "rrun1~a0"), thread)!!.id)
        // 2. By the quoted text.
        assertEquals("aaaaaaaa-1111-2222", PeerLines.reactionTarget("p1", Reaction("👍", "how are my projects"), thread)!!.id)
        // 3. The newest message that went to that agent.
        assertEquals("bbbbbbbb-3333-4444", PeerLines.reactionTarget("p1", Reaction("👍", "something else"), thread)!!.id)
        assertEquals("cccccccc-5555", PeerLines.reactionTarget("p2", Reaction("👍", "something else"), thread)!!.id)
        assertNull(PeerLines.reactionTarget("p9", Reaction("👍", "nothing"), listOf(peer("n", "p9", t0))))
    }

    @Test fun reactionChipsAreOnePerAgentAndRemovable() {
        val m = user("u", "hi", t0)
        val a = PeerLines.applyReaction(m, "p1", Reaction("👍", "hi"))
        assertEquals(listOf(ReactionChip("👍", "p1")), a.reactions)
        val b = PeerLines.applyReaction(a, "p1", Reaction("❤️", "hi"))
        assertEquals(listOf(ReactionChip("❤️", "p1")), b.reactions)
        val c = PeerLines.applyReaction(PeerLines.applyReaction(b, "p2", Reaction("😂", "hi")), "p1", Reaction("❤️", "hi", removed = true))
        assertEquals(listOf(ReactionChip("😂", "p2")), c.reactions)
        assertTrue(PeerLines.applyReaction(c, "p2", Reaction("😂", "hi")) === c)
    }

    @Test fun missedHintIsStaleOnceThatAgentReplied() {
        val hint = ThreadMessage("closed:d:x", t0 + 60_000, Main.AUTHOR, "Scout may have answered in Messages", kind = MessageKind.NOTICE,
            peerId = "p2", sourceId = PeerLines.MISSED_PREFIX + "d:x", detail = t0.toString())
        assertTrue(PeerLines.staleMissedHints("p2", listOf(hint)).isEmpty())
        assertEquals(listOf(hint), PeerLines.staleMissedHints("p2", listOf(hint, peer("s1", "p2", t0 + 30_000))))
        // A reply from another agent doesn't clear it.
        assertTrue(PeerLines.staleMissedHints("p2", listOf(hint, peer("n1", "p1", t0 + 30_000))).isEmpty())
        assertTrue(PeerLines.answeredSince("p2", t0, listOf(peer("s1", "p2", t0 + 1))))
    }

    @Test fun juno_turn_lines_stay_together() {
        val sorted = listOf(
            user("u1", "plan it", t0, runId = "r1"),
            ThreadMessage("run:r1", t0 + 1, Main.AUTHOR, "Asking Nova", kind = MessageKind.RUN, runId = "r1"),
            ThreadMessage("ask:r1~a0", t0 + 2, Main.AUTHOR, "dates?", kind = MessageKind.SUBTASK, runId = "r1"),
            user("u2", "@Scout unrelated", t0 + 3),
            ThreadMessage("in:x", t0 + 4, "p1", "Nov 26", runId = "r1"),
            ThreadMessage("answer:r1", t0 + 5, Main.AUTHOR, "Nov 26 it is", runId = "r1"),
        )
        assertEquals(listOf("u1", "run:r1", "ask:r1~a0", "in:x", "answer:r1", "u2"), ThreadLayout.arrange(sorted).map { it.id })
    }
}
