package app.atall.core

import app.atall.relay.RelayEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words for the relay parts of Settings → Agents and the debug test task. Pure Kotlin; tested in RelayCopyTest. */
object RelayCopy {
    /** The names Kush knows his relay agents by, keyed by relay agent_id. */
    private val names = mapOf(
        "kdot" to "kDot",
        "grok" to "Grok",
        "hark" to "Hark",
        "duet" to "Claude Duet",
        "muse" to "Meta Muse",
    )

    fun agentName(agentId: String, clientName: String? = null): String =
        names[agentId.lowercase(Locale.ROOT)] ?: clientName?.takeIf { it.isNotBlank() } ?: agentId

    /** The agent's last MCP tool, in plain words. */
    fun tool(name: String?): String? = when (name?.trim()) {
        null, "" -> null
        "atall_inbox" -> "Checked for tasks"
        "atall_get_task" -> "Read a task"
        "atall_claim" -> "Took a task"
        "atall_progress" -> "Sent progress"
        "atall_post_result" -> "Sent a result"
        "atall_ask" -> "Asked you something"
        "atall_request_approval" -> "Asked for your OK"
        "atall_manifest" -> "Shared what it can do"
        "atall_ping_write" -> "Sent a test ping"
        else -> name.removePrefix("atall_").replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "Seen just now", "Seen 5 min ago", "Seen 3 h ago", "Seen yesterday", "Seen Nov 2"; "Not seen yet" without a time. */
    fun lastSeen(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Not seen yet"
        val ago = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
        return when {
            ago.toMinutes() < 1 -> "Seen just now"
            ago.toMinutes() < 60 -> "Seen ${ago.toMinutes()} min ago"
            ago.toHours() < 24 -> "Seen ${ago.toHours()} h ago"
            ago.toDays() < 2 -> "Seen yesterday"
            else -> "Seen " + DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(zone).format(t)
        }
    }

    /** The caption under an agent: "Seen 5 min ago · Sent a result". */
    fun agentLine(lastSeenIso: String?, lastTool: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        listOfNotNull(lastSeen(lastSeenIso, now, zone), tool(lastTool)).joinToString(" · ")

    fun eventKind(type: String): String = when (type) {
        RelayEvent.RESULT -> "Result"
        RelayEvent.PROGRESS -> "Progress"
        RelayEvent.QUESTION -> "Question"
        RelayEvent.CLAIM -> "Took it"
        RelayEvent.APPROVAL_REQUEST -> "Wants your OK"
        RelayEvent.PRESENCE -> "Online"
        RelayEvent.MANIFEST -> "Tools"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** One readable line from an event payload: its summary/text/question, else compact JSON, cut to [max]. */
    fun eventText(payload: JsonElement, max: Int = 280): String {
        val obj = payload as? JsonObject
        val picked = obj?.let { o ->
            listOf("summary", "text", "question", "message", "note", "reason", "status")
                .firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content }
        } ?: when (payload) {
            is JsonNull -> ""
            is JsonPrimitive -> payload.content
            is JsonArray, is JsonObject -> payload.toString()
        }
        val one = picked.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }
}
