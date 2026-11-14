package app.atall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebugLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun redactsPhoneNumbersTokensAndSecrets() {
        val raw = "Texts from +1 408-555-0101 and (669) 250-8514, Bearer abc.def-ghi, token=s3cr3tvalue, " +
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlX2hlcmU, key sk-proj-ABCDEFGHIJKLMNOP1234, " +
            "dsn https://0123456789abcdef0123456789abcdef@o1.ingest.sentry.io/42, your code is 482913, opaque 9f8e7d6c5b4a39281706f5e4d3c2b1a0ffee"
        val out = Redact.text(raw)
        for (leak in listOf("408-555-0101", "250-8514", "abc.def-ghi", "s3cr3tvalue", "eyJhbGci", "sk-proj", "0123456789abcdef0123", "482913", "9f8e7d6c5b4a"))
            assertFalse("$leak leaked: $out", out.contains(leak))
        assertTrue(out, out.contains("[phone]") && out.contains("Bearer [secret]") && out.contains("token=[secret]"))
    }

    @Test fun keepsOrdinaryTextIdsTimesAndPrices() {
        val s = "r452c2ac5~a1 Hark REPLYING -> ANSWERED after 17s; budget \$900, Nov 26-30, 2026-11-14 10:02, WHOOP 4.0"
        assertEquals(s, Redact.text(s))
    }

    @Test fun keepsTheLast500LinesAcrossARestartAndDropsTextWhenAsked() {
        val f = File(tmp.root, "log.jsonl")
        var t = 1_763_000_000_000L
        val log = DebugLog(f, clock = { t++ }, max = 500)
        repeat(620) { log.add("juno", "reply", "r1", "a1 Hark", "text number $it to +14085550101") }
        assertEquals(500, log.entries().size)
        val again = DebugLog(f, clock = { t++ }, max = 500)
        assertEquals(500, again.entries().size)
        assertEquals("text number 619 to [phone]", again.entries().last().text)
        val withText = again.export(includeText = true, header = "Atall test")
        assertTrue(withText.startsWith("Atall test\n") && withText.contains("text number 619 to [phone]"))
        val noText = again.export(includeText = false)
        assertFalse(noText.contains("text number"))
        assertTrue(noText.contains("[text, 26 chars]"))
        assertFalse(f.readText().contains("5550101"))
    }

    @Test fun sinksSeeEveryEntryAndASinkFailureIsHarmless() {
        val log = DebugLog(null)
        val seen = mutableListOf<String>()
        log.addSink { error("boom") }
        log.addSink { seen += it.event }
        log.add("app", "start")
        log.add("juno", "run.phase", "r1", "WAITING -> MERGING")
        assertEquals(listOf("start", "run.phase"), seen)
    }
}
