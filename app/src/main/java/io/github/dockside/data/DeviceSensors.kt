package io.github.dockside.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sqrt

data class BatteryInfo(val level: Int, val charging: Boolean, val plugType: String?)

fun parseBattery(intent: Intent?): BatteryInfo {
    if (intent == null) return BatteryInfo(0, false, null)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugType = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        8 /* BATTERY_PLUGGED_DOCK, API 33 */ -> "dock"
        else -> null
    }
    val charging = plugType != null || status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0
    return BatteryInfo(level * 100 / scale, charging, plugType)
}

fun currentBattery(context: Context): BatteryInfo =
    parseBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

fun batteryFlow(activityContext: Context): Flow<BatteryInfo> = callbackFlow {
    // The application context outlives the Activity, so unregistering after onDestroy is safe.
    val context = activityContext.applicationContext
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            trySend(parseBattery(intent))
        }
    }
    val sticky = ContextCompat.registerReceiver(
        context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
    )
    trySend(parseBattery(sticky))
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/** Ambient light in lux; emits nothing on devices without a light sensor. */
fun ambientLightFlow(context: Context): Flow<Float> = callbackFlow {
    val sm = context.getSystemService(SensorManager::class.java)
    val sensor = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    if (sensor == null) {
        awaitClose { }
        return@callbackFlow
    }
    val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            trySend(e.values[0])
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }
    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    awaitClose { sm.unregisterListener(listener) }
}

/** Raw gravity-ish acceleration samples (x, y, z) in m/s². */
fun accelerometerFlow(context: Context, delay: Int = SensorManager.SENSOR_DELAY_NORMAL): Flow<FloatArray> = callbackFlow {
    val sm = context.getSystemService(SensorManager::class.java)
    val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (sensor == null) {
        awaitClose { }
        return@callbackFlow
    }
    val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            trySend(e.values.copyOf())
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }
    sm.registerListener(listener, sensor, delay)
    awaitClose { sm.unregisterListener(listener) }
}

/**
 * Tracks whether the device lies on its long edge (landscape), and whether it has been
 * still for a while. Mirrors the "on its side and stationary" rule.
 */
class PostureTracker(private val stillWindowMs: Long = 1500) {
    enum class Posture { LANDSCAPE, PORTRAIT, FLAT, UNKNOWN }

    var posture = Posture.UNKNOWN
        private set

    /** In landscape: true when the device's right edge points up (display rotated 90°). */
    var rightEdgeUp = true
        private set
    private var lastMovement = 0L
    private var lastMagnitude = SensorManager.GRAVITY_EARTH

    /** Returns true if this sample counted as movement (used for "motion to wake"). */
    fun onSample(v: FloatArray, now: Long = System.currentTimeMillis()): Boolean {
        val (x, y, z) = Triple(v[0], v[1], v[2])
        val mag = sqrt(x * x + y * y + z * z)
        val moved = abs(mag - lastMagnitude) > 0.6f || abs(mag - SensorManager.GRAVITY_EARTH) > 1.8f
        lastMagnitude = mag
        if (moved) lastMovement = now
        // Any stand angle counts as "on its side" as long as the long edge is clearly lower
        // than the short one; only an almost flat phone is excluded.
        posture = when {
            abs(z) > 8.8f -> Posture.FLAT
            abs(x) > 3.5f && abs(x) > abs(y) * 1.3f -> Posture.LANDSCAPE
            abs(y) > 3.5f && abs(y) > abs(x) -> Posture.PORTRAIT
            else -> Posture.UNKNOWN
        }
        if (posture == Posture.LANDSCAPE) rightEdgeUp = x > 0
        return moved
    }

    fun isStill(now: Long = System.currentTimeMillis()) = now - lastMovement > stillWindowMs
}
