package io.github.dockside.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class DeviceKind { HEADPHONES, EARBUDS, SPEAKER, WATCH, OTHER }

/** A connected accessory; [level] is 0..100, or -1 while the device hasn't reported it. */
data class DeviceBattery(val address: String, val name: String, val level: Int, val kind: DeviceKind)

/**
 * Connected Bluetooth accessories (headphones, speakers, watches) and their battery levels.
 *
 * Connections come from the public profile APIs. Levels come from the system broadcast that
 * Bluetooth sends whenever a device reports its battery (headsets do on connect and on change);
 * the last value per device is kept for the life of the process.
 */
object BluetoothBatteries {
    // Public in the framework source but hidden from the SDK; stable since Android 8.1.
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    private const val LE_AUDIO = 22 // BluetoothProfile.LE_AUDIO, API 33
    private const val HEARING_AID = 21 // BluetoothProfile.HEARING_AID, API 29

    private val levels = ConcurrentHashMap<String, Int>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Fires when a device connects, disconnects or reports a new battery level. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Volatile private var listening = false

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (device != null && level in 0..100) levels[device.address] = level
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let { levels.remove(it.address) }
            }
            _changes.tryEmit(Unit)
        }
    }

    /** Starts collecting battery reports; safe to call repeatedly. */
    fun listen(context: Context) {
        if (listening || !hasPermission(context)) return
        listening = true
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { listening = false }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun read(context: Context): List<DeviceBattery> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        listen(context)
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return@withContext emptyList()
        val adapter = manager.adapter ?: return@withContext emptyList()
        if (!adapter.isEnabled) return@withContext emptyList()
        val profiles = buildList {
            add(BluetoothProfile.A2DP); add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= 29) add(HEARING_AID)
            if (Build.VERSION.SDK_INT >= 33) add(LE_AUDIO)
        }
        // audio connections: whatever plays sound is headphones / earbuds / a speaker
        val audio = LinkedHashMap<String, BluetoothDevice>()
        for (profile in profiles) connectedOn(context, adapter, profile).forEach { audio.putIfAbsent(it.address, it) }
        val result = audio.values.map { d -> d.toBattery(kindOf(d, audio = true)) }.toMutableList()
        // paired BLE accessories such as watches; headphones often expose a second BLE identity
        // ("Name-LE"), which must not show up twice
        val audioNames = result.map { it.name.lowercase().removeSuffix("-le").trim() }.filter { it.isNotEmpty() }
        runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
            .filter { it.bondState == BluetoothDevice.BOND_BONDED && it.address !in audio }
            .map { it.toBattery(kindOf(it, audio = false)) }
            .filter { b ->
                val base = b.name.lowercase().removeSuffix("-le").trim()
                b.kind == DeviceKind.WATCH && audioNames.none { it.isNotEmpty() && (base.startsWith(it) || it.startsWith(base)) }
            }
            .forEach(result::add)
        result
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedOn(context: Context, adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> =
        withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                val ok = runCatching {
                    adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val list = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                            runCatching { adapter.closeProfileProxy(p, proxy) }
                            if (cont.isActive) cont.resume(list)
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    }, profile)
                }.getOrDefault(false)
                if (!ok && cont.isActive) cont.resume(emptyList())
            }
        } ?: emptyList()

    /** The framework's own cached level, where the platform still lets apps read it. */
    private fun reportedLevel(d: BluetoothDevice): Int =
        runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1).takeIf { it in 0..100 } ?: -1

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBattery(kind: DeviceKind) = DeviceBattery(
        address = address,
        name = runCatching { name }.getOrNull().orEmpty(),
        level = levels[address] ?: reportedLevel(this),
        kind = kind,
    )

    /**
     * Picks the pictogram: common naming first (buds, watch, speaker…), then the device class.
     * Anything connected for audio is at least headphones; many headsets report no class.
     */
    @SuppressLint("MissingPermission")
    private fun kindOf(d: BluetoothDevice, audio: Boolean): DeviceKind {
        val name = runCatching { d.name }.getOrNull().orEmpty().lowercase()
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        val hint = when {
            listOf("buds", "pods", "earbud", "tws", "ear (", "free").any { it in name } -> DeviceKind.EARBUDS
            listOf("watch", "band", "fit").any { it in name } -> DeviceKind.WATCH
            listOf("speaker", "boom", "flip", "charge", "go ", "колонк", "soundlink", "partybox").any { it in name } -> DeviceKind.SPEAKER
            listOf("headphone", "наушник", "live", "tune", "wh-", "qc", "nc").any { it in name } -> DeviceKind.HEADPHONES
            else -> null
        }
        if (hint != null) return hint
        return when (cls?.majorDeviceClass) {
            BluetoothClass.Device.Major.WEARABLE -> DeviceKind.WATCH
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> DeviceKind.SPEAKER
                else -> DeviceKind.HEADPHONES
            }
            else -> when {
                audio -> DeviceKind.HEADPHONES
                cls?.majorDeviceClass == null && d.type == BluetoothDevice.DEVICE_TYPE_LE -> DeviceKind.WATCH
                else -> DeviceKind.OTHER
            }
        }
    }
}
