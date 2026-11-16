package app.atall.core

import app.atall.coordinator.adapters.CardPeer
import app.atall.coordinator.adapters.WakeConfig
import app.atall.coordinator.adapters.WakeKind
import java.util.Locale

/**
 * C2 routing: which of the user's agents Juno reaches over the Atall connector. The connector is used when the phone is
 * paired and the agent has a live connection on the relay; otherwise the agent's text route (if any) is used.
 */
interface ConnectorAccess {
    /** The agent's relay id when it is reachable over the connector right now, else null. */
    fun relayIdFor(p: AgentProfile): String?

    /** A peer that posts signed task cards for [p], or null when the connector can't reach it now. */
    fun peer(p: AgentProfile): CardPeer?

    /** Runs started by "Run Story 1": never fall back to text. */
    fun connectorOnly(runId: String): Boolean = false

    /**
     * The agent's relay id when it is connected on the relay, whatever its route (Settings → Wake-up shows for it even
     * when its asks go by text). Same as [relayIdFor] unless a route like Full text keeps it off the connector.
     */
    fun linkedRelayId(p: AgentProfile): String? = relayIdFor(p)

    /** C3.1: [p] is connected but set to Full text, so its asks go by text (never a card). */
    fun byFullText(p: AgentProfile): Boolean = false

    /** How [p] is woken for a task card (webhook, event, its own schedule, …), or null when not on the connector. */
    fun wakeKind(p: AgentProfile): WakeKind? = null

    companion object {
        val NONE = object : ConnectorAccess {
            override fun relayIdFor(p: AgentProfile): String? = null
            override fun peer(p: AgentProfile): CardPeer? = null
        }
    }
}

/**
 * C3.1 "Full text": an agent whose route ([WakeConfig.kind]) is [WakeKind.FULL_TEXT] is never reached over the
 * connector. [relayIdFor] and [peer] say "no connector route", so Juno (@all, Juno asks) and the Router (@mentions) send
 * the whole packed ask by text at once and the text reply is the answer: no relay task, no "Atall #ref" nudge, no
 * fallback ladder. [linkedRelayId] still names the connection, for Settings.
 */
class FullTextRouting(private val base: ConnectorAccess, private val config: (relayId: String) -> WakeConfig) : ConnectorAccess {
    private fun fullText(relayId: String) = config(relayId).kind == WakeKind.FULL_TEXT
    override fun relayIdFor(p: AgentProfile): String? = base.relayIdFor(p)?.takeUnless(::fullText)
    override fun peer(p: AgentProfile): CardPeer? = if (base.relayIdFor(p)?.let(::fullText) == true) null else base.peer(p)
    override fun connectorOnly(runId: String): Boolean = base.connectorOnly(runId)
    override fun linkedRelayId(p: AgentProfile): String? = base.linkedRelayId(p)
    override fun byFullText(p: AgentProfile): Boolean = base.linkedRelayId(p)?.let(::fullText) == true
    override fun wakeKind(p: AgentProfile): WakeKind? = base.linkedRelayId(p)?.let { config(it).kind }
}

/**
 * How fast an agent usually answers, before the score log has measured it (fresh install). On the connector and woken by
 * a webhook or an event (Grok, kDot): fast. By text (RCS, Full text), on its own schedule (Duet, Muse), or only after a
 * tap: slow, so Juno never holds the answer for it; its answer folds in as a review. The score log replaces this as soon
 * as the agent has answered once.
 */
object LatencyPriors {
    const val FAST_MS = 60_000L
    const val SLOW_MS = 30 * 60_000L

    fun forAgent(onConnector: Boolean, wake: WakeKind?): Long = when {
        !onConnector -> SLOW_MS
        wake == null || wake == WakeKind.WEBHOOK || wake == WakeKind.EVENTS -> FAST_MS
        else -> SLOW_MS
    }
}

/** Pure rules: name matching between the user's agents and relay ids, per-agent connector timeouts. */
object ConnectorRouting {
    /** One-word names (mentionable) for agents that are only on the connector. */
    private val shortNames = mapOf("kdot" to "kDot", "grok" to "Grok", "hark" to "Hark", "duet" to "Duet", "muse" to "Muse")

    fun shortName(relayId: String, clientName: String? = null): String {
        shortNames[relayId.lowercase(Locale.ROOT)]?.let { return it }
        val full = RelayCopy.agentName(relayId, clientName)
        return full.split(Regex("\\s+")).last().filter { it.isLetterOrDigit() }.take(24).ifEmpty { relayId.take(24) }
    }

    /** Words a user's agent may be called for relay id [relayId]: "duet", "claude duet", "Duet". */
    private fun handles(relayId: String): Set<String> {
        val id = relayId.lowercase(Locale.ROOT)
        val full = RelayCopy.agentName(id).lowercase(Locale.ROOT)
        return setOf(id, full, full.split(' ').last(), shortName(id).lowercase(Locale.ROOT))
    }

    /** The relay id of [p] among [relayIds]: its saved [AgentProfile.relayId], else a name or alias match. */
    fun relayIdOf(p: AgentProfile, relayIds: Collection<String>): String? {
        p.relayId?.let { return it.takeIf { id -> relayIds.any { r -> r.equals(id, ignoreCase = true) } } }
        val names = (listOf(p.name) + p.aliases).map { it.trim().lowercase(Locale.ROOT) }.toSet()
        return relayIds.firstOrNull { r -> handles(r).any { it in names } }
    }

    /** Relay agents no registry agent stands for yet: (relay id, name to add). */
    fun missing(registry: List<AgentProfile>, relayIds: Collection<String>): List<Pair<String, String>> =
        relayIds.distinctBy { it.lowercase(Locale.ROOT) }
            .filter { r -> registry.none { relayIdOf(it, listOf(r)) != null } }
            .map { r -> r to uniqueName(shortName(r), registry) }

    private fun uniqueName(name: String, registry: List<AgentProfile>): String {
        if (registry.none { it.name.equals(name, ignoreCase = true) } && !name.equals(Main.NAME, ignoreCase = true)) return name
        var n = 2
        while (registry.any { it.name.equals("$name$n", ignoreCase = true) }) n++
        return "$name$n"
    }

    data class Timeouts(val softMs: Long, val hardMs: Long)

    /**
     * Per-agent timeouts over the connector (m05: a wait never ends before a pending agent's soft timeout). Agents pull
     * tasks when they next look (C3 adds wake-ups), so these are longer than a text's 3 minutes. Duet checks every few
     * hours on its own.
     */
    fun timeouts(relayId: String): Timeouts = when (relayId.lowercase(Locale.ROOT)) {
        "duet" -> Timeouts(15 * MIN, 4 * 60 * MIN)
        "muse" -> Timeouts(10 * MIN, 2 * 60 * MIN)
        else -> Timeouts(6 * MIN, 60 * MIN)
    }

    private const val MIN = 60_000L

    /** Story 1 (design.md), the C2 exit test: Juno asks Grok and Duet over the connector only. */
    fun story1(grok: String, duet: String) = "@${Main.NAME} ask @$grok and @$duet: Plan Saturday dinner for four, no pork or beef."
}
