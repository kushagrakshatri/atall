package app.atall.inbox

import app.atall.inbox.testing.FakeClassifier
import app.atall.inbox.testing.RecordingTransport
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class OtpRedactionTest {
  private fun red(s: String) = Redactor.redact(s)

  @Test fun commonCodeShapesAreRedacted() {
    val cases = mapOf(
      "Your Uber code is 4821. Don't share it." to "4821",
      "G-582913 is your Google verification code." to "G-582913",
      "Your verification code: 582 913" to "582 913",
      "Your login code is X7K2P9" to "X7K2P9",
      "123456 is your Instagram code" to "123456",
      "Chase: one-time passcode 77311904, expires in 10 min" to "77311904",
    )
    for ((text, code) in cases) {
      val r = red(text)
      assertTrue(r.hasCode, text)
      assertFalse(r.text.contains(code), "${r.text} still holds $code")
      assertTrue(Redactor.CODE in r.text, text)
      assertEquals(listOf(code), r.codes, text)
    }
  }

  @Test fun passwordsAndMagicLinks() {
    val p = red("Temporary password: hunter22")
    assertEquals("Temporary password: [secret]", p.text)
    val l = red("Tap this magic link to sign in: https://app.example.com/login?token=abc123")
    assertFalse(l.text.contains("token"))
    assertTrue(Redactor.LINK in l.text)
  }

  @Test fun ordinaryNumbersAreLeftAlone() {
    for (s in listOf(
      "Your bill of \$1,240.00 is due at 10:30 on 11/20",
      "Use promo code SAVE20 for 20% off",
      "Call 408-555-0199 about the verification of your address",
      "Your verification fee of \$1500 was charged",
      "Order 1Z999AA10123456784 shipped",
      "Dinner at 7pm, table for 4",
    )) {
      val r = red(s)
      assertFalse(r.hasCode, "false positive in: $s -> ${r.text}")
      assertEquals(s, r.text)
    }
  }

  @Test fun codeShelfIsMemoryOnlyAndExpires() {
    val r = Rig()
    val a = r.added(nova("Your Chase verification code is 482913"))
    assertTrue(a.item.hasCode)
    assertFalse(a.item.sources.single().text.contains("482913"))
    assertEquals("482913", r.inbox.revealCode(a.item.id))
    assertTrue(r.inbox.state.value.needsYou.single().canRevealCode)
    assertEquals(Notify.BUZZ, a.notify)
    r.clock.advance(11 * MIN)
    r.inbox.refresh()
    assertNull(r.inbox.revealCode(a.item.id))
    assertEquals(0, r.inbox.waitingOnYou().count) // codes stop needing you when they expire
    assertEquals(CodeShelf(r.clock).toString().contains("482913"), false)
  }

  @Test fun codesNeverReachDiskDigestOrLlm() {
    val dir = Files.createTempDirectory("inbox-otp").toFile()
    val c = FakeClassifier(decide = { "fyi" })
    val r = Rig(store = FileInboxStore(dir), classifier = c)
    r.ingest(nova("Your Chase verification code is 482913"))
    // Unclear wording, but it contains a code: it must never be offered to the LLM.
    r.ingest(scout("fwd from bank, not sure what this is: verification 77311904"))
    // Unclear and code-free: this one does go to the LLM, redacted.
    r.ingest(scout("Interesting article on transformer scaling laws"))
    val sent = c.calls.flatten().joinToString(" ") { it.text }
    assertFalse(sent.contains("482913")); assertFalse(sent.contains("77311904"))
    assertEquals(1, c.calls.flatten().size)
    val onDisk = dir.walkTopDown().filter { it.isFile }.joinToString("") { it.readText() }
    assertFalse(onDisk.contains("482913")); assertFalse(onDisk.contains("77311904"))
    assertTrue(onDisk.contains("[code]"))
    val d = r.inbox.digest(T0 + HOUR)
    assertFalse(d.plainText().contains("482913"))
    assertFalse(d.promptForJuno().contains("77311904"))
    assertEquals(2, d.codesCount)
  }

  @Test fun gateBlocksAnythingThatStillLooksLikeACode() {
    assertFailsWith<LlmGate.Blocked> { LlmGate.check(listOf(ClassifyItem("x", "Nova", "your OTP is 991204"))) }
    val t = RecordingTransport { """{"items":[]}""" }
    val cls = ChatGptJsonClassifier(t, model = "test-model")
    assertFailsWith<LlmGate.Blocked> { runBlocking { cls.classify(listOf(ClassifyItem("x", "Nova", "login code 4471"))) } }
    assertTrue(t.bodies.isEmpty()) // nothing left the phone
  }

  @Test fun chatGptClassifierSendsStrictSchemaAndParses() {
    val t = RecordingTransport { body ->
      val payload = body["input"].toString()
      assertTrue(payload.contains("i1"))
      """{"items":[{"ref":"i1","lane":"needs_you","reason":"asks for approval"},{"ref":"zz","lane":"fyi","reason":"unknown ref"}]}"""
    }
    val out = runBlocking { ChatGptJsonClassifier(t, model = "test-model").classify(listOf(ClassifyItem("i1", "Nova", "Approve the plan for Sat?"))) }
    assertEquals(listOf(ClassifyAnswer("i1", "needs_you", "asks for approval")), out)
    val body = t.bodies.single()
    val format = body["text"]!!.jsonObject["format"]!!.jsonObject
    assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
    assertEquals("true", format["strict"]!!.jsonPrimitive.content)
    assertEquals("false", body["store"]!!.jsonPrimitive.content)
    assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
  }

  @Test fun badModelJsonCountsAsAFailedAttempt() {
    val cls = ChatGptJsonClassifier(RecordingTransport { "not json" }, model = "test-model")
    val r = Rig(classifier = null)
    val inbox = AgentInbox(InMemoryInboxStore(), cls, r.clock, InboxConfig(zone = LA))
    val res = runBlocking { inbox.ingest(nova("Interesting article on transformer scaling laws")) } as IngestResult.Added
    assertEquals(Lane.FYI, res.item.lane)
    assertEquals(1, res.item.llmAttempts)
    assertTrue(res.item.awaitingLlm)
  }
}
