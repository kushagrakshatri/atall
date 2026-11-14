package app.atall.core

import app.atall.coordinator.AskState
import app.atall.coordinator.AskStatus
import app.atall.coordinator.Phase
import app.atall.coordinator.RunMode
import app.atall.coordinator.RunState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Your name" setting, the header it feeds, and sub-task status words. */
class UserNameTest {
    @Test fun settingWinsElseChatGptFirstName() {
        assertEquals("Kush", UserName.effective("  Kush ", "Kushagra"))
        assertEquals("Kushagra", UserName.effective("", UserName.firstName("Kushagra Kshatri")))
        assertEquals("", UserName.effective(" ", ""))
        assertEquals("Kushagra", UserName.fieldValue("", "Kushagra")) // the Settings field is prefilled
        assertEquals("Ana Maria", UserName.normalize("  Ana   Maria "))
        assertEquals(UserName.MAX_CHARS, UserName.normalize("x".repeat(100)).length)
    }

    @Test fun headerUsesTheSettingName() {
        assertEquals("From Atall (Kush's thread):", ContextPack.header(UserName.effective("Kush", "Kushagra")))
        assertEquals("From Atall (James' thread):", ContextPack.header("James"))
        assertEquals("From Atall (your user's thread):", ContextPack.header(""))
    }

    private val run = RunState("r1", RunMode.COORDINATE, "q", emptyList(), 0, Phase.WAITING, emptyList())
    private fun ask(status: AskStatus) = AskState("r1~a0", "p1", "Trip dates?", status = status, createdAt = 0, sentAt = 0)

    @Test fun aReactionAloneNeverReadsAsReplied() {
        val chip = listOf(ReactionChip("🧳", "p1"))
        assertEquals("Reacted 🧳 · waiting", JunoBridge.statusWord(ask(AskStatus.PENDING), run, chip))
        // Even an ask an older build marked answered by the tapback alone (no real reply).
        assertEquals("Reacted 🧳 · waiting", JunoBridge.statusWord(ask(AskStatus.ANSWERED), run, chip))
        assertEquals("Reacted 🧳 · no reply yet", JunoBridge.statusWord(ask(AskStatus.PENDING), run.copy(phase = Phase.DONE), chip))
        assertEquals("Reacted 🧳 · didn't answer", JunoBridge.statusWord(ask(AskStatus.TIMED_OUT), run, chip))
        assertEquals("Waiting", JunoBridge.statusWord(ask(AskStatus.ANSWERED), run))
        // Another agent's chip doesn't count.
        assertEquals("Waiting", JunoBridge.statusWord(ask(AskStatus.PENDING), run, listOf(ReactionChip("🧳", "p2"))))
    }
}
