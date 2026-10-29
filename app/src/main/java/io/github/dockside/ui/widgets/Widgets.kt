package io.github.dockside.ui.widgets

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.BitmapLoader
import io.github.dockside.data.CalEvent
import io.github.dockside.data.Sky
import io.github.dockside.data.WidgetKind
import io.github.dockside.data.skyFor
import io.github.dockside.service.LiveEvents
import io.github.dockside.standby.StandbyData
import io.github.dockside.ui.clocks.AnalogClock
import io.github.dockside.ui.clocks.ClockInfo
import io.github.dockside.ui.clocks.RollingText
import io.github.dockside.ui.clocks.WorldCities
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.rememberFrameTime
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.toLocal
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun widgetTitle(kind: WidgetKind): Int = when (kind) {
    WidgetKind.ANALOG_CLOCK -> R.string.w_analog
    WidgetKind.DIGITAL_CLOCK -> R.string.w_digital
    WidgetKind.WORLD_CLOCK -> R.string.w_world
    WidgetKind.CALENDAR_DAY -> R.string.w_cal_day
    WidgetKind.CALENDAR_MONTH -> R.string.w_cal_month
    WidgetKind.UP_NEXT -> R.string.w_up_next
    WidgetKind.WEATHER -> R.string.w_weather
    WidgetKind.BATTERY -> R.string.w_battery
    WidgetKind.ALARM -> R.string.w_alarm
    WidgetKind.PHOTO -> R.string.w_photo
    WidgetKind.NOW_PLAYING -> R.string.w_now_playing
    WidgetKind.STOPWATCH -> R.string.w_stopwatch
    WidgetKind.SQUARE_CLOCK -> R.string.w_square_clock
    WidgetKind.CITY_TIME -> R.string.w_city_time
    WidgetKind.DEVICES -> R.string.w_devices
    WidgetKind.WEATHER_HOURLY -> R.string.w_weather_hourly
}

/** Intent opened by a widget's "open" arrow, if it has one. */
fun widgetIntent(kind: WidgetKind, data: StandbyData): Intent? = when (kind) {
    WidgetKind.CALENDAR_DAY, WidgetKind.CALENDAR_MONTH, WidgetKind.UP_NEXT ->
        Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(System.currentTimeMillis().toString()).build())
    WidgetKind.ALARM, WidgetKind.ANALOG_CLOCK, WidgetKind.DIGITAL_CLOCK, WidgetKind.WORLD_CLOCK, WidgetKind.STOPWATCH,
    WidgetKind.SQUARE_CLOCK, WidgetKind.CITY_TIME ->
        Intent(AlarmClock.ACTION_SHOW_ALARMS)
    WidgetKind.WEATHER, WidgetKind.WEATHER_HOURLY -> data.weather?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/en/docs#latitude=${it.lat}&longitude=${it.lon}"))
    }
    WidgetKind.DEVICES -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    WidgetKind.NOW_PLAYING -> null
    else -> null
}

/**
 * How relevant a widget is right now (0..1). Smart Rotate shows the most relevant one.
 */
fun relevance(kind: WidgetKind, data: StandbyData, now: Long): Float {
    val hour = now.toLocal().hour
    val nextEvent = data.events.firstOrNull { !it.allDay && it.end > now }
    return when (kind) {
        WidgetKind.NOW_PLAYING -> if (data.media?.playing == true) 1f else 0f
        WidgetKind.UP_NEXT -> nextEvent?.let { if (it.begin - now < 45 * 60_000) 0.9f else 0.2f } ?: 0f
        WidgetKind.CALENDAR_DAY -> if (nextEvent != null && nextEvent.begin - now < 3 * 3600_000) 0.6f else 0.35f
        WidgetKind.ALARM -> data.nextAlarm?.let { if (hour >= 21 || hour < 5) 0.7f else 0.1f } ?: 0f
        WidgetKind.WEATHER -> if (hour in 6..9) 0.65f else 0.3f
        WidgetKind.WEATHER_HOURLY -> if (hour in 6..9) 0.6f else 0.28f
        WidgetKind.BATTERY -> if (data.battery.charging && data.battery.level < 25) 0.75f else 0.15f
        WidgetKind.ANALOG_CLOCK -> 0.4f
        WidgetKind.DIGITAL_CLOCK -> 0.38f
        WidgetKind.SQUARE_CLOCK -> 0.39f
        WidgetKind.DEVICES -> if (data.battery.charging) 0.3f else 0.15f
        else -> 0.2f
    }
}

@Composable
fun WidgetContent(kind: WidgetKind, data: StandbyData, settings: AppSettings, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (kind) {
            WidgetKind.ANALOG_CLOCK -> AnalogClock(
                Palette.Orange, ClockInfo(data.h24), Modifier.fillMaxSize().padding(6.dp),
                showInfo = true, faceColor = Palette.Card,
            )
            WidgetKind.DIGITAL_CLOCK -> DigitalClockWidget(data)
            WidgetKind.WORLD_CLOCK -> WorldClockWidget(settings, data)
            WidgetKind.CALENDAR_DAY -> CalendarDayWidget(data)
            WidgetKind.CALENDAR_MONTH -> MonthWidget()
            WidgetKind.UP_NEXT -> UpNextWidget(data)
            WidgetKind.WEATHER -> WeatherWidget(data)
            WidgetKind.BATTERY -> BatteryWidget(data)
            WidgetKind.ALARM -> AlarmWidget(data)
            WidgetKind.PHOTO -> PhotoWidget(data)
            WidgetKind.NOW_PLAYING -> NowPlayingWidget(data)
            WidgetKind.STOPWATCH -> StopwatchWidget()
            WidgetKind.SQUARE_CLOCK -> SquareClockWidget()
            WidgetKind.CITY_TIME -> CityTimeWidget(settings, data)
            WidgetKind.DEVICES -> DevicesWidget(data)
            WidgetKind.WEATHER_HOURLY -> HourlyWeatherWidget(data)
        }
    }
}

// ------------------------------------------------------------- helpers

/** Sizes relative to the widget's shorter side, so widgets scale with the stack. */
internal class Metrics(private val side: Dp, private val density: Density) {
    fun unit(f: Float): TextUnit = with(density) { (side * f).toSp() }
    fun dp(f: Float): Dp = side * f
}

@Composable
internal fun BoxWithConstraintsScope.metrics() = Metrics(minOf(maxWidth, maxHeight), LocalDensity.current)

internal fun style(size: TextUnit, color: Color = Color.White, weight: FontWeight = FontWeight.Bold) =
    TextStyle(color = color, fontFamily = RoundedFamily, fontWeight = weight, fontSize = size, lineHeight = size * 1.12f)

@Composable
internal fun Placeholder(text: Int) {
    Text(
        stringResource(text), style = style(18.sp, Palette.Gray, FontWeight.SemiBold),
        textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
    )
}

// ------------------------------------------------------------- clocks

@Composable
private fun DigitalClockWidget(data: StandbyData) {
    val now by rememberTicker()
    val t = now.toLocal()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val m = metrics()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TimeText.shortDateLine(t).uppercase(), style = style(m.unit(0.085f), Palette.Red))
            RollingText(TimeText.time(t, data.h24), style(m.unit(0.34f), weight = FontWeight.Black), float = false)
            data.temperature()?.let { Text(it, style = style(m.unit(0.08f), Palette.Gray)) }
        }
    }
}

@Composable
private fun WorldClockWidget(settings: AppSettings, data: StandbyData) {
    val cities = settings.worldCities.mapNotNull { WorldCities.byZone(it) }.take(4)
    val now by rememberTicker(60_000)
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val m = metrics()
        val cell = m.dp(0.5f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            cities.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { city ->
                        val zone = ZoneId.of(city.zone)
                        val local = now.toLocal(zone)
                        val day = local.hour in 6..17
                        val offset = (zone.rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds -
                            ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds) / 3600
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnalogClock(
                                Palette.Orange, ClockInfo(data.h24), Modifier.size(cell * 0.66f), zone = zone,
                                faceColor = if (day) Color(0xFFE9E9EE) else Palette.CardHigh, numerals = false,
                            )
                            Text(city.name, style = style(m.unit(0.055f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.hours_offset, offset), style = style(m.unit(0.045f), Palette.Gray))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- calendar

@Composable
private fun CalendarDayWidget(data: StandbyData) {
    val now by rememberTicker(60_000)
    val t = now.toLocal()
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Text(TimeText.weekdayLong(t).uppercase(), style = style(m.unit(0.1f), Palette.Red, FontWeight.ExtraBold))
            // the big date takes whatever height is left, so the event line always fits
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(
                    t.dayOfMonth.toString(),
                    style = style(m.unit(0.46f), weight = FontWeight.Black).copy(
                        lineHeight = m.unit(0.46f),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                )
            }
            val event = data.events.firstOrNull { it.end > now && it.begin < now + 24 * 3600_000L }
            when {
                !data.calendarAllowed -> Text(stringResource(R.string.needs_calendar), style = style(m.unit(0.065f), Palette.Gray))
                event == null -> Text(stringResource(R.string.no_events_today), style = style(m.unit(0.075f), Palette.Gray))
                else -> EventRow(event, m.unit(0.085f), m.unit(0.07f))
            }
        }
    }
}

@Composable
private fun EventRow(event: CalEvent, titleSize: TextUnit, timeSize: TextUnit) {
    val context = LocalContext.current
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(
            Modifier.width(5.dp).height(with(LocalDensity.current) { (titleSize.toDp() + timeSize.toDp()) * 1.15f })
                .background(Color(event.color or 0xFF000000.toInt()), RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(event.title, style = style(titleSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (event.allDay) stringResource(R.string.all_day)
                else TimeText.shortTime(event.begin, context) + " – " + TimeText.shortTime(event.end, context),
                style = style(timeSize, Palette.Gray, FontWeight.SemiBold), maxLines = 1,
            )
        }
    }
}

@Composable
private fun MonthWidget() {
    val now by rememberTicker(60_000)
    val today = now.toLocal().toLocalDate()
    val first = today.withDayOfMonth(1)
    val firstDow = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = (first.dayOfWeek.value - firstDow.value + 7) % 7
    val days = today.lengthOfMonth()
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        val cellText = m.unit(0.072f)
        Column(Modifier.fillMaxSize()) {
            Text(TimeText.monthLong(today.atStartOfDay()).uppercase(), style = style(m.unit(0.085f), Palette.Red, FontWeight.ExtraBold))
            Spacer(Modifier.height(m.dp(0.025f)))
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val dow = firstDow.plus(i.toLong())
                    Text(
                        dow.getDisplayName(JTextStyle.NARROW, Locale.getDefault()), style = style(cellText, Palette.Gray),
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
            val rows = (lead + days + 6) / 7
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - lead + 1
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (day in 1..days) {
                                    val isToday = day == today.dayOfMonth
                                    val weekend = today.withDayOfMonth(day).dayOfWeek.let {
                                        it == java.time.DayOfWeek.SATURDAY || it == java.time.DayOfWeek.SUNDAY
                                    }
                                    if (isToday) Box(Modifier.size(m.dp(0.115f)).background(Palette.Red, CircleShape))
                                    Text(
                                        day.toString(),
                                        style = style(
                                            cellText, if (weekend && !isToday) Palette.Gray else Color.White,
                                            if (isToday) FontWeight.Black else FontWeight.Bold,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNextWidget(data: StandbyData) {
    val now by rememberTicker(60_000)
    val t = now.toLocal()
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(TimeText.weekdayShort(t).uppercase() + " ", style = style(m.unit(0.1f), Palette.Red, FontWeight.ExtraBold))
                Text(t.dayOfMonth.toString(), style = style(m.unit(0.1f), weight = FontWeight.ExtraBold))
            }
            Spacer(Modifier.height(m.dp(0.03f)))
            val upcoming = data.events.filter { it.end > now }.take(4)
            when {
                !data.calendarAllowed -> Text(stringResource(R.string.needs_calendar), style = style(m.unit(0.065f), Palette.Gray))
                upcoming.isEmpty() -> Text(stringResource(R.string.no_events), style = style(m.unit(0.075f), Palette.Gray))
                else -> {
                    var lastDay: LocalDate? = t.toLocalDate()
                    upcoming.forEach { e ->
                        val d = e.begin.toLocal().toLocalDate()
                        if (d != lastDay) {
                            lastDay = d
                            Text(
                                if (d == t.toLocalDate().plusDays(1)) stringResource(R.string.tomorrow).uppercase()
                                else TimeText.shortDateLine(d.atStartOfDay()).uppercase(),
                                style = style(m.unit(0.055f), Palette.Gray, FontWeight.ExtraBold),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        EventRow(e, m.unit(0.075f), m.unit(0.06f))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- weather

@Composable
fun skyName(sky: Sky, isDay: Boolean): String = stringResource(
    when (sky) {
        Sky.CLEAR -> if (isDay) R.string.sky_sunny else R.string.sky_clear
        Sky.PARTLY_CLOUDY -> R.string.sky_partly
        Sky.CLOUDY -> R.string.sky_cloudy
        Sky.FOG -> R.string.sky_fog
        Sky.DRIZZLE -> R.string.sky_drizzle
        Sky.RAIN -> R.string.sky_rain
        Sky.SNOW -> R.string.sky_snow
        Sky.THUNDER -> R.string.sky_thunder
    }
)

/** Current conditions, large: place, temperature, sky, high and low. */
@Composable
private fun WeatherWidget(data: StandbyData) {
    val w = data.weather ?: run { Placeholder(R.string.needs_location); return }
    val sky = skyFor(w.code)
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.place.ifBlank { "—" }, style = style(m.unit(0.1f), weight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (w.place.isNotBlank()) {
                    Icon(Glyphs.Locate, null, tint = Color.White, modifier = Modifier.padding(start = 6.dp).size(m.dp(0.065f)))
                }
            }
            Text(
                "${w.temp.roundToInt()}°",
                style = style(m.unit(0.34f), weight = FontWeight.Normal).copy(
                    lineHeight = m.unit(0.34f),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier.padding(top = m.dp(0.02f)),
            )
            Spacer(Modifier.weight(1f))
            SkyIcon(sky, w.isDay, Modifier.size(m.dp(0.12f)))
            Text(skyName(sky, w.isDay), style = style(m.unit(0.085f), weight = FontWeight.SemiBold), maxLines = 1)
            Text(
                stringResource(R.string.high_low, "${w.high.roundToInt()}°", "${w.low.roundToInt()}°"),
                style = style(m.unit(0.085f), weight = FontWeight.SemiBold), maxLines = 1,
            )
        }
    }
}

/** Current conditions plus the next hours. */
@Composable
private fun HourlyWeatherWidget(data: StandbyData) {
    val w = data.weather ?: run { Placeholder(R.string.needs_location); return }
    val sky = skyFor(w.code)
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.place.ifBlank { "—" }, style = style(m.unit(0.085f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Glyphs.Pin, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(m.dp(0.06f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${w.temp.roundToInt()}°", style = style(m.unit(0.3f), weight = FontWeight.Medium))
                Spacer(Modifier.weight(1f))
                SkyIcon(sky, w.isDay, Modifier.size(m.dp(0.26f)))
            }
            Text(skyName(sky, w.isDay), style = style(m.unit(0.075f)))
            Text(
                stringResource(R.string.high_low, "${w.high.roundToInt()}°", "${w.low.roundToInt()}°"),
                style = style(m.unit(0.07f), Palette.Gray),
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                w.hourly.forEach { h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (data.h24) "%02d".format(h.hour) else "${(h.hour + 11) % 12 + 1}", style = style(m.unit(0.055f), Palette.Gray))
                        SkyIcon(skyFor(h.code), h.isDay, Modifier.size(m.dp(0.09f)).padding(vertical = 2.dp))
                        Text("${h.temp.roundToInt()}°", style = style(m.unit(0.06f)))
                    }
                }
            }
        }
    }
}

/** Original weather pictograms drawn with simple shapes. */
@Composable
fun SkyIcon(sky: Sky, isDay: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val s = size.minDimension
        when (sky) {
            Sky.CLEAR -> if (isDay) drawSun(Offset(s / 2, s / 2), s * 0.22f) else drawMoon(Offset(s / 2, s / 2), s * 0.3f)
            Sky.PARTLY_CLOUDY -> {
                if (isDay) drawSun(Offset(s * 0.62f, s * 0.36f), s * 0.17f) else drawMoon(Offset(s * 0.64f, s * 0.36f), s * 0.22f)
                drawCloud(Offset(s * 0.44f, s * 0.62f), s * 0.8f, Color.White)
            }
            Sky.CLOUDY -> drawCloud(Offset(s / 2, s * 0.55f), s, Color.White)
            Sky.FOG -> {
                drawCloud(Offset(s / 2, s * 0.42f), s * 0.85f, Color(0xFFD1D1D6))
                for (i in 0..2) drawLine(Color.White, Offset(s * 0.15f, s * (0.72f + i * 0.1f)), Offset(s * 0.85f, s * (0.72f + i * 0.1f)), s * 0.05f, StrokeCap.Round)
            }
            Sky.DRIZZLE, Sky.RAIN -> {
                drawCloud(Offset(s / 2, s * 0.4f), s * 0.9f, Color.White)
                val n = if (sky == Sky.RAIN) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.32f + i * 0.36f / maxOf(1, n - 1))
                    drawLine(Palette.Cyan, Offset(x, s * 0.68f), Offset(x - s * 0.06f, s * 0.9f), s * 0.06f, StrokeCap.Round)
                }
            }
            Sky.SNOW -> {
                drawCloud(Offset(s / 2, s * 0.4f), s * 0.9f, Color.White)
                for (i in 0..2) drawCircle(Color.White, s * 0.045f, Offset(s * (0.3f + i * 0.2f), s * (0.78f + (i % 2) * 0.1f)))
            }
            Sky.THUNDER -> {
                drawCloud(Offset(s / 2, s * 0.38f), s * 0.9f, Color(0xFFD1D1D6))
                val p = Path().apply {
                    moveTo(s * 0.55f, s * 0.55f); lineTo(s * 0.38f, s * 0.78f); lineTo(s * 0.5f, s * 0.78f)
                    lineTo(s * 0.42f, s * 0.98f); lineTo(s * 0.66f, s * 0.7f); lineTo(s * 0.54f, s * 0.7f); close()
                }
                drawPath(p, Palette.Yellow)
            }
        }
    }
}

private fun DrawScope.drawSun(c: Offset, r: Float) {
    for (i in 0 until 8) {
        val a = i * PI.toFloat() / 4
        drawLine(Palette.Yellow, c + Offset(cos(a), sin(a)) * (r * 1.35f), c + Offset(cos(a), sin(a)) * (r * 1.75f), r * 0.22f, StrokeCap.Round)
    }
    drawCircle(Palette.Yellow, r, c)
}

private fun DrawScope.drawMoon(c: Offset, r: Float) {
    val p = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c, r))
        val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.55f, -r * 0.4f), r * 0.85f)) }
        op(this, cut, androidx.compose.ui.graphics.PathOperation.Difference)
    }
    drawPath(p, Color(0xFFF2F2F7))
}

private fun DrawScope.drawCloud(c: Offset, w: Float, color: Color) {
    val h = w * 0.32f
    drawRoundRect(color, Offset(c.x - w * 0.4f, c.y - h * 0.1f), Size(w * 0.8f, h * 0.75f), androidx.compose.ui.geometry.CornerRadius(h * 0.4f))
    drawCircle(color, w * 0.17f, Offset(c.x - w * 0.16f, c.y))
    drawCircle(color, w * 0.23f, Offset(c.x + w * 0.08f, c.y - h * 0.25f))
}

// ------------------------------------------------------------- battery, alarm

@Composable
private fun BatteryWidget(data: StandbyData) {
    val b = data.battery
    val color = when {
        b.level <= 20 && !b.charging -> Palette.Red
        else -> Palette.Green
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val m = metrics()
        val d = m.dp(0.72f)
        Box(Modifier.size(d), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.09f
                val inset = stroke / 2
                drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(color, -90f, 360f * b.level / 100f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // phone glyph
                Box(
                    Modifier.size(width = d * 0.16f, height = d * 0.28f)
                        .background(Color.White, RoundedCornerShape(d * 0.04f))
                        .padding(d * 0.018f)
                        .background(Color.Black, RoundedCornerShape(d * 0.03f))
                )
                if (b.charging) Icon(Glyphs.Bolt, null, tint = color, modifier = Modifier.size(d * 0.14f))
            }
        }
        Text(
            "${b.level}%", style = style(m.unit(0.13f), weight = FontWeight.ExtraBold),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun AlarmWidget(data: StandbyData) {
    val context = LocalContext.current
    val now by rememberTicker(60_000)
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Icon(Glyphs.Alarm, null, tint = Palette.Orange, modifier = Modifier.size(m.dp(0.17f)))
            Spacer(Modifier.weight(1f))
            val alarm = data.nextAlarm
            if (alarm == null) {
                Text(stringResource(R.string.no_alarm), style = style(m.unit(0.12f), Palette.Gray))
            } else {
                val day = alarm.toLocal().toLocalDate()
                val today = now.toLocal().toLocalDate()
                Text(
                    when (day) {
                        today -> stringResource(R.string.today)
                        today.plusDays(1) -> stringResource(R.string.tomorrow)
                        else -> TimeText.shortDateLine(day.atStartOfDay())
                    }.uppercase(),
                    style = style(m.unit(0.085f), Palette.Orange, FontWeight.ExtraBold),
                )
                Text(TimeText.shortTime(alarm, context), style = style(m.unit(0.26f), weight = FontWeight.Black), maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------- photo

@Composable
private fun PhotoWidget(data: StandbyData) {
    val context = LocalContext.current
    val uris = data.albums.firstOrNull()?.uris.orEmpty()
    if (uris.isEmpty()) {
        Placeholder(R.string.needs_photos); return
    }
    val index by produceState(0, uris) {
        while (true) {
            delay(15_000); value = (value + 1) % uris.size
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, uris, index) {
        value = BitmapLoader.load(context, uris[index % uris.size], 900)
    }
    Crossfade(bitmap, animationSpec = tween(900), label = "photo") { bmp ->
        if (bmp != null) Image(
            bmp, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
        )
    }
}

// ------------------------------------------------------------- media

@Composable
private fun NowPlayingWidget(data: StandbyData) {
    val media = data.media
    if (media == null) {
        val listener = LiveEvents.isListenerEnabled(LocalContext.current)
        Placeholder(if (listener) R.string.not_playing else R.string.needs_listener); return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(m.dp(0.36f)).clip(RoundedCornerShape(12.dp)).background(Palette.CardHigh), contentAlignment = Alignment.Center) {
                    val art = remember(media.art) { media.art?.asImageBitmap() }
                    if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Glyphs.Music, null, tint = Palette.Pink, modifier = Modifier.size(m.dp(0.16f)))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.appName, style = style(m.unit(0.06f), Palette.Gray), maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(media.title, style = style(m.unit(0.09f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(media.artist, style = style(m.unit(0.08f), Palette.Gray), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.durationMs > 0) {
                Spacer(Modifier.height(m.dp(0.03f)))
                SeekBar(media, m.dp(0.025f), showTimes = false)
            }
            Spacer(Modifier.height(m.dp(0.03f)))
            MediaControls(media.playing, m.dp(0.13f))
        }
    }
}

@Composable
fun MediaControls(playing: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        CircleButton(Glyphs.Previous, size) { LiveEvents.previous() }
        CircleButton(if (playing) Glyphs.Pause else Glyphs.Play, size * 1.3f) { LiveEvents.playPause() }
        CircleButton(Glyphs.Next, size) { LiveEvents.next() }
    }
}

@Composable
fun CircleButton(icon: ImageVector, size: Dp, tint: Color = Color.White, background: Color = Color.Transparent, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize(0.62f)) }
}

// ------------------------------------------------------------- stopwatch (interactive)

private object StopwatchState {
    var startedAt by mutableLongStateOf(0L)
    var accumulated by mutableLongStateOf(0L)
    var running by mutableStateOf(false)
}

@Composable
private fun StopwatchWidget() {
    val s = StopwatchState
    // Tick every frame only while running; a stopped stopwatch costs nothing.
    var frame by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.running) {
        while (s.running) withFrameMillis { frame = System.currentTimeMillis() }
    }
    val elapsed = s.accumulated + if (s.running) frame - s.startedAt else 0L
    val cs = (elapsed / 10) % 100
    val sec = (elapsed / 1000) % 60
    val min = elapsed / 60_000
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(
                "%02d:%02d.%02d".format(min, sec, cs),
                style = style(m.unit(0.19f), weight = FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PillButton(stringResource(R.string.reset), Palette.CardHigh, Color.White, m.dp(0.2f)) {
                    s.running = false; s.accumulated = 0L
                }
                PillButton(
                    stringResource(if (s.running) R.string.stop else R.string.start),
                    if (s.running) Color(0xFF3A1714) else Color(0xFF0E3A1A),
                    if (s.running) Palette.Red else Palette.Green, m.dp(0.2f),
                ) {
                    val now = System.currentTimeMillis()
                    if (s.running) {
                        s.accumulated += now - s.startedAt; s.running = false
                    } else {
                        s.startedAt = now; s.running = true
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size * 1.9f, size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style(with(LocalDensity.current) { (size * 0.32f).toSp() }, fg))
    }
}

/**
 * Track progress that can be dragged or tapped to jump within the track. While held, the bar
 * thickens and follows the finger; the jump is sent when it's released.
 */
@Composable
fun SeekBar(m: io.github.dockside.service.MediaInfo, thickness: Dp, showTimes: Boolean = true) {
    val tick by rememberTicker(250)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // After a seek, hold the bar where the finger left it until the player reports the new
    // position; otherwise it snaps back to the old spot for a moment and then jumps.
    var pending by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val live = (tick.let { m.positionNow() }.toFloat() / m.durationMs).coerceIn(0f, 1f)
    LaunchedEffect(tick, pending) {
        val (target, at) = pending ?: return@LaunchedEffect
        val arrived = abs(live - target) < 0.02f
        val gaveUp = android.os.SystemClock.elapsedRealtime() - at > 2500
        if (arrived || gaveUp) pending = null
    }
    val fraction = dragFraction ?: pending?.first ?: live
    fun seek(f: Float) {
        pending = f to android.os.SystemClock.elapsedRealtime()
        LiveEvents.seekTo((f * m.durationMs).toLong())
    }
    val grow by androidx.compose.animation.core.animateFloatAsState(if (dragFraction != null) 1.8f else 1f, label = "grow")
    Column {
        Box(
            Modifier.fillMaxWidth().height(thickness * 4.5f)
                .then(
                    if (!m.canSeek) Modifier else Modifier.pointerInput(m.durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                dragFraction?.let(::seek)
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        ) { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }.pointerInput(m.durationMs) {
                        detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
                    }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(thickness * grow).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
        }
        if (showTimes) {
            val pos = (fraction * m.durationMs).toLong()
            Row(Modifier.fillMaxWidth()) {
                val small = TextStyle(color = Color.White.copy(alpha = 0.6f), fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(formatTrackTime(pos), style = small)
                Spacer(Modifier.weight(1f))
                Text("-" + formatTrackTime(m.durationMs - pos), style = small)
            }
        }
    }
}

fun formatTrackTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
