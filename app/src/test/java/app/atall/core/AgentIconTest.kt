package app.atall.core

import app.atall.api.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentIconTest {
    private fun agent(name: String, relayId: String? = null, aliases: List<String> = emptyList()) =
        AgentProfile(id = "a-$name", name = name, channel = ChannelType.CONNECTOR, address = relayId.orEmpty(), aliases = aliases, relayId = relayId)

    @Test fun renamedAgentKeepsItsIconFromItsSavedRelayId() {
        assertEquals(AgentLogo.GROK_BOT, AgentIcon.of(agent("Poet", relayId = "grok")))
        assertEquals(AgentLogo.CLAUDE_DUET, AgentIcon.of(agent("Grok", relayId = "duet")))
        assertEquals(AgentLogo.CHATGPT, AgentIcon.of(agent("Muse", relayId = "kdot")))
        assertEquals(AgentLogo.MUSE, AgentIcon.of(agent("Helper", relayId = "muse")))
        assertEquals(AgentLogo.HARK, AgentIcon.of(agent("Friend", relayId = "hark")))
    }

    @Test fun linkedRelayIdNamesTheAppWhenNoneIsSaved() {
        assertEquals(AgentLogo.MUSE, AgentIcon.of(agent("Scout"), linkedRelayId = "muse"))
        // A saved relay id wins over the live link.
        assertEquals(AgentLogo.GROK_BOT, AgentIcon.of(agent("Scout", relayId = "grok"), linkedRelayId = "muse"))
    }

    @Test fun anUnlinkedAgentIsMatchedLikeTheRelayLinksIt() {
        assertEquals(AgentLogo.GROK_BOT, AgentIcon.of(agent("Grok")))
        assertEquals(AgentLogo.CLAUDE_DUET, AgentIcon.of(agent("Duet")))
        assertEquals(AgentLogo.MUSE, AgentIcon.of(agent("Muse")))
        assertEquals(AgentLogo.CHATGPT, AgentIcon.of(agent("kDot")))
        assertEquals(AgentLogo.HARK, AgentIcon.of(agent("Hark")))
        assertEquals(AgentLogo.CLAUDE_DUET, AgentIcon.of(agent("Poet", aliases = listOf("claude duet"))))
    }

    @Test fun unknownAgentsGetTheSpark() {
        assertEquals(AgentLogo.NONE, AgentIcon.of(agent("Nova")))
        // An agent linked to some other relay identity is not matched by its name.
        assertEquals(AgentLogo.NONE, AgentIcon.of(agent("Grok", relayId = "someone-else")))
        assertEquals(AgentLogo.NONE, AgentIcon.of(agent("Grok"), linkedRelayId = "someone-else"))
        // A name that only contains a brand word is not an identity.
        assertEquals(AgentLogo.NONE, AgentIcon.of(agent("Grokker")))
    }
}
