package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.core.AgentProfile
import app.atall.core.Main
import app.atall.ui.theme.AppTheme

/** The ChatGPT account row. */
data class AccountUi(val state: String, val email: String)

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Icon(Glyph.Back, AppTheme.colors.ink) }
        Spacer(Modifier.width(4.dp))
        Text(title, style = AppTheme.type.title, color = AppTheme.colors.ink)
    }
}

@Composable
internal fun SectionLabel(text: String) =
    Text(text, Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp), style = AppTheme.type.label, color = AppTheme.colors.ink3)

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(AppTheme.colors.surface).hairlineBorder(AppTheme.radii.card, strong = false)) { content() }
}

/** Settings → Debugging: the message-text switch, Copy debug log, the version, the hidden Sentry DSN field. */
data class DebugUi(
    val sendText: Boolean = true,
    val onSendText: (Boolean) -> Unit = {},
    val onCopyLog: () -> Unit = {},
    val copiedNote: String? = null,
    val version: String = "",
    val dsn: String = "",
    val onDsn: (String) -> Unit = {},
    val dsnNote: String = "",
    /** C3: pretend the relay is down, to try the text fallback on the phone. */
    val relayDown: Boolean = false,
    val onRelayDown: (Boolean) -> Unit = {},
)

/** C3: Settings → Agents → the agent → Wake-up. Null for agents that aren't on the connector. */
data class WakeUi(
    val labels: List<String>,
    val selected: Int,
    val caption: String,
    val onSelect: (Int) -> Unit,
    /** Grok: the routine's webhook URL and key (the key is never shown back). */
    val webhook: Boolean = false,
    val webhookUrl: String = "",
    val onWebhookUrl: (String) -> Unit = {},
    val webhookKeySaved: Boolean = false,
    val onWebhookKey: (String) -> Unit = {},
    /** On its own schedule: how often it checks, in minutes (Duet's pickup window). */
    val windowMinutes: String? = null,
    val onWindow: (String) -> Unit = {},
    /** Muse: its WhatsApp number, so a tap opens that chat. */
    val whatsApp: String? = null,
    val onWhatsApp: (String) -> Unit = {},
    val onCopySetup: () -> Unit = {},
    val copied: Boolean = false,
)

@Composable
private fun WakeSection(w: WakeUi) {
    val c = AppTheme.colors
    SectionLabel("Wake-up")
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                w.labels.forEachIndexed { i, l -> Chip(l, { w.onSelect(i) }, filled = i == w.selected) }
            }
            Spacer(Modifier.height(8.dp))
            Text(w.caption, style = AppTheme.type.caption, color = c.ink3)
            Spacer(Modifier.height(10.dp))
            Chip(if (w.copied) "Copied" else "Copy setup", w.onCopySetup)
        }
    }
    if (w.webhook) {
        Spacer(Modifier.height(8.dp))
        Field("Webhook URL", w.webhookUrl, w.onWebhookUrl, helper = "From the routine's Webhook section (POST to).")
        var key by remember { mutableStateOf("") }
        Field("Webhook key", key, { key = it; w.onWebhookKey(it) }, helper = if (w.webhookKeySaved && key.isEmpty()) "Saved. Paste a new one to replace it." else "Kept encrypted on this phone.")
    }
    w.windowMinutes?.let { Spacer(Modifier.height(8.dp)); Field("Checks about every (minutes)", it, w.onWindow) }
    w.whatsApp?.let { Spacer(Modifier.height(8.dp)); Field("Muse's WhatsApp number", it, w.onWhatsApp, helper = "Optional. A tap opens that chat.") }
}

@Composable
fun AgentsScreen(
    peers: List<PeerUi>, account: AccountUi, notificationAccess: Boolean,
    onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit,
    onSignIn: () -> Unit, onSignOut: () -> Unit, onManageUsage: () -> Unit, onTurnOnAccess: () -> Unit, onLicences: () -> Unit,
    /** "Your name": what agents and Juno call you. Prefilled with the ChatGPT first name until you change it. */
    userName: String = "", onUserNameChange: (String) -> Unit = {},
    debug: DebugUi = DebugUi(),
    relay: RelayParams = RelayParams(),
) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Agents", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Card {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    MainAvatar(36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Main.NAME, style = AppTheme.type.headline, color = c.ink)
                        Text("Default · answers anything you don't @mention", style = AppTheme.type.caption, color = c.ink3)
                    }
                }
                peers.forEach { p ->
                    HorizontalDivider(color = c.hairline)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(p.name, p.hue, 36.dp, dimmed = p.paused || !p.channel.implemented)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = AppTheme.type.headline, color = c.ink)
                            Text("${p.status} · ${Copy.descriptor(p.channel)}", style = AppTheme.type.caption, color = c.ink3)
                        }
                        Icon(Glyph.Chevron, c.ink3, Modifier.size(20.dp), size = 20.dp)
                    }
                }
                HorizontalDivider(color = c.hairline)
                Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AddAvatar(36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Add an agent", style = AppTheme.type.body, color = c.ink)
                }
            }
            if (peers.isEmpty()) {
                Text("No other agents yet. Add an agent you text with.", Modifier.padding(start = 4.dp, top = 10.dp), style = AppTheme.type.body2, color = c.ink2)
            }
            if (!notificationAccess) {
                SectionLabel("Replies")
                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent replies can't reach the thread.", Modifier.weight(1f), style = AppTheme.type.body2, color = c.ink)
                        Chip("Turn on", onTurnOnAccess)
                    }
                }
            }
            RelaySection(relay)
            SectionLabel("Your name")
            Field(
                "Your name", userName, onUserNameChange,
                helper = "What ${Main.NAME} and your agents call you, as in \"From Atall (${possessive(userName.trim().ifBlank { "Your" }) } thread)\".",
            )
            SectionLabel("ChatGPT account")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when (account.state) {
                            "signed_in" -> account.email.ifBlank { "Signed in" }
                            "ended" -> "Your sign-in ended"
                            else -> "Not signed in"
                        },
                        style = AppTheme.type.body, color = c.ink,
                    )
                    Text("Powers ${Main.NAME}. Messages to ${Main.NAME} go to OpenAI.", style = AppTheme.type.caption, color = c.ink3)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (account.state == "signed_in") {
                            Chip("Manage usage", onManageUsage)
                            Chip("Sign out", onSignOut)
                        } else {
                            Chip(if (account.state == "ended") "Sign in again" else "Sign in", onSignIn, filled = true)
                        }
                    }
                }
            }
            SectionLabel("Debugging")
            Card {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send message text with debug logs", style = AppTheme.type.body, color = c.ink)
                        Text("Phone numbers, codes and keys are always removed", style = AppTheme.type.caption, color = c.ink3)
                    }
                    Switch(
                        checked = debug.sendText, onCheckedChange = debug.onSendText,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.onInk, uncheckedTrackColor = c.sunken, uncheckedBorderColor = c.hairlineStrong, uncheckedThumbColor = c.ink3),
                    )
                }
                HorizontalDivider(color = c.hairline)
                Row(Modifier.fillMaxWidth().clickable(onClick = debug.onCopyLog).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Copy debug log", style = AppTheme.type.body, color = c.ink)
                        Text(debug.copiedNote ?: "The last 500 lines, ready to paste", style = AppTheme.type.caption, color = c.ink3)
                    }
                }
                if (relay.ui.paired) TestTaskRow(relay)
                if (relay.ui.paired) {
                    HorizontalDivider(color = c.hairline)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Pretend the relay is down", style = AppTheme.type.body, color = c.ink)
                            Text("Tests the fallback by text", style = AppTheme.type.caption, color = c.ink3)
                        }
                        Switch(
                            checked = debug.relayDown, onCheckedChange = debug.onRelayDown,
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.onInk, uncheckedTrackColor = c.sunken, uncheckedBorderColor = c.hairlineStrong, uncheckedThumbColor = c.ink3),
                        )
                    }
                }
            }
            SectionLabel("About")
            var taps by remember { mutableStateOf(0) }
            Card {
                Row(Modifier.fillMaxWidth().clickable(onClick = onLicences).padding(14.dp)) {
                    Text("Open-source licences", style = AppTheme.type.body, color = c.ink)
                }
                HorizontalDivider(color = c.hairline)
                // Five taps on the version show the hidden Sentry DSN field (for a build that has none).
                Row(Modifier.fillMaxWidth().clickable { taps++ }.padding(14.dp)) {
                    Text("Version ${debug.version}", style = AppTheme.type.body, color = c.ink)
                }
            }
            if (taps >= 5 || debug.dsn.isNotBlank()) {
                SectionLabel("Debug: Sentry DSN")
                Field(
                    "Debug: Sentry DSN", debug.dsn, debug.onDsn,
                    helper = debug.dsnNote,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AddAgentScreen(form: AgentFormState, existing: List<AgentProfile>, onBack: () -> Unit, onAdd: () -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Add an agent", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Any agent you text with. Use the name you'll @mention.", style = AppTheme.type.body2, color = c.ink2)
            Spacer(Modifier.height(16.dp))
            AgentForm(form, existing)
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton(if (form.name.isBlank()) "Add agent" else "Add ${form.name.trim()}", onAdd, enabled = form.name.isNotBlank())
        }
    }
}

@Composable
fun AgentDetailScreen(
    peer: PeerUi, form: AgentFormState, existing: List<AgentProfile>,
    onBack: () -> Unit, onSave: () -> Unit, onPause: (Boolean) -> Unit, onRemove: () -> Unit,
    askBeforeSharing: Boolean = false, onAskBeforeSharing: (Boolean) -> Unit = {},
    wake: WakeUi? = null,
) {
    val c = AppTheme.colors
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            PeerAvatar(form.name.ifBlank { peer.name }, hueOf(form.hue), 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(peer.name, style = AppTheme.type.title, color = c.ink)
            Text("${peer.status} · ${Copy.descriptor(peer.channel)}", style = AppTheme.type.body2, color = c.ink2)
            SectionLabel("Reach")
            Card {
                Column(Modifier.padding(14.dp)) {
                    val connector = peer.channel == app.atall.api.ChannelType.CONNECTOR
                    Text(when {
                        peer.channel == app.atall.api.ChannelType.RCS -> "Texts from ${peer.address}"
                        connector -> "Over the Atall connector"
                        else -> "${Copy.channelLabel(peer.channel)} · ${peer.address}"
                    }, style = AppTheme.type.body, color = c.ink)
                    Text(if (connector) "Signed tasks, verified answers" else "Read from your notifications", style = AppTheme.type.caption, color = c.ink3)
                }
                HorizontalDivider(color = c.hairline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Paused", style = AppTheme.type.body, color = c.ink)
                        Text("Atall won't send to or listen for ${peer.name}", style = AppTheme.type.caption, color = c.ink3)
                    }
                    Switch(
                        checked = peer.paused, onCheckedChange = onPause,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.onInk, uncheckedTrackColor = c.sunken, uncheckedBorderColor = c.hairlineStrong, uncheckedThumbColor = c.ink3),
                    )
                }
            }
            wake?.let { WakeSection(it) }
            SectionLabel("Talking it over")
            Card {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask before sharing its answers", style = AppTheme.type.body, color = c.ink)
                        Text("When your agents talk something over, ${peer.name}'s answers go to the others only after you tap Share.", style = AppTheme.type.caption, color = c.ink3)
                    }
                    Switch(
                        checked = askBeforeSharing, onCheckedChange = onAskBeforeSharing,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.onInk, uncheckedTrackColor = c.sunken, uncheckedBorderColor = c.hairlineStrong, uncheckedThumbColor = c.ink3),
                    )
                }
                HorizontalDivider(color = c.hairline)
                Text("In a talk, ${Main.NAME} sends ${peer.name} what the others said.", Modifier.padding(14.dp), style = AppTheme.type.caption, color = c.ink3)
            }
            SectionLabel("Name and number")
            AgentForm(form, existing)
            SectionLabel("Color")
            HueSwatches(form.hue) { form.hue = it }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save", onSave, enabled = form.name.isNotBlank())
            Spacer(Modifier.height(8.dp))
            TextButton("Remove ${peer.name}", { confirm = true }, color = c.danger)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove ${peer.name}?", style = AppTheme.type.headline) },
            text = { Text("Past messages stay in the thread. You won't be able to @mention ${peer.name} until you add it again.", style = AppTheme.type.body2) },
            confirmButton = { Chip("Remove", { confirm = false; onRemove() }, filled = true) },
            dismissButton = { Chip("Cancel", { confirm = false }) },
            containerColor = c.raised,
        )
    }
}

@Composable
fun LicencesDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licences", style = AppTheme.type.headline) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Atall is MIT licensed. Geist and Geist Mono fonts:", style = AppTheme.type.body2)
                Spacer(Modifier.height(8.dp))
                Text(text, style = AppTheme.type.caption)
            }
        },
        confirmButton = { Chip("Done", onDismiss, filled = true) },
        containerColor = AppTheme.colors.raised,
    )
}

@Preview(name = "Agents", widthDp = 412, heightDp = 900)
@Composable
private fun AgentsPreview() = PreviewTheme {
    AgentsScreen(SamplePeers, AccountUi("signed_in", "you@example.com"), notificationAccess = false, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Agent detail", widthDp = 412, heightDp = 1000)
@Composable
private fun AgentDetailPreview() = PreviewTheme {
    val p = SamplePeers[0]
    AgentDetailScreen(p, AgentFormState(p.name, p.channel, p.address, 0, p.id), emptyList(), {}, {}, {}, {})
}

@Preview(name = "Add agent", widthDp = 412, heightDp = 900)
@Composable
private fun AddAgentScreenPreview() = PreviewTheme { AddAgentScreen(AgentFormState(), emptyList(), {}, {}) }

/** "Kush" -> "Kush's", "James" -> "James'", blank stays "Your". */
private fun possessive(name: String) = when {
    name == "Your" -> "your"
    name.endsWith("s", ignoreCase = true) -> "$name'"
    else -> "$name's"
}
