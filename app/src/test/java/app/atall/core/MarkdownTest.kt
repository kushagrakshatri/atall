package app.atall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Juno's Markdown, rendered in the thread and flattened for agents. */
class MarkdownTest {
    @Test fun boldItalicCodeAndLinksBecomeSpans() {
        val l = Markdown.parse("**Death Valley + Vegas** is *the* pick, see `notes` or [map](https://example.com)").single()
        assertEquals(Markdown.Span("Death Valley + Vegas", bold = true), l.spans[0])
        assertEquals(Markdown.Span("the", italic = true), l.spans[2])
        assertEquals(Markdown.Span("notes", code = true), l.spans[4])
        assertEquals("Death Valley + Vegas is the pick, see notes or map (https://example.com)", l.text)
    }

    @Test fun listsAndHeadingsRenderWithMarkers() {
        val lines = Markdown.parse("## Plan\n\n\n- Day 1: **drive**\n* Day 2: hike\n  - nested\n1. first\n2) second\n---\n> quoted")
        assertEquals(listOf("Plan", "", "• Day 1: drive", "• Day 2: hike", "  • nested", "1. first", "2. second", "quoted"), lines.map { it.text })
        assertTrue(lines[0].heading)
        assertTrue(lines[2].spans.any { it.bold && it.text == "drive" })
    }

    @Test fun plainTextForAgentsHasNoMarkup() {
        val plain = Markdown.plain("**Death Valley + Vegas**\n- Day 1: _drive_\n- Day 2: `hike`\n### Budget\n__\$900__ each")
        assertEquals("Death Valley + Vegas\n- Day 1: drive\n- Day 2: hike\nBudget\n\$900 each", plain)
    }

    @Test fun ordinaryTextIsUntouched() {
        for (t in listOf("snake_case_name and 2*3*4", "I'm free at 7 * maybe", "price is $5 - $10", "a_b", "file_name.txt")) {
            assertEquals(t, Markdown.plain(t))
        }
        // Unclosed markers (a streaming answer) stay as typed.
        assertEquals("**Death Val", Markdown.plain("**Death Val"))
        assertFalse(Markdown.parse("**Death Val").single().spans.any { it.bold })
    }

    @Test fun codeFencesKeepTheirLinesAsCode() {
        val lines = Markdown.parse("Run:\n```\n./build.sh\n```")
        assertEquals(listOf("Run:", "./build.sh"), lines.map { it.text })
        assertTrue(lines[1].spans.single().code)
    }
}
