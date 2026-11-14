package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.core.AgentProfile
import app.atall.core.Main
import app.atall.core.Mentions
import app.atall.core.Route
import app.atall.ui.theme.AppTheme

/** One entry in the "Send to" picker. [peer] null = the main agent. */
data class PickerEntry(val name: String, val peer: PeerUi?)

/** Who the picker offers for [query]: Juno first, then peers (paused or unreachable ones are hidden). */
fun pickerEntries(query: String, peers: List<PeerUi>): List<PickerEntry> {
    val visible = peers.filter { !it.paused && it.channel.implemented }
    val all = listOf(PickerEntry(Main.NAME, null)) + visible.map { PickerEntry(it.name, it) }
    val order = Mentions.filter(query, all.map { it.name })
    return order.mapNotNull { n -> all.firstOrNull { it.name == n } }
}

/** Replace the "@prefix" ending at the cursor with "@Name ". */
fun insertMention(value: TextFieldValue, name: String): TextFieldValue {
    val cursor = value.selection.start
    val (_, at) = Mentions.activeToken(value.text, cursor) ?: return value
    val text = value.text.substring(0, at) + "@" + name + " " + value.text.substring(cursor).trimStart()
    val pos = at + name.length + 2
    return TextFieldValue(text, TextRange(pos.coerceAtMost(text.length)))
}

@Composable
fun Composer(peers: List<PeerUi>, onSend: (String) -> Unit, onAddAgent: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var dismissedAt by remember { mutableStateOf(-1) }
    val c = AppTheme.colors
    val profiles = remember(peers) { peers.map { AgentProfile(it.id, it.name) } }
    val names = remember(profiles) { Mentions.nameMap(profiles) }
    val token = Mentions.activeToken(value.text, value.selection.start)?.takeIf { it.second != dismissedAt }
    val route = Mentions.route(value.text, profiles)
    val addressed = (route as? Route.Direct)?.peerIds?.mapNotNull { id -> peers.firstOrNull { it.id == id }?.name }
        ?.takeIf { value.text.trimStart().startsWith("@") }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (token != null) {
            MentionPicker(
                query = token.first,
                entries = pickerEntries(token.first, peers),
                onPick = { value = insertMention(value, it.name) },
                onAddAgent = { dismissedAt = token.second; onAddAgent() },
            )
            Spacer(Modifier.size(8.dp))
        }
        if (addressed != null && token == null) {
            Row(Modifier.padding(start = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To ${addressed.joinToString(" and ")} only", style = AppTheme.type.caption, color = c.ink3)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable {
                    val stripped = Mentions.stripLeading(value.text, Mentions.find(value.text, names))
                    value = TextFieldValue(stripped, TextRange(stripped.length))
                }, contentAlignment = Alignment.Center) { Icon(Glyph.Close, c.ink3, size = 14.dp) }
            }
        }
        Surface(
            shape = RoundedCornerShape(AppTheme.radii.composer),
            color = if (c.isDark) c.sunken else c.surface,
            shadowElevation = if (c.isDark) 0.dp else AppTheme.elevation.composer,
            modifier = Modifier.fillMaxWidth().hairlineBorder(RoundedCornerShape(AppTheme.radii.composer), strong = false),
        ) {
            Row(Modifier.heightIn(min = 52.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (value.text.isEmpty()) Text("Message ${Main.NAME}", style = AppTheme.type.body, color = c.ink4)
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it; if (Mentions.activeToken(it.text, it.selection.start) == null) dismissedAt = -1 },
                        textStyle = AppTheme.type.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.iris),
                        visualTransformation = MentionHighlight(names, peers),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.size(44.dp).clip(CircleShape).clickable {
                    val cur = value.selection.start
                    val needsSpace = cur > 0 && !value.text[cur - 1].isWhitespace()
                    val ins = (if (needsSpace) " @" else "@")
                    val t = value.text.substring(0, cur) + ins + value.text.substring(cur)
                    value = TextFieldValue(t, TextRange(cur + ins.length)); dismissedAt = -1
                }, contentAlignment = Alignment.Center) { Icon(Glyph.At, c.ink2) }
                val canSend = value.text.isNotBlank()
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (canSend) c.ink else c.sunken)
                        .clickable(enabled = canSend) { onSend(value.text); value = TextFieldValue("") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.Send, if (canSend) c.onInk else c.ink4, size = 20.dp) }
            }
        }
    }
}

/** Colors complete mentions in the field (same text, so offsets map 1:1). */
private class MentionHighlight(val names: Map<String, String>, val peers: List<PeerUi>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val found = Mentions.find(text.text, names)
        if (found.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(text.text)
            for (m in found) addStyle(SpanStyle(fontWeight = FontWeight(600)), m.range.first, m.range.last + 1)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

@Composable
fun MentionPicker(query: String, entries: List<PickerEntry>, onPick: (PickerEntry) -> Unit, onAddAgent: () -> Unit) {
    val c = AppTheme.colors
    Surface(
        shape = AppTheme.radii.popover, color = c.raised, shadowElevation = AppTheme.elevation.popover,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(6.dp)) {
            Text("Send to", Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp), style = AppTheme.type.label, color = c.ink3)
            if (entries.isEmpty()) {
                Text("No agent called \"$query\"", Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = AppTheme.type.body2, color = c.ink2)
            }
            entries.forEachIndexed { i, e ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(AppTheme.radii.m))
                        .background(if (i == 0) c.sunken else c.raised).clickable { onPick(e) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.peer == null) MainAvatar(36.dp) else PeerAvatar(e.name, e.peer.hue, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(e.name, style = AppTheme.type.headline.copy(fontSize = AppTheme.type.body.fontSize), color = c.ink)
                        Text(if (e.peer == null) "Default · picks who to ask" else Copy.descriptor(e.peer.channel), style = AppTheme.type.label, color = c.ink3)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = c.hairline)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(AppTheme.radii.m)).clickable(onClick = onAddAgent).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start,
            ) {
                AddAvatar(36.dp)
                Spacer(Modifier.width(12.dp))
                Text("Add an agent", style = AppTheme.type.body, color = c.ink)
            }
        }
    }
}

@Preview(name = "Mention picker", widthDp = 412)
@Composable
private fun PickerPreview() = PreviewTheme {
    MentionPicker("", pickerEntries("", SamplePeers), {}, {})
}

@Preview(name = "Mention picker · no match", widthDp = 412)
@Composable
private fun PickerNoMatchPreview() = PreviewTheme {
    MentionPicker("zx", pickerEntries("zx", SamplePeers), {}, {})
}

@Preview(name = "Composer", widthDp = 412)
@Composable
private fun ComposerPreview() = PreviewTheme { Composer(SamplePeers, {}, {}) }
