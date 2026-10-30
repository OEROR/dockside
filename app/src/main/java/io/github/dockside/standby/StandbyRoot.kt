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
import kotlin.math.cos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.blur
import io.github.dockside.ui.common.LocalMotion
import io.github.dockside.service.ClockEvents
import io.github.dockside.ui.alarm.AlarmRinging
import io.github.dockside.ui.alarm.DaySummary
import io.github.dockside.ui.common.Motion
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
import io.github.dockside.ui.widgets.CircleButton
import io.github.dockside.ui.widgets.SeekBar
import io.github.dockside.ui.widgets.StackSide
import io.github.dockside.ui.widgets.WidgetsView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

    /** Filming mode: play through the highlights by itself (see [Showcase]). */
    val showcase: Boolean get() = false
}

private const val NIGHT_LUX = 3f
private const val DAY_LUX = 12f
private const val DARK_ROOM_LUX = 1.5f

@Composable
fun StandbyRoot(host: StandbyHost) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val settings by store.settings.collectAsState()
    val liveData = rememberStandbyData(settings)
    val data = if (host.showcase) remember(liveData) { Showcase.fillIn(liveData) } else liveData
    // filming mode changes nothing in the saved settings
    val update: ((AppSettings) -> AppSettings) -> Unit = { if (!host.showcase) store.update(it) }
    // ... and shows every effect, floating digits included
    val motion = if (host.showcase) Motion(screens = true, pages = true, backdrop = true, springs = true, floating = true)
    else Motion.of(settings.motion)
    val scope = rememberCoroutineScope()

    // --- arriving and leaving: Dockside fades and settles in rather than cutting in
    val appear = remember { Animatable(if (motion.screens) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val leave: () -> Unit = {
        scope.launch {
            if (motion.screens) appear.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            host.exit(false)
        }
    }

    // --- views, remembered per charger (location memory by plug type)
    val plug = data.battery.plugType ?: "none"
    val pager = rememberPagerState(initialPage = remember { settings.chargerViews[plug] ?: Views.WIDGETS }) { Views.COUNT }
    LaunchedEffect(pager.settledPage) {
        delay(1200) // after the swipe has settled, so saving never interrupts an animation
        if (!host.showcase) store.update { it.copy(chargerViews = it.chargerViews + (plug to pager.settledPage)) }
    }

    var editWidgets by remember { mutableStateOf<StackSide?>(null) }
    var editClock by remember { mutableStateOf(false) }
    var editPhotos by remember { mutableStateOf(false) }
    val editing = editWidgets != null || editClock || editPhotos
    // the alarm's day summary (after "More"); declared early so the display rules can see it
    var showSummary by remember { mutableStateOf(false) }
    val alarmUp by rememberUpdatedState(showSummary)

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
            // a ringing alarm (and its summary) keeps the screen awake
            val alarm = ClockEvents.ringing.value != null || alarmUp || host.showcase
            sleeping = if (alarm) false else when (settings.displayOff) {
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
            delay(1500); leave()
        }
    }
    LaunchedEffect(portraitSince != 0L) {
        if (portraitSince != 0L && host.exitWhenUpright && !settings.ignorePosture) {
            delay(1200); leave()
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

    // --- the clock app's alarms, timers and stopwatch, shown Dockside's way
    val ringingNow by ClockEvents.ringing.collectAsState()
    val ringing = ringingNow?.takeIf { settings.alarmTakeover || it.demo }
    var lastRinging by remember { mutableStateOf(ringing) }
    // a demo from the settings must not come back the next time Dockside opens
    DisposableEffect(Unit) { onDispose { if (ClockEvents.ringing.value?.demo == true) ClockEvents.dismissScreen() } }
    LaunchedEffect(ringing) {
        if (ringing != null) {
            lastRinging = ringing; showSummary = false
            lastTouch = SystemClock.elapsedRealtime(); sleeping = false
        }
    }

    // --- Now Playing goes full screen when playback starts
    var showPlayer by remember { mutableStateOf(false) }
    // Closing the player collapses that app's music into the bubble until it stops playing;
    // the next track no longer pops the full player back up.
    var dismissedApp by remember { mutableStateOf<String?>(null) }
    val media = data.media
    // the last track stays on the player while it shrinks back into the bubble
    var playerMedia by remember { mutableStateOf(media) }
    LaunchedEffect(media) { if (media != null) playerMedia = media }
    // 0 = the bubble, 1 = the full player; it grows out of the bubble and shrinks back into it
    val expand = remember { Animatable(0f) }
    LaunchedEffect(showPlayer, motion.screens) {
        val target = if (showPlayer) 1f else 0f
        when {
            !motion.screens -> expand.snapTo(target)
            showPlayer -> expand.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 140f))
            else -> expand.animateTo(target, spring(dampingRatio = 1f, stiffness = 220f))
        }
    }
    val playerOnScreen by remember { derivedStateOf { expand.value > 0f } }
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

    // the alarm screen shows its sky in full colour, even when Night Mode is on
    // --- filming mode: the show itself
    if (host.showcase) {
        LaunchedEffect(Unit) {
            delay(1500)
            pager.animateScrollToPage(Views.CLOCK)
            while (true) {
                for ((style, ms) in Showcase.faces) {
                    Showcase.showClock(style); delay(ms)
                }
                pager.animateScrollToPage(Views.WIDGETS, animationSpec = tween(1600, easing = FastOutSlowInEasing))
                delay(3500)
                // music starts: the player grows out of the bubble, then is closed into it
                LiveEvents.publishMedia(Showcase.demoTrack())
                delay(7000)
                dismissedApp = "io.github.dockside.demo"; showPlayer = false
                delay(2500)
                LiveEvents.publishMedia(null)
                delay(800)
                // the alarm, then "More"
                ClockEvents.demo()
                delay(5500)
                ClockEvents.dismissScreen(); showSummary = true
                delay(6500)
                showSummary = false
                delay(900)
                pager.animateScrollToPage(Views.CLOCK, animationSpec = tween(1600, easing = FastOutSlowInEasing))
                delay(500)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                Showcase.showClock(null)
                LiveEvents.publishMedia(null)
                if (ClockEvents.ringing.value?.demo == true) ClockEvents.dismissScreen()
            }
        }
    }

    val nightFraction by animateFloatAsState(if (night && ringing == null && !showSummary) 1f else 0f, tween(1800), label = "night")
    NightFilter(nightFraction)
    val sleepAlpha by animateFloatAsState(if (sleeping) 1f else 0f, tween(if (sleeping) 1200 else 350), label = "sleep")

    CompositionLocalProvider(LocalMotion provides motion) {
    ForceLandscape(clockwise = rightEdgeUp) { Box(
        Modifier.fillMaxSize().background(Color.Black)
            .graphicsLayer {
                val a = appear.value
                alpha = a
                scaleX = 0.94f + 0.06f * a; scaleY = scaleX
            }
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
            userScrollEnabled = !editing && !showPlayer && ringing == null && !showSummary,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val live = page == pager.currentPage || (pager.isScrollInProgress && abs(page - pager.currentPage) <= 1)
            val shiftClock = rememberTicker(3 * 60_000)
            CompositionLocalProvider(LocalLive provides live) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            // while swiping, pages turn and shrink a little like cards
                            if (!motion.pages) return@graphicsLayer
                            val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                            val a = abs(off)
                            if (a == 0f) return@graphicsLayer
                            val s = 1f - 0.16f * sin(a * PI.toFloat() / 2)
                            scaleX = s; scaleY = s
                            rotationY = off * 32f
                            cameraDistance = 14f * density
                            alpha = 1f - 0.5f * a
                            shape = RoundedCornerShape(36.dp.toPx() * (a * 5f).coerceAtMost(1f))
                            clip = true
                        }
                        .clipToBounds().graphicsLayer {
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
            visible = media != null && (!showPlayer || motion.screens) && !editing,
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

        if (playerOnScreen) playerMedia?.let { m ->
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    // the player's outline grows from the bubble (46 dp circle, centred 32 dp
                    // below the top) to the whole screen
                    val p = expand.value.coerceIn(0f, 1f)
                    val r = 23.dp.toPx()
                    val cy = 32.dp.toPx()
                    val left = lerp(size.width / 2 - r, 0f, p)
                    val top = lerp(cy - r, 0f, p)
                    val right = lerp(size.width / 2 + r, size.width, p)
                    val bottom = lerp(cy + r, size.height, p)
                    shape = object : androidx.compose.ui.graphics.Shape {
                        override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
                            androidx.compose.ui.graphics.Outline.Rounded(
                                androidx.compose.ui.geometry.RoundRect(left, top, right, bottom, androidx.compose.ui.geometry.CornerRadius(lerp(r, 0f, p)))
                            )
                    }
                    clip = true
                }
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = expand.value
                        alpha = ((p - 0.12f) / 0.5f).coerceIn(0f, 1f)
                        // the springy overshoot shows here, as a slight swell of the content
                        scaleX = 0.88f + 0.12f * p; scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    }
                ) {
                    NowPlayingFull(m) {
                        showPlayer = false; dismissedApp = m.packageName
                    }
                }
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

        AnimatedVisibility(
            visible = showSummary,
            enter = fadeIn(tween(700)) + scaleIn(tween(700), initialScale = 1.05f),
            exit = fadeOut(tween(500)) + scaleOut(tween(500), targetScale = 0.96f),
        ) { DaySummary(data) { showSummary = false } }
        AnimatedVisibility(
            visible = ringing != null,
            enter = fadeIn(tween(900)) + scaleIn(tween(900), initialScale = 1.08f),
            exit = fadeOut(tween(600)) + scaleOut(tween(600), targetScale = 1.04f),
        ) { lastRinging?.let { r -> AlarmRinging(r, data) { showSummary = true } } }

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
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

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
        Modifier.fillMaxSize().background(Color(0xFF1C1C1E))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 120f) onDismiss() }) { _, d -> total += d }
            }
    ) {
        val h = maxHeight
        val drifting = LocalMotion.current.backdrop
        val drift = rememberInfiniteTransition(label = "drift")
        val phase by drift.animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "phase")
        Crossfade(track, animationSpec = tween(900), label = "backdrop") { t ->
            val glow = remember(t.art) {
                // with a real blur (Android 12+) a little detail helps; without it a tinier copy
                // stretched with filtering is what reads as blurred
                val px = if (android.os.Build.VERSION.SDK_INT >= 31) 24 else 10
                t.art?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it, px, px, true).asImageBitmap() }.getOrNull() }
            }
            if (glow != null) {
                // The cover, stretched over the whole screen and heavily blurred. The blur is
                // rendered once into its layer; the slow drift only moves that layer around.
                Image(
                    glow, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            alpha = 0.95f
                            if (drifting) {
                                val s = 1.45f + 0.12f * sin(phase * 2)
                                scaleX = s; scaleY = s
                                translationX = size.width * 0.08f * sin(phase)
                                translationY = size.height * 0.08f * cos(phase * 3)
                                rotationZ = 6f * sin(phase * 2 + 1f)
                            } else {
                                scaleX = 1.3f; scaleY = 1.3f
                            }
                        }
                        .blur(36.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x8F000000)))
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            // the cover stands large while playing and settles back a little on pause
            val coverScale by animateFloatAsState(if (m.playing) 1f else 0.8f, spring(dampingRatio = 0.75f, stiffness = 260f), label = "cover")
            Box(Modifier.size(h * 0.78f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.04f))
                    },
                    modifier = Modifier.graphicsLayer { scaleX = coverScale; scaleY = coverScale },
                    label = "cover",
                ) { t ->
                    val art = remember(t.art) { t.art?.asImageBitmap() }
                    val shape = RoundedCornerShape(h * 0.03f)
                    Box(
                        Modifier.size(h * 0.78f).shadow(24.dp, shape).clip(shape).background(Palette.CardHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Glyphs.Music, null, tint = Palette.Pink, modifier = Modifier.size(h * 0.22f))
                    }
                }
            }
            Spacer(Modifier.width(44.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VolumeBar(Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyphs.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        (fadeIn(tween(450, delayMillis = 100)) + slideInVertically(tween(450)) { it / 4 }) togetherWith
                            (fadeOut(tween(250)) + slideOutVertically(tween(300)) { -it / 4 })
                    },
                    label = "titles",
                ) { t ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            t.title, style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            t.artist, style = TextStyle(color = Color.White.copy(alpha = 0.45f), fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(0.6f))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // sized by height, but never wider than the column (narrower on tablets)
                    val unit = minOf(h, maxWidth * 1.2f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val dim = Color.White.copy(alpha = 0.4f)
                        CircleButton(Glyphs.Previous, unit * 0.22f, tint = dim) { LiveEvents.previous() }
                        CircleButton(if (m.playing) Glyphs.Pause else Glyphs.Play, unit * 0.26f) { LiveEvents.playPause() }
                        CircleButton(Glyphs.Next, unit * 0.22f, tint = dim) { LiveEvents.next() }
                    }
                }
                Spacer(Modifier.weight(0.6f))
                if (m.durationMs > 0) SeekBar(m, 11.dp, fill = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/** Media volume as a wide pill; drag or tap to change it. */
@Composable
private fun VolumeBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(android.media.AudioManager::class.java) }
    val stream = android.media.AudioManager.STREAM_MUSIC
    val max = remember { audio?.getStreamMaxVolume(stream)?.coerceAtLeast(1) ?: 1 }
    var level by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // follows the hardware buttons too
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) level = (audio?.getStreamVolume(stream) ?: 0) / max.toFloat()
            delay(400)
        }
    }
    fun set(f: Float) {
        level = f.coerceIn(0f, 1f)
        runCatching { audio?.setStreamVolume(stream, (level * max).roundToInt(), 0) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(36.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; set(it.x / size.width) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); set(change.position.x / size.width) }
                }
                .pointerInput(Unit) { detectTapGestures { set(it.x / size.width) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(13.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(level).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(Glyphs.Speaker, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(26.dp))
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

