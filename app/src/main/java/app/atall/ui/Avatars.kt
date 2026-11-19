package app.atall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atall.R
import app.atall.core.AgentLogo
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

/** How an agent tile looks in a row of them: normal, working (a soft accent ring) or waiting (faded, greyscale). */
enum class TileState { NORMAL, WORKING, WAITING, NEEDS_YOU }

private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private fun logoRes(logo: AgentLogo): Int? = when (logo) {
    AgentLogo.GROK -> R.drawable.logo_grok
    AgentLogo.HARK -> R.drawable.logo_hark
    AgentLogo.META -> R.drawable.logo_meta
    AgentLogo.CLAUDE -> R.drawable.logo_claude
    AgentLogo.OPENAI -> R.drawable.logo_openai
    AgentLogo.NONE -> null
}

/**
 * An agent's real mark on a neutral tile: surface fill, a 1 px hairline, 30% corners. One-colour marks (Grok, Hark,
 * Claude, OpenAI) follow the ink, so they flip in dark mode; Meta AI (Muse) keeps its colours. An agent with no known
 * mark gets a small shape in its hue. Never a letter.
 */
@Composable
fun AgentTile(logo: AgentLogo, hue: AgentHue, size: Dp, modifier: Modifier = Modifier, state: TileState = TileState.NORMAL) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(percent = 30)
    val ring = when (state) { TileState.WORKING -> c.iris; TileState.NEEDS_YOU -> c.warning; else -> null }
    val outer = if (ring != null) Modifier.background(ring.copy(alpha = 0.16f), RoundedCornerShape(percent = 34)).padding((size.value * 0.14f).coerceAtLeast(2f).dp) else Modifier
    Box(modifier.then(outer), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(shape).background(c.surface)
                .border(if (ring != null) 1.25.dp else 1.dp, ring ?: c.hairlineStrong, shape)
                .alpha(if (state == TileState.WAITING) 0.5f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            val res = logoRes(logo)
            val markSize = size * (if (logo == AgentLogo.HARK) 0.66f else 0.64f)
            if (res != null) {
                val tint = when {
                    state == TileState.WAITING && logo.tinted -> ColorFilter.tint(c.ink3)
                    state == TileState.WAITING -> Greyscale
                    state == TileState.WORKING && logo.tinted -> ColorFilter.tint(c.iris)
                    logo.tinted -> ColorFilter.tint(c.ink)
                    else -> null
                }
                Image(painterResource(res), contentDescription = null, Modifier.size(markSize), colorFilter = tint)
            } else {
                val color = if (state == TileState.WAITING) c.ink3 else AppTheme.agent(hue).solid
                Canvas(Modifier.size(markSize)) {
                    // A four-point spark: neutral, never a letter.
                    val w = this.size.minDimension; val h = w / 2f
                    val p = Path().apply {
                        moveTo(h, 0f); quadraticTo(h, h, w, h); quadraticTo(h, h, h, w); quadraticTo(h, h, 0f, h); quadraticTo(h, h, h, 0f); close()
                    }
                    drawPath(p, color)
                }
            }
        }
    }
}

/** A peer agent's avatar everywhere in the app: its real mark on a tile ([AgentTile]). */
@Composable
fun PeerAvatar(name: String, hue: AgentHue, size: Dp, modifier: Modifier = Modifier, dimmed: Boolean = false, relayId: String? = null) =
    AgentTile(AgentLogo.of(name, relayId), hue, size, modifier, if (dimmed) TileState.WAITING else TileState.NORMAL)

@Composable
fun AddAvatar(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(AppTheme.radii.peerAvatar).background(AppTheme.colors.surface)
            .then(Modifier.hairlineBorder(AppTheme.radii.peerAvatar)),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyph.Plus, AppTheme.colors.ink2, size = size * 0.5f) }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color))
