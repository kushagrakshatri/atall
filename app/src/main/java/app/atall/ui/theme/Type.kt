@file:OptIn(ExperimentalTextApi::class)

package app.atall.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atall.R

// Geist + Geist Mono, SIL Open Font License 1.1 (see fonts/OFL-Geist.txt).
// Bundle as res/font/geist_variable.ttf and res/font/geist_mono_variable.ttf.
// Variable-axis weights need API 26+; below that Android falls back to the default instance.
private fun geist(w: Int) = Font(
    resId = R.font.geist_variable,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)
private fun geistMono(w: Int) = Font(
    resId = R.font.geist_mono_variable,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))
val GeistMono = FontFamily(geistMono(400), geistMono(500))

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, family: FontFamily = Geist) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = (tracking / size).em,
    lineHeightStyle = Trim,
)

/** Full app type scale. Tracking is given in px at size (matches design-system.md). */
@Immutable
data class AppType(
    val display: TextStyle = style(32, 38, 600, -0.8),   // onboarding titles
    val title: TextStyle = style(22, 28, 600, -0.4),     // screen titles, agent name on detail
    val headline: TextStyle = style(17, 22, 600, -0.2),  // app bar name, empty-state title
    val body: TextStyle = style(16, 23, 400, -0.1),      // message text (agent + user)
    val bodyStrong: TextStyle = style(16, 23, 600, -0.1),
    val body2: TextStyle = style(14, 20, 400),           // list values, secondary paragraphs
    val sender: TextStyle = style(13, 18, 600, -0.1),    // name above an agent turn (13.5 in mocks)
    val label: TextStyle = style(13, 16, 500),           // strip label, chips, section labels
    val button: TextStyle = style(16, 20, 600, -0.1),
    val caption: TextStyle = style(12, 16, 400),         // helper text, timestamps
    val micro: TextStyle = style(11, 14, 500, family = GeistMono), // elapsed timers, durations
)

/** Material 3 mapping so stock components inherit the scale. */
fun AppType.toMaterial() = Typography(
    displayLarge = display, displayMedium = display, displaySmall = display,
    headlineLarge = title, headlineMedium = title, headlineSmall = headline,
    titleLarge = title, titleMedium = headline, titleSmall = label,
    bodyLarge = body, bodyMedium = body2, bodySmall = caption,
    labelLarge = button, labelMedium = label, labelSmall = caption,
)
