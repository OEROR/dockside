package io.github.dockside

import io.github.dockside.ui.photos.PhotosView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.dockside.ui.common.nightMatrix
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import io.github.dockside.data.AppSettings
import io.github.dockside.data.BatteryInfo
import io.github.dockside.data.CalEvent
import io.github.dockside.data.ClockStyle
import io.github.dockside.data.HourWeather
import io.github.dockside.data.TempUnit
import io.github.dockside.data.Weather
import io.github.dockside.data.WidgetKind
import io.github.dockside.service.IncomingNotification
import io.github.dockside.service.MediaInfo
import io.github.dockside.standby.NotificationCard
import io.github.dockside.standby.NowPlayingBubble
import io.github.dockside.standby.NowPlayingFull
import io.github.dockside.standby.StandbyData
import io.github.dockside.standby.clockInfo
import io.github.dockside.ui.clocks.ClockView
import io.github.dockside.ui.clocks.FullScreenClock
import io.github.dockside.ui.widgets.StackSide
import io.github.dockside.ui.widgets.WidgetContent
import io.github.dockside.ui.widgets.WidgetsView
import org.junit.Rule
import org.junit.Test

/** Renders every Dockside screen on the JVM; run `./gradlew recordPaparazziDebug` to refresh. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 2400, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        maxPercentDifference = 100.0,
    )

    private val now = System.currentTimeMillis()
    private val settings = AppSettings()
    private val data = StandbyData(
        weather = Weather(
            place = "Lisbon", temp = 18.4, code = 2, isDay = true, high = 21.0, low = 13.0,
            hourly = listOf(
                HourWeather(15, 19.0, 1, true), HourWeather(16, 19.0, 2, true), HourWeather(17, 18.0, 3, true),
                HourWeather(18, 16.0, 61, true), HourWeather(19, 15.0, 2, false),
            ),
            unit = TempUnit.CELSIUS, lat = 38.7, lon = -9.1,
        ),
        events = listOf(
            CalEvent("Design review", now + 40 * 60_000, now + 100 * 60_000, false, 0xFFFF9F0A.toInt(), null),
            CalEvent("Dinner with Sam", now + 5 * 3600_000, now + 7 * 3600_000, false, 0xFF32D158.toInt(), null),
            CalEvent("Flight to Berlin", now + 26 * 3600_000, now + 29 * 3600_000, false, 0xFF0A84FF.toInt(), null),
        ),
        calendarAllowed = true,
        battery = BatteryInfo(76, true, "ac"),
        nextAlarm = now + 9 * 3600_000,
        albums = emptyList(),
        media = MediaInfo("pkg", "Music", "Night Drive", "The Midnight Orchestra", null, true, 240_000, 92_000, 0, 0f),
        h24 = true,
    )

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
    }

    @Test fun clockDigital() = shot { FullScreenClock(ClockStyle.DIGITAL, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockAnalog() = shot { FullScreenClock(ClockStyle.ANALOG, 1, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockFloat() = shot { FullScreenClock(ClockStyle.FLOAT, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockSolar() = shot { FullScreenClock(ClockStyle.SOLAR, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockWorld() = shot { FullScreenClock(ClockStyle.WORLD, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockLines() = shot { FullScreenClock(ClockStyle.LINES, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockGradient() = shot { FullScreenClock(ClockStyle.GRADIENT, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockTall() = shot { FullScreenClock(ClockStyle.TALL, 0, data.clockInfo(settings, paparazzi.context)) }
    @Test fun clockEdit() = shot { ClockView(settings.copy(clockStyle = ClockStyle.DIGITAL), data.clockInfo(settings, paparazzi.context), true, {}, {}) }

    @Test fun widgetsView() = shot { WidgetsView(settings, data, null, {}, {}, {}) }
    @Test fun widgetsEdit() = shot { WidgetsView(settings, data, StackSide.RIGHT, {}, {}, {}) }

    private fun pair(a: WidgetKind, b: WidgetKind) = shot {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            WidgetContent(a, data, settings, Modifier.weight(1f).fillMaxHeight())
            WidgetContent(b, data, settings, Modifier.weight(1f).fillMaxHeight())
        }
    }

    @Test fun widgetsClockCalendar() = pair(WidgetKind.DIGITAL_CLOCK, WidgetKind.CALENDAR_MONTH)
    @Test fun widgetsWeatherUpNext() = pair(WidgetKind.WEATHER, WidgetKind.UP_NEXT)
    @Test fun widgetsWorldBattery() = pair(WidgetKind.WORLD_CLOCK, WidgetKind.BATTERY)
    @Test fun widgetsAlarmStopwatch() = pair(WidgetKind.ALARM, WidgetKind.STOPWATCH)
    @Test fun widgetsAnalogWeather() = pair(WidgetKind.ANALOG_CLOCK, WidgetKind.WEATHER)
    @Test fun widgetsHourly() = pair(WidgetKind.WEATHER_HOURLY, WidgetKind.CALENDAR_MONTH)
    @Test fun widgetsSquareCity() = pair(WidgetKind.SQUARE_CLOCK, WidgetKind.CITY_TIME)
    @Test fun widgetsDevicesWeather() = pair(WidgetKind.DEVICES, WidgetKind.WEATHER)
    @Test fun photosEmpty() = shot { PhotosView(settings, emptyList(), true, data.battery, false, {}, {}) }
    @Test fun widgetsNowPlayingDay() = pair(WidgetKind.NOW_PLAYING, WidgetKind.CALENDAR_DAY)

    /** Night Mode: the same colour matrix the app applies to its view layer. */
    @Test fun nightMode() = shot {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(nightMatrix(1f))) }
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    drawContent()
                    canvas.restore()
                }
            }
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                WidgetContent(WidgetKind.ANALOG_CLOCK, data, settings, Modifier.weight(1f).fillMaxHeight())
                WidgetContent(WidgetKind.CALENDAR_DAY, data, settings, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    @Test fun nowPlayingBubble() = shot {
        Box(Modifier.fillMaxSize()) {
            WidgetsView(settings, data, null, {}, {}, {})
            Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { NowPlayingBubble(data.media!!) {} }
        }
    }
    @Test fun deviceIcons() = shot { io.github.dockside.ui.widgets.DeviceIconsPreview() }
    @Test fun nowPlaying() = shot { NowPlayingFull(data.media!!) {} }

    @Test fun alarmRinging() = shot {
        io.github.dockside.ui.alarm.AlarmRinging(
            io.github.dockside.service.RingingClock("k", "clock", io.github.dockside.service.ClockKind.ALARM, "", emptyList()), data, animateIn = false,
        ) {}
    }
    @Test fun daySummary() = shot { io.github.dockside.ui.alarm.DaySummary(data, animateIn = false) {} }
    /** A drawn stand-in cover (warm sunset blocks) to show the blurred backdrop. */
    @Test fun nowPlayingCover() = shot {
        val art = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(0xFF3B2A3A.toInt())
            val paint = android.graphics.Paint()
            paint.color = 0xFFE07A5F.toInt(); c.drawRect(30f, 60f, 120f, 220f, paint)
            paint.color = 0xFFF2CC8F.toInt(); c.drawRect(130f, 40f, 210f, 160f, paint)
            paint.color = 0xFF81B29A.toInt(); c.drawCircle(170f, 200f, 30f, paint)
        }
        CompositionLocalProvider(io.github.dockside.ui.common.LocalMotion provides io.github.dockside.ui.common.Motion(backdrop = true)) {
            NowPlayingFull(data.media!!.copy(art = art, title = "Laidback Evening", artist = "Ambient Room")) {}
        }
    }
    @Test fun notification() = shot {
        NotificationCard(
            IncomingNotification("k", "pkg", "Messages", null, "Alex", "Are we still on for breakfast tomorrow?", 0, now, null),
            revealed = true,
        ) {}
    }

}
