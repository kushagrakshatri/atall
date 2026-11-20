package app.atall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answer card's text: tiles glued to the last word, the late-change highlight, the caption, the real marks. */
class CardTextTest {
    @Test fun creditTilesAreGluedToTheLastWordSoTheyNeverWrapAlone() {
        // The last word and every tile are one inline object: the only break opportunity is the space before the word.
        val one = CardText.glued("A minivan won't fit six people and their bags.", listOf("grok"))
        assertEquals(listOf(CardText.Piece.Words("A minivan won't fit six people and their "), CardText.Piece.Tail("bags.", listOf("grok"))), one)
        assertEquals("A minivan won't fit six people and their \uFFFC", CardText.flat(one))
        // Two credits (a repeat dropped) ride in the same object as the word.
        val two = CardText.glued("You're 25, so the age rule doesn't block you.  ", listOf("grok", "hark", "grok"))
        assertEquals(CardText.Piece.Tail("you.", listOf("grok", "hark")), two.last())
        assertEquals(1, CardText.flat(two).count { it == CardText.TILE })
        // A one-word line is all tail; a line with no credits is just its words, no object at all.
        assertEquals(listOf(CardText.Piece.Tail("Saturday.", listOf("nova"))), CardText.glued("Saturday.", listOf("nova")))
        assertEquals("Nice.", CardText.flat(CardText.glued("Nice.", emptyList())))
        assertFalse(CardText.flat(CardText.glued("Nice.", emptyList())).contains(CardText.TILE))
    }

    @Test fun aLateChangeHighlightsOnlyTheChangedWords() {
        val before = "Book a Suburban or Expedition MAX. A minivan won't fit six people and their bags."
        val after = "Book a Suburban or Expedition MAX and add the rental's damage cover. A minivan won't fit six people and their bags."
        val r = CardText.changedRanges(before, after)
        assertEquals(listOf("and add the rental's damage cover"), r.map { after.substring(it) })
        // A new line is highlighted whole; nothing changed means no highlight.
        val after2 = "Book the SUV.\nYour card likely won't cover a vehicle this big."
        assertEquals(listOf("Your card likely won't cover a vehicle this big"), CardText.changedRanges("Book the SUV.", after2).map { after2.substring(it) })
        assertTrue(CardText.changedRanges(before, before).isEmpty())
        assertTrue(CardText.changedRanges(null, after).isEmpty())
        // Ranges are cut per line.
        val inLine = CardText.rangesInLine(CardText.changedRanges("Book the SUV.", after2), "Book the SUV.\n".length, after2.length - "Book the SUV.\n".length)
        assertEquals(listOf(0..46), inLine)
    }

    @Test fun changedCaptionIsPlain() {
        assertEquals("Changed just now", CardText.changedLabel(1_000, 61_000))
        assertEquals("Changed 12 min ago", CardText.changedLabel(0, 12 * 60_000 + 5))
        assertTrue(CardText.changedLabel(0, 3 * 3_600_000, java.time.ZoneId.of("UTC")).startsWith("Changed at "))
    }

    @Test fun emphasisMarkersAreNotDrawnInTheCard() {
        assertEquals("Rooh at 7. You're vegetarian.", CardText.plainLine("**Rooh at 7.** You're vegetarian."))
        assertEquals("2 * 3 = 6", CardText.plainLine("2 * 3 = 6"))
    }

    @Test fun eachAgentWearsItsOwnAppIconByIdentity() {
        // By relay id: the agent's own app, not its parent brand.
        assertEquals(AgentLogo.GROK_BOT, AgentLogo.of("grok"))
        assertEquals(AgentLogo.MUSE, AgentLogo.of("muse"))
        assertEquals(AgentLogo.CLAUDE_DUET, AgentLogo.of("duet"))
        assertEquals(AgentLogo.CHATGPT, AgentLogo.of("kdot"))
        assertEquals(AgentLogo.HARK, AgentLogo.of("hark"))
        // By Play package.
        assertEquals(AgentLogo.GROK_BOT, AgentLogo.of("ai.x.grok.bot"))
        assertEquals(AgentLogo.MUSE, AgentLogo.of("com.facebook.aura"))
        assertEquals(AgentLogo.CLAUDE_DUET, AgentLogo.of("com.anthropic.aibo"))
        assertEquals(AgentLogo.CHATGPT, AgentLogo.of("com.openai.chatgpt"))
        assertEquals(AgentLogo.CHATGPT, AgentLogo.of(" KDOT "))
        // The parent brands' own apps are not these agents; unknown or blank identities get the neutral spark.
        assertEquals(AgentLogo.NONE, AgentLogo.of("ai.x.grok"))
        assertEquals(AgentLogo.NONE, AgentLogo.of("com.anthropic.claude"))
        assertEquals(AgentLogo.NONE, AgentLogo.of("nova"))
        assertEquals(AgentLogo.NONE, AgentLogo.of(""))
        assertEquals(AgentLogo.NONE, AgentLogo.of(null))
        // Bitmaps are never tinted; only the Hark vector follows the ink.
        assertTrue(AgentLogo.GROK_BOT.bitmap && AgentLogo.MUSE.bitmap && AgentLogo.CLAUDE_DUET.bitmap && AgentLogo.CHATGPT.bitmap)
        assertTrue(!AgentLogo.HARK.bitmap && AgentLogo.HARK.tinted && !AgentLogo.GROK_BOT.tinted && !AgentLogo.NONE.tinted)
    }
}
