package io.github.dockside.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.dockside.R
import io.github.dockside.data.PostureTracker
import io.github.dockside.data.SettingsStore
import io.github.dockside.data.currentBattery
import io.github.dockside.settings.MainActivity
import io.github.dockside.standby.StandbyActivity

/**
 * Foreground service that watches for the activation conditions:
 * charging + lying on its side (landscape) + stationary + locked.
 * When they are met the Dockside screen is launched over the lock screen.
 */
class MonitorService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val posture = PostureTracker()
    private var sensorRegistered = false
    private var charging = false

    /** Disarmed after the user leaves Dockside; re-armed by a posture change, re-plug or screen off. */
    private var armed = true
    private var lastPosture = PostureTracker.Posture.UNKNOWN
    private var lastEval = 0L
    private var lastBlocker: String? = ""
    private var samples = 0
    private var lastSample = FloatArray(3)
    private var sensorSilent = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = true; armed = true; updateSensor()
                    // plugging in a phone that is already locked starts right away
                    handler.postDelayed({ evaluate() }, 600)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    charging = false; updateSensor()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    armed = true
                    // Locking the phone while it sits sideways on a charger enters Dockside right away.
                    handler.postDelayed({ evaluate(fromScreenOff = true) }, 350)
                }
                Intent.ACTION_SCREEN_ON -> evaluate()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_MONITOR, monitorNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        charging = currentBattery(this).charging
        updateSensor()
        // keep accessory battery reports while the service runs, for the Batteries widget
        io.github.dockside.data.BluetoothBatteries.listen(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISARM) armed = false
        if (!SettingsStore.get(this).current.enabled) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (sensorRegistered) getSystemService(SensorManager::class.java)?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun monitorNotification() = NotificationCompat.Builder(this, CHANNEL_MONITOR)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.monitor_title))
        .setContentText(getString(if (sensorSilent) R.string.sensor_silent else R.string.monitor_text))
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(getString(if (sensorSilent) R.string.sensor_silent else R.string.monitor_text))
        )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()

    private fun showSensorWarning(silent: Boolean) {
        if (sensorSilent == silent) return
        sensorSilent = silent
        Log.w(TAG, if (silent) "accelerometer is silent" else "accelerometer is back")
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_MONITOR, monitorNotification())
    }

    private fun updateSensor() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (charging && !sensorRegistered) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorRegistered = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "accelerometer registered=$sensorRegistered")
                // Some phones' sensor hubs stop reporting until a reboot; tell the user instead of failing silently.
                val before = samples
                handler.postDelayed({ if (sensorRegistered && samples == before) showSensorWarning(true) }, 8000)
            }
        } else if (!charging && sensorRegistered) {
            sm.unregisterListener(this)
            sensorRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (samples++ == 0) Log.i(TAG, "first accelerometer sample")
        if (sensorSilent) showSensorWarning(false)
        lastSample = event.values.copyOf()
        posture.onSample(event.values)
        if (posture.posture != lastPosture) {
            if (posture.posture != PostureTracker.Posture.LANDSCAPE) armed = true
            lastPosture = posture.posture
        }
        val now = System.currentTimeMillis()
        if (now - lastEval > 500) {
            lastEval = now
            evaluate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun evaluate(fromScreenOff: Boolean = false) {
        val settings = SettingsStore.get(this).current
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val locked = keyguard?.isKeyguardLocked == true || power?.isInteractive == false || fromScreenOff
        val blocker = when {
            !settings.enabled -> "disabled"
            !charging -> "not charging"
            !armed -> "disarmed"
            StandbyActivity.isShowing -> "already showing"
            !settings.ignorePosture && posture.posture != PostureTracker.Posture.LANDSCAPE -> "posture ${posture.posture}"
            !settings.ignorePosture && !fromScreenOff && !posture.isStill() -> "moving"
            settings.requireLocked && !locked -> "unlocked"
            else -> null
        }
        if (blocker != lastBlocker || fromScreenOff) {
            Log.i(
                TAG, "evaluate(screenOff=$fromScreenOff): ${blocker ?: "launch"}; samples=$samples " +
                    "g=${lastSample.joinToString { "%.1f".format(it) }}"
            )
            lastBlocker = blocker
        }
        if (blocker != null) return
        armed = false
        launchStandby()
    }

    private fun launchStandby() {
        val intent = Intent(this, StandbyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        // With "display over other apps" granted we may start the activity directly.
        if (Settings.canDrawOverlays(this)) {
            runCatching { startActivity(intent) }
                .onSuccess { Log.i(TAG, "launched directly"); return }
                .onFailure { Log.w(TAG, "direct launch failed", it) }
        }
        Log.i(TAG, "launching through a full-screen intent")
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_STANDBY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.standby_ready))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_LAUNCH, n)
    }

    companion object {
        private const val TAG = "Dockside"
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_STANDBY = "standby"
        const val NOTIFICATION_MONITOR = 1
        const val NOTIFICATION_LAUNCH = 2
        private const val ACTION_DISARM = "io.github.dockside.DISARM"

        fun createChannels(context: Context) {
            if (android.os.Build.VERSION.SDK_INT < 26) return // no channels before Android 8
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STANDBY, context.getString(R.string.channel_standby), NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        fun start(context: Context) {
            if (!SettingsStore.get(context).current.enabled) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        /** Called when the user leaves Dockside on purpose, so it is not re-entered immediately. */
        fun disarm(context: Context) {
            if (!SettingsStore.get(context).current.enabled) return
            runCatching { context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_DISARM)) }
        }

        fun canUseFullScreenIntent(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 34)
                context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
            else true
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        MonitorService.start(context)
    }
}
