package io.github.dockside.standby

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import io.github.dockside.data.CalEvent
import io.github.dockside.data.ClockStyle
import io.github.dockside.data.HourWeather
import io.github.dockside.data.TempUnit
import io.github.dockside.data.Weather
import io.github.dockside.service.MediaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Filming mode": Dockside plays through its highlights by itself (clock faces, page turns, the
 * player, the alarm) so a video can be recorded without touching the screen. Missing weather or
 * calendar data is filled in with sample data so no frame shows an empty placeholder.
 */
object Showcase {
    private val _clockStyle = MutableStateFlow<ClockStyle?>(null)

    /** The face the clock page should turn to; null outside filming mode. */
    val clockStyle: StateFlow<ClockStyle?> = _clockStyle.asStateFlow()

    internal fun showClock(style: ClockStyle?) {
        _clockStyle.value = style
    }

    /** The faces shown, in order, and how long each stays. */
    val faces = listOf(
        ClockStyle.FLOAT to 4500L, ClockStyle.GRADIENT to 3000L, ClockStyle.TALL to 3000L,
        ClockStyle.WORLD to 3000L, ClockStyle.LINES to 3000L, ClockStyle.SOLAR to 3000L,
    )

    fun fillIn(data: StandbyData): StandbyData {
        val now = System.currentTimeMillis()
        return data.copy(
            weather = data.weather ?: Weather(
                place = "Lisbon", temp = 18.4, code = 2, isDay = true, high = 21.0, low = 13.0,
                hourly = listOf(
                    HourWeather(15, 19.0, 1, true), HourWeather(16, 19.0, 2, true), HourWeather(17, 18.0, 3, true),
                    HourWeather(18, 16.0, 61, true), HourWeather(19, 15.0, 2, false),
                ),
                unit = TempUnit.CELSIUS, lat = 38.7, lon = -9.1,
            ),
            events = data.events.ifEmpty {
                listOf(
                    CalEvent("Design review", now + 40 * 60_000, now + 100 * 60_000, false, 0xFFFF9F0A.toInt(), null),
                    CalEvent("Dinner with Sam", now + 5 * 3600_000, now + 7 * 3600_000, false, 0xFF32D158.toInt(), null),
                )
            },
            calendarAllowed = true,
        )
    }

    /** A track with a drawn cover (warm blocks on plum), for the player part of the show. */
    fun demoTrack(): MediaInfo {
        val art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xFF2B1E3A.toInt(), 0xFF5A2D4A.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 300f, 300f, p)
            p.shader = null
            p.color = 0xFFF2CC8F.toInt(); c.drawCircle(200f, 105f, 52f, p)
            p.color = 0xFFE07A5F.toInt(); c.drawRect(40f, 150f, 150f, 300f, p)
            p.color = 0xFF81B29A.toInt(); c.drawRect(165f, 190f, 260f, 300f, p)
        }
        return MediaInfo(
            "io.github.dockside.demo", "Music", "Night Drive", "The Midnight Orchestra", art, true,
            214_000, 61_000, android.os.SystemClock.elapsedRealtime(), 1f,
        )
    }
}
