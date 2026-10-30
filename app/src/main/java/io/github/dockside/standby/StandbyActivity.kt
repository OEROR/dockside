package io.github.dockside.standby

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.dockside.service.MonitorService

/** Full-screen Dockside shown over the lock screen. */
class StandbyActivity : ComponentActivity(), StandbyHost {
    override val exitOnUndock get() = !intent.getBooleanExtra(EXTRA_PREVIEW, false)
    override val showcase get() = intent.getBooleanExtra(EXTRA_SHOWCASE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        getSystemService(NotificationManager::class.java)?.cancel(MonitorService.NOTIFICATION_LAUNCH)
        onBackPressedDispatcher.addCallback(this) { exit(true) }
        setContent { StandbyRoot(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        lastSeenAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        lastSeenAt = System.currentTimeMillis()
        // Only light the screen up on the launch itself, not when coming back from screen-off.
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(false)
        else @Suppress("DEPRECATION") window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    /** Home or Recents: the user left Dockside on purpose. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        exit(true)
    }

    override fun onDestroy() {
        isShowing = false
        super.onDestroy()
    }

    override fun requestUnlock(onUnlocked: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            onUnlocked(); return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = onUnlocked()
            })
            return
        }
        // Android 7: ask the window to dismiss the keyguard (the PIN pad shows if there is one),
        // then carry on once it is gone; give up after a minute.
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        val handler = android.os.Handler(mainLooper)
        val started = System.currentTimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                if (!km.isKeyguardLocked) {
                    @Suppress("DEPRECATION")
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                    onUnlocked()
                } else if (System.currentTimeMillis() - started < 60_000) handler.postDelayed(this, 300)
            }
        })
    }

    override fun setAwake(awake: Boolean) {
        val attrs = window.attributes
        if (awake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            // Let the system timeout switch the panel off; until then keep it as dark as possible.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attrs.screenBrightness = 0.01f
        }
        window.attributes = attrs
    }

    override fun open(intent: Intent) = requestUnlock {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    override fun open(pendingIntent: PendingIntent) = requestUnlock {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pendingIntent.send(this, 0, null, null, null, null, opts.toBundle())
            } else pendingIntent.send()
        }
        finish()
    }

    override fun exit(userInitiated: Boolean) {
        if (userInitiated) MonitorService.disarm(this)
        finish()
    }

    override fun finish() {
        super.finish()
        // fade out instead of the default slide, whichever way Dockside is closed
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        /** Opened from settings: stay open even when not charging or held upright. */
        const val EXTRA_PREVIEW = "preview"

        /** With [EXTRA_PREVIEW]: filming mode, Dockside plays through its highlights by itself. */
        const val EXTRA_SHOWCASE = "showcase"

        /** When Dockside was last on screen (here or as the screen saver), for the alarm takeover. */
        @Volatile
        var lastSeenAt = 0L

        @Volatile
        var isShowing = false
            private set
    }
}
