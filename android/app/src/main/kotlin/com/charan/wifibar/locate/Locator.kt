package com.charan.wifibar.locate

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.charan.wifibar.core.AccessPoint
import com.charan.wifibar.core.Arrival
import com.charan.wifibar.core.ArrivalDetector
import com.charan.wifibar.core.DeadReckoning
import com.charan.wifibar.core.MapPoint
import com.charan.wifibar.core.Observation
import com.charan.wifibar.core.PollRate
import com.charan.wifibar.core.RouterSearch
import com.charan.wifibar.core.Bssid
import com.charan.wifibar.core.DirectionSweep
import com.charan.wifibar.core.DistanceEstimate
import com.charan.wifibar.core.DistanceEstimator
import com.charan.wifibar.core.LinkInfo
import com.charan.wifibar.core.SignalEnvironment
import com.charan.wifibar.core.SignalSample
import com.charan.wifibar.core.SignalTracker
import com.charan.wifibar.core.SignalTrend
import com.charan.wifibar.core.WiGLEClient
import com.charan.wifibar.core.WiGLECredentials
import com.charan.wifibar.core.WiGLENetwork
import com.charan.wifibar.core.WifiBand
import com.charan.wifibar.wifi.TAG
import com.charan.wifibar.wifi.WifiRepository
import com.charan.wifibar.wigle.CredentialStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Readings at least this often (ms) allow the Mac-style continuous turn instead of stop-and-hold. */
const val CONTINUOUS_SWEEP_REFRESH = 1_200L

/** The access point being located. */
data class LocateTarget(val ssid: String, val bssid: String, val band: WifiBand, val channel: Int, val channelWidth: Int) {
    val channelSummary: String
        get() = buildList {
            add(band.label)
            add("Ch $channel")
            if (channelWidth > 0) add("$channelWidth MHz")
        }.joinToString(" · ")

    companion object {
        fun of(ssid: String, ap: AccessPoint) = ap.bssid?.let { LocateTarget(ssid, it, ap.band, ap.channel, ap.channelWidth) }
        fun of(link: LinkInfo) = if (link.ssid != null && link.bssid != null) {
            LocateTarget(link.ssid!!, link.bssid!!, link.band, link.channel, 0)
        } else {
            null
        }
    }
}

/**
 * Stop-and-hold sweep. Android refreshes the connected RSSI only about every 3 s (measured on the
 * Xiaomi Pad 8), too slow for a smooth turn, so the person faces 8 directions in turn and holds each.
 */
sealed interface SweepState {
    data object Idle : SweepState
    data object Unavailable : SweepState
    data object Calibrating : SweepState
    data class Holding(val stop: Int, val target: Double, val turned: Double, val holdProgress: Float, val readings: Int) : SweepState
    /**
     * The Mac's style, used when readings come at least once every 1.2 s (FastPoll): one slow, continuous
     * turn to the left, readings plotted as they arrive. [readings] are (degrees turned, dBm).
     */
    data class Turning(val turned: Double, val readings: List<Pair<Double, Int>>, val tooFast: Boolean, val wrongWay: Boolean) : SweepState
    /** [replayLabel] is set when showing a recorded sweep instead of one just measured. */
    data class Done(val result: DirectionSweep.Result, val startAzimuth: Double?, val replayLabel: String? = null) : SweepState
    data class Failed(val message: String) : SweepState
}

sealed interface MapState {
    data object Idle : MapState
    data object Loading : MapState
    data class Found(val network: WiGLENetwork) : MapState
    data object NotFound : MapState
    data class Failed(val message: String) : MapState
}

/** Why "Walk to find" says stop. */
enum class WalkStop {
    /** Two readings in a row averaging −32 dBm or stronger: within about a metre. */
    STRONG_SIGNAL,

    /** At least 80% likely that the router is within 2 m of you. */
    CONFIDENT,
}

/** What the search says after the latest reading ([RouterSearch]). */
data class WalkGuidance(
    /** Which way to walk (map heading, degrees left of the start direction). */
    val heading: Double,
    /** Most likely router position and roughly one standard deviation around it (m). */
    val mostLikely: MapPoint,
    val spread: Double,
    /** Cells with noticeable probability: (centre, probability relative to the best cell). */
    val heat: List<Pair<MapPoint, Double>>,
    /** Grid cell size (m), for drawing [heat]. */
    val cell: Double,
)

/** "Walk to find": the walk so far and where the router most likely is. */
data class WalkState(
    val path: List<MapPoint>,
    val observations: List<Observation>,
    /** Null until there are a few readings. */
    val guidance: WalkGuidance?,
    val steps: Int,
    /** Probability that the router is within 2 m of where you are now. */
    val nearby: Double = 0.0,
    val stop: WalkStop? = null,
)

data class LocateState(
    val target: LocateTarget,
    val samples: List<SignalSample> = emptyList(),
    val peak: SignalSample? = null,
    val trend: SignalTrend = SignalTrend.UNKNOWN,
    val isLinkReading: Boolean = false,
    val isMissing: Boolean = false,
    val environment: SignalEnvironment = SignalEnvironment.INDOORS,
    val playsSound: Boolean = false,
    val sweep: SweepState = SweepState.Idle,
    val hasCredentials: Boolean = false,
    val map: MapState = MapState.Idle,
    /** While following the arrow: keep walking, or stop (and why). */
    val arrival: Arrival = Arrival.WALKING,
    /** Best (2-reading) signal on this walk, for "you passed it" messages. */
    val walkBest: SignalSample? = null,
    /** Non-null while "Walk to find" is running. */
    val finding: WalkState? = null,
    /** How often Android refreshes the connected signal, as learned from the readings (ms). */
    val refreshMillis: Long = 3_000,
) {
    /** Fast enough for the Mac-style continuous sweep. */
    val fastReadings: Boolean get() = isLinkReading && refreshMillis <= CONTINUOUS_SWEEP_REFRESH
    val latestRssi: Int? get() = samples.lastOrNull()?.rssi
    val estimate: DistanceEstimate?
        get() = latestRssi?.let { DistanceEstimator.estimate(it, target.band, environment) }
}

class Locator(
    private val context: Context,
    private val repository: WifiRepository,
    private val scope: CoroutineScope,
    target: LocateTarget,
) {
    private val tracker = SignalTracker()
    private val walk = ArrivalDetector()
    private var sweep = DirectionSweep()
    val motion = MotionSource(context)
    private val _state = MutableStateFlow(LocateState(target, hasCredentials = CredentialStore.load(context) != null))
    val state: StateFlow<LocateState> = _state.asStateFlow()
    private val target get() = _state.value.target

    private var sampling: Job? = null
    private var sweeping: Job? = null
    private var lastSampleAt = 0L
    private var lastSampleRssi: Int? = null
    private var lastLinkRssi: Int? = null
    /** How often Android refreshes the connected signal: 3 s by default, faster after FastPoll. */
    private val pollRate = PollRate()
    private var lastSeenMicros = 0L
    private var lastScanRequest = 0L
    private var startAzimuth: Double? = null
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }

    fun start() {
        if (sampling != null) return
        Log.i(TAG, "Locating ${target.ssid} ${target.bssid}")
        sampling = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(if (_state.value.sweep is SweepState.Holding || _state.value.sweep is SweepState.Turning || _state.value.finding != null) 250 else 500)
            }
        }
    }

    fun stop() {
        sampling?.cancel()
        sampling = null
        // Steps can't be counted while the app is away, so a walk can't resume where it left off.
        if (_state.value.finding != null) stopFinding()
        cancelSweep()
    }

    fun setEnvironment(environment: SignalEnvironment) = _state.update { it.copy(environment = environment) }
    fun setSound(on: Boolean) = _state.update { it.copy(playsSound = on) }

    fun resetHistory() {
        tracker.reset()
        publish()
    }

    // MARK: Sampling

    private fun sampleOnce() {
        val link = repository.readLink()
        val now = SystemClock.elapsedRealtime()
        val linkMatches = link?.bssid?.let { Bssid.normalize(it) == Bssid.normalize(target.bssid) } == true
        val rssi: Int?
        if (linkMatches) {
            // Android refreshes the link every ~3 s (faster after FastPoll); count a repeat of the same
            // value only once per refresh, learned from when the value changes.
            val value = link!!.rssi
            if (value != lastLinkRssi) {
                pollRate.changed(now)
                lastLinkRssi = value
            }
            rssi = value.takeIf { it != lastSampleRssi || now - lastSampleAt >= pollRate.interval * 9 / 10 }
            _state.update { it.copy(isLinkReading = true, isMissing = false) }
        } else {
            // Other APs come from scans: throttled to 4 per 2 minutes, and only new results count.
            if (now - lastScanRequest >= 30_000) {
                lastScanRequest = now
                repository.startScan()
            }
            val ap = repository.state.value.accessPoints.firstOrNull { ap ->
                ap.bssid?.let { Bssid.normalize(it) == Bssid.normalize(target.bssid) } == true
            }
            rssi = ap?.takeIf { it.seenAtMicros > lastSeenMicros }?.also { lastSeenMicros = it.seenAtMicros }?.rssi
            _state.update { it.copy(isLinkReading = false, isMissing = ap == null) }
        }
        if (rssi == null) return
        lastSampleAt = now
        lastSampleRssi = rssi
        tracker.trendSpan = if (linkMatches) 3 else 1
        tracker.trendThreshold = if (linkMatches) 3.0 else 4.0
        val sample = SignalSample(System.currentTimeMillis(), rssi)
        if (tracker.add(sample)) newBest()
        onSweepReading(rssi)
        if (_state.value.finding != null) addWalkReading(rssi)
        else if (_state.value.sweep is SweepState.Done) followArrow(sample)
        publish()
    }

    private fun publish() = _state.update {
        it.copy(samples = tracker.samples.toList(), peak = tracker.peak, trend = tracker.trend, refreshMillis = pollRate.interval)
    }

    private fun newBest() {
        if (!_state.value.playsSound) return
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    // MARK: Walk to find

    private val deadReckoning = DeadReckoning()
    private val observations = ArrayList<Observation>()
    private var search: RouterSearch? = null
    private var belief: RouterSearch.Belief? = null
    private var guidance: WalkGuidance? = null
    private var stepCount = 0
    private var androidSteps = 0
    private var estimateRequest = 0
    private var walkStop: WalkStop? = null
    private var recorder: WalkRecorder? = null
    private val walked: Double get() = stepCount * deadReckoning.stepLength

    /**
     * Start walking: every reading is tagged with where you are (steps × heading) and which way you face,
     * the router's position is re-estimated ([RouterSearch]) and the arrow says which way to walk next.
     */
    fun startFinding() {
        if (_state.value.finding != null) stopFinding() // "Start over from here"
        if (!motion.start(walking = true)) {
            _state.update { it.copy(sweep = SweepState.Unavailable) }
            return
        }
        cancelSweepOnly()
        deadReckoning.reset()
        observations.clear()
        stepCount = 0
        androidSteps = 0
        estimateRequest = 0
        walkStop = null
        belief = null
        guidance = null
        search = RouterSearch(referenceRssi = DistanceEstimator.referenceRssi(target.band))
        keepWalking()
        val recorder = runCatching { WalkRecorder(File(context.filesDir, "walks"), SystemClock.elapsedRealtimeNanos()) }
            .onFailure { Log.w(TAG, "Walk recording unavailable", it) }.getOrNull()
        this.recorder = recorder
        motion.onAcceleration = { nanos, up, filtered -> recorder?.acceleration(nanos, up, filtered) }
        motion.onStep = { fromAccelerometer ->
            // The accelerometer count moves you; Android's step detector is only recorded, to compare.
            if (fromAccelerometer) {
                deadReckoning.step(motion.yaw)
                stepCount += 1
            } else {
                androidSteps += 1
            }
            val here = deadReckoning.position
            recorder?.step(SystemClock.elapsedRealtimeNanos(), fromAccelerometer, here.x, here.y, motion.yaw)
            if (fromAccelerometer) publishWalk()
        }
        scope.launch {
            delay(300) // let gravity settle, then count turns from here
            motion.tracker.resetYaw()
            Log.i(TAG, "Walk to find started (recording ${recorder?.file?.name})")
            publishWalk()
        }
    }

    fun stopFinding() {
        if (motion.onStep != null) {
            Log.i(TAG, "Walk to find stopped: $stepCount steps (Android's detector: $androidSteps), ${observations.size} readings, saved ${recorder?.file?.name}")
        }
        recorder?.close()
        recorder = null
        motion.onAcceleration = null
        motion.onStep = null
        motion.stop()
        walkStop = null
        _state.update { it.copy(finding = null) }
    }

    /** Where you are now and which way you face, for the live map (read each frame, not observed). */
    val walkPosition: MapPoint get() = deadReckoning.position
    val facing: Double get() = motion.yaw

    private fun addWalkReading(rssi: Int) {
        val here = deadReckoning.position
        observations.add(Observation(here, motion.yaw, rssi, walked))
        recorder?.reading(SystemClock.elapsedRealtimeNanos(), rssi, here.x, here.y, motion.yaw)
        val search = search ?: return
        if (observations.size < 3) return publishWalk()
        val snapshot = observations.toList()
        val walkedNow = walked
        val facingNow = motion.yaw
        // Plan one reading interval ahead at ~0.8 m/s (at least 1.5 m), as simulated.
        val stride = maxOf(1.5, 0.8 * pollRate.interval / 1000.0)
        val request = ++estimateRequest
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val (belief, guidance) = withContext(Dispatchers.Default) {
                val belief = search.belief(snapshot, walkedNow)
                val heading = search.guide(belief, here, facingNow, stride)
                val top = belief.weights.max()
                val heat = belief.cells.indices.filter { belief.weights[it] > 0.05 * top }.map { belief.cells[it] to belief.weights[it] / top }
                belief to WalkGuidance(heading, belief.mostLikely, belief.spread, heat, cell = 1.0)
            }
            if (request != estimateRequest) return@launch // a newer reading already replaced it
            this@Locator.belief = belief
            this@Locator.guidance = guidance
            val best = guidance.mostLikely
            Log.i(
                TAG,
                "Walk guidance: ${snapshot.size} readings (latest $rssi dBm), $stepCount steps (Android's detector: $androidSteps), " +
                    "walk %.0f° (turn %.0f°), within 2 m %.0f%%, best (%.1f, %.1f) ±%.1f m, you at (%.1f, %.1f), refresh %d ms, %d ms".format(
                        guidance.heading, DirectionSweep.signedDifference(facingNow, guidance.heading),
                        100 * belief.within(here, 2.0), best.x, best.y, guidance.spread, here.x, here.y,
                        pollRate.interval, SystemClock.elapsedRealtime() - started,
                    ),
            )
            publishWalk()
        }
    }

    private fun publishWalk() {
        if (motion.onStep == null) return
        val nearby = belief?.within(deadReckoning.position, 2.0) ?: 0.0
        val stop = walkStop(nearby)
        if (stop != walkStop) {
            Log.i(TAG, "Walk stop: $walkStop -> $stop (within 2 m %.0f%%)".format(100 * nearby))
            if (stop != null && walkStop == null) stopSignal()
            walkStop = stop
        }
        _state.update {
            it.copy(finding = WalkState(deadReckoning.path.toList(), observations.toList(), guidance, stepCount, nearby, stop))
        }
    }

    /**
     * Stop when the signal is very strong, or when it's ≥ 80% likely the router is within 2 m of you.
     * Checked on every step as well as every reading. Each has a margin before it clears again
     * (−36 dBm, 60%), so a value hovering at the threshold doesn't buzz again and again.
     */
    private fun walkStop(nearby: Double): WalkStop? {
        // About 3 s of readings (2 at Android's default rate, 3 at one a second): with faster readings two
        // lucky jumps in a row became likely, and simulated wrong stops rose from 3% to 5%.
        val window = maxOf(2, Math.round(3_000.0 / pollRate.interval).toInt())
        val recent = observations.takeLast(window)
        val strong = if (walkStop == WalkStop.STRONG_SIGNAL) -36.0 else -32.0
        if (recent.size == window && recent.map { it.rssi }.average() >= strong) return WalkStop.STRONG_SIGNAL
        val sure = if (walkStop == WalkStop.CONFIDENT) 0.6 else 0.8
        return if (nearby >= sure) WalkStop.CONFIDENT else null
    }

    // MARK: Following the arrow

    /** Feeds the walk after a sweep; buzzes once when it's time to stop. */
    private fun followArrow(sample: SignalSample) {
        val before = _state.value.arrival
        val now = walk.add(sample)
        _state.update { it.copy(arrival = now, walkBest = walk.best) }
        if (now != before) Log.i(TAG, "Walk: $before -> $now at ${sample.rssi} dBm (best ${walk.best?.rssi})")
        if (now != Arrival.WALKING && before == Arrival.WALKING) stopSignal()
    }

    /** "Keep walking": start judging the walk afresh from here. */
    fun keepWalking() {
        walk.reset()
        _state.update { it.copy(arrival = Arrival.WALKING, walkBest = null) }
    }

    private fun stopSignal() {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        if (_state.value.playsSound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
    }

    // MARK: Direction sweep

    private companion object {
        const val READINGS_PER_STOP = 4
    }


    private var stopHeldSince: Long? = null
    private var readingsAtStop = 0

    fun startSweep() {
        if (!_state.value.isLinkReading) return
        if (_state.value.finding != null) stopFinding()
        if (!motion.start()) {
            _state.update { it.copy(sweep = SweepState.Unavailable) }
            return
        }
        sweeping?.cancel()
        sweep = DirectionSweep()
        startAzimuth = null
        _state.update { it.copy(sweep = SweepState.Calibrating) }
        Log.i(TAG, "Direction sweep started")
        sweeping = scope.launch {
            // Android delivers the gyroscope with its offset already removed (measured residue on the
            // Xiaomi Pad 8: 0.005–0.011 °/s), so no "hold still" calibration: learning an offset while the
            // device moves made the heading creep. Just let gravity settle and count from zero.
            delay(300)
            motion.tracker.resetYaw()
            Log.i(TAG, "Sweep start: up ${motion.tracker.upDirection.fmt()}")
            startAzimuth = motion.azimuth
            val continuous = pollRate.interval <= CONTINUOUS_SWEEP_REFRESH
            Log.i(TAG, "Sweep style: ${if (continuous) "continuous turn" else "stop and hold"} (refresh ${pollRate.interval} ms)")
            if (continuous) {
                turnHistory.clear()
                _state.update { it.copy(sweep = SweepState.Turning(0.0, emptyList(), tooFast = false, wrongWay = false)) }
            } else {
                moveToStop(0)
            }
            var tick = 0
            while (isActive) {
                if (_state.value.sweep is SweepState.Turning) updateTurning() else updateHolding()
                if (tick++ % 10 == 0) Log.i(TAG, "Sweep yaw ${"%.1f".format(motion.yaw)} azimuth ${motion.azimuth?.let { "%.0f".format(it) }}")
                delay(100)
            }
        }
    }

    private fun moveToStop(stop: Int) {
        stopHeldSince = null
        readingsAtStop = 0
        _state.update { it.copy(sweep = SweepState.Holding(stop, stop * 45.0, motion.yaw, 0f, 0)) }
    }

    /**
     * Within ±12° of the stop's direction until 4 readings arrive (~12 s at Android's 3 s refresh).
     * Fewer proved too noisy: one tested direction ranged over 15 dB with 3 readings.
     */
    private fun updateHolding() {
        val holding = _state.value.sweep as? SweepState.Holding ?: return
        val now = SystemClock.elapsedRealtime()
        val aligned = abs(motion.yaw - holding.target) <= 12
        if (!aligned) stopHeldSince = null else if (stopHeldSince == null) stopHeldSince = now
        val held = stopHeldSince?.let { now - it } ?: 0L
        val progress = (readingsAtStop / READINGS_PER_STOP.toFloat()).coerceAtMost(1f)
        if (aligned && held >= 6_000 && readingsAtStop >= READINGS_PER_STOP) {
            vibrateTick()
            if (holding.stop == 7) finishSweep() else moveToStop(holding.stop + 1)
            return
        }
        _state.update { it.copy(sweep = holding.copy(turned = motion.yaw, holdProgress = progress, readings = readingsAtStop)) }
    }

    private val turnHistory = ArrayDeque<Pair<Long, Double>>()

    /** Continuous turn: track the angle and speed; done after a full circle to the left. */
    private fun updateTurning() {
        val turning = _state.value.sweep as? SweepState.Turning ?: return
        val now = SystemClock.elapsedRealtime()
        val yaw = motion.yaw
        turnHistory.addLast(now to yaw)
        while (turnHistory.size > 2 && now - turnHistory.first().first > 1_500) turnHistory.removeFirst()
        val (t0, y0) = turnHistory.first()
        val speed = if (now > t0) abs(yaw - y0) / ((now - t0) / 1000.0) else 0.0
        // Faster than ~15°/s leaves gaps wider than 15° between readings at one a second.
        val tooFast = speed > 15 * (1_000.0 / pollRate.interval)
        if (yaw >= 360) {
            vibrateTick()
            finishSweep()
            return
        }
        _state.update { it.copy(sweep = turning.copy(turned = yaw, tooFast = tooFast, wrongWay = yaw < -20)) }
    }

    private fun onSweepReading(rssi: Int) {
        (_state.value.sweep as? SweepState.Turning)?.let { turning ->
            val yaw = motion.yaw
            if (yaw < 0) return // turned right first; readings count once the circle goes left
            sweep.add(yaw, rssi)
            _state.update { it.copy(sweep = turning.copy(readings = turning.readings + (yaw to rssi))) }
            Log.i(TAG, "Sweep reading yaw ${"%.0f".format(yaw)} rssi $rssi")
            return
        }
        val holding = _state.value.sweep as? SweepState.Holding ?: return
        if (stopHeldSince == null) return
        sweep.add(motion.yaw, rssi)
        readingsAtStop += 1
        Log.i(TAG, "Sweep reading stop ${holding.stop} yaw ${"%.0f".format(motion.yaw)} rssi $rssi")
    }

    /**
     * After the 8th direction. The stops sit at known angles (0°, 45° … 315°) of a full circle, so the
     * turn counts as exactly 360°. Unlike the Mac, Android's gyroscope arrives factory-calibrated, and
     * asking people to "turn back to the start" proved unreliable (tested: Done tapped at 307°).
     */
    private fun finishSweep() {
        sweeping?.cancel()
        val total = 360.0
        try {
            val result = sweep.result(total)
            Log.i(TAG, "Direction sweep: ended at yaw ${motion.yaw.toInt()}°, ${sweep.readings.size} readings, best ${result.bearing.toInt()}°, spread ${"%.1f".format(result.spread)} dB, tied sectors ${result.confidence?.tiedSectors}, clear ${result.isClear}")
            keepWalking()
            _state.update { it.copy(sweep = SweepState.Done(result, startAzimuth)) }
            scaleForPointer = result.scale
        } catch (failure: DirectionSweep.Failure) {
            Log.i(TAG, "Direction sweep failed: turn ${total.toInt()}°, ${sweep.readings.size} readings, $failure")
            motion.stop()
            _state.update { it.copy(sweep = SweepState.Failed(message(failure))) }
        }
    }

    /**
     * Shows a recorded sweep's result with a live arrow, without walking a new sweep. Directions are
     * relative to how the device faces when the replay starts. Used for demos in debug builds.
     */
    fun replay(readings: List<Pair<Double, Int>>, label: String) {
        if (!motion.start()) {
            _state.update { it.copy(sweep = SweepState.Unavailable) }
            return
        }
        sweeping?.cancel()
        sweeping = scope.launch {
            delay(300)
            motion.tracker.resetYaw()
            val recorded = DirectionSweep().apply { readings.forEach { (yaw, rssi) -> add(yaw, rssi) } }
            val result = recorded.result(360.0)
            scaleForPointer = 1.0
            Log.i(TAG, "Replaying sweep: ${readings.size} readings, direction ${result.direction.toInt()}°, clear ${result.isClear}")
            keepWalking()
            _state.update { it.copy(sweep = SweepState.Done(result, motion.azimuth, label)) }
            logHeadingWhileShowingResult()
        }
    }

    /** For checking drift: the heading every 2 s for a minute while a result is on screen. */
    private suspend fun logHeadingWhileShowingResult() {
        repeat(30) {
            if (_state.value.sweep !is SweepState.Done) return
            Log.i(TAG, "Result heading yaw ${"%.1f".format(motion.yaw)}")
            delay(2_000)
        }
    }

    fun cancelSweep() {
        cancelSweepOnly()
        if (_state.value.finding == null) motion.stop()
    }

    private fun cancelSweepOnly() {
        sweeping?.cancel()
        sweeping = null
        _state.update { it.copy(sweep = SweepState.Idle) }
    }

    private var scaleForPointer = 1.0

    /** Degrees to turn from where you face now to the strongest direction: positive = left. */
    fun turnToStrongest(result: DirectionSweep.Result): Double =
        DirectionSweep.signedDifference(motion.yaw * scaleForPointer, result.direction)

    private fun vibrateTick() {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    private fun message(failure: DirectionSweep.Failure) = when (failure) {
        is DirectionSweep.Failure.TurnTooSmall ->
            "You turned about ${failure.degrees.toInt()}°. Finish the circle so you face where you started, then tap Done."
        is DirectionSweep.Failure.TurnTooLarge ->
            "You turned about ${failure.degrees.toInt()}°, more than one circle. Stop as soon as you face where you started."
        is DirectionSweep.Failure.TooFewReadings ->
            "Not enough signal readings. Hold each direction until its bar fills."
    }

    // MARK: WiGLE

    fun saveCredentials(name: String, token: String) {
        CredentialStore.save(context, WiGLECredentials(name.trim(), token.trim()))
        _state.update { it.copy(hasCredentials = true) }
    }

    fun forgetCredentials() {
        CredentialStore.clear(context)
        _state.update { it.copy(hasCredentials = false, map = MapState.Idle) }
    }

    fun lookUpOnWiGLE() {
        val credentials = CredentialStore.load(context) ?: return
        _state.update { it.copy(map = MapState.Loading) }
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { WiGLEClient(credentials).lookUp(target.bssid) }
                    ?.let { MapState.Found(it) } ?: MapState.NotFound
            } catch (e: Exception) {
                MapState.Failed(e.message ?: "WiGLE lookup failed.")
            }
            _state.update { it.copy(map = result) }
        }
    }
}

private fun com.charan.wifibar.core.Vec3.fmt() = "(%.3f, %.3f, %.3f)".format(x, y, z)
