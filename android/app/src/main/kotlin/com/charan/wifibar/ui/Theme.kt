package com.charan.wifibar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.charan.wifibar.core.SignalQuality

/**
 * Material 3 in the brand palette ([BrandLightColors] / [BrandDarkColors], generated from Branding/BRAND.md),
 * or, when [useWallpaperColours] is on, Android's own look: the wallpaper's dynamic colours.
 */
@Composable
fun WiFiBarTheme(useWallpaperColours: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        useWallpaperColours -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDarkColors
        else -> BrandLightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The WiFiBar mark, plotted from the same formula as Branding/make_brand.py (`mark_geometry`): the beam
 * r(φ) = L·exp(−φ²/2σ²), σ = 25°, aimed at 45°, and the router dot on the sweep circle. One colour only:
 * White on dark, Ink Navy on light (BRAND.md). No glow or arc at in-app sizes.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = if (isSystemInDarkTheme()) Color.White else Brand.InkNavy) {
    Canvas(modifier.size(size)) {
        // REGULAR proportions at 1024: L = 436, gap = 40, dot radius = 46.
        val l = 436.0; val gap = 40.0; val dotR = 46.0
        val sigma = Math.toRadians(25.0); val bearing = Math.toRadians(45.0)
        val beam = (0..400).map { i ->
            val phi = -Math.PI + 2 * Math.PI * i / 400
            val r = l * kotlin.math.exp(-phi * phi / (2 * sigma * sigma))
            Offset((r * kotlin.math.cos(phi + bearing)).toFloat(), (r * kotlin.math.sin(phi + bearing)).toFloat())
        }
        val dot = Offset(((l + gap + dotR) * kotlin.math.cos(bearing)).toFloat(), ((l + gap + dotR) * kotlin.math.sin(bearing)).toFloat())
        val minX = minOf(beam.minOf { it.x }, dot.x - dotR.toFloat()); val maxX = maxOf(beam.maxOf { it.x }, dot.x + dotR.toFloat())
        val minY = minOf(beam.minOf { it.y }, dot.y - dotR.toFloat()); val maxY = maxOf(beam.maxOf { it.y }, dot.y + dotR.toFloat())
        val scale = minOf(this.size.width / (maxX - minX), this.size.height / (maxY - minY))
        // Centre the bounding box; flip y (the geometry is y-up).
        val ox = this.size.width / 2 - (minX + maxX) / 2 * scale
        val oy = this.size.height / 2 + (minY + maxY) / 2 * scale
        fun map(p: Offset) = Offset(ox + p.x * scale, oy - p.y * scale)
        val path = androidx.compose.ui.graphics.Path().apply {
            beam.forEachIndexed { i, p -> map(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            close()
        }
        drawPath(path, color)
        drawCircle(color, (dotR * scale).toFloat(), map(dot))
    }
}

/**
 * Wi-Fi glyph lighting one part per bar, like the Mac app's: weak lights nothing,
 * fair the dot, good the dot and inner arc, excellent everything.
 */
@Composable
fun WifiGlyph(quality: SignalQuality, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val center = Offset(w / 2, this.size.height * 0.8f)
        val dim = tint.copy(alpha = 0.25f)
        drawCircle(if (quality.bars >= 1) tint else dim, radius = w * 0.085f, center = center)
        for ((radiusFraction, needed) in listOf(0.36f to 2, 0.6f to 3)) {
            val radius = w * radiusFraction
            drawArc(
                color = if (quality.bars >= needed) tint else dim,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Status colours for quality; always shown next to the quality label, never alone. */
@Composable
fun SignalQuality.color(): Color = when (this) {
    SignalQuality.EXCELLENT, SignalQuality.GOOD -> Color(0xFF34C759)
    SignalQuality.FAIR -> Color(0xFFFFB300)
    SignalQuality.WEAK -> MaterialTheme.colorScheme.error
}
