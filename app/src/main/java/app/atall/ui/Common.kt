package app.atall.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme

fun hueOf(index: Int): AgentHue = AgentHue.entries[Math.floorMod(index, AgentHue.entries.size)]

@Composable
fun Modifier.hairlineBorder(shape: Shape, strong: Boolean = true): Modifier =
    border(1.dp, if (strong) AppTheme.colors.hairlineStrong else AppTheme.colors.hairline, shape)

/** Filled ink pill, full width. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape)
            .background(if (enabled) c.ink else c.hairlineStrong)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppTheme.type.button, color = if (enabled) c.onInk else c.ink3) }
}

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = AppTheme.colors.ink) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppTheme.type.button, color = color) }
}

/** Small outlined pill ("Sign in", "Tap to send to X"). */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val c = AppTheme.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(CircleShape)
            .background(if (filled) c.ink else c.surface)
            .then(if (filled) Modifier else Modifier.hairlineBorder(CircleShape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppTheme.type.label.copy(fontSize = AppTheme.type.body2.fontSize), color = if (filled) c.onInk else c.ink) }
}
