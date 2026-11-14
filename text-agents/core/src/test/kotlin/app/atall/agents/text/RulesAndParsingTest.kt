package app.atall.agents.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the spike's AgentRulesTest, plus the hardening (short codes, group chats, self lines). */
class RulesAndParsingTest {
  private fun agent(id: String, name: String, number: String = "", vararg aliases: String) =
    AgentConfig(AgentId(id), name, number, aliases.toList()).normalized()

  private val wren = agent("wren", "Wren", "+1 (408) 555-0101", "Wren AI")
  private val qbit = agent("qbit", "qBit", "+14085550102")

  @Test fun matchesNameAliasAndNumberExactlyOnly() {
    assertTrue(Rules.matches(wren, "Wren"))
    assertTrue(Rules.matches(wren, "  wren "))
    assertTrue(Rules.matches(wren, "wren   ai"))
    assertTrue(Rules.matches(wren, "+14085550101"))
    assertTrue(Rules.matches(wren, "(408) 555-0101"))
    assertFalse(Rules.matches(wren, "Wrenness"))
    assertFalse(Rules.matches(wren, "Mom"))
    assertFalse(Rules.matches(wren, ""))
    assertFalse(Rules.matches(wren, null))
    assertFalse(Rules.matches(wren, "+14085550109"))
    assertFalse(Rules.matches(agent("x", "qBit"), "+14085550102"))
  }

  @Test fun shortCodesNeedExactDigits() {
    val sc = agent("sc", "Bot", "55501")
    assertTrue(Rules.matches(sc, "55501"))
    assertFalse(Rules.matches(sc, "155501"))
    assertFalse(Rules.matches(sc, "5550"))
    // A full number never matches a short code by its tail.
    assertFalse(Rules.matches(sc, "+1 408 555 5501"))
  }

  @Test fun findsTheFirstMatch() {
    val list = listOf(qbit, wren)
    assertSame(qbit, Rules.find(list, "QBIT"))
    assertSame(wren, Rules.find(list, "+1 408-555-0101"))
    assertNull(Rules.find(list, "ChatGPT"))
  }

  @Test fun normalizes() {
    assertEquals("+14085550101", Rules.normalizeNumber(" +1 (408) 555-0101 "))
    assertEquals("4085550101", Rules.normalizeNumber("408.555.0101"))
    assertEquals("", Rules.normalizeNumber("none"))
    assertEquals("", Rules.normalizeNumber(null))
    val n = AgentConfig(AgentId("a"), " A ", "(408) 1", listOf(" x ", "X", "", "y")).normalized()
    assertEquals("A", n.name)
    assertEquals(listOf("x", "y"), n.aliases)
  }

  @Test fun messageIdsDedupeRepostsButNotNewLines() {
    val id = AgentId("wren")
    val a = Rules.messageId(id, 1000, "hi")
    assertEquals(a, Rules.messageId(id, 1000, " hi "))
    assertNotEquals(a, Rules.messageId(id, 1001, "hi"))
    assertNotEquals(a, Rules.messageId(id, 1000, "hi!"))
    assertNotEquals(a, Rules.messageId(id, 1000, "hi", self = true))
    assertNotEquals(a, Rules.messageId(AgentId("qbit"), 1000, "hi"))
  }

  @Test(expected = IllegalArgumentException::class) fun rejectsBlankName() { AgentConfig(AgentId("a"), "  ") }

  @Test(expected = IllegalArgumentException::class) fun rejectsBlankId() { AgentId(" ") }

  // ------------------------------------------------------------------ parsing

  private fun snap(
    title: String? = "Wren", lines: List<SnapshotLine> = emptyList(), pkg: String = Rules.MESSAGES_PACKAGE,
    summary: Boolean = false, group: Boolean = false, text: String? = null, convTitle: String? = null,
  ) = NotificationSnapshot(pkg, "k", 5_000, summary, convTitle, title, text, group, "You", lines)

  @Test fun routesOnlyMessagesOneToOneAgentNotifications() {
    val agents = listOf(wren, qbit)
    assertSame(wren, Parsing.route(snap(), agents))
    assertNull(Parsing.route(snap(pkg = "com.whatsapp"), agents))
    assertNull(Parsing.route(snap(summary = true), agents))
    assertNull(Parsing.route(snap(group = true), agents))
    assertNull(Parsing.route(snap(title = "Mom"), agents))
    // Conversation title wins over EXTRA_TITLE.
    assertSame(qbit, Parsing.route(snap(title = "Wren", convTitle = "qBit"), agents))
    // No title: the newest non-self sender decides; self lines never route.
    assertSame(qbit, Parsing.route(snap(title = null, lines = listOf(SnapshotLine("a", 1, "Wren"), SnapshotLine("b", 2, "qBit"), SnapshotLine("c", 3, null))), agents))
    assertNull(Parsing.route(snap(title = null, lines = listOf(SnapshotLine("c", 3, "You"))), agents))
  }

  @Test fun classifiesSelfLinesAndSortsByTime() {
    val s = snap(lines = listOf(SnapshotLine("b", 20, "Wren"), SnapshotLine("mine", 15, null), SnapshotLine("a", 10, "Wren"), SnapshotLine("also mine", 30, "you"), SnapshotLine("  ", 40, "Wren")))
    val out = Parsing.lines(s, wren.id)
    assertEquals(listOf("a", "mine", "b", "also mine"), out.map { it.text })
    assertEquals(listOf(false, true, false, true), out.map { it.self })
    assertTrue(out.all { it.timeReliable })
  }

  @Test fun plainTextFallbackIsOneUnreliableLine() {
    val out = Parsing.lines(snap(text = " hello "), wren.id)
    assertEquals(1, out.size)
    assertEquals("hello", out[0].text)
    assertFalse(out[0].timeReliable)
    assertTrue(Parsing.lines(snap(text = " "), wren.id).isEmpty())
  }
}
