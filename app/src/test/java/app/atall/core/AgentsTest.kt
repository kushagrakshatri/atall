package app.atall.core

import app.atall.api.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun nameRules() {
        val existing = listOf(AgentProfile("a", "Nova"))
        assertEquals("Add a name.", AgentValidation.nameError(" ", existing))
        assertEquals("Juno is already in your thread.", AgentValidation.nameError("juno", existing))
        assertEquals("You already have an agent called Nova.", AgentValidation.nameError("nova", existing))
        assertNull(AgentValidation.nameError("nova", existing, editingId = "a"))
        assertEquals("Use one word, up to 24 letters or numbers.", AgentValidation.nameError("Grok Bot", existing))
        assertEquals("Use one word, up to 24 letters or numbers.", AgentValidation.nameError("@Nova", existing))
        assertNull(AgentValidation.nameError("Scout", existing))
        assertNull(AgentValidation.nameError("qBit", existing))
    }

    @Test fun numberRules() {
        val existing = listOf(AgentProfile("a", "Nova", ChannelType.RCS, "+14085550101"))
        assertEquals("Use a full number, like (408) 555-0142.", AgentValidation.addressError(ChannelType.RCS, "555-01", existing))
        assertEquals("That number is already Nova.", AgentValidation.addressError(ChannelType.RCS, "(408) 555-0101", existing))
        assertNull(AgentValidation.addressError(ChannelType.RCS, "(408) 555-0102", existing))
        assertNull(AgentValidation.addressError(ChannelType.RCS, "55501", existing)) // short code
        assertEquals("This kind of agent isn't available yet.", AgentValidation.addressError(ChannelType.WHATSAPP, "Muse", existing))
    }

    @Test fun registryPersistsAndPicksFreeHues() {
        val f = File(tmp.root, "agents.json")
        val r = AgentRegistry(f, clock = { 42 })
        val a = r.add("Nova", ChannelType.RCS, "(408) 555-0101")
        val b = r.add("Scout", ChannelType.RCS, "+1 408 555 0102")
        assertEquals("4085550101", a.address) // no country code typed: kept as typed digits; matching uses the last 10
        assertEquals("14085550102".let { "+$it" }, b.address)
        assertNotEquals(a.hue, b.hue)
        assertEquals(42L, a.createdAt)
        r.update(a.copy(paused = true))
        val again = AgentRegistry(f)
        assertEquals(listOf("Nova", "Scout"), again.all().map { it.name })
        assertEquals(true, again.get(a.id)!!.paused)
        again.remove(a.id)
        assertEquals(listOf("Scout"), AgentRegistry(f).all().map { it.name })
        assertEquals(ChannelType.RCS, AgentRegistry(f).all()[0].channel)
    }

    @Test fun corruptRegistryStartsEmpty() {
        val f = File(tmp.root, "agents.json").apply { writeText("{not json") }
        assertEquals(0, AgentRegistry(f).all().size)
    }
}
