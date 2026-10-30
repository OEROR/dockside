package io.github.dockside.ui.alarm

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.R
import io.github.dockside.data.skyFor
import io.github.dockside.service.ClockAction
import io.github.dockside.service.ClockEvents
import io.github.dockside.service.ClockKind
import io.github.dockside.service.ClockRole
import io.github.dockside.service.RingingClock
import io.github.dockside.standby.StandbyData
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.rememberFrameTime
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.softGlow
import io.github.dockside.ui.common.toLocal
import io.github.dockside.ui.widgets.SkyIcon
import io.github.dockside.ui.widgets.skyName
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------- the sky behind it all

/** The part of the day an alarm rings in, which sets the greeting and the sky. */
enum class DayPart { MORNING, DAY, EVENING, NIGHT }

fun dayPart(hour: Int) = when (hour) {
    in 5..11 -> DayPart.MORNING
    in 12..16 -> DayPart.DAY
    in 17..21 -> DayPart.EVENING
    else -> DayPart.NIGHT
}

private class SkyColors(val top: Color, val middle: Color, val horizon: Color, val sun: Color)

private fun skyOf(part: DayPart) = when (part) {
    DayPart.MORNING -> SkyColors(Color(0xFF0B1D3A), Color(0xFF4B3C7A), Color(0xFFF0876A), Color(0xFFFFD18A))
    DayPart.DAY -> SkyColors(Color(0xFF16508F), Color(0xFF3F8FD2), Color(0xFFA9D8F5), Color(0xFFFFF3C4))
    DayPart.EVENING -> SkyColors(Color(0xFF1B1035), Color(0xFF6B2D6B), Color(0xFFF2784B), Color(0xFFFF9E5E))
    DayPart.NIGHT -> SkyColors(Color(0xFF02040D), Color(0xFF0A1433), Color(0xFF1A2550), Color(0xFFE8ECF5))
}

/**
 * A sky for the time of day: the sun climbs over the horizon in the morning, sinks in a warm
 * evening, and at night a moon hangs among slowly twinkling stars. [rise] (0..1) drives the
 * entrance; everything else moves on its own, slowly.
 */
@Composable
private fun Sky(part: DayPart, rise: Float, modifier: Modifier = Modifier) {
    val c = skyOf(part)
    val frame = rememberFrameTime()
    val stars = remember { List(70) { i -> Triple((i * 0.6180339f) % 1f, ((i * 0.3819660f * 7) % 1f) * 0.62f, i) } }
    Canvas(modifier.fillMaxSize()) {
        val t = frame.value
        drawRect(Brush.verticalGradient(0f to c.top, 0.55f to c.middle, 1f to c.horizon))
        if (part == DayPart.NIGHT) {
            stars.forEach { (x, y, i) ->
                val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t / (900f + i * 37) + i))
                drawCircle(Color.White.copy(alpha = twinkle * rise), size.minDimension * (0.002f + (i % 3) * 0.001f), Offset(x * size.width, y * size.height))
            }
            val moon = Offset(size.width * 0.8f, size.height * (0.55f - 0.3f * rise))
            softGlow(moon, size.minDimension * 0.35f, 0f to c.sun.copy(alpha = 0.25f * rise), 1f to Color.Transparent)
            drawCircle(c.sun.copy(alpha = rise), size.minDimension * 0.07f, moon)
            drawCircle(c.middle.copy(alpha = rise), size.minDimension * 0.065f, moon + Offset(size.minDimension * 0.035f, -size.minDimension * 0.02f))
            return@Canvas
        }
        // the sun rises to just above the horizon (in the evening it sits lower, sinking slowly),
        // wrapped in a glow and a few halos that breathe
        val height = if (part == DayPart.EVENING) 0.98f + 0.03f * sin(t / 9000f) else 0.86f
        val sun = Offset(size.width * 0.5f, size.height * (1.35f - (1.35f - height) * rise))
        val r = size.minDimension * 0.2f
        val pulse = 1f + 0.05f * sin(t / 1300f)
        softGlow(sun, size.width * 0.75f * pulse, 0f to c.sun.copy(alpha = 0.5f), 0.45f to c.horizon.copy(alpha = 0.22f), 1f to Color.Transparent)
        for (k in 1..3) {
            val breathe = 1f + 0.06f * sin(t / 1700f + k)
            drawCircle(c.sun.copy(alpha = 0.07f * rise), r * (1f + k * 0.45f) * breathe, sun)
        }
        drawCircle(c.sun, r, sun)
        // haze over the horizon
        drawRect(Brush.verticalGradient(0.75f to Color.Transparent, 1f to c.horizon.copy(alpha = 0.6f)))
    }
}

@Composable
fun greeting(part: DayPart): String = stringResource(
    when (part) {
        DayPart.MORNING -> R.string.greet_morning
        DayPart.DAY -> R.string.greet_day
        DayPart.EVENING -> R.string.greet_evening
        DayPart.NIGHT -> R.string.greet_night
    }
)

// ------------------------------------------------------------- a ringing alarm or timer

/**
 * Shown while an alarm or timer rings. "Close" presses the clock app's own dismiss button;
 * "More" dismisses too and then opens the day's summary.
 */
@Composable
fun AlarmRinging(r: RingingClock, data: StandbyData, animateIn: Boolean = true, onMore: () -> Unit) {
    val now by rememberTicker(1000)
    val t = now.toLocal()
    val part = dayPart(t.hour)
    // screenshot tests render the finished entrance
    val enter = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(r.key) { enter.animateTo(1f, tween(2600, easing = FastOutSlowInEasing)) }
    val ring = rememberInfiniteTransition(label = "ring")
    val beat by ring.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "beat")
    val timer = r.kind == ClockKind.TIMER

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).swallowTouches()) {
        val h = maxHeight
        Sky(part, enter.value)
        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
        Column(
            Modifier.fillMaxSize().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            fun Modifier.rise(i: Int) = graphicsLayer {
                val p = ((enter.value * 2.2f) - i * 0.25f).coerceIn(0f, 1f)
                alpha = p; translationY = (1f - p) * 40.dp.toPx()
            }
            Text(
                if (timer) stringResource(R.string.timer_done) else greeting(part),
                style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp),
                modifier = Modifier.rise(0),
            )
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.rise(1)) {
                // rings spreading from the time, like sound
                Canvas(Modifier.size(h * 0.62f)) {
                    for (k in 0..1) {
                        val p = (beat + k * 0.5f) % 1f
                        drawCircle(
                            Color.White.copy(alpha = 0.22f * (1f - p)), size.minDimension / 2f * (0.55f + 0.45f * p),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        TimeText.time(t, data.h24),
                        style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold, fontSize = (h.value * 0.24f).sp),
                    )
                    Text(
                        r.label.ifBlank { if (timer) stringResource(R.string.timer) else TimeText.dateLine(t) },
                        style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // a lone unrecognised button is most likely "dismiss"; otherwise press() falls back to
            // the clock app's own screen
            val dismiss = r.action(ClockRole.DISMISS) ?: r.actions.singleOrNull { it.role == ClockRole.OTHER }
            Row(Modifier.rise(2), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                r.action(ClockRole.SNOOZE)?.let { snooze ->
                    Pill(stringResource(R.string.alarm_snooze), Color.White.copy(alpha = 0.16f), Color.White) { ClockEvents.press(snooze) }
                }
                Pill(stringResource(if (timer) R.string.timer_stop else R.string.alarm_close), Color.White.copy(alpha = 0.22f), Color.White) {
                    ClockEvents.press(dismiss)
                }
                if (timer) {
                    r.action(ClockRole.ADD)?.let { add -> Pill(add.title, Color.White.copy(alpha = 0.16f), Color.White) { ClockEvents.press(add) } }
                } else {
                    Pill(stringResource(R.string.alarm_more), Color.White, Color(0xFF1C1C1E)) {
                        ClockEvents.press(dismiss); onMore()
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(background).clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp))
    }
}

// ------------------------------------------------------------- the day's summary

/** After "More": greeting, weather, today's events and the phone's charge, card by card. */
@Composable
fun DaySummary(data: StandbyData, animateIn: Boolean = true, onClose: () -> Unit) {
    val context = LocalContext.current
    val now by rememberTicker(30_000)
    val t = now.toLocal()
    val part = dayPart(t.hour)
    val enter = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    // leaves by itself after a while, like a notification would
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(4 * 60_000); onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black).swallowTouches()) {
        Sky(part, 1f)
        Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).card(enter.value, 0)) {
                    Text(greeting(part), style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp))
                    Text(
                        TimeText.dateLine(t) + " · " + TimeText.time(t, data.h24),
                        style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    )
                }
                Pill(stringResource(R.string.done), Color.White, Color(0xFF1C1C1E), onClose)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // weather
                Glass(Modifier.weight(1.15f).card(enter.value, 1)) {
                    val w = data.weather
                    if (w == null) {
                        Text(stringResource(R.string.summary_no_weather), style = small(Color.White.copy(alpha = 0.7f)))
                    } else {
                        val sky = skyFor(w.code)
                        Text(w.place, style = small(Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${w.temp.roundToInt()}°", style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Medium, fontSize = 54.sp))
                            Spacer(Modifier.width(10.dp))
                            SkyIcon(sky, w.isDay, Modifier.size(44.dp))
                        }
                        Text(skyName(sky, w.isDay), style = small(Color.White))
                        Text("↑${w.high.roundToInt()}°  ↓${w.low.roundToInt()}°", style = small(Color.White.copy(alpha = 0.7f)))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            w.hourly.take(5).forEach { hw ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("%02d".format(hw.hour), style = tiny())
                                    SkyIcon(skyFor(hw.code), hw.isDay, Modifier.size(22.dp))
                                    Text("${hw.temp.roundToInt()}°", style = tiny(Color.White))
                                }
                            }
                        }
                    }
                }
                // today
                Glass(Modifier.weight(1f).card(enter.value, 2)) {
                    Text(stringResource(R.string.summary_today), style = small(Palette.Red))
                    Spacer(Modifier.height(6.dp))
                    val today = data.events.filter { it.end > now && it.begin < now + 18 * 3600_000L }.take(3)
                    when {
                        !data.calendarAllowed -> Text(stringResource(R.string.needs_calendar), style = small(Color.White.copy(alpha = 0.7f)))
                        today.isEmpty() -> Text(stringResource(R.string.summary_no_events), style = small(Color.White.copy(alpha = 0.7f)))
                        else -> today.forEach { e ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(e.title, style = small(Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (e.allDay) stringResource(R.string.all_day) else TimeText.shortTime(e.begin, context),
                                        style = tiny(),
                                    )
                                }
                            }
                        }
                    }
                }
                // phone and next alarm
                Glass(Modifier.weight(0.8f).card(enter.value, 3)) {
                    Text(stringResource(R.string.summary_battery), style = small(Color.White.copy(alpha = 0.8f)))
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        ChargeDial(data.battery.level, data.battery.charging, 92.dp, enter.value)
                    }
                    data.nextAlarm?.let {
                        Text(stringResource(R.string.summary_next_alarm), style = tiny())
                        Text(TimeText.shortTime(it, context), style = small(Color.White))
                    }
                }
            }
        }
    }
}

/** Keeps taps and swipes from reaching the pages underneath; the screen's own buttons still work. */
private fun Modifier.swallowTouches() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

private fun Modifier.card(p: Float, i: Int) = graphicsLayer {
    val q = ((p * 1.8f) - i * 0.18f).coerceIn(0f, 1f)
    alpha = q
    translationY = (1f - q) * 60.dp.toPx()
    scaleX = 0.94f + 0.06f * q; scaleY = scaleX
}

@Composable
private fun Glass(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.1f)).padding(16.dp),
        content = content,
    )
}

private fun small(color: Color = Color.White) = TextStyle(color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
private fun tiny(color: Color = Color.White.copy(alpha = 0.6f)) = TextStyle(color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)

@Composable
private fun ChargeDial(level: Int, charging: Boolean, size: Dp, p: Float) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.11f
            val color = when {
                charging -> Palette.Green
                level <= 20 -> Palette.Red
                else -> Color.White
            }
            drawCircle(Color.White.copy(alpha = 0.15f), this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            drawArc(
                color, -90f, 360f * level / 100f * p, false,
                Offset(stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("$level%", style = small(Color.White))
    }
}
