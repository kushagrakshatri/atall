package app.atall.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// -----------------------------------------------------------------------------
// Raw palette. Never use these directly in UI; use AppTheme.colors.* (semantic).
// Contrast notes (WCAG): ink2/ink3 on bg >= 4.5:1 in both themes; every agent
// solid passes 4.5:1 for its onSolid text and as a text color on bg.
// -----------------------------------------------------------------------------
internal object Palette {
    // Light neutrals (warm paper)
    val Paper = Color(0xFFFAFAF7)
    val White = Color(0xFFFFFFFF)
    val Sand100 = Color(0xFFF2F1EC)
    val Sand200 = Color(0xFFE6E5DF)
    val Sand300 = Color(0xFFD6D5CE)
    val Ink900 = Color(0xFF16161A)
    val Ink700 = Color(0xFF52525A)
    val Ink600 = Color(0xFF6F6F78)
    val Ink400 = Color(0xFFA3A3AB)

    // Dark neutrals
    val Night = Color(0xFF0E0E10)
    val Night1 = Color(0xFF17171A)
    val Night2 = Color(0xFF1C1C20)
    val Night3 = Color(0xFF1F1F23)
    val NightLine = Color(0xFF26262B)
    val NightLine2 = Color(0xFF34343A)
    val NightBubble = Color(0xFF2B2B31)
    val Fog100 = Color(0xFFEDEDEF)
    val Fog300 = Color(0xFFA1A1AA)
    val Fog400 = Color(0xFF85858F)
    val Fog600 = Color(0xFF5A5A62)

    // Functional
    val IrisLight = Color(0xFF4C4FD8)
    val IrisDark = Color(0xFF9A9CFF)
    val SuccessLight = Color(0xFF1F8A4C)
    val SuccessDark = Color(0xFF4CC38A)
    val WarnLight = Color(0xFFA15C00)
    val WarnDark = Color(0xFFF2A541)
    val DangerLight = Color(0xFFC42B2B)
    val DangerDark = Color(0xFFFF6B6B)
}

/** Semantic color roles. Names describe purpose, not hue. */
@Immutable
data class AppColors(
    val bg: Color,            // canvas behind the thread
    val surface: Color,       // cards, lists, composer (light)
    val sunken: Color,        // delegation strip, inputs in dark, selected rows
    val raised: Color,        // popovers, sheets (paired with elevation)
    val hairline: Color,      // 1dp dividers and card outlines
    val hairlineStrong: Color,// outlined buttons, input borders, inactive toggles
    val ink: Color,           // primary text, primary buttons
    val ink2: Color,          // secondary text
    val ink3: Color,          // tertiary text, timestamps, section labels
    val ink4: Color,          // placeholder & disabled only (not for content)
    val userBubble: Color,
    val onUserBubble: Color,
    val onInk: Color,         // text on primary (ink) buttons
    val iris: Color,          // caret, focus ring, selection, links. Use sparingly.
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightAppColors = AppColors(
    bg = Palette.Paper,
    surface = Palette.White,
    sunken = Palette.Sand100,
    raised = Palette.White,
    hairline = Palette.Sand200,
    hairlineStrong = Palette.Sand300,
    ink = Palette.Ink900,
    ink2 = Palette.Ink700,
    ink3 = Palette.Ink600,
    ink4 = Palette.Ink400,
    userBubble = Palette.Ink900,
    onUserBubble = Palette.Paper,
    onInk = Palette.Paper,
    iris = Palette.IrisLight,
    success = Palette.SuccessLight,
    warning = Palette.WarnLight,
    danger = Palette.DangerLight,
    isDark = false,
)

val DarkAppColors = AppColors(
    bg = Palette.Night,
    surface = Palette.Night1,
    sunken = Palette.Night2,
    raised = Palette.Night3,
    hairline = Palette.NightLine,
    hairlineStrong = Palette.NightLine2,
    ink = Palette.Fog100,
    ink2 = Palette.Fog300,
    ink3 = Palette.Fog400,
    ink4 = Palette.Fog600,
    userBubble = Palette.NightBubble,
    onUserBubble = Palette.Fog100,
    onInk = Palette.Night,
    iris = Palette.IrisDark,
    success = Palette.SuccessDark,
    warning = Palette.WarnDark,
    danger = Palette.DangerDark,
    isDark = true,
)

// -----------------------------------------------------------------------------
// Agent identity. The main agent is neutral (ink, circle). Peer agents pick one
// hue (squircle). Each hue resolves to a solid, a translucent container, and an
// on-solid color per theme.
// -----------------------------------------------------------------------------
@Immutable
data class AgentColors(val solid: Color, val container: Color, val onSolid: Color)

enum class AgentHue(private val light: Color, private val dark: Color) {
    Teal(Color(0xFF0A7860), Color(0xFF3CCBA6)),
    Ember(Color(0xFFB54818), Color(0xFFFF8A55)),
    Violet(Color(0xFF7445D6), Color(0xFFA98BFF)),
    Blue(Color(0xFF2A64CC), Color(0xFF6EA2FF)),
    Rose(Color(0xFFC2365F), Color(0xFFFF7FA0)),
    Olive(Color(0xFF5A7618), Color(0xFFA9CC55)),
    Slate(Color(0xFF4A6580), Color(0xFF93AECB)),
    Gold(Color(0xFF946800), Color(0xFFF0BE45));

    fun resolve(dark: Boolean): AgentColors =
        if (dark) AgentColors(this.dark, this.dark.copy(alpha = 0.16f), Palette.Night)
        else AgentColors(light, light.copy(alpha = 0.10f), Palette.White)

    companion object {
        /** Deterministic default for a newly added agent: first hue not already taken. */
        fun nextFree(taken: Set<AgentHue>): AgentHue = entries.firstOrNull { it !in taken } ?: entries[taken.size % entries.size]
    }
}

/** The main agent's identity colors: inverse ink. */
fun mainAgentColors(c: AppColors) = AgentColors(solid = c.ink, container = c.sunken, onSolid = c.bg)
