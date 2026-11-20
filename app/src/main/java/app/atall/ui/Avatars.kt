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
import androidx.compose.ui.layout.ContentScale
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
    AgentLogo.CHATGPT -> R.drawable.app_chatgpt
    AgentLogo.GROK_BOT -> R.drawable.app_grok_bot
    AgentLogo.MUSE -> R.drawable.app_muse
    AgentLogo.CLAUDE_DUET -> R.drawable.app_claude_duet
    AgentLogo.HARK -> R.drawable.logo_hark
    AgentLogo.NONE -> null
}

/** App icons whose own background is white or near the page colour: they get a hairline in light mode. */
private fun lightIcon(logo: AgentLogo) = logo == AgentLogo.CHATGPT || logo == AgentLogo.MUSE || logo == AgentLogo.CLAUDE_DUET

/**
 * An agent's real app icon as a full-bleed tile: the bitmap fills the rounded tile (30% corners, clipped), never tinted;
 * a 1 px hairline only in light mode and only for icons whose own background is white or cream. The Hark mark (vector)
 * sits on a surface tile with a hairline and follows the ink. An agent with no known app gets a small spark in its hue.
 * Never a letter. Working: an accent ring around the tile. Waiting: faded and greyscale.
 */
@Composable
fun AgentTile(logo: AgentLogo, hue: AgentHue, size: Dp, modifier: Modifier = Modifier, state: TileState = TileState.NORMAL) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(percent = 30)
    val ring = when (state) { TileState.WORKING -> c.iris; TileState.NEEDS_YOU -> c.warning; else -> null }
    val outer = if (ring != null) Modifier.background(ring.copy(alpha = 0.16f), RoundedCornerShape(percent = 34)).padding((size.value * 0.14f).coerceAtLeast(2f).dp) else Modifier
    val waiting = state == TileState.WAITING
    val res = logoRes(logo)
    Box(modifier.then(outer), contentAlignment = Alignment.Center) {
        if (res != null && logo.bitmap) {
            // Full bleed: the icon is the tile. The ring (working) or a light-mode hairline (white icons) sits on top.
            val edge = ring ?: c.hairlineStrong.takeIf { !c.isDark && lightIcon(logo) }
            Box(Modifier.size(size).alpha(if (waiting) 0.45f else 1f), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(res), contentDescription = null, Modifier.size(size).clip(shape),
                    contentScale = ContentScale.Crop, colorFilter = if (waiting) Greyscale else null,
                )
                if (edge != null) Box(Modifier.size(size).border(if (ring != null) 1.25.dp else 1.dp, edge, shape))
            }
        } else Box(
            Modifier.size(size).clip(shape).background(c.surface)
                .border(if (ring != null) 1.25.dp else 1.dp, ring ?: c.hairlineStrong, shape)
                .alpha(if (waiting) 0.5f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            val markSize = size * 0.66f
            if (res != null) {
                val tint = when {
                    waiting -> ColorFilter.tint(c.ink3)
                    state == TileState.WORKING -> ColorFilter.tint(c.iris)
                    else -> ColorFilter.tint(c.ink)
                }
                Image(painterResource(res), contentDescription = null, Modifier.size(markSize), colorFilter = tint)
            } else {
                val color = if (waiting) c.ink3 else AppTheme.agent(hue).solid
                Canvas(Modifier.size(size * 0.64f)) {
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

/** A peer agent's avatar everywhere in the app: its app icon ([AgentTile]), picked from its app identity, not its name. */
@Composable
fun PeerAvatar(logo: AgentLogo, hue: AgentHue, size: Dp, modifier: Modifier = Modifier, dimmed: Boolean = false) =
    AgentTile(logo, hue, size, modifier, if (dimmed) TileState.WAITING else TileState.NORMAL)

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
