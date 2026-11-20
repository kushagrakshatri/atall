package app.atall.core

/** Which app icon an agent wears ([AgentLogo]), from its app identity. Kept apart from CardText so that stays standalone. */
object AgentIcon {
    /**
     * An agent's icon: its saved relay id, else the relay id the connector links it to right now ([linkedRelayId]),
     * else the relay identity its name or aliases stand for (the same rule that links an agent to the relay,
     * [ConnectorRouting.relayIdOf]), used only while the agent has no saved relay id. A renamed agent keeps its icon
     * because its saved or linked relay id doesn't change.
     */
    fun of(p: AgentProfile, linkedRelayId: String? = null): AgentLogo {
        AgentLogo.of(p.relayId).takeIf { it != AgentLogo.NONE }?.let { return it }
        AgentLogo.of(linkedRelayId).takeIf { it != AgentLogo.NONE }?.let { return it }
        if (p.relayId != null || linkedRelayId != null) return AgentLogo.NONE
        return AgentLogo.of(ConnectorRouting.relayIdOf(p, AgentLogo.KNOWN_RELAY_IDS))
    }
}
