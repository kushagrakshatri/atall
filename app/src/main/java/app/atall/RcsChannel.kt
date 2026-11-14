package app.atall

import android.content.Context
import app.atall.agents.text.Agent
import app.atall.agents.text.AgentConfig
import app.atall.agents.text.AgentId
import app.atall.agents.text.LiveStatus
import app.atall.agents.text.TextAgentHub
import app.atall.agents.text.android.TextAgents
import app.atall.api.ChannelAdapter
import app.atall.api.ChannelType
import app.atall.api.PeerInfo

/** SMS/RCS through Google Messages: the text-agents transport behind [ChannelAdapter]. */
class RcsChannel(private val context: Context) : ChannelAdapter {
    override val channel = ChannelType.RCS
    val hub: TextAgentHub get() = TextAgents.hub(context)

    override fun register(peer: PeerInfo): Agent =
        TextAgents.register(context, AgentConfig(AgentId(peer.id), peer.name, peer.address, peer.aliases))

    override fun unregister(peerId: String) {
        if (hub.agents().any { it.id.value == peerId }) hub.unregister(AgentId(peerId))
    }

    override fun agent(peerId: String): Agent? = hub.agent(AgentId(peerId))

    fun registeredIds(): Set<String> = hub.agents().map { it.id.value }.toSet()

    fun liveStatus(peerId: String): LiveStatus = hub.liveStatus(AgentId(peerId))
}
