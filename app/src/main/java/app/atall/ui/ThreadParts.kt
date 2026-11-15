package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.atall.coordinator.AgreementMap
import app.atall.coordinator.ChallengeState
import app.atall.coordinator.MapStatus
import app.atall.coordinator.ParticipantState
import app.atall.core.Attribution
import app.atall.core.Main
import app.atall.core.Markdown
import app.atall.core.ReactionChip
import app.atall.core.SendStatus
import app.atall.core.ThreadMessage
import app.atall.ui.theme.AppTheme
import kotlinx.serialization.json.Json

/** One label per agent line, always: "replied directly", "answered Juno" or "messaged you". */
fun attributionLabel(peer: PeerUi?, m: ThreadMessage): String? {
    if (peer == null) return null
    return when (m.shownAttribution) {
        Attribution.DIRECT -> "replied directly"
        Attribution.JUNO -> "answered ${Main.NAME}"
        Attribution.UNPROMPTED -> "messaged you"
        null -> null
    }
}

/**
 * The provenance badge on an agent's answer (C2): "Verified" when it came over the Atall connector with a relay
 * countersignature that checked out for that agent's connection, "Via text" when it came as a text. Nothing elsewhere.
 */
fun provenanceLabel(m: ThreadMessage): String? = when (m.via) {
    app.atall.coordinator.Via.CONNECTOR -> "Verified"
    app.atall.coordinator.Via.TEXT -> "Via text"
    null -> null
}

/** Juno's and agents' text with its Markdown rendered: bold, italics, inline code, bullet and numbered lists. */
internal fun markdownText(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    Markdown.parse(text).forEachIndexed { i, line ->
        if (i > 0) append("\n")
        if (line.prefix.isNotEmpty()) append(line.prefix)
        for (sp in line.spans) {
            val style = SpanStyle(
                fontWeight = if (sp.bold || line.heading) FontWeight(600) else null,
                fontStyle = if (sp.italic) FontStyle.Italic else null,
                fontFamily = if (sp.code) FontFamily.Monospace else null,
                background = if (sp.code) codeBackground else Color.Unspecified,
            )
            withStyle(style) { append(sp.text) }
        }
    }
}

/** Tapbacks under the user's bubble ("👍 Nova") or on a sub-task. */
@Composable
internal fun ReactionChips(chips: List<ReactionChip>, peers: Map<String, PeerUi>) {
    val c = AppTheme.colors
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in chips) {
            val name = peers[r.by]?.name ?: "Agent"
            Text(
                "${r.emoji} $name",
                Modifier.clip(CircleShape).background(c.sunken).padding(horizontal = 8.dp, vertical = 2.dp),
                style = AppTheme.type.caption, color = c.ink2,
            )
        }
    }
}

/** The head of a delegating Juno turn: "Asking Nova and Scout" (live) or "Asked Nova and Scout · 18s". */
@Composable
internal fun RunHead(m: ThreadMessage, peers: List<PeerUi>, onStop: (String) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MainAvatar(28.dp)
        Spacer(Modifier.width(AppTheme.spacing.avatarToText))
        Column(Modifier.weight(1f)) {
            Text(Main.NAME, style = AppTheme.type.sender, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labelWithNames(m.text, peers), style = AppTheme.type.label, color = c.ink2)
                if (m.streaming) { Spacer(Modifier.width(6.dp)); Dot(c.ink3) }
            }
            m.detail?.takeIf { it.isNotBlank() }?.let { d ->
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, style = AppTheme.type.micro, color = if (m.streaming) c.warning else c.ink3)
                    if (m.streaming && m.runId != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("Stop waiting", Modifier.clip(CircleShape).clickable { onStop(m.runId!!) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = AppTheme.type.caption, color = c.ink)
                    }
                }
            }
        }
    }
}

/** Agent names in the strip label in their hue, bold. */
private fun labelWithNames(text: String, peers: List<PeerUi>) = buildAnnotatedString {
    val byName = peers.sortedByDescending { it.name.length }
    var i = 0
    while (i < text.length) {
        val p = byName.firstOrNull { text.startsWith(it.name, i) && (i + it.name.length == text.length || !text[i + it.name.length].isLetterOrDigit()) && (i == 0 || !text[i - 1].isLetterOrDigit()) }
        if (p != null) {
            withStyle(SpanStyle(fontWeight = FontWeight(600))) { append(p.name) }
            i += p.name.length
        } else { append(text[i]); i++ }
    }
}

/**
 * What Juno asked one agent: "To Nova" and the sub-task, with its status ("Waiting", "Replied in 12s",
 * "Reacted 🧳 · waiting") and the agent's tapback as a small chip.
 */
@Composable
internal fun SubTask(m: ThreadMessage, peer: PeerUi?, peers: Map<String, PeerUi> = emptyMap()) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("To ", style = AppTheme.type.caption, color = c.ink3)
            Text(peer?.name ?: "agent", style = AppTheme.type.sender, color = peer?.let { AppTheme.agent(it.hue).solid } ?: c.ink2)
            m.detail?.let { Text(" · $it", style = AppTheme.type.caption, color = c.ink3) }
        }
        val text = remember(m.text) { markdownText(m.text, c.sunken) }
        Text(text, Modifier.padding(top = 2.dp), style = AppTheme.type.body2, color = c.ink2)
        if (m.reactions.isNotEmpty()) ReactionChips(m.reactions, peers + listOfNotNull(peer).associateBy { it.id })
    }
}

/** The approval gate: "Okay to have Nova do this?" with what it would send, Yes / No. */
@Composable
internal fun Approval(m: ThreadMessage, peer: PeerUi?, onApprove: (String, Boolean) -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(m.text, style = AppTheme.type.bodyStrong, color = c.ink)
        m.detail?.let { Text(it, Modifier.padding(top = 2.dp), style = AppTheme.type.body2, color = c.ink2) }
        Spacer(Modifier.height(8.dp))
        // A talk relay that carries a private agent's answer: "Share Hark's answer with kDot?".
        val share = m.text.startsWith("Share ")
        when (m.status) {
            SendStatus.SENT -> Text(if (share) "Shared with ${peer?.name ?: "the agent"}." else "Approved. Sent to ${peer?.name ?: "the agent"}.", style = AppTheme.type.caption, color = c.ink3)
            SendStatus.NOT_SENT -> Text(if (share) "Not shared." else "Not sent.", style = AppTheme.type.caption, color = c.ink3)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (share) "Share" else "Yes, send it", { onApprove(m.id, true) }, filled = true)
                Chip(if (share) "Don't share" else "No", { onApprove(m.id, false) })
            }
        }
    }
}

private val MapJson = Json { ignoreUnknownKeys = true }

/** The @all Agreement Map: verdict, what everyone agrees on, disputes (with "Talk it over"), one-offs, raw answers. */
@Composable
internal fun AgreementCard(m: ThreadMessage, peers: Map<String, PeerUi>, onTalkOver: (String, String) -> Unit) {
    val c = AppTheme.colors
    val map = remember(m.detail) { m.detail?.let { runCatching { MapJson.decodeFromString(AgreementMap.serializer(), it) }.getOrNull() } }
    var raw by rememberSaveable(m.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
        Text(if (map?.status == MapStatus.COLLECTING) "Collecting answers" else m.text, style = AppTheme.type.bodyStrong, color = c.ink)
        if (map == null) return@Column
        val who = map.participants.joinToString(" · ") { p ->
            p.name + when (p.state) {
                ParticipantState.ANSWERED -> p.answeredInMs?.let { " ${it / 1000}s" } ?: " ✓"
                ParticipantState.WAITING -> " …"
                ParticipantState.DIDNT_ANSWER -> " didn't answer"
                ParticipantState.NEEDS_TAP -> " needs a tap"
            }
        }
        if (who.isNotBlank()) Text(who, Modifier.padding(top = 4.dp), style = AppTheme.type.caption, color = c.ink3)
        if (map.agreed.isNotEmpty()) {
            Section("Everyone agrees")
            map.agreed.forEach { Bullet(it.statement) }
        }
        if (map.disputed.isNotEmpty()) {
            Section("They disagree")
            map.disputed.forEach { d ->
                Text(d.topic, Modifier.padding(top = 4.dp), style = AppTheme.type.body2.copy(fontWeight = FontWeight(600)), color = c.ink)
                d.sides.forEach { side -> Bullet(side.agentIds.joinToString(", ") { peers[it]?.name ?: if (it == Main.AUTHOR) Main.NAME else "Agent" } + ": " + side.statement) }
                when (d.challenge) {
                    ChallengeState.NONE -> if (map.status == MapStatus.READY && m.runId != null) {
                        Spacer(Modifier.height(4.dp)); Chip("Talk it over", { onTalkOver(m.runId!!, d.id) })
                    }
                    ChallengeState.ASKED -> Text("Talking it over…", style = AppTheme.type.caption, color = c.ink3)
                    ChallengeState.RESOLVED -> d.resolution?.let { Text(it, style = AppTheme.type.caption, color = c.ink3) }
                }
            }
        }
        if (map.unique.isNotEmpty()) {
            Section("Only one said")
            map.unique.forEach { u -> Bullet((u.agentIds.firstOrNull()?.let { peers[it]?.name ?: if (it == Main.AUTHOR) Main.NAME else null }?.let { "$it: " } ?: "") + u.statement) }
        }
        if (map.raw.isNotEmpty()) {
            Text(if (raw) "Hide answers" else "Show every answer", Modifier.padding(top = 8.dp).clickable { raw = !raw }, style = AppTheme.type.label, color = c.iris)
            if (raw) map.raw.forEach { a ->
                Text(a.name, Modifier.padding(top = 6.dp), style = AppTheme.type.sender, color = c.ink)
                Text(a.text, style = AppTheme.type.body2, color = c.ink2)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 10.dp), style = AppTheme.type.label, color = AppTheme.colors.ink3)
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", Modifier.padding(top = 2.dp), style = AppTheme.type.body2, color = AppTheme.colors.ink)
}
