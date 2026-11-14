package app.atall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private val nova = AgentProfile("p1", "Nova")
    private val scout = AgentProfile("p2", "Scout")
    private val agents = listOf(nova, scout)

    @Test fun noMentionGoesToMain() {
        val r = Mentions.route("what's the weather?", agents)
        assertEquals(Route.ToMain("what's the weather?", emptyList()), r)
    }

    @Test fun singlePeerIsDirectAndStripped() {
        val r = Mentions.route("@Nova where's the game again?", agents)
        assertEquals(Route.Direct(listOf("p1"), "where's the game again?"), r)
    }

    @Test fun caseInsensitiveAndPunctuation() {
        assertEquals(Route.Direct(listOf("p1"), "hi"), Mentions.route("@nova, hi", agents))
        assertEquals(Route.Direct(listOf("p2"), "ping"), Mentions.route("@SCOUT: ping", agents))
    }

    @Test fun mainMentionWithPeersGoesToMainWithConstraints() {
        val r = Mentions.route("@Juno ask @Nova and @Scout, then pick the cheaper one", agents)
        assertEquals(Route.ToMain("@Juno ask @Nova and @Scout, then pick the cheaper one", listOf("p1", "p2")), r)
    }

    @Test fun twoPeersWithoutMainFanOut() {
        val r = Mentions.route("@Nova @Scout status?", agents) as Route.Direct
        assertEquals(listOf("p1", "p2"), r.peerIds)
        assertEquals("status?", r.text)
    }

    @Test fun inlineMentionKeepsName() {
        val r = Mentions.route("tell me what @Nova thinks", agents) as Route.Direct
        assertEquals("tell me what Nova thinks", r.text)
    }

    @Test fun unknownAndEmailAreText() {
        assertEquals(Route.ToMain("mail me at a@nova.com", emptyList()), Mentions.route("mail me at a@nova.com", agents))
        assertEquals(Route.ToMain("@Novak hi", emptyList()), Mentions.route("@Novak hi", agents))
    }

    @Test fun onlyMentionSendsWholeText() {
        val r = Mentions.route("@Nova", agents) as Route.Direct
        assertEquals("@Nova", r.text)
    }

    @Test fun activeToken() {
        assertEquals("No" to 4, Mentions.activeToken("Ask @No", 7))
        assertEquals("" to 0, Mentions.activeToken("@", 1))
        assertNull(Mentions.activeToken("Ask Nova", 8))
        assertNull(Mentions.activeToken("a@b", 3))
    }

    @Test fun filterPrefixFirst() {
        assertEquals(listOf("Nova", "Canova"), Mentions.filter("nov", listOf("Canova", "Juno", "Nova")))
        assertEquals(listOf("Juno", "Nova"), Mentions.filter("", listOf("Juno", "Nova")))
    }

    @Test fun findRanges() {
        val m = Mentions.find("hey @Nova.", Mentions.nameMap(agents))
        assertEquals(1, m.size)
        assertEquals(4..8, m[0].range)
        assertTrue(Mentions.find("no mentions", Mentions.nameMap(agents)).isEmpty())
    }
}
