package com.charan.wifibar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charan.wifibar.core.MapPoint
import com.charan.wifibar.core.DirectionSweep
import com.charan.wifibar.core.SignalQuality
import com.charan.wifibar.locate.LocateState
import com.charan.wifibar.locate.Locator
import com.charan.wifibar.locate.WalkState
import com.charan.wifibar.locate.WalkStop
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** "Find the router": walk around; a heading-up mini-map shows where it most likely is. */
@Composable
fun FindCard(state: LocateState, locator: Locator) {
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Find the router", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.finding != null) TextButton(onClick = locator::stopFinding) { Text("Stop") }
            }
            val walk = state.finding
            if (walk == null) {
                Text(
                    "Walk holding the device in front of you and follow the arrow. Each reading is placed on a map of where " +
                        "you've walked; the arrow picks the way that gets you closest while learning the most.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!state.isLinkReading) {
                    Text(
                        "Works best on the network you're connected to: it updates every 3 seconds; others only every 30 or so.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = locator::startFinding) { Text("Start walking") }
            } else {
                LiveFinding(walk, state, locator)
            }
        }
    }
}

@Composable
private fun LiveFinding(walk: WalkState, state: LocateState, locator: Locator) {
    // Position and heading change ~100×/s; read them each frame instead of observing.
    var facing by remember { mutableDoubleStateOf(0.0) }
    var here by remember { mutableStateOf(MapPoint.ORIGIN) }
    LaunchedEffect(Unit) {
        while (true) {
            facing = locator.facing
            here = locator.walkPosition
            delay(33)
        }
    }
    MiniMap(walk, here, facing, Modifier.fillMaxWidth().widthIn(max = 460.dp).aspectRatio(1f))

    val guidance = walk.guidance
    val onSurface = MaterialTheme.colorScheme.onSurface
    when {
        walk.stop != null -> FindStopNotice(walk.stop, walk.nearby, state.latestRssi, locator)
        guidance == null -> Instruction(
            "Walk slowly, any direction",
            "Readings so far: ${walk.observations.size} of 3 (about one every 3 seconds). Then the arrow takes over.",
        )
        else -> {
            val turn = DirectionSweep.signedDifference(facing, guidance.heading)
            val straight = abs(turn) < 20
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(56.dp)) {
                    val color = if (straight) Color(0xFF34C759) else onSurface
                    rotateArrow(-turn.toFloat(), color)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        if (straight) "Walk straight ahead" else if (turn > 0) "Turn ${turn.roundToInt()}° left" else "Turn ${(-turn).roundToInt()}° right",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val best = (guidance.mostLikely - here).length
                    Text(
                        if (guidance.spread <= 4) "Best guess ≈ ${best.roundToInt()} m away (±${guidance.spread.roundToInt()} m)."
                        else "Still narrowing it down (±${guidance.spread.roundToInt()} m). The arrow also picks ways that tell it most.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // How sure it is that the router is within 2 m of you; Stop comes at 80%.
            Column(Modifier.fillMaxWidth().widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { (walk.nearby / 0.8).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(100 * walk.nearby).roundToInt()}% sure the router is within 2 m of you · stops at 80%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "${walk.steps} steps · ${walk.observations.size} readings" + (state.latestRssi?.let { " · now $it dBm" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FindStopNotice(stop: WalkStop, nearby: Double, rssi: Int?, locator: Locator) {
    val (badge, title, detail) = when (stop) {
        WalkStop.STRONG_SIGNAL -> Triple(
            SignalQuality.EXCELLENT.color(),
            "Stop here",
            "The signal is very strong (${rssi} dBm): the router should be within about a metre. Look around you, including up high.",
        )
        WalkStop.CONFIDENT -> Triple(
            SignalQuality.EXCELLENT.color(),
            "Stop: it's within 2 m of you",
            "${(100 * nearby).roundToInt()}% sure. Look around, including up high and behind furniture. " +
                "If you walk on, the arrow comes back.",
        )
    }
    Canvas(Modifier.padding(vertical = 8.dp).size(72.dp)) {
        // An octagon: the universal "stop" shape, in the status colour.
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        val path = Path()
        for (i in 0 until 8) {
            val a = Math.toRadians(22.5 + i * 45.0)
            val p = Offset((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, badge)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    TextButton(onClick = locator::startFinding) { Text("Start over from here") }
}

@Composable
private fun Instruction(title: String, detail: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateArrow(degrees: Float, color: Color) {
    val w = size.width
    val c = Offset(w / 2, size.height / 2)
    val rad = Math.toRadians(degrees.toDouble())
    fun rot(x: Float, y: Float): Offset {
        val dx = x - c.x
        val dy = y - c.y
        return Offset((c.x + dx * cos(rad) - dy * sin(rad)).toFloat(), (c.y + dx * sin(rad) + dy * cos(rad)).toFloat())
    }
    val pts = listOf(
        w / 2 to w * 0.06f, w * 0.82f to w * 0.48f, w * 0.61f to w * 0.48f, w * 0.61f to w * 0.94f,
        w * 0.39f to w * 0.94f, w * 0.39f to w * 0.48f, w * 0.18f to w * 0.48f,
    ).map { (x, y) -> rot(x, y) }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color)
}

/**
 * Heading-up map: you're at the centre pointing up, and the world turns as you turn.
 * Shows the walked path, readings coloured by strength, the position probability as a faint cloud,
 * and the most likely router spot with its uncertainty circle.
 */
@Composable
private fun MiniMap(walk: WalkState, here: MapPoint, facing: Double, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val pathColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Brand: the router is the Beam Cyan dot with a glow (as on the icon); the cloud is the theme's blue.
    val me = MaterialTheme.colorScheme.onSurface
    val cloud = MaterialTheme.colorScheme.primary
    val router = Brand.BeamCyan
    val routerEdge = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Transparent else Brand.InkNavy
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val good = SignalQuality.EXCELLENT.color()
    val fair = SignalQuality.FAIR.color()
    val weak = SignalQuality.WEAK.color()

    Canvas(modifier) {
        val radiusPx = size.minDimension / 2 - 4.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        val guidance = walk.guidance
        // Fit the best guess and the walk, at least 6 m.
        var extent = 6.0
        guidance?.takeIf { it.spread <= 4 }?.let { extent = max(extent, (it.mostLikely - here).length + max(1.0, it.spread)) }
        walk.path.forEach { extent = max(extent, (it - here).length) }
        extent = min(extent * 1.15, 30.0)
        val scale = (radiusPx / extent).toFloat()

        val headingRad = Math.toRadians(facing)
        val forward = MapPoint(-sin(headingRad), cos(headingRad))
        val right = MapPoint(cos(headingRad), sin(headingRad))
        fun screen(p: MapPoint): Offset {
            val d = p - here
            val f = d.x * forward.x + d.y * forward.y
            val r = d.x * right.x + d.y * right.y
            return Offset(c.x + (r * scale).toFloat(), c.y - (f * scale).toFloat())
        }

        drawCircle(background, radiusPx, c)
        // Distance rings.
        val ringStep = listOf(1.0, 2.0, 5.0, 10.0).first { extent / it <= 4 }
        var d = ringStep
        while (d <= extent) {
            drawCircle(ring, (d * scale).toFloat(), c, style = Stroke(1.dp.toPx()))
            val text = measurer.measure("${d.roundToInt()} m", labelStyle)
            drawText(text, topLeft = Offset(c.x + 3.dp.toPx(), c.y - (d * scale).toFloat()))
            d += ringStep
        }
        // Probability cloud.
        if (guidance != null) {
            val dot = max(2f, (0.55 * guidance.cell * scale).toFloat())
            guidance.heat.forEach { (p, w) ->
                val s = screen(p)
                if ((s - c).getDistance() < radiusPx) drawCircle(cloud.copy(alpha = (0.22 * w).toFloat()), dot, s)
            }
        }
        // Walked path.
        if (walk.path.size >= 2) {
            val path = Path()
            walk.path.forEachIndexed { i, p -> screen(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, pathColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        // Readings, coloured by strength.
        walk.observations.forEach { o ->
            val color = when (SignalQuality.of(o.rssi)) {
                SignalQuality.EXCELLENT, SignalQuality.GOOD -> good
                SignalQuality.FAIR -> fair
                SignalQuality.WEAK -> weak
            }
            drawCircle(color, 5.dp.toPx(), screen(o.position))
        }
        // Best guess and its uncertainty, once it means something.
        if (guidance != null && guidance.spread <= 4) {
            val s = screen(guidance.mostLikely)
            drawCircle(cloud, (guidance.spread * scale).toFloat().coerceAtMost(radiusPx), s, style = Stroke(1.5.dp.toPx()))
            val glow = 22.dp.toPx()
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(listOf(router.copy(alpha = 0.45f), Color.Transparent), center = s, radius = glow),
                glow, s,
            )
            drawCircle(router, 9.dp.toPx(), s)
            drawCircle(routerEdge, 9.dp.toPx(), s, style = Stroke(1.5.dp.toPx()))
        }
        // You: a triangle at the centre, always pointing up (the way the tablet faces).
        val tip = 14.dp.toPx()
        val you = Path().apply {
            moveTo(c.x, c.y - tip)
            lineTo(c.x + tip * 0.7f, c.y + tip * 0.7f)
            lineTo(c.x, c.y + tip * 0.3f)
            lineTo(c.x - tip * 0.7f, c.y + tip * 0.7f)
            close()
        }
        drawPath(you, me)
    }
}
