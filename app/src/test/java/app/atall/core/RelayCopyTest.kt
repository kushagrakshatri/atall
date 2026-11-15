package app.atall.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RelayCopyTest {
    private val now = Instant.parse("2026-11-15T18:30:00Z")
    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun knownAgentsGetTheirNames() {
        assertEquals(listOf("kDot", "Grok", "Hark", "Claude Duet", "Meta Muse"), listOf("kdot", "grok", "hark", "duet", "muse").map { RelayCopy.agentName(it, "x") })
        assertEquals("Perplexity", RelayCopy.agentName("pplx", "Perplexity"))
        assertEquals("pplx", RelayCopy.agentName("pplx", null))
    }

    @Test fun lastSeenIsShortAndPlain() {
        assertEquals("Not seen yet", RelayCopy.lastSeen(null, now, la))
        assertEquals("Seen just now", RelayCopy.lastSeen("2026-11-15T18:29:40Z", now, la))
        assertEquals("Seen 5 min ago", RelayCopy.lastSeen("2026-11-15T18:25:00Z", now, la))
        assertEquals("Seen 3 h ago", RelayCopy.lastSeen("2026-11-15T15:10:00Z", now, la))
        assertEquals("Seen yesterday", RelayCopy.lastSeen("2026-11-14T10:00:00Z", now, la))
        assertEquals("Seen Nov 2", RelayCopy.lastSeen("2026-11-02T20:00:00Z", now, la))
        assertEquals("Seen just now", RelayCopy.lastSeen("2026-11-15T18:31:00Z", now, la)) // clock skew: never "in the future"
        assertEquals("Not seen yet", RelayCopy.lastSeen("garbage", now, la))
    }

    @Test fun toolsInPlainWords() {
        assertEquals("Sent a result", RelayCopy.tool("atall_post_result"))
        assertEquals("Checked for tasks", RelayCopy.tool("atall_inbox"))
        assertEquals("Something new", RelayCopy.tool("atall_something_new"))
        assertNull(RelayCopy.tool(null))
        assertEquals("Seen 5 min ago · Took a task", RelayCopy.agentLine("2026-11-15T18:25:00Z", "atall_claim", now, la))
        assertEquals("Not seen yet", RelayCopy.agentLine(null, null, now, la))
    }

    @Test fun eventTextPicksTheReadablePart() {
        assertEquals("I'm kDot.", RelayCopy.eventText(Json.parseToJsonElement("""{"summary":"I'm kDot.","claims":[]}""")))
        assertEquals("Budget?", RelayCopy.eventText(Json.parseToJsonElement("""{"question_id":"q1","question":"Budget?"}""")))
        assertEquals("""{"n":1}""", RelayCopy.eventText(Json.parseToJsonElement("""{"n":1}""")))
        assertEquals("", RelayCopy.eventText(JsonNull))
        val long = RelayCopy.eventText(Json.parseToJsonElement("""{"summary":"${"word ".repeat(100)}"}"""), max = 50)
        assertTrue(long.length <= 50 && long.endsWith("…"))
        assertEquals("a b", RelayCopy.eventText(Json.parseToJsonElement("""{"text":"a\n\n b"}""")))
    }
}
