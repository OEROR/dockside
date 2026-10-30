package io.github.dockside.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class DisplayOff { AUTOMATIC, AFTER_20_SECONDS, NEVER }

enum class ClockStyle { DIGITAL, ANALOG, FLOAT, SOLAR, WORLD, LINES, GRADIENT, TALL }

/** Starting colour of each face, as an index into its palette (see Palette.accents). */
fun defaultColor(style: ClockStyle) = when (style) {
    ClockStyle.ANALOG, ClockStyle.LINES -> 1 // orange
    ClockStyle.TALL -> 7 // blue
    else -> 0
}

enum class WidgetKind {
    ANALOG_CLOCK, DIGITAL_CLOCK, WORLD_CLOCK, CALENDAR_DAY, CALENDAR_MONTH, UP_NEXT,
    WEATHER, BATTERY, ALARM, PHOTO, NOW_PLAYING, STOPWATCH,
    SQUARE_CLOCK, CITY_TIME, DEVICES, WEATHER_HOURLY
}

enum class TempUnit { CELSIUS, FAHRENHEIT }

/** When the red night tint is used. */
enum class NightMode {
    /** Always full colour. */
    OFF,

    /** Red only when the front light sensor reports a dark room; never guessed from the clock. */
    LIGHT_SENSOR,

    /** Red from 23:00 to 6:00, for phones without a working light sensor. */
    SCHEDULE,
}

/** Page order of the three horizontally swiped Dockside views. */
object Views {
    const val WIDGETS = 0
    const val PHOTOS = 1
    const val CLOCK = 2
    const val COUNT = 3
}

data class StackConfig(
    val widgets: List<WidgetKind>,
    val index: Int = 0,
    val smartRotate: Boolean = true,
    val suggestions: Boolean = true,
)

data class ManualLocation(val name: String, val lat: Double, val lon: Double)

data class AppSettings(
    val enabled: Boolean = true,
    val requireLocked: Boolean = true,
    /** Start on charge + lock in any position, for phones whose motion sensor doesn't work. */
    val ignorePosture: Boolean = false,
    val displayOff: DisplayOff = DisplayOff.AUTOMATIC,
    val nightMode: NightMode = NightMode.LIGHT_SENSOR,
    val motionToWake: Boolean = true,
    /** Slowly shift everything on screen so nothing stays in one place for hours (OLED). */
    val burnInGuard: Boolean = true,
    val showNotifications: Boolean = true,
    val previewOnTap: Boolean = false,
    val clockStyle: ClockStyle = ClockStyle.DIGITAL,
    /** Index into the style's palette, per clock style. */
    val clockColors: Map<ClockStyle, Int> = emptyMap(),
    val left: StackConfig = StackConfig(
        listOf(WidgetKind.ANALOG_CLOCK, WidgetKind.DIGITAL_CLOCK, WidgetKind.WORLD_CLOCK, WidgetKind.BATTERY)
    ),
    val right: StackConfig = StackConfig(
        listOf(
            WidgetKind.CALENDAR_DAY, WidgetKind.WEATHER, WidgetKind.WEATHER_HOURLY, WidgetKind.UP_NEXT,
            WidgetKind.CALENDAR_MONTH, WidgetKind.ALARM, WidgetKind.PHOTO, WidgetKind.STOPWATCH,
        )
    ),
    val photoUris: List<String> = emptyList(),
    val hiddenAlbums: Set<String> = emptySet(),
    val photoAlbum: String? = null,
    /** Last visible view, remembered per charger type ("ac", "usb", "wireless", "dock"). */
    val chargerViews: Map<String, Int> = emptyMap(),
    val worldCities: List<String> = listOf("America/Los_Angeles", "America/New_York", "Europe/London", "Asia/Tokyo"),
    val manualLocation: ManualLocation? = null,
    val tempUnit: TempUnit = TempUnit.CELSIUS,
    val motion: MotionPrefs = MotionPrefs(),
    /** Show ringing alarms and timers, and running stopwatches, Dockside's way. */
    val alarmTakeover: Boolean = true,
) {
    fun colorIndex(style: ClockStyle) = clockColors[style] ?: defaultColor(style)

}

/**
 * "Super animations": one master switch plus a switch per effect, so slower phones (or people
 * who prefer a calmer screen) can turn any of them off.
 */
data class MotionPrefs(
    val enabled: Boolean = true,
    /** The player grows out of its bubble; Dockside fades in and out instead of cutting. */
    val screens: Boolean = true,
    /** Pages turn like cards while swiping between widgets, photos and clocks. */
    val pages: Boolean = true,
    /** The blurred cover behind the player drifts slowly. */
    val backdrop: Boolean = true,
    /** Digits and widgets settle with a little bounce. */
    val springs: Boolean = true,
    /** The clock digits float about all the time, clearly visible (off by default: calmer). */
    val floating: Boolean = false,
)

class SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dockside", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    val current get() = state.value

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(state.value)
        if (next == state.value) return
        state.value = next
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
    }

    private fun read(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return AppSettings()
        return runCatching { fromJson(JSONObject(raw)) }.getOrElse { AppSettings() }
    }

    companion object {
        private const val KEY = "settings_v1"

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext).also { instance = it }
        }

        private fun stackToJson(s: StackConfig) = JSONObject()
            .put("widgets", JSONArray(s.widgets.map { it.name }))
            .put("index", s.index)
            .put("smartRotate", s.smartRotate)
            .put("suggestions", s.suggestions)

        private fun stackFromJson(o: JSONObject?, fallback: StackConfig): StackConfig {
            if (o == null) return fallback
            val widgets = o.optJSONArray("widgets").strings().mapNotNull { enumOrNull<WidgetKind>(it) }
            return StackConfig(
                widgets = widgets,
                index = o.optInt("index", 0),
                smartRotate = o.optBoolean("smartRotate", true),
                suggestions = o.optBoolean("suggestions", true),
            )
        }

        fun toJson(s: AppSettings): JSONObject = JSONObject()
            .put("enabled", s.enabled)
            .put("requireLocked", s.requireLocked)
            .put("ignorePosture", s.ignorePosture)
            .put("displayOff", s.displayOff.name)
            .put("nightMode", s.nightMode.name)
            .put("motionToWake", s.motionToWake)
            .put("burnInGuard", s.burnInGuard)
            .put("showNotifications", s.showNotifications)
            .put("previewOnTap", s.previewOnTap)
            .put("clockStyle", s.clockStyle.name)
            .put("clockColors", JSONObject().apply { s.clockColors.forEach { (k, v) -> put(k.name, v) } })
            .put("left", stackToJson(s.left))
            .put("right", stackToJson(s.right))
            .put("photoUris", JSONArray(s.photoUris))
            .put("hiddenAlbums", JSONArray(s.hiddenAlbums.toList()))
            .put("photoAlbum", s.photoAlbum)
            .put("chargerViews", JSONObject().apply { s.chargerViews.forEach { (k, v) -> put(k, v) } })
            .put("worldCities", JSONArray(s.worldCities))
            .put("manualLocation", s.manualLocation?.let {
                JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)
            })
            .put("tempUnit", s.tempUnit.name)
            .put("alarmTakeover", s.alarmTakeover)
            .put("motion", JSONObject()
                .put("enabled", s.motion.enabled).put("screens", s.motion.screens).put("pages", s.motion.pages)
                .put("backdrop", s.motion.backdrop).put("springs", s.motion.springs).put("floating", s.motion.floating))

        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                enabled = o.optBoolean("enabled", d.enabled),
                requireLocked = o.optBoolean("requireLocked", d.requireLocked),
                ignorePosture = o.optBoolean("ignorePosture", d.ignorePosture),
                displayOff = enumOrNull<DisplayOff>(o.optString("displayOff")) ?: d.displayOff,
                nightMode = enumOrNull<NightMode>(o.optString("nightMode"))
                    // settings from 1.0/1.1 stored a switch: off stays off, on becomes the light sensor
                    ?: if (o.optString("nightMode") == "false") NightMode.OFF else d.nightMode,
                motionToWake = o.optBoolean("motionToWake", d.motionToWake),
                burnInGuard = o.optBoolean("burnInGuard", d.burnInGuard),
                showNotifications = o.optBoolean("showNotifications", d.showNotifications),
                previewOnTap = o.optBoolean("previewOnTap", d.previewOnTap),
                clockStyle = enumOrNull<ClockStyle>(o.optString("clockStyle")) ?: d.clockStyle,
                clockColors = o.optJSONObject("clockColors")?.let { c ->
                    c.keys().asSequence().mapNotNull { k -> enumOrNull<ClockStyle>(k)?.let { it to c.optInt(k) } }.toMap()
                } ?: d.clockColors,
                left = stackFromJson(o.optJSONObject("left"), d.left),
                right = stackFromJson(o.optJSONObject("right"), d.right),
                photoUris = o.optJSONArray("photoUris").strings(),
                hiddenAlbums = o.optJSONArray("hiddenAlbums").strings().toSet(),
                photoAlbum = o.optString("photoAlbum").takeIf { it.isNotEmpty() && !o.isNull("photoAlbum") },
                chargerViews = o.optJSONObject("chargerViews")?.let { c ->
                    c.keys().asSequence().associateWith { c.optInt(it) }
                } ?: d.chargerViews,
                worldCities = o.optJSONArray("worldCities")?.strings() ?: d.worldCities,
                manualLocation = o.optJSONObject("manualLocation")?.let {
                    ManualLocation(it.optString("name"), it.optDouble("lat"), it.optDouble("lon"))
                },
                tempUnit = enumOrNull<TempUnit>(o.optString("tempUnit")) ?: d.tempUnit,
                alarmTakeover = o.optBoolean("alarmTakeover", d.alarmTakeover),
                motion = o.optJSONObject("motion")?.let { m ->
                    val dm = d.motion
                    MotionPrefs(
                        enabled = m.optBoolean("enabled", dm.enabled), screens = m.optBoolean("screens", dm.screens),
                        pages = m.optBoolean("pages", dm.pages), backdrop = m.optBoolean("backdrop", dm.backdrop),
                        springs = m.optBoolean("springs", dm.springs), floating = m.optBoolean("floating", dm.floating),
                    )
                } ?: d.motion,
            )
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
