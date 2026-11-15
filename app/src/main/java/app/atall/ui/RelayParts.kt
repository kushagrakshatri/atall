package app.atall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.RelayUi
import app.atall.TestTaskUi
import app.atall.core.RelayCopy
import app.atall.relay.RelayAgent
import app.atall.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** What Settings → Agents needs for the relay. Defaults keep previews and older call sites working. */
data class RelayParams(
    val ui: RelayUi = RelayUi(),
    /** Pairs with the owner code; returns null on success, else the line to show. */
    val onConnect: suspend (String) -> String? = { null },
    val onRevoke: (RelayAgent) -> Unit = {},
    val test: TestTaskUi? = null,
    val onSendTest: () -> Unit = {},
    val onCloseTest: () -> Unit = {},
    /** Debug → Run Story 1 (C2): null when sent, else what's missing. */
    val onStory1: suspend () -> String? = { null },
)

/** "Connected agents": Connect relay (asks once for the owner code), then each agent with last seen, last tool and Revoke. */
@Composable
internal fun RelaySection(p: RelayParams) {
    val c = AppTheme.colors
    var connecting by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<RelayAgent?>(null) }
    SectionLabel("Connected agents")
    Card {
        if (!p.ui.paired) {
            Row(Modifier.fillMaxWidth().clickable { connecting = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Connect relay", style = AppTheme.type.body, color = c.ink)
                    Text("Lets your AI apps send results to this phone", style = AppTheme.type.caption, color = c.ink3)
                }
                Icon(Glyph.Chevron, c.ink3, Modifier.size(20.dp), size = 20.dp)
            }
        } else {
            if (p.ui.agents.isEmpty()) {
                Text(
                    if (p.ui.loading) "Loading…" else "No agents connected yet.",
                    Modifier.padding(14.dp), style = AppTheme.type.body2, color = c.ink2,
                )
            }
            p.ui.agents.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = c.hairline)
                val name = RelayCopy.agentName(a.agentId, a.clientName)
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PeerAvatar(name, hueOf(a.agentId.hashCode()), 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = AppTheme.type.headline, color = c.ink)
                        Text(RelayCopy.agentLine(a.lastSeen, a.lastTool), style = AppTheme.type.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Chip("Revoke", { revoking = a })
                }
            }
        }
    }
    p.ui.problem?.let { line ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f), style = AppTheme.type.body2, color = c.danger)
            if (p.ui.reconnect) { Spacer(Modifier.width(8.dp)); Chip("Connect again", { connecting = true }) }
        }
    }
    if (connecting) ConnectDialog(p.onConnect) { connecting = false }
    revoking?.let { a ->
        val name = RelayCopy.agentName(a.agentId, a.clientName)
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke $name?", style = AppTheme.type.headline) },
            text = { Text("$name loses access right away. You can connect it again later.", style = AppTheme.type.body2) },
            confirmButton = { Chip("Revoke", { revoking = null; p.onRevoke(a) }, filled = true) },
            dismissButton = { Chip("Cancel", { revoking = null }) },
            containerColor = c.raised,
        )
    }
}

@Composable
private fun ConnectDialog(onConnect: suspend (String) -> String?, onDone: () -> Unit) {
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Connect relay", style = AppTheme.type.headline) },
        text = {
            Column {
                Text("Enter your owner code. You only do this once.", style = AppTheme.type.body2, color = c.ink2)
                Spacer(Modifier.height(12.dp))
                Field("Owner code", code, { code = it; error = null }, error = error, keyboard = KeyboardType.Password)
            }
        },
        confirmButton = {
            Chip(if (busy) "Connecting…" else "Connect", {
                if (busy) return@Chip
                busy = true
                scope.launch {
                    val problem = onConnect(code)
                    busy = false
                    if (problem == null) onDone() else error = problem
                }
            }, filled = true)
        },
        dismissButton = { Chip("Cancel", { if (!busy) onDone() }) },
        containerColor = c.raised,
    )
}

/** Debugging → "Send test task to all": the row, shown once the relay is connected. */
@Composable
internal fun TestTaskRow(p: RelayParams) {
    val c = AppTheme.colors
    HorizontalDivider(color = c.hairline)
    Row(Modifier.fillMaxWidth().clickable(onClick = p.onSendTest).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send test task to all", style = AppTheme.type.body, color = c.ink)
            Text("Posts a signed task and shows what comes back", style = AppTheme.type.caption, color = c.ink3)
        }
    }
    p.test?.let { TestTaskDialog(it, p.onCloseTest) }
    // C2 exit test on the phone: Story 1 with Grok and Duet over the connector only.
    val scope = rememberCoroutineScope()
    var story by remember { mutableStateOf<String?>(null) }
    HorizontalDivider(color = c.hairline)
    Row(Modifier.fillMaxWidth().clickable { scope.launch { story = p.onStory1() ?: "Sent. It's in your thread." } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Run Story 1", style = AppTheme.type.body, color = c.ink)
            Text(story ?: "Dinner plan from Grok and Duet, over Atall only", style = AppTheme.type.caption, color = c.ink3)
        }
    }
}

@Composable
private fun TestTaskDialog(t: TestTaskUi, onClose: () -> Unit) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Test task #${t.ref}", style = AppTheme.type.headline) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(t.status, style = AppTheme.type.body2, color = c.ink2)
                t.events.forEach { e ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.verified) Icon(Glyph.Check, c.success, Modifier.size(16.dp), size = 16.dp)
                        else Icon(Glyph.Close, c.danger, Modifier.size(16.dp), size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${RelayCopy.agentName(e.agentId, e.clientName)} · ${RelayCopy.eventKind(e.type)}" + if (e.verified) "" else " · not verified",
                            style = AppTheme.type.label, color = if (e.verified) c.ink else c.danger,
                        )
                    }
                    val text = RelayCopy.eventText(e.payload)
                    if (text.isNotBlank()) Text(text, Modifier.padding(start = 22.dp, top = 2.dp), style = AppTheme.type.caption, color = c.ink2)
                }
            }
        },
        confirmButton = { Chip("Done", onClose, filled = true) },
        containerColor = c.raised,
    )
}

@Preview(name = "Connected agents", widthDp = 412, heightDp = 500)
@Composable
private fun RelaySectionPreview() = PreviewTheme {
    Column(Modifier.padding(16.dp)) {
        RelaySection(RelayParams(RelayUi(paired = true, agents = listOf(
            RelayAgent("kdot", "cn_1", "ChatGPT", lastSeen = "2026-11-15T18:00:00Z", lastTool = "atall_post_result"),
            RelayAgent("duet", "cn_2", "Claude", lastSeen = null, lastTool = null),
        ))))
    }
}
