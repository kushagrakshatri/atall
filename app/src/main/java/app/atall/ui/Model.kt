package app.atall.ui

import app.atall.api.ChannelType
import app.atall.core.AgentLogo
import app.atall.core.Main
import app.atall.ui.theme.AgentHue

/** What the UI needs about a peer. [status] is one word-ish: Listening · Can reply now · Needs a tap · Paused. */
data class PeerUi(
    val id: String,
    val name: String,
    val hue: AgentHue,
    val channel: ChannelType,
    val address: String,
    val paused: Boolean = false,
    val status: String = "Listening",
    /** Its app icon, from its app identity ([app.atall.core.AgentIcon]), never from [name]. */
    val logo: AgentLogo = AgentLogo.NONE,
)

object Copy {
    const val MAIN = Main.NAME

    fun withPeers(peers: List<PeerUi>): String = when (peers.size) {
        0 -> "Add an agent"
        1 -> "With ${peers[0].name}"
        2 -> "With ${peers[0].name} and ${peers[1].name}"
        else -> "With ${peers[0].name}, ${peers[1].name} and ${peers.size - 2} more"
    }

    fun channelLabel(c: ChannelType) = when (c) {
        ChannelType.RCS -> "Text"
        ChannelType.WHATSAPP -> "WhatsApp"
        ChannelType.APP_NOTIFICATIONS -> "App notifications"
        ChannelType.CONNECTOR -> "Atall connector"
    }

    fun descriptor(c: ChannelType) = when (c) {
        ChannelType.RCS -> "By text"
        ChannelType.WHATSAPP -> "On WhatsApp"
        ChannelType.APP_NOTIFICATIONS -> "From its app"
        ChannelType.CONNECTOR -> "Over Atall"
    }

    fun addressLabel(c: ChannelType) = when (c) {
        ChannelType.RCS -> "Phone number"
        ChannelType.WHATSAPP -> "WhatsApp contact name"
        ChannelType.APP_NOTIFICATIONS -> "App"
        ChannelType.CONNECTOR -> "Connection"
    }

    fun addressHelper(c: ChannelType, name: String) = when (c) {
        ChannelType.RCS -> "The number ${name.ifBlank { "the agent" }} texts you from."
        ChannelType.WHATSAPP -> "The chat name WhatsApp shows."
        ChannelType.APP_NOTIFICATIONS -> "The app that posts its messages."
        ChannelType.CONNECTOR -> "Connected in Settings → Connected agents."
    }
}
