package app.atall.core

import app.atall.agents.text.Rules
import app.atall.api.ChannelType
import app.atall.api.PeerInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Locale
import java.util.UUID

/** The main agent's fixed identity. Users can't add a peer with this name. */
object Main {
    const val NAME = "Juno"
    const val AUTHOR = "main"
}

/** A peer agent the user added. Channel-agnostic: [address] means what [channel] needs. */
@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val channel: ChannelType = ChannelType.RCS,
    val address: String = "",
    val aliases: List<String> = emptyList(),
    /** Index into AgentHue.entries; kept as an int so this file stays free of Compose. */
    val hue: Int = 0,
    val paused: Boolean = false,
    val createdAt: Long = 0,
    /**
     * "Ask before sharing its answers with other agents" (in a talk, a "Share with X?" tap). Null: the default, which
     * is on for agents that read the user's private accounts (Hark, Muse) and off for the rest.
     */
    val askBeforeSharing: Boolean? = null,
) {
    fun toPeer() = PeerInfo(id, name, channel, address, aliases, paused)
    val monogram: String get() = name.trim().take(1).ifEmpty { "?" }
    val sharesPrivately: Boolean get() = askBeforeSharing ?: AgentPrivacy.privateByDefault(name)
}

/** Which agents' answers count as private by default (risks.md #1): those that answer from the user's own accounts. */
object AgentPrivacy {
    private val PRIVATE = setOf("hark", "muse")
    fun privateByDefault(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in PRIVATE
}

/** Plain-language checks for the add/edit form. Returns the first problem as user-facing copy, or null. */
object AgentValidation {
    private val NAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,23}$")

    fun nameError(name: String, existing: List<AgentProfile>, editingId: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Add a name."
        if (!NAME.matches(n)) return "Use one word, up to 24 letters or numbers."
        if (n.equals(Main.NAME, ignoreCase = true)) return "${Main.NAME} is already in your thread."
        existing.firstOrNull { it.id != editingId && it.name.equals(n, ignoreCase = true) }
            ?.let { return "You already have an agent called ${it.name}." }
        return null
    }

    fun addressError(channel: ChannelType, address: String, existing: List<AgentProfile>, editingId: String? = null): String? {
        if (!channel.implemented) return "This kind of agent isn't available yet."
        return when (channel) {
            ChannelType.RCS -> numberError(address, existing.filter { it.channel == ChannelType.RCS }, editingId)
            else -> if (address.isBlank()) "Add how to reach it." else null
        }
    }

    private fun numberError(raw: String, existing: List<AgentProfile>, editingId: String?): String? {
        val n = Rules.normalizeNumber(raw)
        val digits = n.filter { it.isDigit() }
        val shortCode = digits.length in 3..6 && raw.trim().all { it.isDigit() }
        if (!shortCode && digits.length < 10) return "Use a full number, like (408) 555-0142."
        existing.firstOrNull { it.id != editingId && sameNumber(it.address, n) }
            ?.let { return "That number is already ${it.name}." }
        return null
    }

    fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }; val y = b.filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        return if (x.length < 7 || y.length < 7) x == y else x.takeLast(10) == y.takeLast(10)
    }

    /** Normalized address to store. */
    fun normalizeAddress(channel: ChannelType, address: String): String =
        if (channel == ChannelType.RCS) Rules.normalizeNumber(address) else address.trim()
}

/** The user's agents, persisted as one JSON file (atomic rewrite). Source of truth for every channel. */
class AgentRegistry(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var agents: List<AgentProfile> = load()

    fun all(): List<AgentProfile> = synchronized(lock) { agents }
    fun get(id: String): AgentProfile? = synchronized(lock) { agents.firstOrNull { it.id == id } }
    fun byName(name: String): AgentProfile? = synchronized(lock) { agents.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }

    /** First hue (0..7) no agent uses yet; wraps when all are taken. */
    fun nextHue(hueCount: Int = 8): Int = synchronized(lock) {
        val taken = agents.map { it.hue }.toSet()
        (0 until hueCount).firstOrNull { it !in taken } ?: (agents.size % hueCount)
    }

    fun add(name: String, channel: ChannelType, address: String, hue: Int? = null): AgentProfile = synchronized(lock) {
        val p = AgentProfile(
            id = "a-" + UUID.randomUUID().toString().take(8),
            name = name.trim(),
            channel = channel,
            address = AgentValidation.normalizeAddress(channel, address),
            hue = hue ?: nextHue(),
            createdAt = clock(),
        )
        agents = agents + p
        save()
        p
    }

    fun update(p: AgentProfile): AgentProfile = synchronized(lock) {
        val fixed = p.copy(name = p.name.trim(), address = AgentValidation.normalizeAddress(p.channel, p.address))
        agents = agents.map { if (it.id == p.id) fixed else it }
        save()
        fixed
    }

    fun remove(id: String) = synchronized(lock) { agents = agents.filter { it.id != id }; save() }

    private fun load(): List<AgentProfile> = try {
        if (file.isFile) Json.decodeFromString(ListSerializer(AgentProfile.serializer()), file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun save() = AtomicFile.write(file, Json.encodeToString(ListSerializer(AgentProfile.serializer()), agents))
}

internal fun String.folded() = trim().lowercase(Locale.ROOT)
