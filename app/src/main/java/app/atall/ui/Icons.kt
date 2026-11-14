package app.atall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small line icons drawn on a 24-unit grid, so the app needs no icon library. */
enum class Glyph { Plus, At, Send, Back, People, Close, Check, Bell, Lock, Chevron }

@Composable
fun Icon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Pair<Float, Float>) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        }
        fun DrawScope.line(vararg pts: Pair<Float, Float>) = drawPath(p(*pts), tint, style = stroke)
        when (glyph) {
            Glyph.Plus -> { line(12f to 5f, 12f to 19f); line(5f to 12f, 19f to 12f) }
            Glyph.Close -> { line(6f to 6f, 18f to 18f); line(18f to 6f, 6f to 18f) }
            Glyph.Check -> line(5f to 12.5f, 10f to 17.5f, 19f to 7f)
            Glyph.Send -> { line(12f to 19f, 12f to 5f); line(6f to 11f, 12f to 5f, 18f to 11f) }
            Glyph.Back -> { line(19f to 12f, 5f to 12f); line(11f to 6f, 5f to 12f, 11f to 18f) }
            Glyph.Chevron -> line(7f to 10f, 12f to 15f, 17f to 10f)
            Glyph.At -> {
                drawCircle(tint, 3.6f * u, Offset(12f * u, 12f * u), style = stroke)
                drawArc(tint, -10f, 300f, false, Offset(3f * u, 3f * u), Size(18f * u, 18f * u), style = stroke)
                line(15.6f to 9f, 15.6f to 13.5f, 16.6f to 15.2f, 18.6f to 15.6f, 20.6f to 14.2f, 21f to 12f)
            }
            Glyph.People -> {
                drawCircle(tint, 3.3f * u, Offset(9f * u, 8.5f * u), style = stroke)
                drawArc(tint, 180f, 180f, false, Offset(3f * u, 14f * u), Size(12f * u, 10f * u), style = stroke)
                drawCircle(tint, 2.6f * u, Offset(16.5f * u, 8f * u), style = stroke)
                drawArc(tint, 200f, 140f, false, Offset(14f * u, 13.5f * u), Size(8f * u, 8f * u), style = stroke)
            }
            Glyph.Bell -> {
                line(6f to 16f, 6f to 11f)
                drawArc(tint, 180f, 180f, false, Offset(6f * u, 5f * u), Size(12f * u, 12f * u), style = stroke)
                line(18f to 11f, 18f to 16f)
                line(4.5f to 16.5f, 19.5f to 16.5f)
                line(10f to 19.5f, 14f to 19.5f)
            }
            Glyph.Lock -> {
                drawRoundRect(tint, Offset(5f * u, 11f * u), Size(14f * u, 9f * u), CornerRadius(2f * u), style = stroke)
                drawArc(tint, 180f, 180f, false, Offset(8f * u, 5f * u), Size(8f * u, 9f * u), style = stroke)
            }
        }
    }
}
