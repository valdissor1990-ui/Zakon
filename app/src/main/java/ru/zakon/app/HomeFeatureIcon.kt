package ru.zakon.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class HomeFeatureIcon {
    Codes, Constitution, Laws, Search, Favorites, Cabinet
}

/** Consistent, scalable line icons drawn natively; no emoji/font fallback is involved. */
@Composable
internal fun HomeFeatureIconView(
    icon: HomeFeatureIcon,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    color: Color = Color(0xFFFFE0A0)
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.075f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        fun line(a: Offset, b: Offset, width: Float = stroke) =
            drawLine(color, a, b, width, cap = StrokeCap.Round)
        fun circle(c: Offset, r: Float, width: Float = stroke) =
            drawCircle(color, r, c, style = Stroke(width))
        fun rect(left: Float, top: Float, width: Float, height: Float, radius: Float = s * 0.06f) =
            drawRoundRect(color, Offset(left, top), Size(width, height), androidx.compose.ui.geometry.CornerRadius(radius), style = Stroke(stroke))
        when (icon) {
            HomeFeatureIcon.Codes -> {
                val l = s * 0.17f
                val t = s * 0.22f
                val w = s * 0.66f
                val h = s * 0.58f
                val path = Path().apply {
                    moveTo(l, t + s * 0.04f)
                    cubicTo(l + s * 0.18f, t - s * 0.02f, center.x - s * 0.06f, t + s * 0.02f, center.x, t + s * 0.12f)
                    cubicTo(center.x + s * 0.06f, t + s * 0.02f, l + w - s * 0.18f, t - s * 0.02f, l + w, t + s * 0.04f)
                    lineTo(l + w, t + h)
                    cubicTo(l + w - s * 0.18f, t + h - s * 0.06f, center.x + s * 0.06f, t + h - s * 0.02f, center.x, t + h + s * 0.02f)
                    cubicTo(center.x - s * 0.06f, t + h - s * 0.02f, l + s * 0.18f, t + h - s * 0.06f, l, t + h)
                    close()
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(center.x, t + s * 0.12f), Offset(center.x, t + h + s * 0.02f), stroke * 0.8f)
                line(Offset(l + s * 0.1f, t + s * 0.22f), Offset(center.x - s * 0.08f, t + s * 0.26f), stroke * 0.65f)
                line(Offset(center.x + s * 0.08f, t + s * 0.26f), Offset(l + w - s * 0.1f, t + s * 0.22f), stroke * 0.65f)
            }
            HomeFeatureIcon.Constitution -> {
                val left = s * 0.16f
                val right = s * 0.84f
                val top = s * 0.24f
                line(Offset(left, top + s * 0.12f), Offset(center.x, top - s * 0.08f))
                line(Offset(center.x, top - s * 0.08f), Offset(right, top + s * 0.12f))
                line(Offset(left - s * 0.02f, top + s * 0.13f), Offset(right + s * 0.02f, top + s * 0.13f))
                for (x in listOf(s * 0.27f, s * 0.43f, s * 0.59f, s * 0.75f)) {
                    line(Offset(x, top + s * 0.16f), Offset(x, s * 0.72f), stroke * 0.9f)
                }
                line(Offset(s * 0.18f, s * 0.75f), Offset(s * 0.82f, s * 0.75f))
                line(Offset(s * 0.12f, s * 0.84f), Offset(s * 0.88f, s * 0.84f))
            }
            HomeFeatureIcon.Laws -> {
                line(Offset(center.x, s * 0.19f), Offset(center.x, s * 0.79f))
                line(Offset(s * 0.27f, s * 0.31f), Offset(s * 0.73f, s * 0.31f))
                line(Offset(center.x, s * 0.25f), Offset(s * 0.27f, s * 0.31f))
                line(Offset(center.x, s * 0.25f), Offset(s * 0.73f, s * 0.31f))
                for (x in listOf(s * 0.27f, s * 0.73f)) {
                    line(Offset(x, s * 0.31f), Offset(x - s * 0.11f, s * 0.57f), stroke * 0.8f)
                    line(Offset(x, s * 0.31f), Offset(x + s * 0.11f, s * 0.57f), stroke * 0.8f)
                    drawArc(color, 0f, 180f, false, Offset(x - s * 0.13f, s * 0.52f), Size(s * 0.26f, s * 0.14f), style = Stroke(stroke))
                }
                line(Offset(s * 0.36f, s * 0.81f), Offset(s * 0.64f, s * 0.81f))
                line(Offset(s * 0.25f, s * 0.88f), Offset(s * 0.75f, s * 0.88f))
            }
            HomeFeatureIcon.Search -> {
                circle(Offset(s * 0.44f, s * 0.43f), s * 0.23f)
                line(Offset(s * 0.61f, s * 0.61f), Offset(s * 0.83f, s * 0.83f), stroke * 1.15f)
            }
            HomeFeatureIcon.Favorites -> {
                val points = (0 until 10).map { i ->
                    val angle = Math.PI * (i * 36.0 - 90.0) / 180.0
                    val radius = if (i % 2 == 0) s * 0.37f else s * 0.16f
                    Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius)
                }
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            HomeFeatureIcon.Cabinet -> {
                circle(Offset(center.x, s * 0.34f), s * 0.14f)
                val shoulders = Path().apply {
                    moveTo(s * 0.2f, s * 0.82f)
                    cubicTo(s * 0.22f, s * 0.58f, s * 0.78f, s * 0.58f, s * 0.8f, s * 0.82f)
                }
                drawPath(shoulders, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(s * 0.2f, s * 0.82f), Offset(s * 0.8f, s * 0.82f), stroke * 0.8f)
            }
        }
    }
}
