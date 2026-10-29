package io.github.dockside.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.dockside.BuildConfig
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.CalendarRepository
import io.github.dockside.data.DisplayOff
import io.github.dockside.data.ManualLocation
import io.github.dockside.data.NightMode
import io.github.dockside.data.ambientLightFlow
import io.github.dockside.data.PhotoRepository
import io.github.dockside.data.Place
import io.github.dockside.data.SettingsStore
import io.github.dockside.data.TempUnit
import io.github.dockside.data.WeatherRepository
import io.github.dockside.service.LiveEvents
import io.github.dockside.service.MonitorService
import io.github.dockside.standby.StandbyActivity
import io.github.dockside.ui.clocks.WorldCities
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MonitorService.start(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Orange, onPrimary = Color.Black,
                    background = Color.Black, surface = Palette.Card,
                    secondaryContainer = Palette.CardHigh, onSecondaryContainer = Color.White,
                )
            ) { SettingsScreen() }
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.settings.collectAsState()
    // bumped on resume so permission states refresh after visiting system settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            store.update { it.copy(photoUris = (it.photoUris + uris.map(Uri::toString)).distinct()) }
        }
    }
    fun openSettings(action: String, withPackage: Boolean = true) {
        val intent = Intent(action).apply { if (withPackage) data = Uri.parse("package:" + context.packageName) }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(action)) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                fontFamily = RoundedFamily, fontWeight = FontWeight.Black, fontSize = 38.sp, color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Section {
                SwitchRow(stringResource(R.string.app_name), stringResource(R.string.settings_enabled_desc), s.enabled) { on ->
                    store.update { it.copy(enabled = on) }
                    if (on) MonitorService.start(context) else MonitorService.stop(context)
                }
                Divider()
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(context, StandbyActivity::class.java)
                                .putExtra(StandbyActivity.EXTRA_PREVIEW, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text(stringResource(R.string.preview), fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Header(R.string.section_activation)
            Section {
                SwitchRow(stringResource(R.string.require_locked), stringResource(R.string.require_locked_desc), s.requireLocked) { on ->
                    store.update { it.copy(requireLocked = on) }
                }
                Divider()
                SwitchRow(stringResource(R.string.ignore_posture), stringResource(R.string.ignore_posture_desc), s.ignorePosture) { on ->
                    store.update { it.copy(ignorePosture = on) }
                }
                Divider()
                ActionRow(stringResource(R.string.screensaver), stringResource(R.string.screensaver_desc), stringResource(R.string.open)) {
                    openSettings(Settings.ACTION_DREAM_SETTINGS, withPackage = false)
                }
            }
        }
        item {
            Header(R.string.section_display)
            Section {
                Text(stringResource(R.string.display_off), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                listOf(
                    DisplayOff.AUTOMATIC to R.string.display_off_auto,
                    DisplayOff.AFTER_20_SECONDS to R.string.display_off_20,
                    DisplayOff.NEVER to R.string.display_off_never,
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { store.update { it.copy(displayOff = mode) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(s.displayOff == mode, onClick = { store.update { it.copy(displayOff = mode) } })
                        Column {
                            Text(stringResource(label), color = Color.White)
                            if (mode == DisplayOff.AUTOMATIC) Text(stringResource(R.string.display_off_auto_desc), color = Palette.Gray, fontSize = 13.sp)
                        }
                    }
                }
                Divider()
                Text(stringResource(R.string.night_mode), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.night_mode_desc), color = Palette.Gray, fontSize = 13.sp)
                listOf(
                    NightMode.LIGHT_SENSOR to R.string.night_sensor,
                    NightMode.SCHEDULE to R.string.night_schedule,
                    NightMode.OFF to R.string.night_off,
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { store.update { it.copy(nightMode = mode) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(s.nightMode == mode, onClick = { store.update { it.copy(nightMode = mode) } })
                        Column {
                            Text(stringResource(label), color = Color.White)
                            if (mode == NightMode.LIGHT_SENSOR) LightReading()
                        }
                    }
                }
                Divider()
                SwitchRow(stringResource(R.string.motion_to_wake), stringResource(R.string.motion_to_wake_desc), s.motionToWake) { on ->
                    store.update { it.copy(motionToWake = on) }
                }
                Divider()
                SwitchRow(stringResource(R.string.burn_in), stringResource(R.string.burn_in_desc), s.burnInGuard) { on ->
                    store.update { it.copy(burnInGuard = on) }
                }
            }
        }
        item {
            Header(R.string.section_notifications)
            Section {
                SwitchRow(stringResource(R.string.show_notifications), null, s.showNotifications) { on ->
                    store.update { it.copy(showNotifications = on) }
                }
                Divider()
                SwitchRow(stringResource(R.string.preview_on_tap), null, s.previewOnTap, enabled = s.showNotifications) { on ->
                    store.update { it.copy(previewOnTap = on) }
                }
            }
        }
        item {
            Header(R.string.section_content)
            Section {
                ActionRow(
                    stringResource(R.string.photos_pick),
                    if (s.photoUris.isEmpty()) stringResource(R.string.photos_none) else pluralStringResource(R.plurals.photos_count, s.photoUris.size, s.photoUris.size),
                    "+",
                ) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (s.photoUris.isNotEmpty()) {
                    TextButton(onClick = { store.update { it.copy(photoUris = emptyList()) } }) { Text(stringResource(R.string.photos_clear)) }
                }
                Divider()
                val libraryPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                PermissionRow(stringResource(R.string.photos_library), null, refresh.let { PhotoRepository.hasLibraryPermission(context) }) {
                    permissionLauncher.launch(libraryPermission)
                }
                Divider()
                LocationPicker(s, store)
                Divider()
                Text(stringResource(R.string.temp_unit), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TempUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = s.tempUnit == unit,
                            onClick = { store.update { it.copy(tempUnit = unit) } },
                            label = { Text(if (unit == TempUnit.CELSIUS) "°C" else "°F") },
                        )
                    }
                }
                Divider()
                Text(stringResource(R.string.world_cities), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorldCities.all.forEach { city ->
                        val selected = city.zone in s.worldCities
                        FilterChip(
                            selected = selected,
                            onClick = {
                                store.update {
                                    it.copy(worldCities = if (selected) it.worldCities - city.zone else it.worldCities + city.zone)
                                }
                            },
                            label = { Text(city.name) },
                        )
                    }
                }
            }
        }
        item {
            Header(R.string.section_permissions)
            Section {
                refresh // read so the rows below recompose after returning from system settings
                if (Build.VERSION.SDK_INT >= 33) {
                    PermissionRow(stringResource(R.string.perm_notifications), null, context.has(Manifest.permission.POST_NOTIFICATIONS)) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    Divider()
                }
                PermissionRow(stringResource(R.string.perm_fsi), stringResource(R.string.perm_fsi_desc), MonitorService.canUseFullScreenIntent(context)) {
                    if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                }
                Divider()
                PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_desc), Settings.canDrawOverlays(context)) {
                    openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                }
                Divider()
                val pm = context.getSystemService(PowerManager::class.java)
                PermissionRow(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_desc), pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                    openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                }
                Divider()
                PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_desc), LiveEvents.isListenerEnabled(context)) {
                    openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, withPackage = false)
                }
                Divider()
                PermissionRow(stringResource(R.string.perm_calendar), null, CalendarRepository.hasPermission(context)) {
                    permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
                Divider()
                PermissionRow(stringResource(R.string.perm_location), stringResource(R.string.perm_location_desc), context.has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                Divider()
                PermissionRow(stringResource(R.string.perm_audio), stringResource(R.string.perm_audio_desc), context.has(Manifest.permission.RECORD_AUDIO)) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    Divider()
                    PermissionRow(stringResource(R.string.perm_bluetooth), stringResource(R.string.perm_bluetooth_desc), context.has(Manifest.permission.BLUETOOTH_CONNECT)) {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Text(
                    stringResource(
                        R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_DATE,
                    ),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.about), color = Palette.Gray, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationPicker(s: AppSettings, store: SettingsStore) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Place>()) }
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.weather_location), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = s.manualLocation == null,
            onClick = { store.update { it.copy(manualLocation = null) } },
            label = { Text(stringResource(R.string.weather_use_device)) },
        )
        s.manualLocation?.let { loc ->
            FilterChip(selected = true, onClick = {}, label = { Text(loc.name) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_city)) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = { scope.launch { results = WeatherRepository.searchPlaces(query) } }) {
            Text(stringResource(R.string.search))
        }
    }
    results.forEach { place ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                store.update { it.copy(manualLocation = ManualLocation(place.name, place.lat, place.lon)) }
                results = emptyList(); query = ""
            }.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(place.name, color = Color.White)
            Text(place.detail, color = Palette.Gray, fontSize = 13.sp)
        }
    }
}

/** Live reading of the front light sensor, so it's clear whether the automatic mode can work. */
@Composable
private fun LightReading() {
    val context = LocalContext.current
    val lux by produceState<Float?>(null) {
        ambientLightFlow(context).collect { value = it }
    }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); waited = true }
    val text = when {
        lux != null -> stringResource(R.string.light_now, lux!!.roundToInt())
        waited -> stringResource(R.string.light_silent)
        else -> stringResource(R.string.light_waiting)
    }
    Text(text, color = if (lux == null && waited) Palette.Orange else Palette.Gray, fontSize = 13.sp)
}

@Composable
private fun Header(text: Int) {
    Text(
        stringResource(text).uppercase(), color = Palette.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).padding(horizontal = 16.dp, vertical = 6.dp)
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.CardHigh, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Palette.Gray, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Palette.Gray, fontSize = 13.sp) }
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String?, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Palette.Gray, fontSize = 13.sp) }
        }
        Spacer(Modifier.size(12.dp))
        FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String?, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Palette.Gray, fontSize = 13.sp) }
        }
        Spacer(Modifier.size(12.dp))
        if (granted) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF0E3A1A)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.granted), color = Palette.Green, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors()) { Text(stringResource(R.string.grant)) }
        }
    }
}

