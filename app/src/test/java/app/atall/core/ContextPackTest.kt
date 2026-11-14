package app.atall.core

import app.atall.api.ThreadLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an agent receives with a message. Placeholder agents (Nova, Scout) only. */
class ContextPackTest {
    private val names = mapOf("p1" to "Nova", "p2" to "Scout")
    private val t0 = 1_763_000_000_000L
    private fun line(author: String, text: String, at: Long) = ThreadLine("id$at", at, author, text)

    @Test fun directMentionCarriesTheThreadAndWhoElse() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DIRECT, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "i think @nova and @Scout know the deets",
            recent = listOf(
                line(ThreadMessage.USER, "can you all decide on the trip for Thanksgiving", t0),
                line(Main.AUTHOR, "Happy to. Tahoe or the coast?", t0 + 1_000),
            ),
            names = names, others = listOf("Scout"),
        ))
        val lines = text.lines()
        assertEquals("From Atall (Kush's thread):", lines[0])
        // Mentions read as plain names; the recipient's own @ is gone.
        assertEquals("Kush: i think nova and Scout know the deets", lines[1])
        assertTrue(text.contains("- Kush: can you all decide on the trip for Thanksgiving"))
        assertTrue(text.contains("- Juno: Happy to. Tahoe or the coast?"))
        assertTrue(text.endsWith("Also asking: Scout"))
        assertFalse(text.contains("@"))
    }

    @Test fun leadingRecipientMentionIsStripped() {
        val text = ContextPack.pack(ContextPack.Input(ContextPack.Kind.DIRECT, "Kush", "p1", "Nova", "@Nova, where's the game?", names = names))
        assertEquals("From Atall (Kush's thread):\nKush: where's the game?", text)
    }

    @Test fun delegatedLeadsWithWhatJunoNeeds() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "p2", recipientName = "Scout",
            request = "can you all decide on the trip for Thanksgiving",
            need = "Which Thanksgiving dates are free on Kush's calendar, and any travel already booked?",
            recent = listOf(line("p1", "I'd pick Tahoe", t0)), names = names, others = listOf("Nova"),
        ))
        val lines = text.lines()
        assertEquals("From Atall (Kush's thread):", lines[0])
        assertTrue(lines[1].startsWith("Juno needs: Which Thanksgiving dates"))
        assertEquals("Kush asked: \"can you all decide on the trip for Thanksgiving\"", lines[2])
        assertTrue(text.contains("- Nova: I'd pick Tahoe"))
        assertTrue(text.endsWith("Also asking: Nova"))
    }

    @Test fun blindAsksHideOtherAgentsAnswers() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.BLIND, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "best day for the hike?", need = "best day for the hike?",
            recent = listOf(line("p2", "Saturday for sure", t0), line(ThreadMessage.USER, "we said this weekend", t0 + 1)),
            names = names, others = listOf("Scout"),
        ))
        assertFalse(text.contains("Saturday for sure"))
        assertTrue(text.contains("- Kush: we said this weekend"))
        assertTrue(text.contains("Also asking: Scout (answer on your own)"))
        // The question isn't repeated when it is the sub-task itself.
        assertEquals(1, text.lines().count { it.contains("best day for the hike?") })
    }

    @Test fun alwaysFitsInOneText() {
        val long = "word ".repeat(400)
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = long, need = long,
            recent = (0 until 20).map { line(if (it % 2 == 0) ThreadMessage.USER else "p2", long, t0 + it) },
            names = names, others = listOf("Scout"),
        ))
        assertTrue(text.length <= ContextPack.MAX_CHARS)
        assertTrue(text.startsWith("From Atall (Kush's thread):"))
        assertTrue(text.contains("Juno needs:"))
    }

    @Test fun oldTurnsAreNotRecentAndBlankNameIsNeutral() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DIRECT, userName = "", recipientId = "p1", recipientName = "Nova", request = "and now?",
            recent = listOf(line(ThreadMessage.USER, "last week's thing", t0), line(ThreadMessage.USER, "today's thing", t0 + 7 * 3_600_000L)),
            names = names,
        ))
        assertTrue(text.startsWith("From Atall (your user's thread):"))
        assertFalse(text.contains("last week's thing"))
        assertTrue(text.contains("today's thing"))
    }
}
