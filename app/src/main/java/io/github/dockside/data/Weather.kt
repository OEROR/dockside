package io.github.dockside.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class HourWeather(val hour: Int, val temp: Double, val code: Int, val isDay: Boolean)

data class Weather(
    val place: String,
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val high: Double,
    val low: Double,
    val hourly: List<HourWeather>,
    val unit: TempUnit,
    val lat: Double,
    val lon: Double,
    val fetchedAt: Long = System.currentTimeMillis(),
)

enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** WMO weather interpretation codes used by Open-Meteo. */
fun skyFor(code: Int): Sky = when (code) {
    0, 1 -> Sky.CLEAR
    2 -> Sky.PARTLY_CLOUDY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    51, 53, 55, 56, 57 -> Sky.DRIZZLE
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
    71, 73, 75, 77, 85, 86 -> Sky.SNOW
    95, 96, 99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

object WeatherRepository {
    private const val REFRESH_MS = 30 * 60 * 1000L
    @Volatile private var cached: Weather? = null

    fun cachedWeather() = cached

    suspend fun load(context: Context, settings: AppSettings, force: Boolean = false): Weather? =
        withContext(Dispatchers.IO) {
            val c = cached
            if (!force && c != null && c.unit == settings.tempUnit &&
                System.currentTimeMillis() - c.fetchedAt < REFRESH_MS
            ) return@withContext c
            val loc = settings.manualLocation ?: deviceLocation(context) ?: return@withContext c
            runCatching { fetch(loc, settings.tempUnit) }.getOrNull()?.also { cached = it } ?: c
        }

    @SuppressLint("MissingPermission")
    private fun deviceLocation(context: Context): ManualLocation? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        @Suppress("DEPRECATION")
        val name = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: ""
        return ManualLocation(name, loc.latitude, loc.longitude)
    }

    private fun fetch(loc: ManualLocation, unit: TempUnit): Weather {
        val unitParam = if (unit == TempUnit.FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=2$unitParam"
        val json = JSONObject(httpGet(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val currentHour = current.getString("time").take(13) // yyyy-MM-ddTHH
        var start = (0 until times.length()).firstOrNull { times.getString(it).startsWith(currentHour) } ?: 0
        start += 1
        val hours = (start until minOf(start + 5, times.length())).map {
            HourWeather(
                hour = times.getString(it).substring(11, 13).toInt(),
                temp = hourly.getJSONArray("temperature_2m").getDouble(it),
                code = hourly.getJSONArray("weather_code").getInt(it),
                isDay = hourly.getJSONArray("is_day").getInt(it) == 1,
            )
        }
        return Weather(
            place = loc.name,
            temp = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
            high = daily.getJSONArray("temperature_2m_max").getDouble(0),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0),
            hourly = hours,
            unit = unit,
            lat = loc.lat,
            lon = loc.lon,
        )
    }

    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val lang = Locale.getDefault().language
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=$lang&name=" +
            URLEncoder.encode(query.trim(), "UTF-8")
        runCatching {
            val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                Place(
                    name = r.getString("name"),
                    detail = listOfNotNull(r.optString("admin1").ifEmpty { null }, r.optString("country").ifEmpty { null })
                        .joinToString(", "),
                    lat = r.getDouble("latitude"),
                    lon = r.getDouble("longitude"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Dockside/1.0 (open-source Android app)")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
