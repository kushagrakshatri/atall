package app.atall.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ----------------------------------------------------------------------------- Spacing (4dp grid)
@Immutable
data class AppSpacing(
    val xxs: Dp = 2.dp, val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp,
    val l: Dp = 16.dp, val xl: Dp = 20.dp, val xxl: Dp = 24.dp, val xxxl: Dp = 32.dp, val huge: Dp = 48.dp,
    val screenGutter: Dp = 16.dp,      // thread & lists
    val onboardingGutter: Dp = 24.dp,
    val turnGap: Dp = 20.dp,           // between turns from different senders
    val groupGap: Dp = 4.dp,           // consecutive messages from one sender
    val avatarToText: Dp = 10.dp,
    val minTouch: Dp = 48.dp,
)

// ----------------------------------------------------------------------------- Radii
@Immutable
data class AppRadii(
    val xs: Dp = 6.dp,      // mention highlight inside a bubble
    val s: Dp = 8.dp,       // mention chip
    val m: Dp = 12.dp,      // list rows inside popovers
    val l: Dp = 16.dp,      // cards, lists, expanded strip
    val xl: Dp = 20.dp,     // bubbles, popovers, icon tiles
    val composer: Dp = 26.dp,
    val sheet: Dp = 28.dp,
) {
    val card: Shape get() = RoundedCornerShape(l)
    val popover: Shape get() = RoundedCornerShape(xl)
    val pill: Shape get() = CircleShape
    /** User bubble: tail corner (bottom-end) tightens to 6dp on the last bubble of a group. */
    fun userBubble(lastInGroup: Boolean): Shape =
        RoundedCornerShape(topStart = xl, topEnd = xl, bottomEnd = if (lastInGroup) xs else xl, bottomStart = xl)
    /** Peer agent avatar: squircle-ish, 30% corner. Main agent uses CircleShape. */
    val peerAvatar: Shape get() = RoundedCornerShape(percent = 30)
}

// ----------------------------------------------------------------------------- Elevation
// Flat by default: hairlines carry structure. Shadow only for things that float.
@Immutable
data class AppElevation(
    val none: Dp = 0.dp,
    val composer: Dp = 1.dp,   // + 1dp hairline
    val popover: Dp = 8.dp,    // mention autocomplete, menus
    val sheet: Dp = 12.dp,
)

// ----------------------------------------------------------------------------- Motion
@Immutable
data class AppMotion(
    val instant: Int = 90,     // press states, toggles
    val quick: Int = 160,      // chip insert, color swap, small fades
    val base: Int = 240,       // strip expand/collapse, popover in
    val gentle: Int = 360,     // screen-level transitions, onboarding steps
    val stagger: Int = 40,     // per-item delay (autocomplete rows, strip lines)
    val pulse: Int = 1400,     // live-status dot full cycle
    val elapsedTick: Int = 1000,
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),       // most moves
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),     // things arriving
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),      // things leaving (use ~0.7x duration)
) {
    /** For chips, avatars landing, the send button scaling. No bounce on text. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
}

// ----------------------------------------------------------------------------- Locals & accessors
val LocalAppColors = staticCompositionLocalOf { LightAppColors }
val LocalAppType = staticCompositionLocalOf { AppType() }
val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
val LocalAppRadii = staticCompositionLocalOf { AppRadii() }
val LocalAppElevation = staticCompositionLocalOf { AppElevation() }
val LocalAppMotion = staticCompositionLocalOf { AppMotion() }

object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val type: AppType @Composable @ReadOnlyComposable get() = LocalAppType.current
    val spacing: AppSpacing @Composable @ReadOnlyComposable get() = LocalAppSpacing.current
    val radii: AppRadii @Composable @ReadOnlyComposable get() = LocalAppRadii.current
    val elevation: AppElevation @Composable @ReadOnlyComposable get() = LocalAppElevation.current
    val motion: AppMotion @Composable @ReadOnlyComposable get() = LocalAppMotion.current

    @Composable @ReadOnlyComposable
    fun agent(hue: AgentHue): AgentColors = hue.resolve(colors.isDark)
}

private fun AppColors.toMaterialScheme() = if (isDark) darkColorScheme(
    primary = ink, onPrimary = onInk, primaryContainer = sunken, onPrimaryContainer = ink,
    secondary = ink2, onSecondary = bg, tertiary = iris, onTertiary = bg,
    background = bg, onBackground = ink, surface = bg, onSurface = ink,
    surfaceVariant = sunken, onSurfaceVariant = ink2,
    surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    outline = hairlineStrong, outlineVariant = hairline,
    error = danger, onError = bg, inverseSurface = ink, inverseOnSurface = bg, inversePrimary = bg,
    surfaceTint = bg, scrim = Palette.Night,
) else lightColorScheme(
    primary = ink, onPrimary = onInk, primaryContainer = sunken, onPrimaryContainer = ink,
    secondary = ink2, onSecondary = bg, tertiary = iris, onTertiary = Palette.White,
    background = bg, onBackground = ink, surface = bg, onSurface = ink,
    surfaceVariant = sunken, onSurfaceVariant = ink2,
    surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = raised, surfaceContainerHighest = sunken,
    outline = hairlineStrong, outlineVariant = hairline,
    error = danger, onError = Palette.White, inverseSurface = ink, inverseOnSurface = bg, inversePrimary = bg,
    surfaceTint = bg, scrim = Palette.Ink900,
)

/**
 * Root theme. Dynamic color is intentionally off: agent identity depends on a
 * fixed, contrast-checked palette, and the neutral canvas is part of the brand.
 */
@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkAppColors else LightAppColors
    val type = AppType()
    val radii = AppRadii()
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppType provides type,
        LocalAppSpacing provides AppSpacing(),
        LocalAppRadii provides radii,
        LocalAppElevation provides AppElevation(),
        LocalAppMotion provides AppMotion(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = type.toMaterial(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(radii.xs),
                small = RoundedCornerShape(radii.s),
                medium = RoundedCornerShape(radii.l),
                large = RoundedCornerShape(radii.xl),
                extraLarge = RoundedCornerShape(radii.sheet),
            ),
            content = content,
        )
    }
}
