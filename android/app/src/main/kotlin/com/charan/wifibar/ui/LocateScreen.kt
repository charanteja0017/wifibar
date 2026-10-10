package com.charan.wifibar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charan.wifibar.core.Arrival
import com.charan.wifibar.core.DirectionSweep
import com.charan.wifibar.core.DistanceEstimator
import com.charan.wifibar.core.SignalEnvironment
import com.charan.wifibar.core.SignalQuality
import com.charan.wifibar.core.SignalSample
import com.charan.wifibar.core.SignalTrend
import com.charan.wifibar.locate.LocateState
import com.charan.wifibar.locate.Locator
import com.charan.wifibar.locate.MapState
import com.charan.wifibar.locate.SweepState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocateScreen(
    locator: Locator,
    onClose: (() -> Unit)?,
    onOpenMaps: (Double, Double, String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val state by locator.state.collectAsStateWithLifecycle()
    // Walking around with the screen off would stop the readings.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Locate", style = MaterialTheme.typography.titleLarge)
                        Text(
                            state.target.ssid,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FindCard(state, locator)
            (state.sweep as? SweepState.Done)?.takeIf { it.result.isClear }?.let { GuidanceCard(state, it, locator) }
            ReadoutCard(state)
            DirectionCard(state, locator)
            DistanceCard(state, locator)
            HistoryCard(state, locator)
            MapCard(state, locator, onOpenMaps, onOpenUrl)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadoutCard(state: LocateState) {
    val quality = state.latestRssi?.let(SignalQuality::of)
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WifiGlyph(quality ?: SignalQuality.WEAK, size = 48.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            state.latestRssi?.toString() ?: "—",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            " dBm",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(quality?.label ?: "Measuring…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TrendLabel(if (state.isMissing) SignalTrend.UNKNOWN else state.trend)
            }
            state.latestRssi?.let { rssi ->
                LinearProgressIndicator(
                    progress = { SignalQuality.meterFraction(rssi).toFloat() },
                    color = SignalQuality.of(rssi).color(),
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                buildString {
                    append(state.target.channelSummary).append(" · ").append(state.target.bssid).append(". ")
                    append(
                        when {
                            state.isMissing -> "Not in the latest scan; you may be out of range."
                            state.isLinkReading -> "Live from your connection. Android refreshes it about every 3 seconds."
                            else -> "From Wi-Fi scans, which Android limits to 4 every 2 minutes. Join this network for faster readings."
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "Walk slowly and pause at each spot, holding the device the same way. The access point is where the reading peaks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrendLabel(trend: SignalTrend) {
    val (icon, text, color) = when (trend) {
        SignalTrend.STRONGER -> Triple(Icons.Filled.KeyboardArrowUp, "Stronger", SignalQuality.EXCELLENT.color())
        SignalTrend.WEAKER -> Triple(Icons.Filled.KeyboardArrowDown, "Weaker", SignalQuality.FAIR.color())
        SignalTrend.STEADY -> Triple(null, "Steady", MaterialTheme.colorScheme.onSurfaceVariant)
        SignalTrend.UNKNOWN -> Triple(null, "Measuring…", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// MARK: Direction

@Composable
private fun DirectionCard(state: LocateState, locator: Locator) {
    Section(
        "Direction sweep (standing still)",
        footer = "Uses the gyroscope to track how far you turn. Hold the device in front of you: your body blocks signal from behind, so the reading peaks when you face the router.",
    ) {
        when (val sweep = state.sweep) {
            SweepState.Idle -> {
                if (state.isLinkReading) {
                    Text(
                        if (state.fastReadings) {
                            "Stand 5 m or more from the router, hold the device in front of you, then turn slowly to your left in one full circle, " +
                                "taking about 45 seconds. Readings are coming about every ${"%.1f".format(state.refreshMillis / 1000.0)} s, fast enough to turn without stopping."
                        } else {
                            "Stand 5 m or more from the router: closer, reflections make the signal jump more than your body blocks it. " +
                                "You'll face 8 directions in turn, holding each for about 12 seconds (4 readings). " +
                                "(Android refreshes the signal every ${"%.0f".format(state.refreshMillis / 1000.0)} s; with faster readings this becomes one smooth turn.)"
                        },
                    )
                    Button(onClick = locator::startSweep) { Text("Start direction sweep") }
                } else {
                    Text(
                        "Direction finding needs the network you're connected to: other networks only update every 30 seconds or so. Join “${state.target.ssid}” first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SweepState.Unavailable -> Text("This device has no gyroscope, so it can't tell how far you've turned.")
            SweepState.Calibrating -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Hold still for a moment…")
            }
            is SweepState.Holding -> {
                val offBy = DirectionSweep.signedDifference(sweep.turned, sweep.target)
                Text(
                    when {
                        abs(offBy) <= 12 -> "Hold here (${sweep.stop + 1} of 8)…"
                        offBy > 0 -> "Turn ${offBy.roundToInt()}° left to direction ${sweep.stop + 1} of 8"
                        else -> "Turn ${(-offBy).roundToInt()}° right to direction ${sweep.stop + 1} of 8"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                TurnDial(turned = sweep.turned, target = sweep.target, done = sweep.stop)
                LinearProgressIndicator(progress = { sweep.holdProgress }, modifier = Modifier.fillMaxWidth())
                Text("Readings in this direction: ${sweep.readings} of 4", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = locator::cancelSweep) { Text("Cancel") }
            }
            is SweepState.Turning -> {
                Text(
                    when {
                        sweep.wrongWay -> "Turn the other way: to your left"
                        sweep.tooFast -> "Slower: about 45 seconds for the circle"
                        else -> "Turn slowly to your left…"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                LivePolarPlot(sweep.readings, sweep.turned)
                LinearProgressIndicator(progress = { (sweep.turned / 360).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Turned ${sweep.turned.coerceAtLeast(0.0).roundToInt()}° of 360 · ${sweep.readings.size} readings. It finishes by itself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = locator::cancelSweep) { Text("Cancel") }
            }
            is SweepState.Done -> DoneView(sweep, locator)
            is SweepState.Failed -> {
                Text(sweep.message)
                Button(onClick = locator::startSweep, enabled = state.isLinkReading) { Text("Try again") }
            }
        }
    }
}

/**
 * "Go this way": pinned at the top after a clear sweep. The arrow turns as you turn (gyroscope), and the
 * live reading with its trend tells you whether walking that way is working.
 */
@Composable
private fun GuidanceCard(state: LocateState, done: SweepState.Done, locator: Locator) {
    val result = done.result
    var turn by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(result) {
        while (true) {
            turn = locator.turnToStrongest(result)
            delay(50)
        }
    }
    val straight = abs(turn) < 15
    // Everything on this card uses onPrimaryContainer: dynamic palettes can make the container light
    // even in dark mode (seen on the Xiaomi Pad 8), and `primary` then vanished against it.
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    val arrowColor = if (straight) SignalQuality.EXCELLENT.color() else onCard
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Go this way", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            done.replayLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = onCard, textAlign = TextAlign.Center, fontStyle = FontStyle.Italic)
            }
            if (state.arrival == Arrival.WALKING) {
                DirectionArrow(turn, arrowColor, Modifier.padding(vertical = 8.dp).size(140.dp))
                Text(
                    if (straight) "Straight ahead. Walk forward." else if (turn > 0) "Turn ${turn.roundToInt()}° left" else "Turn ${(-turn).roundToInt()}° right",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                StopNotice(state)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.latestRssi?.let { "Now $it dBm · " } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                TrendLabel(state.trend)
            }
            val buttons = ButtonDefaults.textButtonColors(contentColor = onCard)
            Row {
                TextButton(onClick = locator::cancelSweep, colors = buttons) { Text("Clear") }
                if (state.arrival != Arrival.WALKING) {
                    TextButton(onClick = locator::keepWalking, colors = buttons) { Text("Keep walking") }
                }
                TextButton(onClick = locator::startSweep, enabled = state.isLinkReading, colors = buttons) { Text("Sweep again here") }
            }
        }
    }
}

/** Replaces the arrow when it's time to stop walking, saying why and what to do next. */
@Composable
internal fun StopNotice(state: LocateState) {
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    val best = state.walkBest
    val bestAgo = best?.let { ((System.currentTimeMillis() - it.timeMillis) / 1000).coerceAtLeast(0) }
    val (badge, title, detail) = when (state.arrival) {
        Arrival.ARRIVED -> Triple(
            SignalQuality.EXCELLENT.color(),
            "Stop here",
            "The signal is very strong (${state.latestRssi} dBm): the router should be within about a metre. Look around you, including up high.",
        )
        Arrival.PASSED -> Triple(
            SignalQuality.FAIR.color(),
            "Stop: you just passed it",
            "The signal was strongest ${best?.let { "(${it.rssi} dBm) " } ?: ""}${bestAgo?.let { "$it s ago" } ?: "a moment ago"} and has dropped since. Step back to that spot, then sweep again there to pinpoint it.",
        )
        Arrival.PLATEAU -> Triple(
            SignalQuality.FAIR.color(),
            "Stop: it stopped getting stronger",
            "No improvement for the last 15 seconds. Sweep again here for a fresh direction.",
        )
        Arrival.WALKING -> return
    }
    Canvas(Modifier.padding(vertical = 12.dp).size(96.dp)) {
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
    Text(title, style = MaterialTheme.typography.headlineSmall, color = onCard)
    Text(detail, style = MaterialTheme.typography.bodyMedium, color = onCard, textAlign = TextAlign.Center)
}

/** Arrow pointing where to go; [turn] degrees left is drawn rotated left. */
@Composable
private fun DirectionArrow(turn: Double, color: Color, modifier: Modifier) {
    Canvas(modifier.rotate(-turn.toFloat())) {
        val w = size.width
        val path = Path().apply {
            moveTo(w / 2, w * 0.06f)
            lineTo(w * 0.82f, w * 0.48f)
            lineTo(w * 0.61f, w * 0.48f)
            lineTo(w * 0.61f, w * 0.94f)
            lineTo(w * 0.39f, w * 0.94f)
            lineTo(w * 0.39f, w * 0.48f)
            lineTo(w * 0.18f, w * 0.48f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun DoneView(done: SweepState.Done, locator: Locator) {
    val result = done.result
    done.replayLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    if (result.isClear) Text("The arrow at the top of this screen shows which way to go.", style = MaterialTheme.typography.bodySmall)
    val confidence = result.confidence
    val span = confidence?.run?.takeIf { result.isClear && it.size > 1 }
    val where = if (span != null) {
        "between ${place(confidence.from)} and ${place(confidence.to)}"
    } else {
        place(result.direction)
    }
    val compass = done.startAzimuth?.let { start ->
        " (roughly ${cardinal(DirectionSweep.normalized(start - result.direction))} by the compass)"
    } ?: ""
    Text(
        when {
            result.isClear ->
                "Strongest $where, relative to where you started$compass. It's ${"%.0f".format(result.spread)} dB above the weakest direction. Follow the arrow and sweep again as you get closer."
            confidence != null && !confidence.isConsistent ->
                "No clear direction: directions that aren't next to each other measured about the same (" +
                    confidence.tiedSectors.sorted().joinToString(", ") { place(it * 45.0) } +
                    "), which happens when reflections dominate. Move 5 m or more from the router and try again."
            else ->
                "No clear direction: only ${"%.0f".format(result.spread)} dB between the strongest and weakest directions. Hold the device closer to your body, or move a few metres and try again."
        },
    )
    PolarPlot(result)
    Row {
        TextButton(onClick = locator::cancelSweep) { Text("Stop") }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = locator::startSweep) { Text("Sweep again") }
    }
}

/** "straight ahead", "45° to the left", "behind", "90° to the right": relative to where the sweep started. */
private fun place(degreesLeft: Double): String {
    val d = DirectionSweep.normalized(degreesLeft).roundToInt() % 360
    return when {
        d == 0 -> "straight ahead"
        d == 180 -> "behind"
        d < 180 -> "$d° to the left"
        else -> "${360 - d}° to the right"
    }
}

private fun cardinal(azimuth: Double): String {
    val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    return names[((azimuth + 22.5) / 45).toInt() % 8]
}

/** Seen from above: start at the top, left on the left. Ticks mark the 8 directions; the needle is where you face. */
@Composable
private fun TurnDial(turned: Double, target: Double, done: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val r = size.minDimension / 2 - 12.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        fun point(degrees: Double, radius: Float): Offset {
            val rad = Math.toRadians(degrees)
            return Offset((c.x - radius * sin(rad)).toFloat(), (c.y - radius * cos(rad)).toFloat())
        }
        drawCircle(outline, r, c, style = Stroke(2.dp.toPx()))
        for (stop in 0 until 8) {
            val color = when {
                stop < done -> primary
                stop * 45.0 == target -> accent
                else -> outline
            }
            drawCircle(color, if (stop * 45.0 == target) 8.dp.toPx() else 5.dp.toPx(), point(stop * 45.0, r))
        }
        drawLine(primary, c, point(turned, r * 0.85f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(primary, 5.dp.toPx(), c)
    }
}

/** Readings plotted as they arrive during a continuous turn, like the Mac's sweep: farther out = stronger. */
@Composable
private fun LivePolarPlot(readings: List<Pair<Double, Int>>, turned: Double) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f)) {
        val r = size.minDimension / 2 - 22.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        fun point(degrees: Double, radius: Float): Offset {
            val rad = Math.toRadians(degrees)
            return Offset((c.x - radius * sin(rad)).toFloat(), (c.y - radius * cos(rad)).toFloat())
        }
        for (f in listOf(1f, 0.66f, 0.33f)) drawCircle(outline, r * f, c, style = Stroke(1.dp.toPx()))
        for ((deg, label) in listOf(0.0 to "Start", 90.0 to "Left", 180.0 to "Behind", 270.0 to "Right")) {
            drawLine(outline, c, point(deg, r), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(label, labelStyle)
            val p = point(deg, r + 12.dp.toPx())
            drawText(text, topLeft = Offset(p.x - text.size.width / 2, p.y - text.size.height / 2))
        }
        // How far round you've come.
        val swept = turned.coerceIn(0.0, 360.0)
        if (swept > 0) {
            drawArc(
                primary.copy(alpha = 0.10f), startAngle = -90f, sweepAngle = -swept.toFloat(), useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
        }
        // Each reading: farther out = stronger, joined in the order taken.
        if (readings.isNotEmpty()) {
            val low = readings.minOf { it.second }
            val high = readings.maxOf { it.second }
            fun radius(v: Int) = (r * (0.25 + 0.75 * if (high > low) (v - low).toDouble() / (high - low) else 0.5)).toFloat()
            val trace = Path()
            readings.forEachIndexed { i, (deg, v) ->
                val p = point(deg, radius(v))
                if (i == 0) trace.moveTo(p.x, p.y) else trace.lineTo(p.x, p.y)
            }
            drawPath(trace, primary, style = Stroke(1.5.dp.toPx()))
            readings.forEach { (deg, v) -> drawCircle(primary, 3.dp.toPx(), point(deg, radius(v))) }
        }
        // Where you face now.
        drawLine(onSurface, c, point(turned, r), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Smoothed signal around the circle from the sweep, with the strongest direction marked. */
@Composable
private fun PolarPlot(result: DirectionSweep.Result) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f)) {
        val r = size.minDimension / 2 - 22.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        fun point(degrees: Double, radius: Float): Offset {
            val rad = Math.toRadians(degrees)
            return Offset((c.x - radius * sin(rad)).toFloat(), (c.y - radius * cos(rad)).toFloat())
        }
        for (f in listOf(1f, 0.66f, 0.33f)) drawCircle(outline, r * f, c, style = Stroke(1.dp.toPx()))
        for ((deg, label) in listOf(0.0 to "Start", 90.0 to "Left", 180.0 to "Behind", 270.0 to "Right")) {
            drawLine(outline, c, point(deg, r), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(label, labelStyle)
            val p = point(deg, r + 12.dp.toPx())
            drawText(text, topLeft = Offset(p.x - text.size.width / 2, p.y - text.size.height / 2))
        }
        val low = result.profile.min()
        val high = result.profile.max()
        fun radius(v: Double) = (r * (0.25 + 0.75 * if (high > low) (v - low) / (high - low) else 0.5)).toFloat()
        val shape = Path()
        result.profile.forEachIndexed { i, v ->
            val p = point(i * result.profileStep, radius(v))
            if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
        }
        shape.close()
        drawPath(shape, primary.copy(alpha = 0.18f))
        drawPath(shape, primary, style = Stroke(2.dp.toPx()))
        if (result.isClear) {
            result.confidence?.takeIf { it.isConsistent }?.let { span ->
                // Wedge over the tied sectors (each ±22.5°): where the router most likely is.
                val wedge = Path().apply {
                    moveTo(c.x, c.y)
                    var d = span.from - 22.5
                    val end = span.from + (span.run!!.size - 1) * 45.0 + 22.5
                    while (d <= end) { point(d, r).let { lineTo(it.x, it.y) }; d += 3.0 }
                    close()
                }
                drawPath(wedge, primary.copy(alpha = 0.12f))
            }
            drawLine(primary, c, point(result.direction, r), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawCircle(primary, 5.dp.toPx(), point(result.direction, r))
        }
    }
}

// MARK: Distance

@Composable
private fun DistanceCard(state: LocateState, locator: Locator) {
    Section(
        "Estimated distance",
        footer = "A rough guide from signal strength. Walls, people and the router's antennas can put it off by several metres. This device can't measure distance directly (no Wi-Fi RTT).",
    ) {
        val estimate = state.estimate
        Text(
            estimate?.let { listOfNotNull(DistanceEstimator.format(it.meters), DistanceEstimator.formatRange(it)?.let { r -> "($r)" }).joinToString("  ") } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SignalEnvironment.entries.forEachIndexed { index, environment ->
                SegmentedButton(
                    selected = state.environment == environment,
                    onClick = { locator.setEnvironment(environment) },
                    shape = SegmentedButtonDefaults.itemShape(index, SignalEnvironment.entries.size),
                ) { Text(environment.label) }
            }
        }
    }
}

// MARK: History

@Composable
private fun HistoryCard(state: LocateState, locator: Locator) {
    Section("Last 2 minutes") {
        SignalChart(state.samples, state.peak)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Best reading", Modifier.weight(1f))
            Text(state.peak?.let { "${it.rssi} dBm, ${((System.currentTimeMillis() - it.timeMillis) / 1000)} s ago" } ?: "—")
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Beep and vibrate at each new best", Modifier.weight(1f))
            Switch(checked = state.playsSound, onCheckedChange = locator::setSound)
        }
        TextButton(onClick = locator::resetHistory, enabled = state.samples.isNotEmpty()) { Text("Reset history") }
    }
}

/** One series, so no legend: the section title names it. Dashed line marks the best reading. */
@Composable
private fun SignalChart(samples: List<SignalSample>, peak: SignalSample?) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 30.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val now = samples.lastOrNull()?.timeMillis ?: System.currentTimeMillis()
        fun x(t: Long) = left + (size.width - left) * (1 - (now - t) / 120_000f)
        fun y(rssi: Double) = (bottom * (1 - (rssi + 95) / 70)).toFloat().coerceIn(0f, bottom)
        for (level in listOf(-90, -70, -50, -30)) {
            drawLine(grid, Offset(left, y(level.toDouble())), Offset(size.width, y(level.toDouble())), 1f)
            val text = measurer.measure("$level", labelStyle)
            drawText(text, topLeft = Offset(0f, y(level.toDouble()) - text.size.height / 2))
        }
        for ((seconds, label) in listOf(120 to "−2 min", 60 to "−60 s", 0 to "now")) {
            val text = measurer.measure(label, labelStyle)
            val px = x(now - seconds * 1000L)
            drawText(text, topLeft = Offset((px - text.size.width / 2).coerceIn(left, size.width - text.size.width), bottom + 2.dp.toPx()))
        }
        peak?.let {
            drawLine(grid.copy(alpha = 1f), Offset(left, y(it.rssi.toDouble())), Offset(size.width, y(it.rssi.toDouble())), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        if (samples.size >= 2) {
            val path = Path()
            samples.forEachIndexed { i, s ->
                val p = Offset(x(s.timeMillis).coerceAtLeast(left), y(s.rssi.toDouble()))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        samples.lastOrNull()?.let { drawCircle(primary, 4.dp.toPx(), Offset(x(it.timeMillis), y(it.rssi.toDouble()))) }
    }
}

// MARK: Map (WiGLE)

@Composable
private fun MapCard(state: LocateState, locator: Locator, onOpenMaps: (Double, Double, String) -> Unit, onOpenUrl: (String) -> Unit) {
    Section(
        "Map",
        footer = "Looking it up sends this BSSID to wigle.net. WiGLE positions are approximate: they come from where volunteers' phones heard the network.",
    ) {
        if (!state.hasCredentials) {
            var name by remember { mutableStateOf("") }
            var token by remember { mutableStateOf("") }
            Text("Look up where this access point has been mapped. You need a free WiGLE account and its API token.")
            OutlinedTextField(name, { name = it }, label = { Text("API Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it }, label = { Text("API Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenUrl("https://wigle.net/account") }) { Text("Get a token at wigle.net") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { locator.saveCredentials(name, token) }, enabled = name.isNotBlank() && token.isNotBlank()) { Text("Save") }
            }
        } else {
            when (val map = state.map) {
                MapState.Idle -> Unit
                MapState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                MapState.NotFound -> Text("WiGLE has no location for this access point. Nobody has mapped it yet.")
                is MapState.Failed -> Text(map.message, color = MaterialTheme.colorScheme.error)
                is MapState.Found -> {
                    val n = map.network
                    Text(n.address ?: "Address not available", style = MaterialTheme.typography.titleMedium)
                    Text("Coordinates: ${"%.5f".format(n.trilat)}, ${"%.5f".format(n.trilong)}")
                    Text("Last updated on WiGLE: ${n.lastUpdated ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onOpenMaps(n.trilat!!, n.trilong!!, n.ssid ?: state.target.ssid) }) { Text("Open in Maps") }
                }
            }
            Row {
                Button(onClick = locator::lookUpOnWiGLE, enabled = state.map != MapState.Loading) {
                    Text(if (state.map == MapState.Idle) "Look up on WiGLE" else "Look up again")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = locator::forgetCredentials) { Text("Remove token") }
            }
        }
    }
}
