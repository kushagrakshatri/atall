package app.atall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme

/** The main agent: an ink circle with the hub mark (ring + centre dot). */
@Composable
fun MainAvatar(size: Dp, modifier: Modifier = Modifier) {
    val ink = AppTheme.colors.ink
    val bg = AppTheme.colors.bg
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(ink, r)
        drawCircle(bg, r * 0.42f, style = Stroke(width = r * 0.16f))
        drawCircle(bg, r * 0.16f, center = Offset(r, r))
    }
}

/** A peer: a hue squircle with the name's first letter exactly as typed. */
@Composable
fun PeerAvatar(name: String, hue: AgentHue, size: Dp, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val c = AppTheme.agent(hue)
    Box(
        modifier.size(size).alpha(if (dimmed) 0.4f else 1f).clip(AppTheme.radii.peerAvatar).background(c.solid),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifEmpty { "?" },
            color = c.onSolid,
            style = AppTheme.type.headline.copy(fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight(600), lineHeight = (size.value * 0.5f).sp),
        )
    }
}

@Composable
fun AddAvatar(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(AppTheme.radii.peerAvatar).background(AppTheme.colors.surface)
            .then(Modifier.hairlineBorder(AppTheme.radii.peerAvatar)),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyph.Plus, AppTheme.colors.ink2, size = size * 0.5f) }
}

@Composable
fun Dot(color: androidx.compose.ui.graphics.Color, size: Dp = 7.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color))
