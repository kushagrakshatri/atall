package app.atall.coordinator

import app.atall.coordinator.testing.FakeLlm
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Juno calls the user by the name from Settings (placeholder agents Nova and Scout). */
class UserNameTest {
  private fun juno(name: () -> String) =
    Juno({ testRoster }, { null }, FakeLlm(), InMemoryRunStore(), userName = name)

  @Test fun promptNamesTheUserFromSettings() {
    var name = "Kush"
    val j = juno { name }
    val text = j.instructions()
    assertTrue(text.contains("The user's name is Kush."), text)
    assertTrue(text.contains("never by a longer or more formal name"))
    assertFalse(text.contains("{{userLine}}"))
    // A change in Settings applies to the next model call.
    name = "Sam"
    assertTrue(j.instructions().contains("The user's name is Sam."))
  }

  @Test fun blankNameLeavesNoPlaceholderOrNameLine() {
    val text = juno { "  " }.instructions()
    assertFalse(text.contains("The user's name is"))
    assertFalse(text.contains("{{"))
  }

  @Test fun promptAsksForPlainTextToAgents() {
    assertTrue(juno { "" }.instructions().contains("Plain text only: no Markdown"))
  }
}
