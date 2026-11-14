package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.coordinator.EntryKind
import app.atall.coordinator.LandedRow
import app.atall.coordinator.Relay
import app.atall.coordinator.TalkRowState
import app.atall.coordinator.TalkView
import app.atall.core.Main
import app.atall.core.ThreadMessage
import app.atall.ui.theme.AppTheme
import kotlinx.serialization.json.Json

/** What a talk card can do. Nothing here sends to an agent by itself except Wrap up / Leave out / Stop on the talk. */
class TalkActions(
    val onWrapUp: (String) -> Unit = {},
    val onLeaveOut: (String, String) -> Unit = { _, _ -> },
    val onStop: (String) -> Unit = {},
    /** "Ask Hark about this?": puts "@Hark …" in the composer; the user sends it (or not). */
    val onAsk: (String, String) -> Unit = { _, _ -> },
)

private val TalkJson = Json { ignoreUnknownKeys = true }

fun parseTalk(detail: String?): TalkView? = detail?.let { runCatching { TalkJson.decodeFromString(TalkView.serializer(), it) }.getOrNull() }

/** The composer text for "Ask {to} about this?": the user's own words to send, quoting the line that asked. */
fun askAboutDraft(to: String, from: String, quote: String): String = "@$to $from asks: \"$quote\""

/**
 * "Talk it over" (ux.md layout A, expanding into B). Live: one row per agent in the open round ("Waiting on kDot",
 * a reply preview, "Still waiting on kDot" in warning) with Stop, and after the soft deadline Wrap up now and Leave X
 * out. Done: "Where they landed" (only when someone moved or they still disagree), then the rounds behind a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TalkCard(m: ThreadMessage, peers: Map<String, PeerUi>, actions: TalkActions) {
    val c = AppTheme.colors
    val t = remember(m.detail) { parseTalk(m.detail) } ?: return
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    var sentOpen by rememberSaveable(m.id + ":sent") { mutableStateOf(false) }
    val hues = peers.mapValues { AppTheme.agent(it.value.hue).solid }
    fun nameColor(id: String) = hues[id] ?: c.ink
    Column(Modifier.fillMaxWidth().semantics { contentDescription = t.accessibility }) {
        if (t.live) {
            Column(Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
                t.caption?.let { Text(it, style = AppTheme.type.label, color = c.ink3) }
                t.rows.forEach { r ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(r.name, style = AppTheme.type.sender, color = nameColor(r.agentId))
                        Spacer(Modifier.width(8.dp))
                        val color = when (r.state) {
                            TalkRowState.STILL_WAITING -> c.warning
                            TalkRowState.REPLIED -> c.ink
                            else -> c.ink3
                        }
                        Text(if (r.state == TalkRowState.REPLIED) r.line + " ✓" else r.line, style = AppTheme.type.body2, color = color)
                    }
                }
            }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.canWrapUp) Chip("Wrap up now", { actions.onWrapUp(t.runId) }, filled = true)
                t.leaveOut.forEach { a -> Chip("Leave ${a.name} out", { actions.onLeaveOut(t.runId, a.agentId) }) }
                if (t.canStop) Chip("Stop", { actions.onStop(t.runId) })
            }
            return@Column
        }
        if (t.landed.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
                Text("Where they landed", style = AppTheme.type.label, color = c.ink3)
                t.landed.take(3).forEach { LandedLine(it, nameColor(it.agentId)) }
                if (t.landed.size > 3) Text("+${t.landed.size - 3} more", Modifier.padding(top = 4.dp), style = AppTheme.type.caption, color = c.ink3)
            }
        }
        t.endNote?.let { Text(it, Modifier.padding(top = 6.dp), style = AppTheme.type.caption, color = c.ink3) }
        if (t.rounds.isNotEmpty()) {
            Text(if (open) "Hide the talk" else "Show the talk", Modifier.padding(top = 8.dp).clickable { open = !open }, style = AppTheme.type.label, color = c.iris)
        }
        if (open) {
            t.rounds.forEach { r ->
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
                    Row { Text(r.caption, Modifier.weight(1f), style = AppTheme.type.label, color = c.ink3); r.time?.let { Text(it, style = AppTheme.type.micro, color = c.ink3) } }
                    r.lines.forEach { l ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l.name, style = AppTheme.type.sender, color = if (l.kind == EntryKind.AGENT) nameColor(l.agentId) else c.ink)
                                if (l.kind == EntryKind.JUNO) Text(" · ${Main.NAME}", style = AppTheme.type.caption, color = c.ink3)
                                if (l.late) Tag("Late")
                                if (l.passed) Tag("Agrees")
                            }
                            if (!l.passed) Text(remember(l.text) { markdownText(l.text, c.surface) }, style = AppTheme.type.body2, color = c.ink2)
                        }
                    }
                }
            }
            if (t.sent.isNotEmpty()) {
                Text(if (sentOpen) "Hide what ${Main.NAME} sent" else "What ${Main.NAME} sent", Modifier.padding(top = 8.dp).clickable { sentOpen = !sentOpen }, style = AppTheme.type.label, color = c.iris)
                if (sentOpen) t.sent.forEach { s ->
                    Text("To ${s.name}", Modifier.padding(top = 6.dp), style = AppTheme.type.sender, color = nameColor(s.agentId))
                    Text(s.text, style = AppTheme.type.caption, color = c.ink2)
                }
            }
        }
        if (t.suggestions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                t.suggestions.forEach { s -> Chip("Ask ${s.name} about this?", { actions.onAsk(s.name, askAboutDraft(s.name, s.fromName, s.quote)) }) }
            }
        }
    }
}

@Composable
private fun LandedLine(l: LandedRow, color: androidx.compose.ui.graphics.Color) {
    val c = AppTheme.colors
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.name, style = AppTheme.type.sender, color = color)
            Spacer(Modifier.width(8.dp))
            Text(buildAnnotatedString {
                l.first?.let { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = c.ink3)) { append(it) }; append(" → ") }
                withStyle(SpanStyle(fontWeight = FontWeight(600))) { append(l.final) }
            }, style = AppTheme.type.body2, color = c.ink)
            l.tag?.let { Tag(it) }
        }
        if (l.reason.isNotBlank()) Text(l.reason, style = AppTheme.type.caption, color = c.ink3)
    }
}

@Composable
private fun Tag(text: String) {
    val c = AppTheme.colors
    Text(text, Modifier.padding(start = 6.dp).clip(CircleShape).hairlineBorder(CircleShape).padding(horizontal = 6.dp, vertical = 1.dp), style = AppTheme.type.micro, color = c.ink2)
}

/** Juno's choices under a talk's wrap-up ("Go with Rooh"): tapping one sends it as the user's own message. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Choices(m: ThreadMessage, onChoice: (String, String) -> Unit) {
    if (m.choices.isEmpty() || m.runId == null) return
    val c = AppTheme.colors
    Column(Modifier.padding(top = 8.dp)) {
        if (m.yourCall) Text("Your call", Modifier.padding(bottom = 4.dp), style = AppTheme.type.label, color = c.ink3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.choices.forEachIndexed { i, ch -> Chip(ch, { onChoice(m.runId!!, ch) }, filled = i == 0 && !m.yourCall) }
        }
    }
}

/** "Ask Hark about this?" under an agent's line that @mentions another agent. Only fills the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskChips(m: ThreadMessage, peers: Map<String, PeerUi>, onAsk: (String, String) -> Unit) {
    val targets = m.suggest.mapNotNull { peers[it] }.filter { !it.paused }
    if (targets.isEmpty()) return
    val from = peers[m.author]?.name ?: "An agent"
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEach { p -> Chip("Ask ${p.name} about this?", { onAsk(p.name, askAboutDraft(p.name, from, Relay.quoteFor(m.text, p.name))) }) }
    }
}

@Preview(name = "Talk · done", widthDp = 412)
@Composable
private fun TalkPreview() = PreviewTheme {
    val t = TalkView("r1", false, "Nova and Scout talked it over", "1m 48s", false, null, emptyList(), emptyList(),
        listOf(LandedRow("p1", "Nova", null, "Rooh 7:00", null, "Found an earlier table"), LandedRow("p2", "Scout", "Sakoon 7:30", "Rooh 7:00", "Changed its mind", "Sakoon only has bar seats for four")),
        false, false, emptyList(), emptyList(), emptyList(), false, null, emptyList(), "")
    TalkCard(ThreadMessage("t", 0, Main.AUTHOR, t.label, detail = TalkJson.encodeToString(TalkView.serializer(), t)), SamplePeers.associateBy { it.id }, TalkActions())
}
