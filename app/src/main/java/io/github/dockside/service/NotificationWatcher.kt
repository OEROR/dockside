package io.github.dockside.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.dockside.standby.StandbyActivity

data class IncomingNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val title: String,
    val text: String,
    val color: Int,
    val postedAt: Long,
    val contentIntent: PendingIntent?,
)

data class MediaInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val canSeek: Boolean = true,
) {
    fun positionNow(now: Long = android.os.SystemClock.elapsedRealtime()): Long {
        if (!playing) return positionMs
        val p = positionMs + ((now - positionAt) * speed).toLong()
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }
}

/** Live data published by the notification listener: notifications and the active media session. */
object LiveEvents {
    private val _notifications = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 8)
    val notifications: SharedFlow<IncomingNotification> = _notifications.asSharedFlow()

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    internal var controller: MediaController? = null

    internal fun post(n: IncomingNotification) {
        _notifications.tryEmit(n)
    }

    internal fun publishMedia(info: MediaInfo?) {
        _media.value = info
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    /** Jump to [positionMs] in the current track (dragging the progress bar). */
    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }
    fun previous() = controller?.transportControls?.skipToPrevious()

    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NotificationWatcher::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

class NotificationWatcher : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindControllers(list.orEmpty())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = ClockEvents.onRemoved(sbn)

    /**
     * An alarm's own screen opens on top of Dockside. If Dockside was just showing (so the phone
     * is on its charger) and the user wants it, come back on top so the alarm is shown Dockside's
     * way; the clock app keeps ringing until one of its buttons is pressed. Starting from the
     * background needs the "display over other apps" permission.
     */
    private fun bringDocksideBack() {
        val settings = io.github.dockside.data.SettingsStore.get(this).current
        if (!settings.alarmTakeover || !Settings.canDrawOverlays(this)) return
        val seen = StandbyActivity.lastSeenAt
        if (!StandbyActivity.isShowing && System.currentTimeMillis() - seen > 2 * 60_000) return
        handler.postDelayed({
            runCatching {
                startActivity(
                    android.content.Intent(this, StandbyActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, 800)
    }

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { if (it.packageName != packageName) ClockEvents.onPosted(it) } }
        val sm = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = sm
        val me = ComponentName(this, NotificationWatcher::class.java)
        runCatching {
            sm.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            bindControllers(sm.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        unbindAll()
        LiveEvents.publishMedia(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val n = sbn.notification
        if (sbn.packageName != packageName && ClockEvents.onPosted(sbn)) {
            if (ClockEvents.ringing.value?.key == sbn.key) bringDocksideBack()
            return
        }
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_SERVICE) return
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val pm = packageManager
        val appInfo = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
        LiveEvents.post(
            IncomingNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: sbn.packageName,
                appIcon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                title = title,
                text = text,
                color = n.color,
                postedAt = sbn.postTime,
                contentIntent = n.contentIntent,
            )
        )
    }

    private fun bindControllers(list: List<MediaController>) {
        unbindAll()
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
                override fun onSessionDestroyed() = refreshMedia()
            }
            c.registerCallback(cb, handler)
            callbacks[c] = cb
        }
        refreshMedia()
    }

    private fun unbindAll() {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
    }

    private fun refreshMedia() {
        val controllers = callbacks.keys
        val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        LiveEvents.controller = active
        if (active == null) {
            LiveEvents.publishMedia(null); return
        }
        val md = active.metadata
        val st = active.playbackState
        val pm = packageManager
        val appName = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(active.packageName, 0)).toString()
        }.getOrDefault(active.packageName)
        LiveEvents.publishMedia(
            MediaInfo(
                packageName = active.packageName,
                appName = appName,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                playing = st?.state == PlaybackState.STATE_PLAYING,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                positionMs = st?.position ?: 0L,
                positionAt = st?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(),
                speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                // most players advertise seeking; if the state doesn't say, let the user try
                canSeek = st == null || st.actions == 0L || st.actions and PlaybackState.ACTION_SEEK_TO != 0L,
            )
        )
    }
}
