package io.github.dockside.standby

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.dockside.R
import io.github.dockside.data.Album
import io.github.dockside.data.AppSettings
import io.github.dockside.data.BatteryInfo
import io.github.dockside.data.CalEvent
import io.github.dockside.data.CalendarRepository
import io.github.dockside.data.PhotoRepository
import io.github.dockside.data.Weather
import io.github.dockside.data.WeatherRepository
import io.github.dockside.data.batteryFlow
import io.github.dockside.data.currentBattery
import io.github.dockside.data.nextAlarmTime
import io.github.dockside.service.LiveEvents
import io.github.dockside.service.MediaInfo
import io.github.dockside.ui.clocks.ClockInfo
import io.github.dockside.ui.clocks.WorldCities
import io.github.dockside.ui.common.TimeText
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Immutable
data class StandbyData(
    val weather: Weather?,
    val events: List<CalEvent>,
    val calendarAllowed: Boolean,
    val battery: BatteryInfo,
    val nextAlarm: Long?,
    val albums: List<Album>,
    val media: MediaInfo?,
    val h24: Boolean,
) {
    fun temperature(w: Weather? = weather) = w?.let { "${it.temp.roundToInt()}°" }
}

@Composable
fun rememberStandbyData(settings: AppSettings): StandbyData {
    val context = LocalContext.current
    val weather by produceState(WeatherRepository.cachedWeather(), settings.manualLocation, settings.tempUnit) {
        while (true) {
            value = WeatherRepository.load(context, settings)
            delay(if (value == null) 60_000 else 10 * 60_000)
        }
    }
    val events by produceState(emptyList<CalEvent>()) {
        while (true) {
            value = CalendarRepository.upcoming(context)
            delay(5 * 60_000)
        }
    }
    val battery by remember { batteryFlow(context) }.collectAsState(currentBattery(context))
    val alarm by produceState(nextAlarmTime(context)) {
        while (true) {
            value = nextAlarmTime(context)
            delay(60_000)
        }
    }
    val featured = stringResource(R.string.featured)
    val albums by produceState(emptyList<Album>(), settings.photoUris) {
        value = PhotoRepository.albums(context, settings, featured)
    }
    val media by LiveEvents.media.collectAsState()
    val h24 = rememberIs24h(context)
    return StandbyData(
        weather = weather,
        events = events,
        calendarAllowed = CalendarRepository.hasPermission(context),
        battery = battery,
        nextAlarm = alarm,
        albums = albums,
        media = media,
        h24 = h24,
    )
}

@Composable
private fun rememberIs24h(context: Context): Boolean {
    val state: State<Boolean> = produceState(TimeText.is24h(context)) {
        while (true) {
            delay(30_000)
            value = TimeText.is24h(context)
        }
    }
    return state.value
}

fun StandbyData.clockInfo(settings: AppSettings, context: Context): ClockInfo = ClockInfo(
    h24 = h24,
    temperature = temperature(),
    alarm = nextAlarm?.takeIf { it - System.currentTimeMillis() < 24 * 3600_000L }?.let { TimeText.shortTime(it, context) },
    lat = weather?.lat ?: settings.manualLocation?.lat,
    lon = weather?.lon ?: settings.manualLocation?.lon,
    placeName = weather?.place?.takeIf { it.isNotBlank() } ?: settings.manualLocation?.name,
    worldCities = settings.worldCities.mapNotNull { WorldCities.byZone(it) },
)
