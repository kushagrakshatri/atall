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

    @Test fun delegatedIsTheHeaderAndTheSubTaskOnly() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "p2", recipientName = "Scout",
            request = "can you all decide on the trip for Thanksgiving",
            need = "Which Thanksgiving dates are free on Kush's calendar, and any travel already booked?",
            recent = listOf(line("p1", "I'd pick Tahoe", t0)), names = names, others = listOf("Nova"),
        ))
        assertEquals(
            "From Atall (Kush's thread):\nWhich Thanksgiving dates are free on Kush's calendar, and any travel already booked?\nAlso asking: Nova",
            text,
        )
    }

    // The 2026-11-14 device test: a follow-up delegation repeated the sub-task, the user's "yup, go ahead" and raw
    // Markdown from Juno's last answer. Now it is the header and the sub-task, in plain text.
    @Test fun followUpDelegationDropsTheUsersWordsRecentTurnsAndMarkdown() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "yup, go ahead",
            need = "Hold **2 rooms** in Las Vegas for Nov 26-28 and send me the options.\n- under \$200/night\n- Don't book anything yet.",
            recent = listOf(
                line(ThreadMessage.USER, "plan a Thanksgiving road trip", t0),
                line(Main.AUTHOR, "**Death Valley + Vegas** looks best:\n- Day 1: drive\n- Day 2: hike", t0 + 1_000),
            ),
            names = names,
        ))
        assertEquals(
            "From Atall (Kush's thread):\nHold 2 rooms in Las Vegas for Nov 26-28 and send me the options.\n- under \$200/night\n- Don't book anything yet.",
            text,
        )
        assertFalse(text.contains("Juno needs"))
        assertFalse(text.contains("yup, go ahead"))
        assertFalse(text.contains("Recent:"))
        assertFalse(text.contains("*"))
    }

    @Test fun directMentionStripsMarkdownFromRecentTurns() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DIRECT, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "@Nova can you hold the **first** one?",
            recent = listOf(line(Main.AUTHOR, "**Death Valley + Vegas** looks best. See `itinerary` and [the map](https://example.com/map).", t0)),
            names = names,
        ))
        assertTrue(text, text.contains("Kush: can you hold the first one?"))
        assertTrue(text, text.contains("- Juno: Death Valley + Vegas looks best. See itinerary and the map (https://example.com/map)."))
        assertFalse(text.contains("**")); assertFalse(text.contains("`"))
    }

    @Test fun challengeIsJunosTextOnly() {
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.CHALLENGE, userName = "Kush", recipientId = "p2", recipientName = "Scout",
            request = "@all best day?", need = "Nova says Saturday is dry. Are you sure about Sunday?",
            recent = listOf(line(ThreadMessage.USER, "x", t0)), names = names,
        ))
        assertEquals("From Atall (Kush's thread):\nNova says Saturday is dry. Are you sure about Sunday?", text)
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

    @Test fun aLongSubTaskGoesInWholeNeverWithAnEllipsis() {
        val long = (1..120).joinToString(" ") { "step$it" } // ~1,000 chars, past the context budget
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DELEGATED, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "go", need = long,
            recent = (0 until 20).map { line(if (it % 2 == 0) ThreadMessage.USER else "p2", "turn $it", t0 + it) },
            names = names, others = listOf("Scout"),
        ))
        assertEquals("From Atall (Kush's thread):\n$long\nAlso asking: Scout", text)
        assertFalse(text.contains("…"))
    }

    @Test fun directContextDropsWholeTurnsOldestFirstNeverCutsOne() {
        // Six recent turns of ~150 chars each: only the newest few fit in the context budget.
        val turns = (0 until 6).map { n -> line(if (n % 2 == 0) ThreadMessage.USER else Main.AUTHOR, "turn$n " + "x".repeat(140) + " end$n", t0 + n) }
        val request = "@Nova " + "please read this whole request ".repeat(10).trim()
        val text = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DIRECT, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = request, recent = turns, names = names,
        ))
        assertFalse(text, text.contains("…"))
        // The user's words go in whole.
        assertTrue(text.contains("Kush: " + "please read this whole request ".repeat(10).trim()))
        val kept = (0 until 6).filter { text.contains("turn$it ") }
        // Every kept turn is whole, and the kept ones are the newest, contiguous.
        kept.forEach { assertTrue(text.contains("end$it")) }
        assertTrue(kept.isNotEmpty() && kept.size < 6)
        assertEquals((6 - kept.size until 6).toList(), kept)
        assertTrue(text.length <= ContextPack.MAX_CHARS)

        // A single turn bigger than the whole budget is dropped, never shortened.
        val huge = ContextPack.pack(ContextPack.Input(
            kind = ContextPack.Kind.DIRECT, userName = "Kush", recipientId = "p1", recipientName = "Nova",
            request = "and now?", recent = listOf(line(Main.AUTHOR, "y".repeat(900), t0)), names = names,
        ))
        assertEquals("From Atall (Kush's thread):\nKush: and now?", huge)
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
