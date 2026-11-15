package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.api.ChannelType
import app.atall.core.AgentProfile
import app.atall.core.AgentValidation
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme

/** Add/edit form state. Channel-agnostic: [address] means what [channel] needs. */
@Stable
class AgentFormState(name: String = "", channel: ChannelType = ChannelType.RCS, address: String = "", hue: Int = 0, val editingId: String? = null) {
    var name by mutableStateOf(name)
    var channel by mutableStateOf(channel)
    var address by mutableStateOf(address)
    var hue by mutableStateOf(hue)
    var tried by mutableStateOf(false)

    fun nameError(existing: List<AgentProfile>) = AgentValidation.nameError(name, existing, editingId)
    fun addressError(existing: List<AgentProfile>) = AgentValidation.addressError(channel, address, existing, editingId)
    fun valid(existing: List<AgentProfile>) = nameError(existing) == null && addressError(existing) == null
}

@Composable
fun AgentForm(state: AgentFormState, existing: List<AgentProfile>) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChannelPicker(state.channel) { state.channel = it }
        Field("Name", state.name, { state.name = it }, error = if (state.tried || state.name.isNotBlank()) state.nameError(existing) else null)
        Field(
            Copy.addressLabel(state.channel), state.address, { state.address = it },
            helper = Copy.addressHelper(state.channel, state.name.trim()),
            error = if (state.tried) state.addressError(existing) else null,
            keyboard = if (state.channel == ChannelType.RCS) KeyboardType.Phone else KeyboardType.Text,
        )
        // Live preview with a few hue choices.
        Row(
            Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(c.sunken).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(state.name.ifBlank { "?" }, hueOf(state.hue), 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.name.ifBlank { "Name" }, style = AppTheme.type.headline, color = c.ink)
                Text("Shows up like this in your thread", style = AppTheme.type.caption, color = c.ink2)
            }
            HueSwatches(state.hue, count = 3) { state.hue = it }
        }
    }
}

@Composable
fun HueSwatches(selected: Int, count: Int = AgentHue.entries.size, onPick: (Int) -> Unit) {
    val start = if (count < AgentHue.entries.size) (selected - selected % count) else 0
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in start until start + count) {
            val idx = i % AgentHue.entries.size
            val solid = AppTheme.agent(hueOf(idx)).solid
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (idx == selected) Modifier.border(2.dp, solid, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onPick(idx) }.padding(if (idx == selected) 3.dp else 0.dp)
                    .clip(RoundedCornerShape(6.dp)).background(solid),
            )
        }
    }
}

@Composable
private fun ChannelPicker(selected: ChannelType, onPick: (ChannelType) -> Unit) {
    val c = AppTheme.colors
    Column {
        Text("How it reaches you", style = AppTheme.type.label, color = c.ink3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Connector agents are added from the relay (Connected agents), never typed in.
            for (ch in ChannelType.entries.filter { it != ChannelType.CONNECTOR }) {
                val on = ch == selected
                Box(
                    Modifier.heightIn(min = 36.dp).clip(CircleShape)
                        .background(if (on) c.ink else c.surface)
                        .then(if (on) Modifier else Modifier.hairlineBorder(CircleShape))
                        .alpha(if (ch.implemented) 1f else 0.5f)
                        .clickable(enabled = ch.implemented) { onPick(ch) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Copy.channelLabel(ch) + if (ch.implemented) "" else " · soon",
                        style = AppTheme.type.label, color = if (on) c.onInk else c.ink,
                    )
                }
            }
        }
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit,
    helper: String? = null, error: String? = null, keyboard: KeyboardType = KeyboardType.Text,
) {
    val c = AppTheme.colors
    Column {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.radii.l)).background(c.surface)
                .border(if (error != null) 1.5.dp else 1.dp, if (error != null) c.danger else c.hairlineStrong, RoundedCornerShape(AppTheme.radii.l))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, style = AppTheme.type.caption, color = c.ink3)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = AppTheme.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.iris),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        val line = error ?: helper
        if (line != null) Text(line, Modifier.padding(start = 6.dp, top = 6.dp), style = AppTheme.type.caption, color = if (error != null) c.danger else c.ink3)
    }
}

@Preview(name = "Agent form", widthDp = 412)
@Composable
private fun AgentFormPreview() = PreviewTheme {
    Box(Modifier.padding(24.dp)) { AgentForm(AgentFormState("Nova", address = "(408) 555-0142"), emptyList()) }
}
