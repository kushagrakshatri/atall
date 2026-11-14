@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.atall.agents.text

import app.atall.agents.text.testing.FakeMessagesApp
import app.atall.agents.text.testing.ManualClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tapbacks never count as answers; reply actions survive re-posts and fall back to older ones. Placeholders only. */
class ReactionsAndActionsTest {
  private val NOVA = AgentId("nova")
  private val sec = 1_000L

  // ------------------------------------------------------------------ parsing

  @Test fun parsesGoogleMessagesReactionLines() {
    val a = Reactions.parse("👍 to “how are my projects going”")!!
    assertEquals("👍", a.emoji); assertEquals("how are my projects going", a.quoted); assertFalse(a.removed)

    val b = Reactions.parse("Reacted ❤️ to “Book a table for 2…”")!!
    assertEquals("❤️", b.emoji); assertEquals("Book a table for 2", b.quoted)

    val c = Reactions.parse("Nova reacted 😂 to \"that's funny\"")!!
    assertEquals("😂", c.emoji); assertEquals("that's funny", c.quoted)

    assertEquals("👍🏽", Reactions.parse("Reacted with 👍🏽 to your message “ok”")!!.emoji)

    val removed = Reactions.parse("Removed 👍 from “how are my projects going”")!!
    assertTrue(removed.removed)

    // iMessage-style tapbacks that arrive as SMS text.
    assertEquals("👍", Reactions.parse("Liked “see you at 7”")!!.emoji)
    assertEquals("😂", Reactions.parse("Laughed at \"see you at 7\"")!!.emoji)
    assertTrue(Reactions.parse("Removed a like from “see you at 7”")!!.removed)
  }

  @Test fun ordinaryTextIsNotAReaction() {
    for (t in listOf(
      "Sounds good to me",
      "Go to “Settings” and tap Reset",
      "👍",
      "-> to \"the station\"",
      "I liked “Dune” a lot, you should watch it", // not a whole-line tapback
      "Reacted to your message",
      "🎉 to everyone who came!", // no quote marks: text unless it quotes something we sent (see parseUnquoted)
      "Ok 👍 to “x”", // words before the emoji
      "👍\nto “x”", // the reaction words never span lines
    )) assertNull(t, Reactions.parse(t))
  }

  // The exact shape from the 2026-11-14 device test: a tapback on a packed, multi-line text.
  private val packed = "From Atall (Kushagra's thread):\nJuno needs: Ask Scout for trip dates and a budget. Don't book anything yet.\n" +
    "Kush asked: \"yup, go ahead\"\nRecent:\n- Juno: Death Valley + Vegas looks good"

  @Test fun multiLineQuoteFromTheDeviceTestIsAReaction() {
    val r = Reactions.parse("🧳 to “$packed”")!!
    assertEquals("🧳", r.emoji)
    assertTrue(r.quoted, r.quoted.startsWith("From Atall (Kushagra's thread):\nJuno needs:"))
    assertTrue(r.quoted.endsWith("looks good"))
    assertTrue(Reactions.quotes(r.quoted, packed))
  }

  @Test fun truncatedAndUnclosedQuotesAreReactions() {
    val cut = packed.take(60)
    for (t in listOf(
      "🧳 to “$cut…”", // truncated, closed
      "🧳 to “$cut…", // truncated, no closing quote
      "🧳 to “$cut...", // ASCII ellipsis
      "🧳 to “$cut", // cut off, no ellipsis, no closing quote
      "🧳 to \"$cut\"", // straight quotes
      "Reacted 🧳 to “$cut…”",
      "Scout reacted 🧳 to “$cut",
    )) {
      val r = Reactions.parse(t)
      assertNotNull(t, r)
      assertEquals(t, "🧳", r!!.emoji)
      assertTrue(t, Reactions.quotes(r.quoted, packed))
      assertFalse(t, r.quoted.endsWith("…"))
    }
    assertTrue(Reactions.parse("Removed 🧳 from “$cut…")!!.removed)
  }

  @Test fun anyEmojiIncludingZwjSequencesAndKeycaps() {
    for (e in listOf("🧳", "👍🏽", "👨‍👩‍👧‍👦", "🏳️‍🌈", "❤️‍🔥", "🧑🏾‍💻", "🇺🇸", "1️⃣", "‼️", "☕", "🏴󠁧󠁢󠁳󠁣󠁴󠁿")) {
      val r = Reactions.parse("$e to “first line\nsecond line”")
      assertNotNull(e, r)
      assertEquals(e, r!!.emoji)
      assertEquals("first line\nsecond line", r.quoted)
    }
  }

  @Test fun unquotedReactionNeedsAMatchingSentText() {
    val r = Reactions.parseUnquoted("🧳 to ${packed.take(50)}…")!!
    assertTrue(Reactions.quotes(r.quoted, packed))
    assertNull(Reactions.parseUnquoted("🎉 to all")) // too short to be distinctive
    // The hub only accepts it when it quotes a text it sent (hub test below).
  }

  @Test fun quoteMatchesTruncatedAndPackedTexts() {
    assertTrue(Reactions.quotes("how are my projects", "how are my projects going"))
    assertTrue(Reactions.quotes("From Atall (Kush's thread):", "From Atall (Kush's thread):\nKush: where's dinner"))
    assertTrue(Reactions.quotes("Kush: where's dinner", "From Atall (Kush's thread):\nKush: where's dinner tonight"))
    assertFalse(Reactions.quotes("dinner", "lunch plans"))
  }

  // ------------------------------------------------------------------ hub: reactions

  private inner class Rig(val clock: ManualClock = ManualClock(), val phone: FakeMessagesApp = FakeMessagesApp(clock)) {
    val diag = ArrayList<String>()
    val drafts = phone.Drafts()
    val hub = TextAgentHub(InMemoryStore(), drafts, clock, TextAgentConfig(), java.util.Random(7), Dispatchers.Unconfined, diagnostics = { diag += it })
    init { phone.conversation("Nova", "+14085550101"); phone.attach(hub) }
    val nova = hub.register(AgentConfig(NOVA, "Nova", "+14085550101"))
  }

  @Test fun tapbackIsNotTheAnswerAndCarriesItsTarget() = runTest {
    val r = Rig()
    val got = mutableListOf<AgentMessage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.nova.incoming.toList(got) }
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "hi")
    r.clock.advance(10 * sec)
    assertTrue(r.nova.send("how are my projects going", "d:abc:nova:0") is SendResult.Sent)
    r.clock.advance(3 * sec)
    r.phone.agentTexts("Nova", "👍 to “how are my projects going”")
    r.clock.advance(5 * sec)
    r.phone.agentTexts("Nova", "Two are on track, one is late.")

    val tap = got.single { it.reaction != null }
    assertNull(tap.correlation)
    assertEquals("d:abc:nova:0", tap.reaction!!.targetCorrelationId)
    val answer = got.single { it.text.startsWith("Two are") }
    // The real reply is part 0 of the request, not a continuation of the tapback.
    assertEquals("d:abc:nova:0", answer.correlation!!.correlationId)
    assertEquals(0, answer.correlation!!.part)
    // The log keeps the reaction, so a backfill renders a chip, not text.
    assertNotNull(r.nova.history(10).single { it.text.startsWith("👍") }.reaction)
  }

  @Test fun multiLineTapbackOnAPackedTextNeverSettlesTheRequest() = runTest {
    val r = Rig()
    val got = mutableListOf<AgentMessage>()
    val closed = mutableListOf<HubEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.nova.incoming.toList(got) }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.hub.events.toList(closed) }
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "hi")
    r.clock.advance(10 * sec)
    assertTrue(r.nova.send(packed, "rabc~a1") is SendResult.Sent)
    r.clock.advance(8 * sec)
    r.phone.agentTexts("Nova", "🧳 to “$packed”")
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "🧳 to ${packed.take(70)}…") // quote marks lost: still ours, so still a reaction

    val taps = got.filter { it.reaction != null }
    assertEquals(2, taps.size)
    assertTrue(taps.all { it.correlation == null && it.reaction!!.targetCorrelationId == "rabc~a1" })
    // Still waiting for the real reply.
    assertEquals(listOf("rabc~a1"), r.nova.outstanding().map { it.correlationId })
    assertTrue(closed.none { it is RequestClosed })

    r.clock.advance(20 * sec)
    r.phone.agentTexts("Nova", "Nov 26 to 30 works, about 900 each.")
    val answer = got.single { it.text.startsWith("Nov 26") }
    assertEquals("rabc~a1", answer.correlation!!.correlationId)
    assertEquals(0, answer.correlation!!.part)
  }

  @Test fun emojiLedTextThatQuotesNothingWeSentIsText() = runTest {
    val r = Rig()
    val got = mutableListOf<AgentMessage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.nova.incoming.toList(got) }
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "hi")
    r.clock.advance(10 * sec)
    assertTrue(r.nova.send("what should we toast at dinner", "c1") is SendResult.Sent)
    r.clock.advance(5 * sec)
    r.phone.agentTexts("Nova", "🎉 to the best friends anyone could ask for")
    val line = got.single { it.text.startsWith("🎉") }
    assertNull(line.reaction)
    assertEquals("c1", line.correlation!!.correlationId)
  }

  // ------------------------------------------------------------------ hub: reply actions

  @Test fun actionFromAnyPostIsKeptAndReusedAfterTheChatIsRead() = runTest {
    val r = Rig()
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "Your report is ready")
    // The user reads the chat: Messages cancels the notification. The captured action stays usable in its window.
    r.phone.openChat("Nova"); r.phone.closeChat("Nova")
    r.clock.advance(40 * sec)
    assertTrue(r.hub.liveStatus(NOVA).usable)
    assertTrue(r.nova.send("thanks, send it over", "c1") is SendResult.Sent)
    assertTrue(r.diag.any { it.startsWith("send agent=nova via=action") })
  }

  @Test fun repostWithoutActionKeepsTheOneWeHave() = runTest {
    val r = Rig()
    r.clock.advance(sec)
    r.phone.agentTexts("Nova", "first")
    // A MessagingStyle update with no reply action (e.g. Messages' own re-post) must not drop the live action.
    r.hub.onPosted(NotificationSnapshot(Rules.MESSAGES_PACKAGE, r.phone.conversation("Nova").key, r.clock.now(),
      conversationTitle = "Nova", title = "Nova", selfName = "You", lines = listOf(SnapshotLine("first", r.clock.now() - sec, "Nova")), actionCount = 1))
    r.clock.advance(5 * sec)
    assertTrue(r.nova.send("ok", "c1") is SendResult.Sent)
    assertTrue(r.diag.any { it.contains("reply=absent") })
  }

  @Test fun canceledNewestActionFallsBackToAnOlderOne() = runTest {
    val r = Rig()
    var cancelNewest = true
    val older = object : ReplyHandle { var fired = 0; override fun fire(text: String): FireResult { fired++; return FireResult.OK } }
    val newest = object : ReplyHandle { override fun fire(text: String) = if (cancelNewest) FireResult.CANCELED else FireResult.OK }
    val key = "k-nova"
    fun post(h: ReplyHandle, text: String) = r.hub.onPosted(NotificationSnapshot(Rules.MESSAGES_PACKAGE, key, r.clock.now(),
      conversationTitle = "Nova", title = "Nova", selfName = "You", lines = listOf(SnapshotLine(text, r.clock.now(), "Nova")), reply = h, actionCount = 2, replySource = "actions"))
    r.clock.advance(sec); post(older, "one")
    r.clock.advance(sec); post(newest, "two")
    r.clock.advance(5 * sec)
    assertTrue(r.nova.send("got both", "c1") is SendResult.Sent)
    assertEquals(1, older.fired)
    assertTrue(r.diag.any { it.contains("result=CANCELED") })
  }

  @Test fun noActionLogsWhyItFellBackWithoutContent() = runTest {
    val r = Rig()
    val res = r.nova.send("secret plans for Saturday", "c1", SendOptions(allowDraft = false))
    assertTrue(res is SendResult.NeedsUser)
    val line = r.diag.single { it.startsWith("fallback") }
    assertTrue(line, line.contains("reason=NO_LIVE_ACTION") && line.contains("lastNotif=never"))
    assertFalse(r.diag.any { it.contains("secret") || it.contains("555") })
  }
}
