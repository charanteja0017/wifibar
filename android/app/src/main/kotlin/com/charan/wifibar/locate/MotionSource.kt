package com.charan.wifibar.locate

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.charan.wifibar.core.HeadingTracker
import com.charan.wifibar.core.StepCounter
import com.charan.wifibar.core.Vec3
import kotlin.math.PI

/**
 * Feeds the gyroscope and gravity sensors into a [HeadingTracker] (turning left = positive),
 * plus the rotation-vector compass for an approximate absolute bearing.
 * Readings arrive ~100 Hz on the main thread; [yaw] and [azimuth] are read when needed, not observed.
 */
class MotionSource(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepDetector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    /**
     * Called on each step while walking (Walk to find). `fromAccelerometer` is true for steps counted by
     * [StepCounter], which move you on the map; false for Android's step detector, kept for comparison.
     */
    var onStep: ((fromAccelerometer: Boolean) -> Unit)? = null

    /** Each accelerometer sample while walking: (sensor time ns, up-acceleration m/s², filtered). */
    var onAcceleration: ((timeNanos: Long, up: Double, filtered: Double) -> Unit)? = null
    val hasStepDetector: Boolean get() = stepDetector != null
    private val steps = StepCounter()
    private var up: Vec3? = null

    val tracker = HeadingTracker()
    val isAvailable: Boolean get() = gyroscope != null && gravity != null

    /** Degrees clockwise from magnetic north; indoors this can be off by tens of degrees. */
    var azimuth: Double? = null
        private set

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var running = false

    private var walking = false

    /** [walking]: also count steps (accelerometer, plus Android's step detector when permitted). */
    fun start(walking: Boolean = false): Boolean {
        if (!isAvailable) return false
        if (!running) {
            sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
            sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
            rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            running = true
        }
        if (walking && !this.walking) {
            steps.reset()
            accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            stepDetector?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            this.walking = true
        }
        return true
    }

    fun stop() {
        if (running) sensors.unregisterListener(this)
        running = false
        walking = false
    }

    val yaw: Double get() = tracker.yaw

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            // rad/s → deg/s; Android's gyroscope already removes most of its resting offset.
            Sensor.TYPE_GYROSCOPE -> tracker.addRotation(
                Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()) * (180 / PI),
                event.timestamp / 1e9,
            )
            // Android gravity reads +9.81 on z lying flat: it already points up.
            Sensor.TYPE_GRAVITY -> {
                val g = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                tracker.addUp(g)
                if (g.length > 1) up = g / g.length
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val unitUp = up ?: return
                val along = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()) dot unitUp
                val count = steps.add(along, event.timestamp / 1e9)
                onAcceleration?.invoke(event.timestamp, along, steps.filtered)
                repeat(count) { onStep?.invoke(true) }
            }
            Sensor.TYPE_STEP_DETECTOR -> onStep?.invoke(false)
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, v)
                SensorManager.getOrientation(rotationMatrix, orientation)
                azimuth = (Math.toDegrees(orientation[0].toDouble()) + 360) % 360
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
