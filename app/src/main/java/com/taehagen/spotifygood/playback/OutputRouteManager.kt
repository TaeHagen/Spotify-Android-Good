package com.taehagen.spotifygood.playback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.AudioOutputInfo
import com.taehagen.spotifygood.model.AudioOutputType
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, USB, HDMI, CAR, HEARING_AID, OTHER }

/** A local audio output of this phone. */
data class AudioOutput(
    val id: Int,
    val name: String,
    val kind: OutputKind,
    /** Where audio is actually routed right now. */
    val isCurrent: Boolean,
    /** The user's explicit choice (null choice = system default). */
    val isPreferred: Boolean,
    val info: AudioDeviceInfo?,
)

/**
 * Local output routing (docs/ARCHITECTURE.md §9.5): lists outputs, tracks the routed device,
 * lets the user pick one (AudioTrack preferred device; temporary, see [OutputPick]), opens the
 * system output switcher and reports the current output to Spotify Connect. Listeners are
 * registered only between [start] and [stop] (the engine calls these while it runs).
 */
class OutputRouteManager(
    context: Context,
    @Suppress("unused") scope: CoroutineScope,
    private val audioSink: AudioSinkBridge,
    private val rpc: NativeRpc,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    private val _current = MutableStateFlow<AudioOutput?>(null)

    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()
    val current: StateFlow<AudioOutput?> = _current.asStateFlow()

    private val _bluetoothOutput = MutableStateFlow<Boolean?>(null)

    /**
     * A Bluetooth audio output (A2DP, LE Audio, a broadcast sink) is connected, so a headset or car
     * may read the media session ([RemotePlayback.readsPaused]); null while not watched (between
     * [stop] and [start]).
     */
    val bluetoothOutput: StateFlow<Boolean?> = _bluetoothOutput.asStateFlow()

    @Volatile private var started = false
    private val pick = OutputPick()
    @Volatile private var routedDevice: AudioDeviceInfo? = null
    private var lastReported: AudioOutputInfo? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // A new external output (headphones, a car kit) takes over from a pick, like the
            // system switcher. The initial event lists every current device: not new.
            if (pick.onAdded(addedDevices.filter { it.isSink }.map { it.id to kindOf(it.type) })) {
                audioSink.setPreferredDevice(null)
            }
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // The chosen output went away: follow the system route again.
            if (pick.onRemoved(removedDevices.mapTo(HashSet()) { it.id })) audioSink.setPreferredDevice(null)
            refresh()
        }
    }

    private val routingListener: (AudioDeviceInfo?) -> Unit = { device ->
        routedDevice = device
        refresh()
    }

    fun start() {
        if (started) return
        started = true
        lastReported = null
        routedDevice = audioSink.routedDevice()
        audioSink.addRoutingListener(routingListener)
        // Delivers an initial onAudioDevicesAdded with all current devices.
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        refresh()
    }

    fun stop() {
        // A pick does not outlive the session (engine stop, logout): the next one follows the
        // system route, whatever was connected meanwhile.
        if (pick.clear()) {
            audioSink.setPreferredDevice(null)
            refresh()
        }
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioSink.removeRoutingListener(routingListener)
        lastReported = null
        _bluetoothOutput.value = null
    }

    /** null = follow the system default route. */
    fun select(output: AudioOutput?) {
        val info = output?.info?.takeIf { it.type != AudioDeviceInfo.TYPE_UNKNOWN }
        val present = if (info == null) {
            emptySet()
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.mapTo(HashSet()) { it.id }
        }
        pick.pick(info?.id, present)
        audioSink.setPreferredDevice(info)
        // The route change listener confirms the actual result; reflect the choice meanwhile.
        refresh()
    }

    /** Opens the system media output switcher (Cast, BT pairing, etc.); returns false if unsupported. */
    fun showSystemOutputSwitcher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val shown = runCatching { SystemOutputSwitcherDialogController.showDialog(context) }
                .onFailure { Log.w(TAG, "Output switcher failed", it) }
                .getOrDefault(false)
            if (shown) return true
        }
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::refresh)
            return
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && kindOf(it.type) != null }
        _bluetoothOutput.value = if (started) devices.any { RemotePlayback.isBluetoothMediaOutput(it.type) } else null
        val unique = dedupe(devices)
        val routed = routedDevice?.takeIf { r -> unique.any { matches(it, r) } }
            ?: fallbackRoute(unique)
        val preferred = pick.preferredId
        val list = unique
            .map { info ->
                AudioOutput(
                    id = info.id,
                    name = nameOf(info),
                    kind = kindOf(info.type) ?: OutputKind.OTHER,
                    isCurrent = routed != null && matches(info, routed),
                    isPreferred = preferred != null && info.id == preferred,
                    info = info,
                )
            }
            .sortedWith(compareBy<AudioOutput> { if (it.kind == OutputKind.SPEAKER) 0 else 1 }.thenBy { it.name })
        _outputs.value = list
        val current = list.firstOrNull { it.isCurrent }
        _current.value = current
        report(current)
    }

    /** Best guess of the media route when no AudioTrack exists yet (routedDevice() is null). */
    private fun fallbackRoute(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            runCatching { audioManager.getAudioDevicesForAttributes(attrs) }.getOrNull()
                ?.firstOrNull { r -> devices.any { matches(it, r) } }
                ?.let { return it }
        }
        pick.preferredId?.let { id -> devices.firstOrNull { it.id == id }?.let { return it } }
        return devices.minByOrNull { routePriority(it.type) }
    }

    private fun report(current: AudioOutput?) {
        if (!started) return
        val info = current?.let {
            AudioOutputInfo(
                type = when (it.kind) {
                    OutputKind.SPEAKER -> AudioOutputType.SPEAKER
                    OutputKind.BLUETOOTH, OutputKind.HEARING_AID -> AudioOutputType.BLUETOOTH
                    OutputKind.WIRED, OutputKind.USB, OutputKind.HDMI -> AudioOutputType.LINE_OUT
                    OutputKind.CAR -> AudioOutputType.CAR
                    OutputKind.OTHER -> AudioOutputType.UNKNOWN
                },
                name = it.name,
            )
        } ?: AudioOutputInfo(AudioOutputType.UNKNOWN, null)
        if (info == lastReported) return
        lastReported = info
        rpc.fire(
            "player.setAudioOutput",
            buildJsonObject {
                put("type", wireType(info.type))
                info.name?.let { put("name", it) }
            },
        )
    }

    private fun dedupe(devices: List<AudioDeviceInfo>): List<AudioDeviceInfo> {
        // A headset can show up as several sinks (e.g. BLE headset + speaker, two USB endpoints).
        val byKey = LinkedHashMap<String, AudioDeviceInfo>()
        devices.sortedBy { typeRank(it.type) }.forEach { info ->
            val kind = kindOf(info.type) ?: return@forEach
            val identity = if (Build.VERSION.SDK_INT >= 28) info.address.orEmpty() else ""
            val key = when (kind) {
                OutputKind.SPEAKER -> "speaker"
                else -> "$kind|${identity.ifEmpty { info.productName?.toString().orEmpty() }}"
            }
            byKey.putIfAbsent(key, info)
        }
        return byKey.values.toList()
    }

    private fun matches(info: AudioDeviceInfo, routed: AudioDeviceInfo): Boolean {
        if (info.id == routed.id) return true
        val kind = kindOf(info.type) ?: return false
        if (kind != kindOf(routed.type)) return false
        if (kind == OutputKind.SPEAKER) return true
        val a = if (Build.VERSION.SDK_INT >= 28) info.address.orEmpty() else ""
        val b = if (Build.VERSION.SDK_INT >= 28) routed.address.orEmpty() else ""
        return a.isNotEmpty() && a == b
    }

    private fun nameOf(info: AudioDeviceInfo): String {
        val kind = kindOf(info.type) ?: OutputKind.OTHER
        val product = info.productName?.toString()?.trim().orEmpty()
        // Built-in and analog outputs report the phone model as product name; use friendly names.
        val useProduct = product.isNotEmpty() && product != Build.MODEL &&
            kind != OutputKind.SPEAKER && kind != OutputKind.WIRED
        if (useProduct) return product
        val res = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.playback_output_phone
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.playback_output_wired
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> R.string.playback_output_line
            AudioDeviceInfo.TYPE_DOCK -> R.string.playback_output_dock
            AudioDeviceInfo.TYPE_HDMI -> R.string.playback_output_hdmi
            AudioDeviceInfo.TYPE_BUS -> R.string.playback_output_car
            else -> when (kind) {
                OutputKind.BLUETOOTH -> R.string.playback_output_bluetooth
                OutputKind.USB -> R.string.playback_output_usb
                OutputKind.HEARING_AID -> R.string.playback_output_hearing_aid
                else -> R.string.playback_output_other
            }
        }
        return appContext.getString(res)
    }

    private companion object {
        const val TAG = "OutputRoutes"

        // API 31 / 33 device types (compile-time constants; only reported by devices that know them).
        const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET
        const val TYPE_BLE_SPEAKER = AudioDeviceInfo.TYPE_BLE_SPEAKER
        const val TYPE_BLE_BROADCAST = AudioDeviceInfo.TYPE_BLE_BROADCAST

        fun kindOf(type: Int): OutputKind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> OutputKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputKind.BLUETOOTH
            TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> OutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.HEARING_AID
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                OutputKind.USB
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> OutputKind.HDMI
            AudioDeviceInfo.TYPE_DOCK -> OutputKind.OTHER
            AudioDeviceInfo.TYPE_BUS -> OutputKind.CAR
            else -> null
        }

        /** Preferred device type within a duplicate group (lower wins). */
        fun typeRank(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET -> 0
            TYPE_BLE_SPEAKER -> 1
            AudioDeviceInfo.TYPE_USB_HEADSET -> 0
            AudioDeviceInfo.TYPE_USB_DEVICE -> 1
            else -> 2
        }

        /** Which output the system most likely uses for media when we cannot ask the track. */
        fun routePriority(type: Int): Int = when (kindOf(type)) {
            OutputKind.BLUETOOTH, OutputKind.HEARING_AID -> 0
            OutputKind.USB -> 1
            OutputKind.WIRED -> 2
            OutputKind.CAR -> 3
            OutputKind.HDMI -> 4
            OutputKind.OTHER -> 5
            OutputKind.SPEAKER -> 6
            null -> 7
        }

        fun wireType(type: AudioOutputType): String = when (type) {
            AudioOutputType.SPEAKER -> "speaker"
            AudioOutputType.BLUETOOTH -> "bluetooth"
            AudioOutputType.LINE_OUT -> "line_out"
            AudioOutputType.AIRPLAY -> "unknown"
            AudioOutputType.CAR -> "car"
            AudioOutputType.UNKNOWN -> "unknown"
        }
    }
}
