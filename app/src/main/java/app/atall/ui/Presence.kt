package app.atall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.atall.coordinator.AgreementMap
import app.atall.coordinator.PresenceState
import app.atall.coordinator.PresenceView
import app.atall.core.AgentLogo
import app.atall.core.Main
import app.atall.core.ThreadMessage
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme
import kotlinx.serialization.json.Json

private val PresenceJson = Json { ignoreUnknownKeys = true }

fun parsePresence(detail: String?): PresenceView? = detail?.let { runCatching { PresenceJson.decodeFromString(PresenceView.serializer(), it) }.getOrNull() }

/**
 * One row of 20 dp avatars under Juno's answer (fast-coord ux.md §7): check = in, ring = working, moon = later, faded =
 * no answer. Nothing else: no ask cards, no waiting lines, no timer. A tap shows each agent's answer (without its tail)
 * and the comparison, where a disagreement keeps "Talk it over".
 */
@Composable
internal fun PresenceRow(m: ThreadMessage, peers: Map<String, PeerUi>, onTalkOver: (String, String) -> Unit) {
    val c = AppTheme.colors
    val v = remember(m.detail) { parsePresence(m.detail) } ?: return
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(CircleShape).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp).semantics { contentDescription = v.accessibility },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            v.agents.forEach { a -> PresenceAvatar(peers[a.agentId], a.name, a.state) }
        }
        if (open) {
            Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
                v.agents.forEachIndexed { i, a ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val p = peers[a.agentId]
                        Text(a.name, style = AppTheme.type.sender, color = p?.let { AppTheme.agent(it.hue).solid } ?: c.ink)
                        val note = a.note ?: when (a.state) {
                            PresenceState.WORKING -> "working"
                            PresenceState.LATER -> "later"
                            PresenceState.NEEDS_YOU -> "needs you"
                            PresenceState.NO_ANSWER -> "no answer"
                            else -> null
                        }
                        note?.let { Text(" · $it", style = AppTheme.type.caption, color = c.ink3) }
                    }
                    a.text?.let { t ->
                        val text = remember(t) { markdownText(t, c.bg) }
                        Text(text, Modifier.padding(top = 2.dp), style = AppTheme.type.body2, color = c.ink2)
                    }
                }
                v.map?.takeIf { it.disputed.isNotEmpty() }?.let { map -> Disagreements(map, peers, v.runId, onTalkOver) }
            }
        }
    }
}

@Composable
private fun Disagreements(map: AgreementMap, peers: Map<String, PeerUi>, runId: String, onTalkOver: (String, String) -> Unit) {
    val c = AppTheme.colors
    Text("They disagree", Modifier.padding(top = 12.dp), style = AppTheme.type.label, color = c.ink3)
    map.disputed.forEach { d ->
        Text(d.topic, Modifier.padding(top = 4.dp), style = AppTheme.type.body2, color = c.ink)
        d.sides.forEach { side ->
            Text("• " + side.agentIds.joinToString(", ") { peers[it]?.name ?: if (it == Main.AUTHOR) Main.NAME else "Agent" } + ": " + side.statement,
                style = AppTheme.type.body2, color = c.ink2)
        }
        if (d.challenge == app.atall.coordinator.ChallengeState.NONE) {
            Text("Talk it over", Modifier.padding(top = 4.dp).clip(CircleShape).clickable { onTalkOver(runId, d.id) }.padding(vertical = 2.dp),
                style = AppTheme.type.label, color = c.iris)
        }
    }
}

/** A 20 dp avatar with its state: a small check, a ring around it, a moon, or faded. */
@Composable
internal fun PresenceAvatar(peer: PeerUi?, name: String, state: PresenceState, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val c = AppTheme.colors
    val hue = peer?.hue ?: AgentHue.Slate
    val ring = AppTheme.agent(hue).solid
    Box(Modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        if (state == PresenceState.WORKING || state == PresenceState.NEEDS_YOU) {
            Canvas(Modifier.size(size + 6.dp)) { drawCircle(if (state == PresenceState.NEEDS_YOU) c.warning else ring, this.size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
        }
        PeerAvatar(peer?.logo ?: AgentLogo.NONE, hue, size, dimmed = state == PresenceState.LATER || state == PresenceState.NO_ANSWER)
        when (state) {
            PresenceState.IN, PresenceState.AGREED -> Box(
                Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(c.bg), contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Check, c.success, size = 10.dp) }
            PresenceState.LATER -> Text("☾", Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 2.dp), style = AppTheme.type.micro, color = c.ink3)
            else -> {}
        }
    }
}

/**
 * Juno's answer: double-tap = ♥ (the same chip agents' tapbacks use; double-tap again removes it), long-press = "One
 * of them was better", then tap the agent's avatar. No buttons, no rating row.
 */
@Composable
internal fun AnswerGestures(
    m: ThreadMessage,
    agents: List<Pair<String, String>>,
    peers: Map<String, PeerUi>,
    onHeart: (String) -> Unit,
    onOneBetter: (String, String) -> Unit,
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth().pointerInput(m.id, agents) {
        detectTapGestures(onDoubleTap = { onHeart(m.id) }, onLongPress = { if (agents.isNotEmpty()) menu = true })
    }) {
        Column {
            content()
            if (picking) {
                Text("Which one?", Modifier.padding(top = 6.dp), style = AppTheme.type.caption, color = c.ink3)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    agents.forEach { (id, name) ->
                        Column(Modifier.clip(AppTheme.radii.card).clickable { picking = false; m.runId?.let { onOneBetter(it, id) } }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            PeerAvatar(peers[id]?.logo ?: AgentLogo.NONE, peers[id]?.hue ?: AgentHue.Slate, 28.dp)
                            Text(name, style = AppTheme.type.micro, color = c.ink2)
                        }
                    }
                    Text("Cancel", Modifier.align(Alignment.CenterVertically).clickable { picking = false }.padding(4.dp), style = AppTheme.type.caption, color = c.ink3)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("One of them was better") }, onClick = { menu = false; picking = true })
        }
    }
}

/** The Your call card under the answer: one deciding question or "If X: A" lines, then a tap per choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YourCallCard(m: ThreadMessage, onChoice: (String, String) -> Unit) {
    val call = m.call ?: return
    val c = AppTheme.colors
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(12.dp)) {
        Text("Your call", style = AppTheme.type.label, color = c.ink3)
        Text(call, Modifier.padding(top = 4.dp), style = AppTheme.type.body2, color = c.ink)
        if (m.choices.isNotEmpty() && m.runId != null) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.choices.forEach { ch -> Chip(ch, { onChoice(m.runId!!, ch) }) }
            }
        }
    }
}
