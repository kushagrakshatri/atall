package app.atall.core

import app.atall.coordinator.adapters.WakeConfig
import app.atall.coordinator.adapters.WakeDefaults
import app.atall.coordinator.adapters.WakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSetupTest {
    @Test fun tapsOpenTheAgentsAppWithThePromptFilledIn() {
        val p = "Check your Atall inbox for #k3f9"
        assertEquals("https://claude.ai/new?q=Check%20your%20Atall%20inbox%20for%20%23k3f9", WakeSetup.tapTarget("duet", p).url)
        assertEquals("com.anthropic.claude", WakeSetup.tapTarget("duet", p).pkg)
        assertEquals("https://grok.com/?q=Check%20your%20Atall%20inbox%20for%20%23k3f9", WakeSetup.tapTarget("grok", p).url)
        assertNull("Muse without a WhatsApp number: the share sheet", WakeSetup.tapTarget("muse", p).url)
        assertEquals("https://wa.me/15551234567?text=Check%20your%20Atall%20inbox%20for%20%23k3f9", WakeSetup.tapTarget("muse", p, "+1 (555) 123-4567").url)
    }

    @Test fun settingsRoundTripAndDefaults() {
        val s = WakeSetup.Saved().with("Grok", WakeConfig(WakeKind.TAP)).withUrl("grok", " https://x.example/hook ")
        val back = WakeSetup.Saved.decode(s.encode())
        assertEquals(WakeKind.TAP, back.config("grok").kind)
        assertEquals("https://x.example/hook", back.webhookUrls["grok"])
        assertEquals(WakeDefaults.forAgent("kdot"), back.config("kdot"))
        assertEquals(WakeSetup.Saved(), WakeSetup.Saved.decode("not json"))
    }

    @Test fun webhooksCarryNoContentAndNeedHttps() {
        assertEquals("""{"ref":"k3f9","task_id":"tsk_1","source":"atall"}""", WakeSetup.webhookBody("k3f9", "tsk_1"))
        assertTrue(WakeSetup.webhookUrlOk("https://api.x.ai/routines/abc/webhook"))
        assertFalse(WakeSetup.webhookUrlOk("http://api.x.ai/hook"))
        assertFalse(WakeSetup.webhookUrlOk("https://user:pw@host/hook"))
    }

    @Test fun setupLinesNameTheToolsAndApprovalLinesShowMoney() {
        for (id in listOf("grok", "kdot", "hark", "duet", "muse")) {
            val line = WakeSetup.setupPrompt(id, WakeDefaults.forAgent(id))
            assertTrue(line, line.contains("atall_post_result"))
        }
        assertTrue(WakeSetup.setupPrompt("grok", WakeDefaults.forAgent("grok")).contains("webhook"))
        assertEquals("Grok: Book Sakoon 7:30 · \$42", WakeSetup.approvalLine("Grok", "Book Sakoon 7:30", 42.0, "USD"))
        assertEquals("Grok: Pay deposit · 12.50 EUR", WakeSetup.approvalLine("Grok", "Pay deposit", 12.5, "eur"))
        assertEquals("Hark: Change the name", WakeSetup.approvalLine("Hark", "Change the name", null, null))
        assertEquals("Checks on its own about every 3 hours.", WakeSetup.caption("duet", WakeDefaults.forAgent("duet")))
    }
}
