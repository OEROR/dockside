package io.github.dockside.standby

import io.github.dockside.ui.common.easyFling
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.dockside.ui.common.driftOffset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import kotlin.math.sin
import kotlin.math.PI
import io.github.dockside.ui.common.rememberTicker
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.AudioLevels
import io.github.dockside.data.DisplayOff
import io.github.dockside.data.NightMode
import io.github.dockside.data.PostureTracker
import io.github.dockside.data.SettingsStore
import io.github.dockside.data.Views
import io.github.dockside.data.accelerometerFlow
import io.github.dockside.data.ambientLightFlow
import io.github.dockside.service.IncomingNotification
import io.github.dockside.service.LiveEvents
import io.github.dockside.service.MediaInfo
import io.github.dockside.ui.clocks.ClockView
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.LocalLive
import io.github.dockside.ui.common.NightFilter
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.toLocal
import io.github.dockside.ui.photos.PhotosView
import io.github.dockside.ui.widgets.MediaControls
import io.github.dockside.ui.widgets.SeekBar
import io.github.dockside.ui.widgets.StackSide
import io.github.dockside.ui.widgets.WidgetsView
import kotlinx.coroutines.delay
import kotlin.math.abs

/** What the hosting Activity or DreamService provides to the Dockside UI. */
interface StandbyHost {
    /** Leave automatically when unplugged or turned upright (off for previews and the screen saver). */
    val exitOnUndock: Boolean

    /** Leave when the phone is held upright: Dockside is a landscape-only mode. */
    val exitWhenUpright: Boolean get() = exitOnUndock

    /** Runs [onUnlocked] once the device is unlocked (biometrics / PIN when needed). */
    fun requestUnlock(onUnlocked: () -> Unit)

    /** Asleep = display may turn off / go to minimum brightness. */
    fun setAwake(awake: Boolean)

    fun open(intent: Intent)
    fun open(pendingIntent: PendingIntent)
    fun exit(userInitiated: Boolean)
}

private const val NIGHT_LUX = 3f
private const val DAY_LUX = 12f
private const val DARK_ROOM_LUX = 1.5f

@Composable
fun StandbyRoot(host: StandbyHost) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val settings by store.settings.collectAsState()
    val data = rememberStandbyData(settings)
    val update: ((AppSettings) -> AppSettings) -> Unit = { store.update(it) }

    // --- views, remembered per charger (location memory by plug type)
    val plug = data.battery.plugType ?: "none"
    val pager = rememberPagerState(initialPage = remember { settings.chargerViews[plug] ?: Views.WIDGETS }) { Views.COUNT }
    LaunchedEffect(pager.settledPage) {
        delay(1200) // after the swipe has settled, so saving never interrupts an animation
        store.update { it.copy(chargerViews = it.chargerViews + (plug to pager.settledPage)) }
    }

    var editWidgets by remember { mutableStateOf<StackSide?>(null) }
    var editClock by remember { mutableStateOf(false) }
    var editPhotos by remember { mutableStateOf(false) }
    val editing = editWidgets != null || editClock || editPhotos

    // --- activity, light and motion
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastMotion by remember { mutableLongStateOf(0L) }
    var lux by remember { mutableStateOf<Float?>(null) }
    var night by remember { mutableStateOf(false) }
    var sleeping by remember { mutableStateOf(false) }
    var portraitSince by remember { mutableLongStateOf(0L) }
    var rightEdgeUp by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { ambientLightFlow(context).collect { lux = it } }
    LaunchedEffect(Unit) {
        val posture = PostureTracker()
        accelerometerFlow(context).collect { v ->
            val now = SystemClock.elapsedRealtime()
            if (posture.onSample(v)) lastMotion = now
            rightEdgeUp = posture.rightEdgeUp
            portraitSince = if (posture.posture == PostureTracker.Posture.PORTRAIT) {
                if (portraitSince == 0L) now else portraitSince
            } else 0L
        }
    }
    // Night Mode. With the light sensor it follows the room (with hysteresis, so it doesn't
    // flicker); without a reading it stays in colour. The schedule is the user's explicit choice.
    LaunchedEffect(lux, settings.nightMode) {
        when (settings.nightMode) {
            NightMode.OFF -> night = false
            NightMode.SCHEDULE -> while (true) {
                val h = System.currentTimeMillis().toLocal().hour
                night = h >= 23 || h < 6
                delay(30_000)
            }
            NightMode.LIGHT_SENSOR -> {
                val l = lux
                if (l == null) night = false
                else if (!night && l < NIGHT_LUX) {
                    delay(4000); night = true
                } else if (night && l > DAY_LUX) {
                    delay(1500); night = false
                }
            }
        }
    }
    // Display off policy.
    LaunchedEffect(settings.displayOff, settings.motionToWake) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val lastActive = maxOf(lastTouch, if (settings.motionToWake) lastMotion else 0L)
            val idle = now - lastActive
            // only a real light reading counts: without a working sensor the screen stays on
            // rather than going dark on a guess from the clock
            val dark = lux?.let { it < DARK_ROOM_LUX } ?: false
            sleeping = when (settings.displayOff) {
                DisplayOff.NEVER -> false
                DisplayOff.AFTER_20_SECONDS -> idle > 20_000
                DisplayOff.AUTOMATIC -> dark && idle > 30_000
            }
            delay(500)
        }
    }
    LaunchedEffect(sleeping) { host.setAwake(!sleeping) }
    // Coming back from screen-off (power button, notification) counts as activity.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        lastTouch = SystemClock.elapsedRealtime(); sleeping = false
    }

    // --- exit conditions: unplugged or turned upright
    LaunchedEffect(data.battery.charging) {
        if (!data.battery.charging && host.exitOnUndock) {
            delay(1500); host.exit(false)
        }
    }
    LaunchedEffect(portraitSince != 0L) {
        if (portraitSince != 0L && host.exitWhenUpright && !settings.ignorePosture) {
            delay(1200); host.exit(false)
        }
    }

    // --- full-screen notifications
    var banner by remember { mutableStateOf<IncomingNotification?>(null) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.showNotifications) {
        if (settings.showNotifications) LiveEvents.notifications.collect {
            banner = it; revealed = !settings.previewOnTap
            lastTouch = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(banner, revealed) {
        if (banner != null) {
            delay(if (revealed) 12_000 else 8_000); banner = null
        }
    }

    // --- Now Playing goes full screen when playback starts
    var showPlayer by remember { mutableStateOf(false) }
    // Closing the player collapses that app's music into the bubble until it stops playing;
    // the next track no longer pops the full player back up.
    var dismissedApp by remember { mutableStateOf<String?>(null) }
    val media = data.media
    LaunchedEffect(media?.playing, media?.title) {
        when {
            media == null -> {
                showPlayer = false; dismissedApp = null
            }
            media.playing && media.packageName != dismissedApp -> {
                showPlayer = true; lastTouch = SystemClock.elapsedRealtime()
            }
        }
    }

    val nightFraction by animateFloatAsState(if (night) 1f else 0f, tween(1800), label = "night")
    NightFilter(nightFraction)
    val sleepAlpha by animateFloatAsState(if (sleeping) 1f else 0f, tween(if (sleeping) 1200 else 350), label = "sleep")

    ForceLandscape(clockwise = rightEdgeUp) { Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pager,
            flingBehavior = easyFling(pager),
            userScrollEnabled = !editing && !showPlayer,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val live = page == pager.currentPage || (pager.isScrollInProgress && abs(page - pager.currentPage) <= 1)
            val shiftClock = rememberTicker(3 * 60_000)
            CompositionLocalProvider(LocalLive provides live) {
                Box(
                    Modifier.fillMaxSize().clipToBounds().graphicsLayer {
                        // burn-in protection: everything moves by 2-3 px every few minutes,
                        // too little and too seldom to notice
                        if (settings.burnInGuard && !editing) {
                            val d = driftOffset(shiftClock.value * 7, 3.dp.toPx(), 3.dp.toPx())
                            translationX = d.x; translationY = d.y
                        }
                    }
                ) {
                    when (page) {
                        Views.WIDGETS -> WidgetsView(
                            settings, data, editWidgets,
                            onEdit = { side -> if (side == null) editWidgets = null else host.requestUnlock { editWidgets = side } },
                            onUpdate = update, onOpen = host::open,
                        )
                        Views.PHOTOS -> PhotosView(
                            settings, data.albums, data.h24, data.battery, editPhotos,
                            onEdit = { on -> if (!on) editPhotos = false else host.requestUnlock { editPhotos = true } },
                            onUpdate = update,
                        )
                        else -> ClockView(
                            settings, data.clockInfo(settings, context), editClock,
                            onEdit = { on -> if (!on) editClock = false else host.requestUnlock { editClock = true } },
                            onUpdate = update,
                        )
                    }
                }
            }
        }

        // After the player is closed, a small circle at the top keeps the music one tap away.
        AnimatedVisibility(
            visible = media != null && !showPlayer && !editing,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.3f) + fadeIn(),
            exit = scaleOut(targetScale = 0.3f) + fadeOut(),
        ) {
            media?.let { m ->
                NowPlayingBubble(m) {
                    showPlayer = true; dismissedApp = null; lastTouch = SystemClock.elapsedRealtime()
                }
            }
        }

        AnimatedVisibility(showPlayer && media != null, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f)) {
            if (media != null) NowPlayingFull(media) {
                showPlayer = false; dismissedApp = media.packageName
            }
        }

        AnimatedVisibility(
            visible = banner != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            banner?.let { n ->
                NotificationCard(n, revealed) {
                    if (!revealed) revealed = true
                    else {
                        n.contentIntent?.let(host::open); banner = null
                    }
                }
            }
        }

        if (sleepAlpha > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = sleepAlpha }.background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { lastTouch = SystemClock.elapsedRealtime(); sleeping = false }
                    }
            )
        }
    } }
}

/**
 * Lays [content] out in landscape even when the window is portrait (the screen saver runs in
 * the lock screen's orientation), rotating it so it reads upright on a phone lying on its side.
 */
@Composable
private fun ForceLandscape(clockwise: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize().clipToBounds()) { measurables, c ->
        val w = c.maxWidth
        val h = c.maxHeight
        if (h <= w) {
            val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
            layout(w, h) { placeables.forEach { it.place(0, 0) } }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixed(h, w)) }
            layout(w, h) {
                placeables.forEach {
                    it.placeWithLayer((w - h) / 2, (h - w) / 2) { rotationZ = if (clockwise) 90f else -90f }
                }
            }
        }
    }
}

@Composable
internal fun NotificationCard(n: IncomingNotification, revealed: Boolean, onTap: () -> Unit) {
    val icon = remember(n.key) { n.appIcon?.let { runCatching { it.toBitmap(128, 128).asImageBitmap() }.getOrNull() } }
    Row(
        Modifier.padding(top = 18.dp).fillMaxWidth(0.72f).clip(RoundedCornerShape(30.dp)).background(Color(0xF21C1C1E))
            .clickable(onClick = onTap).padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Palette.CardHigh), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, modifier = Modifier.fillMaxSize())
            else Icon(Glyphs.Bell, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(n.appName.uppercase(), style = TextStyle(color = Palette.Gray, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp))
            Text(
                if (revealed) n.title else stringResource(R.string.notification),
                style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (revealed) n.text else stringResource(R.string.tap_to_view),
                style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The collapsed player: a dark circle with a few bars that move with the music's loudness, in a
 * colour taken from the cover. Paused (or silent), the bars settle into dots. Tap to reopen.
 */
@Composable
internal fun NowPlayingBubble(m: MediaInfo, onOpen: () -> Unit) {
    val context = LocalContext.current
    val tint = remember(m.art) { m.art?.let(::coverTint) ?: Palette.Pink }
    var levels by remember { mutableStateOf(FloatArray(AudioLevels.BANDS)) }
    var measured by remember { mutableStateOf(false) }
    LaunchedEffect(m.playing) {
        if (!m.playing) {
            levels = FloatArray(AudioLevels.BANDS); return@LaunchedEffect
        }
        AudioLevels.flow(context).collect { target ->
            measured = true
            // rise instantly, fall gently, like a VU meter
            levels = FloatArray(target.size) { i -> maxOf(target[i], levels[i] * 0.82f) }
        }
        measured = false
    }
    // Without the audio permission the bars still breathe, just not in time with the music.
    val breathe = rememberInfiniteTransition(label = "bars")
    val phase by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Box(
        Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onOpen).padding(5.dp)
            .clip(CircleShape).background(Color(0xFF1A1A1C)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 24.dp, height = 18.dp).graphicsLayer()) {
            val n = AudioLevels.BANDS
            val w = size.width / (n * 2 - 1)
            for (i in 0 until n) {
                val level = when {
                    !m.playing -> 0f
                    measured -> levels[i]
                    else -> 0.25f + 0.5f * abs(sin((phase + i * 0.21f) * 2 * PI.toFloat()))
                }
                val h = w + (size.height - w) * level
                drawRoundRect(
                    tint.copy(alpha = if (m.playing) 1f else 0.7f),
                    Offset(i * 2 * w, (size.height - h) / 2f), Size(w, h),
                    androidx.compose.ui.geometry.CornerRadius(w / 2f),
                )
            }
        }
    }
}

/** A bright, saturated version of the cover's average colour. */
private fun coverTint(art: android.graphics.Bitmap): Color = runCatching {
    val px = android.graphics.Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(px, hsv)
    if (hsv[1] < 0.15f) return Color(0xFFE5E5EA) // greyish covers: plain light bars
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceAtLeast(0.9f)
    Color(android.graphics.Color.HSVToColor(hsv))
}.getOrDefault(Palette.Pink)

@Composable
internal fun NowPlayingFull(m: MediaInfo, onDismiss: () -> Unit) {
    // A tiny copy of the cover, stretched with filtering, reads as a soft blur for free; a real
    // blur effect would be re-rendered on every animation frame.
    val track = Track(m.title, m.artist, m.art)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 120f) onDismiss() }) { _, d -> total += d }
            }
    ) {
        val h = maxHeight
        Crossfade(track, animationSpec = tween(700), label = "backdrop") { t ->
            val glow = remember(t.art) {
                t.art?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it, 12, 12, true).asImageBitmap() }.getOrNull() }
            }
            if (glow != null) {
                Image(
                    glow, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            // New track: the cover and titles cross-fade with a small slide instead of jumping.
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.04f))
                },
                label = "cover",
            ) { t ->
                val art = remember(t.art) { t.art?.asImageBitmap() }
                Box(Modifier.size(h * 0.66f).clip(RoundedCornerShape(26.dp)).background(Palette.CardHigh), contentAlignment = Alignment.Center) {
                    if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Glyphs.Music, null, tint = Palette.Pink, modifier = Modifier.size(h * 0.2f))
                }
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text(m.appName.uppercase(), style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp))
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        (fadeIn(tween(450, delayMillis = 100)) + slideInVertically(tween(450)) { it / 4 }) togetherWith
                            (fadeOut(tween(250)) + slideOutVertically(tween(300)) { -it / 4 })
                    },
                    label = "titles",
                ) { t ->
                    Column {
                        Text(t.title, style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Black, fontSize = 36.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(22.dp))
                if (m.durationMs > 0) SeekBar(m, 7.dp)
                Spacer(Modifier.height(18.dp))
                MediaControls(m.playing, 56.dp)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color(0x55FFFFFF))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Close, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }
}

/**
 * What identifies a track for the change animation. Only title and artist count: playback
 * updates re-deliver the cover as a new bitmap object, which must not restart the animation.
 */
private class Track(val title: String, val artist: String, val art: android.graphics.Bitmap?) {
    override fun equals(other: Any?) = other is Track && other.title == title && other.artist == artist
    override fun hashCode() = title.hashCode() * 31 + artist.hashCode()
}

