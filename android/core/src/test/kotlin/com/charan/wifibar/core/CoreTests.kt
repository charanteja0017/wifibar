package com.charan.wifibar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported case-for-case from the Mac app's Tests/WiFiCoreTests, so both implementations must agree.

private fun ap(ssid: String?, bssid: String?, rssi: Int, channel: Int = 36, band: WifiBand = WifiBand.GHZ_5) =
    AccessPoint(ssid, bssid, rssi, channel, band, 80, SecurityKind.PERSONAL, "WPA2 Personal")

class SignalQualityTest {
    @Test fun boundaries() {
        val cases = listOf(
            -30 to SignalQuality.EXCELLENT, -55 to SignalQuality.EXCELLENT,
            -56 to SignalQuality.GOOD, -67 to SignalQuality.GOOD,
            -68 to SignalQuality.FAIR, -77 to SignalQuality.FAIR,
            -78 to SignalQuality.WEAK, -100 to SignalQuality.WEAK,
        )
        for ((rssi, expected) in cases) assertEquals(expected, SignalQuality.of(rssi), "rssi $rssi")
    }

    @Test fun barsIncrease() {
        assertEquals(listOf(0, 1, 2, 3), SignalQuality.entries.map { it.bars })
    }

    @Test fun meterFractionClamps() {
        assertEquals(0.0, SignalQuality.meterFraction(-120))
        assertEquals(1.0, SignalQuality.meterFraction(-10))
        assertEquals(0.5, SignalQuality.meterFraction(-60))
    }
}

class GroupingTest {
    @Test fun sameNameAccessPointsShareAGroupStrongestFirst() {
        val groups = NetworkGrouping.group(
            listOf(
                ap("Office", "aa:00:00:00:00:01", -70),
                ap("Office", "aa:00:00:00:00:02", -48),
                ap("Office", "aa:00:00:00:00:03", -60, 6, WifiBand.GHZ_2_4),
                ap("Cafe", "bb:00:00:00:00:01", -40),
            ),
            currentSsid = null, currentBssid = null,
        )
        assertEquals(listOf("Cafe", "Office"), groups.map { it.ssid })
        val office = groups.first { it.ssid == "Office" }
        assertEquals(listOf(-48, -60, -70), office.accessPoints.map { it.rssi })
        assertEquals(listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5), office.bands)
    }

    @Test fun joinedNetworkComesFirstAndMarksItsBssid() {
        val groups = NetworkGrouping.group(
            listOf(
                ap("Strong", "cc:00:00:00:00:01", -35),
                ap("Home", "dd:00:00:00:00:01", -75),
                ap("Home", "dd:00:00:00:00:02", -50),
            ),
            currentSsid = "Home", currentBssid = "DD:0:0:0:0:1", knownSsids = setOf("Strong"),
        )
        assertEquals(listOf("Home", "Strong"), groups.map { it.ssid })
        val home = groups[0]
        assertTrue(home.isCurrent && home.isKnown)
        assertEquals(listOf("dd:00:00:00:00:01"), home.accessPoints.filter(home::isCurrent).map { it.bssid })
        assertTrue(groups[1].isKnown && !groups[1].isCurrent)
    }

    @Test fun hiddenAndDuplicateAccessPointsAreDropped() {
        val input = listOf(
            ap(null, "ee:00:00:00:00:01", -50),
            ap("", "ee:00:00:00:00:02", -50),
            ap("Lab", "ff:00:00:00:00:01", -50),
            ap("Lab", "FF:00:00:00:00:01", -52),
        )
        val groups = NetworkGrouping.group(input, null, null)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].accessPoints.size)
        assertEquals(2, NetworkGrouping.hiddenCount(input))
    }

    @Test fun ties() {
        val groups = NetworkGrouping.group(listOf(ap("b", "1", -60), ap("a", "2", -60)), null, null)
        assertEquals(listOf("a", "b"), groups.map { it.ssid })
    }
}

class DistanceEstimatorTest {
    @Test fun referenceFollowsFreeSpaceLossPerBand() {
        assertTrue(abs(DistanceEstimator.referenceRssi(WifiBand.GHZ_2_4) - -30.2) < 0.1)
        assertTrue(abs(DistanceEstimator.referenceRssi(WifiBand.GHZ_5) - -37.3) < 0.1)
        assertTrue(DistanceEstimator.referenceRssi(WifiBand.GHZ_6) < DistanceEstimator.referenceRssi(WifiBand.GHZ_5))
    }

    @Test fun weakerSignalMeansFartherAway() {
        val near = DistanceEstimator.estimate(-45, WifiBand.GHZ_5, SignalEnvironment.INDOORS)
        val far = DistanceEstimator.estimate(-75, WifiBand.GHZ_5, SignalEnvironment.INDOORS)
        assertTrue(near.meters < far.meters)
    }

    @Test fun knownPoint() {
        // −60 dBm at 5 GHz indoors: 10^((−37.26 + 60) / 30) ≈ 5.7 m.
        val estimate = DistanceEstimator.estimate(-60, WifiBand.GHZ_5, SignalEnvironment.INDOORS)
        assertTrue(abs(estimate.meters - 5.7) < 0.1, "got ${estimate.meters}")
        assertTrue(estimate.low < estimate.meters && estimate.meters < estimate.high)
    }

    @Test fun openSpaceReachesFartherForTheSameReading() {
        val indoors = DistanceEstimator.estimate(-70, WifiBand.GHZ_2_4, SignalEnvironment.INDOORS)
        val open = DistanceEstimator.estimate(-70, WifiBand.GHZ_2_4, SignalEnvironment.OPEN_SPACE)
        assertTrue(open.meters > indoors.meters)
    }

    @Test fun veryStrongSignalIsClampedNotZero() {
        val estimate = DistanceEstimator.estimate(-10, WifiBand.GHZ_2_4, SignalEnvironment.INDOORS)
        assertEquals(0.3, estimate.meters)
        assertEquals("< 1 m", DistanceEstimator.format(estimate.meters))
    }

    @Test fun rangeIsOmittedWhenRightNextToTheRouter() {
        val close = DistanceEstimator.estimate(-36, WifiBand.GHZ_5, SignalEnvironment.INDOORS)
        assertEquals("< 1 m", DistanceEstimator.format(close.meters))
        assertNull(DistanceEstimator.formatRange(close))
        val far = DistanceEstimator.estimate(-60, WifiBand.GHZ_5, SignalEnvironment.INDOORS)
        // 10^(22.74/35) ≈ 4.47 m to 10^(22.74/26) ≈ 7.49 m
        assertEquals("4–7 m", DistanceEstimator.formatRange(far))
    }

    @Test fun formatting() {
        val cases = listOf(0.4 to "< 1 m", 5.7 to "≈ 6 m", 9.4 to "≈ 9 m", 23.0 to "≈ 25 m", 38.9 to "≈ 40 m")
        for ((meters, expected) in cases) assertEquals(expected, DistanceEstimator.format(meters))
    }
}

class SignalTrackerTest {
    private fun feed(values: List<Int>, tracker: SignalTracker) =
        values.mapIndexed { index, rssi -> tracker.add(SignalSample(index * 1000L, rssi)) }

    @Test fun peakAndNewBestSignals() {
        val tracker = SignalTracker()
        assertEquals(listOf(false, false, true, false, true), feed(listOf(-70, -72, -65, -65, -60), tracker))
        assertEquals(-60, tracker.peak?.rssi)
    }

    @Test fun trendNeedsEnoughReadings() {
        val tracker = SignalTracker()
        feed(listOf(-80, -70, -60, -50, -40), tracker)
        assertEquals(SignalTrend.UNKNOWN, tracker.trend)
    }

    @Test fun trendUsesAveragesNotSingleReadings() {
        assertEquals(SignalTrend.STRONGER, SignalTracker().also { feed(listOf(-80, -79, -81, -74, -73, -75), it) }.trend)
        assertEquals(SignalTrend.WEAKER, SignalTracker().also { feed(listOf(-60, -61, -59, -66, -65, -67), it) }.trend)
        // One noisy spike doesn't flip the trend.
        assertEquals(SignalTrend.STEADY, SignalTracker().also { feed(listOf(-70, -70, -70, -70, -64, -70), it) }.trend)
    }

    @Test fun oldReadingsFallOutOfTheWindow() {
        val tracker = SignalTracker(windowMillis = 10_000)
        tracker.add(SignalSample(0, -50))
        tracker.add(SignalSample(11_000, -60))
        assertEquals(listOf(-60), tracker.samples.map { it.rssi })
        assertEquals(-50, tracker.peak?.rssi)
    }

    @Test fun resetClearsEverything() {
        val tracker = SignalTracker()
        feed(listOf(-50, -60), tracker)
        tracker.reset()
        assertTrue(tracker.samples.isEmpty() && tracker.peak == null)
    }
}

class HeadingTrackerTest {
    /** Feeds `seconds` of a steady turn at 100 Hz, after calibrating with the gyro's resting offset. */
    private fun turn(rate: Vec3, bias: Vec3, up: Vec3, seconds: Double): Double {
        val tracker = HeadingTracker()
        repeat(50) { tracker.addUp(up) }
        tracker.beginCalibration()
        var time = 0.0
        repeat(100) { tracker.addRotation(bias, time); time += 0.01 }
        tracker.endCalibration()
        repeat((seconds * 100).toInt()) { tracker.addRotation(rate + bias, time); time += 0.01 }
        return tracker.yaw
    }

    @Test fun flatDeviceTurningLeftCountsUp() {
        // Android's gravity sensor reads (0, 0, +9.81) lying flat: it points up.
        val yaw = turn(Vec3(0.0, 0.0, 10.0), Vec3(-0.18, 0.66, -0.12), Vec3(0.0, 0.0, 9.81), 9.0)
        assertTrue(abs(yaw - 90) < 0.5, "got $yaw")
    }

    @Test fun tiltedDeviceStillMeasuresTurnsAboutVertical() {
        val tilt = 30.0 * PI / 180
        val up = Vec3(0.0, sin(tilt), cos(tilt))
        val yaw = turn(up * 20.0, Vec3(0.1, 0.5, 0.0), up * 9.81, 4.5)
        assertTrue(abs(yaw - 90) < 0.5, "got $yaw")
    }

    @Test fun restingOffsetIsRemoved() {
        val yaw = turn(Vec3.ZERO, Vec3(0.3, 0.6, 0.9), Vec3(0.0, 0.0, 9.81), 60.0)
        assertTrue(abs(yaw) < 0.01, "got $yaw")
    }

    @Test fun calibratingWhileTurningIsRejectedInsteadOfCausingDrift() {
        // Turning ~1°/s by hand while "holding still": the old code learned that as an offset and the heading
        // then crept at −1°/s forever. Hand-held motion wobbles, so the calibration is now rejected.
        val tracker = HeadingTracker()
        tracker.addUp(Vec3(0.0, 9.81, 0.0)) // held upright
        tracker.beginCalibration()
        var time = 0.0
        repeat(150) { i -> tracker.addRotation(Vec3(0.3 * sin(i * 1.3), 1.0 + 1.5 * sin(i * 0.7), 0.0), time); time += 0.01 }
        tracker.endCalibration()
        assertFalse(tracker.calibrationAccepted)
        repeat(3000) { tracker.addRotation(Vec3.ZERO, time); time += 0.01 } // 30 s still
        assertTrue(abs(tracker.yaw) < 0.01, "drifted ${tracker.yaw}")
    }

    @Test fun steadyCalibrationIsAccepted() {
        val tracker = HeadingTracker()
        tracker.addUp(Vec3(0.0, 0.0, 9.81))
        tracker.beginCalibration()
        var time = 0.0
        repeat(100) { i -> tracker.addRotation(Vec3(0.02, -0.01, 0.03 + 0.01 * sin(i.toDouble())), time); time += 0.01 }
        tracker.endCalibration()
        assertTrue(tracker.calibrationAccepted)
    }

    @Test fun firstUpReadingIsUsedImmediately() {
        // Held upright from the start: rotation about the device's y axis must count fully at once.
        val tracker = HeadingTracker()
        tracker.addUp(Vec3(0.0, 9.81, 0.0))
        tracker.resetYaw()
        tracker.addRotation(Vec3(0.0, 90.0, 0.0), 0.0)
        tracker.addRotation(Vec3(0.0, 90.0, 0.0), 0.05)
        assertTrue(abs(tracker.yaw - 4.5) < 0.01, "got ${tracker.yaw}")
    }

    @Test fun gapsAreNotIntegrated() {
        val tracker = HeadingTracker()
        tracker.beginCalibration()
        tracker.endCalibration()
        tracker.addRotation(Vec3(0.0, 0.0, 100.0), 0.0)
        tracker.addRotation(Vec3(0.0, 0.0, 100.0), 5.0)
        assertEquals(0.0, tracker.yaw)
    }
}

class DirectionSweepTest {
    private fun sweep(peak: Double, scale: Double = 1.0, clockwise: Boolean = false, depth: Double = 8.0, readings: Int = 60): Pair<DirectionSweep, Double> {
        val sweep = DirectionSweep()
        val random = Random(7)
        repeat(readings) { index ->
            val trueAngle = index.toDouble() / readings * 360
            val signed = if (clockwise) -trueAngle else trueAngle
            val rssi = -60 + depth * cos((DirectionSweep.normalized(signed) - peak) * PI / 180) + random.nextDouble(-2.0, 2.0)
            sweep.add(signed * scale, Math.round(rssi).toInt())
        }
        return sweep to (if (clockwise) -360.0 else 360.0) * scale
    }

    @Test fun findsThePeak() {
        for (peak in listOf(0.0, 45.0, 120.0, 200.0, 310.0)) {
            val (sweep, total) = sweep(peak)
            val result = sweep.result(total)
            assertTrue(DirectionSweep.angularDistance(result.bearing, peak) <= 10, "peak $peak got ${result.bearing}")
            assertTrue(result.isClear)
        }
    }

    @Test fun gyroScaleErrorIsCancelledByTheFullTurn() {
        val (sweep, total) = sweep(120.0, scale = 421.0 / 360)
        val result = sweep.result(total)
        assertTrue(DirectionSweep.angularDistance(result.bearing, 120.0) <= 10)
        assertTrue(abs(result.scale - 360.0 / 421) < 0.001)
    }

    @Test fun turningRightWorksToo() {
        val (sweep, total) = sweep(300.0, clockwise = true)
        assertTrue(DirectionSweep.angularDistance(sweep.result(total).bearing, 300.0) <= 10)
    }

    @Test fun stopAndHoldWithEightDirectionsFindsThePeak() {
        // Android refreshes RSSI ~every 3 s, so the app holds at 8 directions and takes ~2 readings each.
        val sweep = DirectionSweep()
        for (stop in 0 until 8) {
            val angle = stop * 45.0
            repeat(2) { sweep.add(angle, Math.round(-65 + 8 * cos((angle - 135) * PI / 180)).toInt()) }
        }
        val result = sweep.result(360.0)
        assertTrue(DirectionSweep.angularDistance(result.bearing, 135.0) <= 10, "got ${result.bearing}")
    }

    private fun loggedSweep(vararg readings: Pair<Int, Int>) =
        DirectionSweep().apply { readings.forEach { (yaw, rssi) -> add(yaw.toDouble(), rssi) } }.result(360.0)

    @Test fun realNoisySweepFromTheTabletIsNotClear() {
        // Xiaomi Pad 8, 2026-10-10 17:32–17:34, a few metres from the router, 2–4 readings per direction.
        // 225° tied with 0° and 45° on the other side of the circle; the first version drew a wrong arrow.
        val result = loggedSweep(
            7 to -53, -4 to -38, -9 to -36, 50 to -43, 40 to -37, 42 to -42, 43 to -43,
            85 to -49, 86 to -46, 138 to -47, 129 to -45, 177 to -52, 178 to -48,
            219 to -43, 222 to -35, 218 to -39, 224 to -38, 267 to -43, 266 to -55, 314 to -55, 310 to -51,
        )
        assertTrue(result.spread >= 4, "the profile alone looks like a peak: ${result.spread} dB")
        val confidence = result.confidence!!
        assertEquals(5, confidence.bestSector) // 225°
        assertTrue(0 in confidence.tiedSectors && 5 in confidence.tiedSectors, "ties ${confidence.tiedSectors}")
        assertFalse(confidence.isConsistent)
        assertFalse(result.isClear)
    }

    @Test fun realGoodSweepGivesTheFrontLeftQuarter() {
        // Same tablet, 17:46–17:48, 4 readings per direction: 0–90° left ≈ −37…−41 dBm, behind-right ≈ −59.
        val result = loggedSweep(
            0 to -40, 0 to -38, 0 to -42, 0 to -41, 51 to -44, 46 to -45, 48 to -38, 48 to -38,
            92 to -35, 92 to -33, 94 to -34, 88 to -46, 130 to -48, 136 to -54, 136 to -58, 136 to -58,
            180 to -45, 181 to -57, 181 to -47, 181 to -48, 223 to -56, 225 to -59, 225 to -60, 225 to -59,
            279 to -50, 270 to -53, 271 to -52, 270 to -50, 315 to -55, 315 to -52, 315 to -56, 315 to -56,
        )
        val confidence = result.confidence!!
        assertEquals(setOf(0, 1, 2), confidence.tiedSectors)
        assertEquals(listOf(0, 1, 2), confidence.run)
        assertTrue(result.isClear)
        assertEquals(45.0, result.direction)
        assertEquals(0.0 to 90.0, confidence.from to confidence.to)
    }

    @Test fun spanAcrossTheStartWraps() {
        val sweep = DirectionSweep()
        for (stop in 0 until 8) repeat(3) { sweep.add(stop * 45.0, if (stop == 7 || stop == 0) -40 else -60) }
        val result = sweep.result(360.0)
        assertEquals(listOf(7, 0), result.confidence!!.run)
        assertEquals(337.5, result.direction)
    }

    @Test fun flatSignalIsNotAClearDirection() {
        val (sweep, total) = sweep(90.0, depth = 0.5)
        assertFalse(sweep.result(total).isClear)
    }

    @Test fun incompleteSweepsAreRejected() {
        val (sweep, _) = sweep(90.0)
        assertEquals(DirectionSweep.Failure.TurnTooSmall(180.0), assertFailsWith<DirectionSweep.Failure> { sweep.result(180.0) })
        assertEquals(DirectionSweep.Failure.TurnTooLarge(720.0), assertFailsWith<DirectionSweep.Failure> { sweep.result(-720.0) })
        val short = DirectionSweep().apply { add(0.0, -50) }
        assertEquals(DirectionSweep.Failure.TooFewReadings(1), assertFailsWith<DirectionSweep.Failure> { short.result(360.0) })
    }

    @Test fun turnDirections() {
        assertEquals(40.0, DirectionSweep.signedDifference(10.0, 50.0))
        assertEquals(-40.0, DirectionSweep.signedDifference(10.0, 330.0))
        assertEquals(30.0, DirectionSweep.signedDifference(350.0, 20.0))
        assertEquals(270.0, DirectionSweep.normalized(-90.0))
    }
}

class WiGLETest {
    private val sample = """
        {"success":true,"totalResults":2,"first":1,"last":2,"resultCount":2,"results":[
          {"trilat":37.3349,"trilong":-122.009,"ssid":"Neighbour","netid":"AA:BB:CC:DD:EE:00","lastupdt":"2025-01-02T03:04:05.000Z",
           "road":"Main St","housenumber":"1","city":"Cupertino","region":"CA","country":"US","channel":36,"qos":2},
          {"trilat":37.3318,"trilong":-122.0312,"ssid":"Home","netid":"AA:BB:CC:DD:EE:0F","lastupdt":"2024-05-12T10:22:33.000Z",
           "road":"Infinite Loop","housenumber":"1","city":"Cupertino","region":"CA","country":"US","channel":6,"qos":3}
        ],"searchAfter":"x"}
    """.trimIndent()

    @Test fun searchUrlAndAuth() {
        assertEquals("https://api.wigle.net/api/v2/network/search?netid=aa%3Abb%3Acc%3Add%3Aee%3A0f", WiGLEClient.searchUrl("aa:bb:cc:dd:ee:f"))
        assertEquals("Basic QUlEMTIzOnNlY3JldA==", WiGLECredentials("AID123", "secret").authorizationHeader)
    }

    @Test fun picksTheExactBssid() {
        val match = WiGLEClient.bestMatch(WiGLEClient.decode(sample, 200), "aa:bb:cc:dd:ee:0f")!!
        assertEquals("Home", match.ssid)
        assertEquals(37.3318, match.trilat)
        assertEquals("1 Infinite Loop, Cupertino, CA, US", match.address)
        assertEquals("2024-05-12", match.lastUpdated)
    }

    @Test fun noExactMatchMeansNotFound() {
        assertNull(WiGLEClient.bestMatch(WiGLEClient.decode(sample, 200), "11:22:33:44:55:66"))
        assertTrue(WiGLEClient.decode("""{"success":true,"totalResults":0,"results":[]}""", 200).isEmpty())
    }

    @Test fun errors() {
        assertEquals(WiGLEError.Unauthorized, assertFailsWith<WiGLEError> { WiGLEClient.decode("", 401) })
        assertEquals(WiGLEError.RateLimited, assertFailsWith<WiGLEError> { WiGLEClient.decode("", 429) })
        assertEquals(WiGLEError.RateLimited, assertFailsWith<WiGLEError> { WiGLEClient.decode("""{"success":false,"message":"too many queries today"}""", 200) })
        assertEquals(WiGLEError.Failed("WiGLE sent a response this app couldn’t read."), assertFailsWith<WiGLEError> { WiGLEClient.decode("<html>", 200) })
    }
}

class WifiMappingTest {
    @Test fun bandsAndChannels() {
        assertEquals(WifiBand.GHZ_2_4 to 1, WifiMapping.band(2412) to WifiMapping.channel(2412))
        assertEquals(14, WifiMapping.channel(2484))
        assertEquals(WifiBand.GHZ_5 to 36, WifiMapping.band(5180) to WifiMapping.channel(5180)) // measured on the Xiaomi Pad 8
        assertEquals(165, WifiMapping.channel(5825))
        assertEquals(WifiBand.GHZ_6 to 1, WifiMapping.band(5955) to WifiMapping.channel(5955))
        assertEquals(2, WifiMapping.channel(5935))
        assertEquals(WifiBand.UNKNOWN, WifiMapping.band(60480))
    }

    @Test fun widthsAndStandards() {
        assertEquals(listOf(20, 40, 80, 160, 160, 320, 0), listOf(0, 1, 2, 3, 4, 5, -1).map(WifiMapping::channelWidthMhz))
        assertEquals("802.11ac", WifiMapping.wifiStandard(5))
        assertEquals("802.11be", WifiMapping.wifiStandard(8))
    }

    @Test fun security() {
        assertEquals(SecurityKind.PERSONAL to "WPA2/WPA3 Personal", WifiMapping.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(SecurityKind.PERSONAL to "WPA3 Personal", WifiMapping.security("[RSN-SAE-CCMP][ESS][MFPR]"))
        assertEquals(SecurityKind.PERSONAL to "WPA2 Personal", WifiMapping.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(SecurityKind.ENTERPRISE to "WPA2 Enterprise", WifiMapping.security("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(SecurityKind.ENHANCED_OPEN to "Enhanced Open", WifiMapping.security("[RSN-OWE-CCMP][ESS]"))
        assertEquals(SecurityKind.OPEN to "Open", WifiMapping.security("[ESS]"))
    }

    @Test fun redactedValuesBecomeNull() {
        assertEquals("Café ☕📶", WifiMapping.cleanSsid("\"Café ☕📶\""))
        assertNull(WifiMapping.cleanSsid("<unknown ssid>"))
        assertNull(WifiMapping.cleanSsid("\"\""))
        assertNull(WifiMapping.cleanBssid("02:00:00:00:00:00"))
        assertEquals("a6:1b:2c:3d:4e:5f", WifiMapping.cleanBssid("A6:1B:2C:3D:4E:5F"))
    }
}

class ArrivalDetectorTest {
    private fun walk(vararg rssi: Int): List<Arrival> {
        val detector = ArrivalDetector()
        return rssi.mapIndexed { i, value -> detector.add(SignalSample(i * 3_000L, value)) }
    }

    @Test fun risingSignalMeansKeepWalking() {
        assertEquals(List(5) { Arrival.WALKING }, walk(-60, -57, -55, -52, -49))
    }

    @Test fun veryStrongSignalMeansArrived() {
        assertEquals(Arrival.ARRIVED, walk(-50, -42, -36, -31, -30).last())
    }

    @Test fun clearDropAfterThePeakMeansPassed() {
        // Peak around −40, then two smoothed readings ≥ 4 dB lower.
        val states = walk(-52, -46, -41, -40, -44, -47, -49)
        assertEquals(Arrival.PASSED, states.last())
        assertEquals(Arrival.WALKING, states[4]) // one dip alone doesn't count
    }

    @Test fun oneNoisyDipDoesNotStopYou() {
        // A single −8 dB reading (normal noise) in a rising walk.
        assertFalse(Arrival.PASSED in walk(-55, -52, -50, -58, -49, -47, -45))
    }

    @Test fun oneBigNoisyDipDoesNotStopYou() {
        // A single −10 dB reading appears in two overlapping 2-reading averages; it must not count as two drops.
        assertFalse(Arrival.PASSED in walk(-50, -46, -45, -55, -45, -44), "stopped on one noisy reading")
    }

    @Test fun noImprovementForFiveReadingsIsAPlateau() {
        assertEquals(Arrival.PLATEAU, walk(-50, -46, -45, -46, -45, -46, -45, -46).last())
    }

    @Test fun newBestAfterAPlateauResumesWalking() {
        assertEquals(Arrival.WALKING, walk(-50, -46, -45, -46, -45, -46, -45, -46, -41, -39).last())
    }

    @Test fun resetStartsAFreshWalk() {
        val detector = ArrivalDetector()
        listOf(-50, -40, -46, -48).forEachIndexed { i, v -> detector.add(SignalSample(i * 3_000L, v)) }
        detector.reset()
        assertEquals(Arrival.WALKING, detector.status)
        assertNull(detector.best)
    }
}

class DeadReckoningTest {
    @Test fun stepsForwardThenLeft() {
        val pdr = DeadReckoning(stepLength = 1.0)
        repeat(3) { pdr.step(0.0) }   // forward (+y)
        repeat(2) { pdr.step(90.0) }  // turned left (−x)
        assertTrue(abs(pdr.position.x - -2.0) < 1e-9 && abs(pdr.position.y - 3.0) < 1e-9, "at ${pdr.position}")
        assertEquals(6, pdr.path.size)
    }

    @Test fun headingConventionMatchesTheSweep() {
        assertEquals(0.0, MapPoint(0.0, 5.0).heading, 1e-9)      // ahead
        assertEquals(90.0, MapPoint(-5.0, 0.0).heading, 1e-9)    // left
        assertEquals(-90.0, MapPoint(5.0, 0.0).heading, 1e-9)    // right
        val (distance, turn) = RouterFinder.bearingTo(MapPoint.ORIGIN, 0.0, MapPoint(3.0, 3.0))
        assertEquals(sqrt(18.0), distance, 1e-9)
        assertEquals(-45.0, turn, 1e-9)                           // ahead-right = turn 45° right
    }
}

class RouterFinderTest {
    /** Simulated readings: log-distance law (P₁ₘ, n) + facing gain + seeded Gaussian-ish noise. */
    private fun simulate(
        router: MapPoint,
        walk: List<Pair<MapPoint, Double>>,
        noise: Double,
        exponent: Double = 3.0,
        reference: Double = -37.0,
        facingGain: Double = 3.0,
        seed: Int = 1,
    ): List<Observation> {
        val random = Random(seed)
        fun gaussian() = (1..6).sumOf { random.nextDouble(-1.0, 1.0) } / sqrt(2.0) // ≈ N(0,1)
        return walk.map { (p, facing) ->
            val v = router - p
            val d = maxOf(0.5, v.length)
            val f = cos(Math.toRadians(v.heading - facing))
            val rssi = reference - 10 * exponent * log10(d) + facingGain * f + noise * gaussian()
            Observation(p, facing, Math.round(rssi).toInt())
        }
    }

    /** An L: 8 steps forward, then turn right and 7 steps; a reading every step (≈ every 3 s when walking slowly). */
    private val lWalk: List<Pair<MapPoint, Double>> = run {
        val pdr = DeadReckoning(0.7)
        val out = mutableListOf(MapPoint.ORIGIN to 0.0)
        repeat(8) { pdr.step(0.0); out += pdr.position to 0.0 }
        repeat(7) { pdr.step(-90.0); out += pdr.position to -90.0 }
        out
    }

    @Test fun followingTheEstimateReachesTheRouter() {
        // The way it's used: a short L, then step toward the current estimate after each reading.
        // Readings: σ = 5 dB noise, true exponent 2.8 (model assumes 3), facing gain 3 dB.
        val routers = listOf(MapPoint(6.0, 9.0), MapPoint(-4.0, 7.0))
        var reached = 0
        for (router in routers) for (seed in 1..5) {
            val random = Random(seed)
            fun g() = (1..6).sumOf { random.nextDouble(-1.0, 1.0) } / sqrt(2.0)
            fun read(p: MapPoint, facing: Double): Int {
                val v = router - p
                return Math.round(-37.0 - 28 * log10(maxOf(0.5, v.length)) + 3 * cos(Math.toRadians(v.heading - facing)) + 5 * g()).toInt()
            }
            val pdr = DeadReckoning(0.7)
            val obs = mutableListOf(Observation(MapPoint.ORIGIN, 0.0, read(MapPoint.ORIGIN, 0.0)))
            repeat(4) { pdr.step(0.0); obs += Observation(pdr.position, 0.0, read(pdr.position, 0.0)) }
            repeat(4) { pdr.step(-90.0); obs += Observation(pdr.position, -90.0, read(pdr.position, -90.0)) }
            for (step in 1..40) {
                if ((pdr.position - router).length < 1.5) { reached++; break }
                val target = RouterFinder(-37.0).estimate(obs).position ?: break
                val heading = (target - pdr.position).heading
                pdr.step(if ((target - pdr.position).length < 0.4) heading + 90 else heading)
                obs += Observation(pdr.position, heading, read(pdr.position, heading))
            }
        }
        assertTrue(reached >= 7, "reached the router in only $reached/10 simulated walks")
    }

    @Test fun uncertaintyCircleIsHonest() {
        val router = MapPoint(-4.0, 7.0)
        val honest = (1..10).count { seed ->
            val estimate = RouterFinder(-37.0).estimate(simulate(router, lWalk, noise = 5.0, exponent = 2.6, seed = seed))
            (estimate.position!! - router).length <= 2 * estimate.uncertainty
        }
        assertTrue(honest >= 8, "router outside 2× the stated uncertainty in ${10 - honest}/10 walks")
    }

    @Test fun standingStillOnlyKnowsDistance() {
        val still = List(8) { MapPoint.ORIGIN to it * 45.0 }
        val estimate = RouterFinder(-37.0).estimate(simulate(MapPoint(3.0, 4.0), still, noise = 2.0))
        assertEquals(RouterFinder.Status.NEED_MOVEMENT, estimate.status)
    }

    @Test fun straightWalkAsksForATurn() {
        val straight = List(10) { MapPoint(0.0, it * 0.7) to 0.0 }
        val estimate = RouterFinder(-37.0).estimate(simulate(MapPoint(4.0, 3.0), straight, noise = 2.0))
        assertEquals(RouterFinder.Status.NEED_TURN, estimate.status)
    }

    @Test fun standingStillDoesNotUndoATurnedWalk() {
        // Xiaomi Pad 8, 18:39: readings taken while standing still flipped READY back to "turn and walk
        // another way". After a clear L, standing for 8 more readings must keep it READY.
        val router = MapPoint(-4.0, 7.0)
        val ready = (1..10).count { seed ->
            val stood = simulate(router, lWalk + List(8) { lWalk.last() }, noise = 5.0, seed = seed)
            RouterFinder(-37.0).estimate(stood).status == RouterFinder.Status.READY
        }
        assertTrue(ready >= 9, "standing still undid READY in ${10 - ready}/10 walks")
    }

    @Test fun readyMeansTheSideIsKnown() {
        // The first 5 readings of that real walk (a gentle curve). With 5 dB noise the old geometric rule
        // called this READY every time, though the best cell was on the wrong side in 22–35% of runs.
        // READY must now mostly be right about the side; otherwise it should ask for a turn.
        val walked = listOf(MapPoint(0.0, 0.0), MapPoint(1.3, -0.4), MapPoint(3.4, 0.0), MapPoint(4.8, 2.6), MapPoint(6.1, 3.2))
        fun side(p: MapPoint) = 6.5 * p.y - 2.7 * p.x // relative to the walk's overall line
        var ready = 0
        var right = 0
        for (router in listOf(MapPoint(6.0, 1.0), MapPoint(3.0, 5.0), MapPoint(2.0, -3.0), MapPoint(9.0, 6.0))) {
            for (seed in 1..40) {
                val e = RouterFinder(-37.0).estimate(simulate(router, walked.map { it to 0.0 }, noise = 5.0, exponent = 2.8, seed = seed))
                if (e.status == RouterFinder.Status.READY) {
                    ready++
                    if (side(e.position!!) * side(router) > 0) right++
                }
            }
        }
        assertTrue(ready >= 10, "never READY ($ready/160): too timid to be useful")
        assertTrue(right >= 0.8 * ready, "READY but wrong side in ${ready - right}/$ready runs")
    }

    @Test fun needsAFewReadingsFirst() {
        assertEquals(RouterFinder.Status.NEED_READINGS, RouterFinder(-37.0).estimate(emptyList()).status)
    }
}

class StepCounterTest {
    /** [seconds] of up-acceleration at 50 Hz: gravity + [shape] + Gaussian noise. */
    private fun count(seconds: Double, noise: Double, seed: Int = 3, shape: (Double) -> Double): Int {
        val random = Random(seed)
        val counter = StepCounter()
        var steps = 0
        var t = 0.0
        while (t < seconds) {
            steps += counter.add(9.81 + shape(t) + noise * random.nextGaussian(), t)
            t += 0.02
        }
        return steps
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble(1e-12, 1.0)
        return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * nextDouble())
    }

    @Test fun normalWalkCountsEveryStep() {
        // 1.8 steps/s for 10 s = 18 steps.
        val steps = count(10.0, noise = 0.25) { 1.5 * sin(2 * PI * 1.8 * it) }
        assertTrue(steps in 17..19, "counted $steps")
    }

    @Test fun slowGentleWalkStillCounts() {
        // Slow indoor walk holding a tablet: 1.3 steps/s, small bounce.
        val steps = count(10.0, noise = 0.2) { 0.9 * sin(2 * PI * 1.3 * it) }
        assertTrue(steps in 12..14, "counted $steps")
    }

    @Test fun standingStillCountsNothing() {
        val steps = count(30.0, noise = 0.15) { 0.1 * sin(2 * PI * 0.3 * it) }
        assertEquals(0, steps)
    }

    @Test fun oneHandMovementIsNotAStep() {
        // A single lift-and-settle of the tablet, then stillness.
        val steps = count(6.0, noise = 0.05) { t -> if (t in 2.0..2.5) 2.0 * sin(2 * PI * 2 * (t - 2)) else 0.0 }
        assertEquals(0, steps)
    }

    @Test fun doubleHumpedStepCountsOnce() {
        // Heel strike then toe-off: two bounces 0.18 s apart in every 0.6 s step. 12 s = 20 steps.
        fun pulse(phase: Double, center: Double, amp: Double) = amp * kotlin.math.exp(-((phase - center) / 0.05).let { it * it })
        val steps = count(12.0, noise = 0.1) { t ->
            val p = t % 0.6
            pulse(p, 0.10, 2.5) - pulse(p, 0.19, 2.0) + pulse(p, 0.28, 2.5) - pulse(p, 0.37, 2.0)
        }
        assertTrue(steps in 19..21, "counted $steps")
    }

    @Test fun firstStepIsReportedOnceConfirmed() {
        val counter = StepCounter(rise = 0.5, fall = 0.5)
        val reports = mutableListOf<Int>()
        var t = 0.0
        while (t < 3.0) {
            val n = counter.add(9.81 + 1.5 * sin(2 * PI * 1.5 * t), t)
            if (n > 0) reports.add(n)
            t += 0.02
        }
        assertEquals(2, reports.first(), "first report should include the held step")
        assertTrue(reports.drop(1).all { it == 1 })
    }
}

class RouterSearchTest {
    private val bench = SearchBenchmark()

    @Test fun findsTheRouterInSimulatedWalks() {
        // Closed loop, one reading every 3 s, world unlike the model (see SearchBenchmark). 150 runs of the
        // benchmark gave 92% right / 1% wrong; this smaller fixed set must stay well above the old 70%.
        val outcomes = (1..30).toList().parallelStream()
            .map { bench.run(SearchBenchmark.Search(SearchBenchmark.Steer.CLOSER_INFORMED, 1.0, lambda = 6.0), it, 3.0) }
            .toList()
        val right = outcomes.count { it.stopTime != null && it.distanceAtStop!! <= 2.5 }
        val wrong = outcomes.count { it.stopTime != null && it.distanceAtStop!! > 2.5 }
        assertTrue(right >= 25, "stopped near the router in only $right/30")
        assertTrue(wrong <= 1, "stopped far from the router in $wrong/30")
    }

    @Test fun oneWildReadingDoesNotMoveTheRouter() {
        // Readings around a router at (3, 4); then one 25 dB dropout right next to it.
        val router = MapPoint(3.0, 4.0)
        val walk = listOf(MapPoint(0.0, 0.0), MapPoint(0.0, 2.0), MapPoint(1.5, 3.0), MapPoint(3.0, 2.0), MapPoint(4.5, 3.0), MapPoint(4.0, 5.5), MapPoint(2.0, 5.5))
        fun rssi(p: MapPoint) = Math.round(-37 - 28 * log10(sqrt((router - p).length * (router - p).length + 1))).toInt()
        // Facing the router at every reading, so no body shadow is involved.
        val clean = walk.mapIndexed { i, p -> Observation(p, (router - p).heading, rssi(p), i * 2.0) }
        val spoiled = clean + Observation(MapPoint(3.0, 3.0), 0.0, rssi(MapPoint(3.0, 3.0)) - 25, 14.0)
        val search = RouterSearch(-36.4)
        val before = search.belief(clean, 14.0).mostLikely
        val after = search.belief(spoiled, 14.0).mostLikely
        assertTrue((before - router).length <= 1.0, "clean estimate $before")
        assertTrue((after - router).length <= 1.5, "one dropout moved the estimate to $after")
    }

    @Test fun stopsOnlyWhenConfident() {
        // Three readings, all fairly weak: far from sure the router is within 2 m.
        val obs = listOf(Observation(MapPoint(0.0, 0.0), 0.0, -60), Observation(MapPoint(0.0, 1.0), 0.0, -61), Observation(MapPoint(1.0, 1.0), 0.0, -59))
        val belief = RouterSearch(-36.4).belief(obs, 2.0)
        assertTrue(belief.within(MapPoint(1.0, 1.0), 2.0) < 0.2)
    }
}

class PollRateTest {
    /** Android refreshes every [period] ms; the app looks every [look] ms; the value changes on [changeChance] of refreshes. */
    private fun learn(period: Long, look: Long, changeChance: Double, seconds: Int = 40): Long {
        val random = Random(5)
        val rate = PollRate()
        var value = -50
        var shown = value
        var t = 0L
        var nextRefresh = period
        while (t < seconds * 1000L) {
            if (t >= nextRefresh) {
                if (random.nextDouble() < changeChance) value += if (random.nextBoolean()) 1 else -1
                nextRefresh += period
            }
            if (value != shown) { shown = value; rate.changed(t) }
            t += look
        }
        return rate.interval
    }

    @Test fun androidDefaultIsThreeSeconds() = assertEquals(3_000, learn(period = 3_000, look = 250, changeChance = 0.7))

    @Test fun learnsAFasterRefresh() {
        val interval = learn(period = 1_000, look = 250, changeChance = 0.6)
        assertTrue(interval in 750..1_000, "learned $interval ms")
    }

    @Test fun learnsItEvenWhileMostlyStill() {
        // Standing still the value changes on few refreshes, so most gaps span several refreshes.
        val interval = learn(period = 1_000, look = 250, changeChance = 0.3, seconds = 90)
        assertTrue(interval in 750..1_000, "learned $interval ms")
    }

    @Test fun assumesTheDefaultUntilItHasSeenEnough() = assertEquals(3_000, PollRate().also { it.changed(0); it.changed(900) }.interval)
}
