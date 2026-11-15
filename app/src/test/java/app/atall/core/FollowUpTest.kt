package app.atall.core

import app.atall.api.ThreadLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpTest {
    private fun line(author: String, text: String, at: Long) = ThreadLine("l$at", at, author, text)

    @Test fun thinMessagesAreMostlyMentionsOrFollowUps() {
        assertTrue(FollowUp.isThin(FollowUp.words("try to bring in @all", emptyList())))
        assertEquals("try to bring in", FollowUp.words("try to bring in @all", emptyList()))
        assertTrue(FollowUp.isThin(FollowUp.words("@all", emptyList())))
        assertTrue(FollowUp.isThin(FollowUp.words("@Grok thoughts?", listOf("Grok"))))
        assertTrue(FollowUp.isThin("can you get everyone's take on this one please"))
        assertFalse(FollowUp.isThin("which day is best for a long hike near San Jose this weekend?"))
    }

    @Test fun expandsWithThePlanItsQuestionAndTheEarlierRequest() {
        val recent = listOf(
            line(ThreadMessage.USER, "plan Saturday dinner for four, no pork or beef", 1_000),
            line(Main.AUTHOR, "**Go with Sakoon** at 7:30. Book it now. Want me to hold it?", 2_000),
        )
        val e = FollowUp.expand("try to bring in @all", recent, "Kush")!!
        assertEquals("Weigh in on Juno's plan below: agree, push back, or add what's missing.", e.intent)
        assertEquals(
            "Kush's words: \"try to bring in\"\nKush asked earlier: \"plan Saturday dinner for four, no pork or beef\"\n" +
                "Juno's plan: Go with Sakoon at 7:30. Want me to hold it?\nOpen question: Want me to hold it?",
            e.context,
        )
        // An order in Juno's answer ("Book it now.") never travels as quoted context.
        assertFalse(e.context.contains("Book it now"))
        assertTrue(FollowUp.expand("@all double-check this", recent, "Kush")!!.intent.startsWith("Check Juno's plan"))
    }

    @Test fun nothingToReferToOrAStandaloneMessageIsLeftAlone() {
        assertNull(FollowUp.expand("try to bring in @all", emptyList(), "Kush"))
        assertNull(FollowUp.expand("which day is best for a long hike near San Jose this weekend?", listOf(line(Main.AUTHOR, "Sakoon.", 1)), "Kush"))
    }

    @Test fun aLongPlanIsCutAtASentenceEnd() {
        val plan = (1..60).joinToString(" ") { "Sentence number $it about dinner." }
        val e = FollowUp.expand("@all thoughts", listOf(line(Main.AUTHOR, plan, 1)), "Kush")!!
        val quoted = e.context.lines().first { it.startsWith("Juno's plan: ") }.removePrefix("Juno's plan: ")
        assertTrue(quoted.length <= FollowUp.MAX_PLAN)
        assertTrue(quoted.endsWith("dinner."))
    }
}
