package com.taehagen.spotifygood.ui.screens.player

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.AudioOutput
import com.taehagen.spotifygood.playback.OutputKind
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.SessionMessenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Immutable
internal data class DevicesUiState(
    val devices: DeviceList = DeviceList(),
    val outputs: List<AudioOutput> = emptyList(),
    val snapshot: PlaybackSnapshot = PlaybackSnapshot.EMPTY,
    val transferringId: String? = null,
    val refreshing: Boolean = false,
    /** The device the next play goes to (picked while nothing played anywhere), if any. */
    val pendingTargetId: String? = null,
) {
    val thisDevice: ConnectDevice? get() = devices.devices.firstOrNull { it.isThisDevice }
    val thisDeviceId: String? get() = devices.thisDeviceId ?: thisDevice?.id

    /** The Connect device playing when it is not this phone ([remoteActiveDevice]). */
    val remoteActive: ConnectDevice? get() = remoteActiveDevice(devices, snapshot)

    val currentOutput: AudioOutput? get() = outputs.firstOrNull { it.isCurrent }
    val hasPreferredOutput: Boolean get() = outputs.any { it.isPreferred }
    val otherDevices: List<ConnectDevice>
        get() = devices.devices.filter { !it.isThisDevice && it.id != thisDeviceId }.distinctBy { it.id }
    val distinctOutputs: List<AudioOutput> get() = outputs.distinctBy { it.id }
}

/**
 * The device presented as the current one when it is not this phone, or null when this phone is.
 *
 * The playback snapshot wins over the cluster: while it shows playback on this phone (playing or
 * paused, `source == local`, also the offline queue), the cluster's active device is not the
 * current one. The cluster can keep naming a paused speaker as the account's active device after
 * "This phone" kept the offline queue playing here (docs/ARCHITECTURE.md §6.2 `connect.transfer`).
 * Otherwise the cluster's active device (not this phone), else a remote snapshot's device.
 */
internal fun remoteActiveDevice(devices: DeviceList, snapshot: PlaybackSnapshot): ConnectDevice? {
    if (snapshot.source == PlaybackSource.LOCAL && snapshot.isActive) return null
    val thisDeviceId = devices.thisDeviceId ?: devices.devices.firstOrNull { it.isThisDevice }?.id
    val listed = devices.activeDevice?.takeIf { !it.isThisDevice && it.id != thisDeviceId }
    if (listed != null) return listed
    val ref = snapshot.activeDevice ?: return null
    if (snapshot.source != PlaybackSource.REMOTE) return null
    return devices.devices.firstOrNull { it.id == ref.id } ?: ConnectDevice(id = ref.id, name = ref.name, type = ref.type, isActive = true)
}

/**
 * Results for one open devices sheet. The ViewModels outlive the sheet (main-session scope), so a
 * transfer finishing after its sheet was dismissed must not act on the next one: every event names
 * the [sheet] instance that asked ([DevicesSheetContent]) and other sheets ignore it.
 */
internal sealed interface DevicesEvent {
    val sheet: String

    data class TransferFailed(override val sheet: String, val deviceName: String, val network: Boolean) : DevicesEvent
    data class TransferSucceeded(override val sheet: String) : DevicesEvent
    data class RefreshFailed(override val sheet: String) : DevicesEvent

    /**
     * Nothing is playing anywhere and there is no saved session to start (NOT_ACTIVE_DEVICE): the
     * device is fine, there is just nothing to move to it yet. [selected]: it became the pending
     * target, so the next play goes there.
     */
    data class NothingToPlay(
        override val sheet: String,
        val deviceName: String,
        val isThisDevice: Boolean,
        val selected: Boolean = false,
    ) : DevicesEvent
}

/** A sheet message with its arguments, resolved where it is shown. */
@Immutable
internal data class SheetMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

/** What the devices sheet says for [this] result; null for a transfer that worked (the sheet closes). */
internal fun DevicesEvent.message(): SheetMessage? = when (this) {
    is DevicesEvent.TransferSucceeded -> null
    is DevicesEvent.TransferFailed -> SheetMessage(
        if (network) R.string.player_devices_transfer_failed_network else R.string.player_devices_transfer_failed,
        listOf(deviceName),
    )
    is DevicesEvent.RefreshFailed -> SheetMessage(R.string.player_devices_refresh_failed)
    // A transfer only moves what is playing; with nothing playing (and no saved session) a play
    // would start on this phone, so say what does work.
    is DevicesEvent.NothingToPlay -> when {
        selected -> SheetMessage(R.string.player_devices_selected, listOf(deviceName))
        isThisDevice -> SheetMessage(R.string.player_devices_nothing_to_play_here)
        else -> SheetMessage(R.string.player_devices_nothing_to_play, listOf(deviceName))
    }
}

/**
 * The devices sheets on screen (by token). The ViewModels outlive them: a result for a sheet that
 * is gone (dismissed while a transfer or LAN login ran, or between the compositions of a
 * configuration change) is shown in the app's snackbar instead of being dropped.
 */
internal class ShownSheets {
    private val shown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun add(sheet: String) {
        shown += sheet
    }

    fun remove(sheet: String) {
        shown -= sheet
    }

    operator fun contains(sheet: String): Boolean = sheet in shown
}

/**
 * Counts the user's device choices (a transfer, a LAN device tapped, the pending target cleared),
 * so a slow LAN login can tell whether it is still the latest one before moving playback.
 */
internal object DevicePicks {
    private val seq = AtomicLong()

    /** Records a new choice; returns its number. */
    fun mark(): Long = seq.incrementAndGet()

    fun isLatest(pick: Long): Boolean = seq.get() == pick
}

/**
 * The result of a transfer to [deviceName] that failed with [error]; [selected]: the device is now
 * the pending target (`DevicesRepository.pendingTarget`).
 */
internal fun transferFailureEvent(
    sheet: String,
    deviceName: String,
    isThisDevice: Boolean,
    error: Exception,
    selected: Boolean = false,
): DevicesEvent = when {
    error is NativeException && error.code == NativeErrorCode.NOT_ACTIVE_DEVICE ->
        DevicesEvent.NothingToPlay(sheet, deviceName, isThisDevice, selected = selected && !isThisDevice)
    error is NativeException -> DevicesEvent.TransferFailed(sheet, deviceName, error.isNetwork)
    else -> DevicesEvent.TransferFailed(sheet, deviceName, network = false)
}

/** Name of the pending target [id] in [devices] (null without one, or when it isn't listed). */
internal fun pendingTargetName(id: String?, devices: DeviceList): String? =
    id?.let { pending -> devices.devices.firstOrNull { it.id == pending }?.name?.takeIf { it.isNotBlank() } }

internal class DevicesViewModel(graph: AppGraph) : ViewModel() {
    private val devicesRepository = graph.devices
    private val outputs = graph.outputs
    private val transferring = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val eventChannel = Channel<DevicesEvent>(Channel.BUFFERED)
    private val volumeThrottle = VolumeThrottle(viewModelScope) { graph.player.setVolume(it) }
    private val shownSheets = ShownSheets()
    private val messenger = SessionMessenger(graph.app)

    val events: Flow<DevicesEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<DevicesUiState> = combine(
        combine(devicesRepository.devices, devicesRepository.pendingTarget, ::Pair),
        outputs.outputs,
        graph.playback.snapshot,
        transferring,
        refreshing,
    ) { (devices, pendingTarget), outputList, snapshot, transferringId, isRefreshing ->
        DevicesUiState(devices, outputList, snapshot, transferringId, isRefreshing, pendingTarget)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DevicesUiState(
            devicesRepository.devices.value,
            outputs.outputs.value,
            graph.playback.snapshot.value,
            pendingTargetId = devicesRepository.pendingTarget.value,
        ),
    )

    /**
     * Refreshes the device list in the background (the engine may take ~3 s; the cached list stays
     * shown, with [DevicesUiState.refreshing]). Failures are only reported when the user asked.
     */
    fun refresh(sheet: String, userInitiated: Boolean) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                devicesRepository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (userInitiated) deliver(DevicesEvent.RefreshFailed(sheet))
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Moves playback; keeps running if [sheet] is dismissed meanwhile (only its result is dropped). */
    fun transferTo(deviceId: String, deviceName: String, sheet: String) {
        if (transferring.value != null) return
        DevicePicks.mark()
        viewModelScope.launch {
            transferring.value = deviceId
            try {
                devicesRepository.transferTo(deviceId)
                deliver(DevicesEvent.TransferSucceeded(sheet))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val isThisDevice = deviceId == state.value.thisDeviceId
                // The repository keeps a device picked with nothing to play as the next play's target.
                val selected = devicesRepository.pendingTarget.value == deviceId
                deliver(transferFailureEvent(sheet, deviceName, isThisDevice, e, selected))
            } finally {
                transferring.value = null
            }
        }
    }

    /** The sheet [sheet] is on screen (its results go to it) / went away (to the app's snackbar). */
    fun sheetShown(sheet: String) = shownSheets.add(sheet)

    fun sheetGone(sheet: String) = shownSheets.remove(sheet)

    private fun deliver(event: DevicesEvent) {
        if (event.sheet in shownSheets) {
            eventChannel.trySend(event)
        } else {
            event.message()?.let { messenger.post(it.res, *it.args.toTypedArray()) }
        }
    }

    fun selectOutput(output: AudioOutput?) = outputs.select(output)

    fun setVolume(volume: Int) = volumeThrottle.offer(volume)

    fun showSystemOutputSwitcher(context: Context): Boolean = outputs.showSystemOutputSwitcher(context)
}

@Composable
internal fun DevicesSheetContent(onDismiss: () -> Unit) {
    val viewModel = appViewModel { graph -> DevicesViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // This sheet instance (kept across configuration changes): results of a dismissed sheet's
    // transfer or refresh are for that sheet only and must not close or message this one.
    val sheetToken = rememberSaveable { UUID.randomUUID().toString() }
    // LAN discovery lives as long as this sheet (not the list row that shows it).
    val localDevices = rememberLocalDevices(sheetToken) { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    LaunchedEffect(Unit) { viewModel.refresh(sheetToken, userInitiated = false) }
    DisposableEffect(viewModel, sheetToken) {
        viewModel.sheetShown(sheetToken)
        onDispose { viewModel.sheetGone(sheetToken) }
    }
    LaunchedEffect(viewModel, sheetToken) {
        viewModel.events.filter { it.sheet == sheetToken }.collect { event ->
            when (event) {
                is DevicesEvent.TransferSucceeded -> scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                is DevicesEvent.TransferFailed, is DevicesEvent.RefreshFailed, is DevicesEvent.NothingToPlay ->
                    event.message()?.let { message ->
                        val text = context.getString(message.res, *message.args.toTypedArray())
                        scope.launch { snackbar.showSnackbar(text) }
                    }
            }
        }
    }

    PlayerSurfaceTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Box {
                DevicesList(
                    state = state,
                    localDevices = localDevices,
                    onRefresh = { viewModel.refresh(sheetToken, userInitiated = true) },
                    onTransfer = { id, name -> viewModel.transferTo(id, name, sheetToken) },
                    onSelectOutput = viewModel::selectOutput,
                    onVolumeChange = viewModel::setVolume,
                    onMoreDevices = {
                        if (!viewModel.showSystemOutputSwitcher(context)) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.player_devices_switcher_unavailable)) }
                        }
                    },
                )
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun DevicesList(
    state: DevicesUiState,
    localDevices: LocalDevicesHolder,
    onRefresh: () -> Unit,
    onTransfer: (id: String, name: String) -> Unit,
    onSelectOutput: (AudioOutput?) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onMoreDevices: () -> Unit,
) {
    val remote = state.remoteActive
    val thisPhone = stringResource(R.string.player_devices_this_phone)
    val others = state.otherDevices
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_devices_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (state.refreshing) {
                    val refreshingLabel = stringResource(R.string.player_devices_refreshing)
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = refreshingLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.player_devices_refresh))
                    }
                }
            }
        }
        item(key = "current") {
            CurrentDeviceCard(state = state, remote = remote, onVolumeChange = onVolumeChange)
        }
        item(key = "header:phone") { SheetSectionLabel(thisPhone) }
        // Also with a device picked for the next play: picking the phone takes the next play back.
        if (remote != null || state.pendingTargetId != null) {
            item(key = "phone") {
                val id = state.thisDeviceId
                DeviceListItem(
                    title = thisPhone,
                    subtitle = if (state.transferringId == id && id != null) {
                        stringResource(R.string.player_devices_connecting)
                    } else {
                        stringResource(R.string.player_devices_tap_to_play)
                    },
                    icon = Icons.Rounded.Smartphone,
                    highlighted = false,
                    busy = id != null && state.transferringId == id,
                    enabled = id != null && state.transferringId == null,
                    onClick = { if (id != null) onTransfer(id, thisPhone) },
                )
            }
        }
        item(key = "output:auto") {
            OutputListItem(
                title = stringResource(R.string.player_devices_automatic),
                subtitle = stringResource(R.string.player_devices_automatic_description),
                icon = Icons.Rounded.AutoMode,
                selected = !state.hasPreferredOutput,
                routed = false,
                onClick = { onSelectOutput(null) },
            )
        }
        items(state.distinctOutputs, key = { "output:${it.id}" }) { output ->
            OutputListItem(
                title = outputName(output),
                subtitle = when {
                    output.isPreferred && !output.isCurrent -> stringResource(R.string.player_output_selected)
                    output.isCurrent -> stringResource(R.string.player_devices_routed)
                    else -> null
                },
                icon = output.kind.icon(),
                selected = output.isCurrent,
                routed = output.isCurrent,
                onClick = { onSelectOutput(output) },
            )
        }
        item(key = "more") {
            DeviceListItem(
                title = stringResource(R.string.player_devices_more),
                subtitle = stringResource(R.string.player_devices_more_description),
                // The system output switcher: no Cast support (no Cast SDK), so no Cast icon either.
                icon = Icons.Rounded.SpeakerGroup,
                highlighted = false,
                busy = false,
                enabled = true,
                onClick = onMoreDevices,
            )
        }
        item(key = "header:connect") { SheetSectionLabel(stringResource(R.string.player_devices_connect)) }
        if (others.isEmpty()) {
            item(key = "connect:empty") {
                EmptyState(
                    title = stringResource(R.string.player_devices_none),
                    // Only devices signed in to the account are listed (no local network discovery).
                    message = stringResource(R.string.player_devices_tip_account),
                    icon = Icons.Rounded.Devices,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        } else {
            items(others, key = { "device:${it.id}" }) { device ->
                val active = device.id == remote?.id
                val busy = state.transferringId == device.id
                val pending = !active && device.id == state.pendingTargetId
                DeviceListItem(
                    title = device.name,
                    subtitle = when {
                        busy -> stringResource(R.string.player_devices_connecting)
                        active -> stringResource(R.string.player_devices_playing)
                        pending -> stringResource(R.string.player_devices_selected_subtitle)
                        !device.canPlay -> stringResource(R.string.player_devices_cannot_play)
                        device.isGroup -> stringResource(R.string.player_devices_group)
                        else -> listOfNotNull(device.brand, device.model).joinToString(" ").ifBlank { null }
                    },
                    icon = device.type.icon(device.isGroup),
                    highlighted = active || pending,
                    busy = busy,
                    pending = pending,
                    enabled = device.canPlay && !active && state.transferringId == null,
                    onClick = { onTransfer(device.id, device.name) },
                )
            }
        }
        // Spotify Connect receivers on the local network that aren't in the account yet (§8).
        item(key = "local") { LocalDevicesSection(localDevices, transferring = state.transferringId != null) }
        item(key = "hint") {
            Text(
                text = stringResource(R.string.player_devices_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun CurrentDeviceCard(state: DevicesUiState, remote: ConnectDevice?, onVolumeChange: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val snapshot = state.snapshot
    val name: String
    val subtitle: String?
    val icon = remote?.type?.icon(remote.isGroup) ?: (state.currentOutput?.kind?.icon() ?: Icons.Rounded.Smartphone)
    val volume: Int
    val supportsVolume: Boolean
    if (remote != null) {
        name = remote.name
        subtitle = stringResource(R.string.player_devices_playing)
        volume = if (snapshot.source == PlaybackSource.REMOTE) snapshot.volume else remote.volume
        supportsVolume = remote.supportsVolume
    } else {
        val output = state.currentOutput
        name = stringResource(R.string.player_devices_this_phone)
        subtitle = output?.takeIf { it.kind != OutputKind.SPEAKER }?.name
        volume = if (snapshot.source == PlaybackSource.LOCAL) snapshot.volume else state.thisDevice?.volume ?: snapshot.volume
        supportsVolume = true
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                text = stringResource(R.string.player_devices_current),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (snapshot.isPlaying) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                }
            }
            if (supportsVolume) {
                VolumeSlider(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun DeviceListItem(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Picked for the next play: a check instead of the playing indicator. */
    pending: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (highlighted) accent else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    color = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (highlighted) accent else MaterialTheme.colorScheme.onSurface) },
        trailingContent = when {
            busy -> {
                { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            pending -> {
                { Icon(Icons.Rounded.Check, contentDescription = null, tint = accent) }
            }
            highlighted -> {
                { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent) }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun OutputListItem(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    routed: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (routed) accent else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (routed) accent else MaterialTheme.colorScheme.onSurface) },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = accent) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun outputName(output: AudioOutput): String =
    if (output.kind == OutputKind.SPEAKER || output.name.isBlank()) stringResource(R.string.player_output_speaker) else output.name

/** Name of the device the next play goes to (see [pendingTargetName]), null without one. */
internal fun DevicesRepository.pendingTargetNameFlow(): Flow<String?> =
    combine(pendingTarget, devices, ::pendingTargetName).distinctUntilChanged()

/**
 * Small bar in the docked bottom stack while a device is picked for the next play (nothing plays
 * anywhere yet, so there is no mini player to show it): tapping opens the devices sheet, the close
 * button keeps the next play on this phone.
 */
@Composable
internal fun PendingDeviceBanner(deviceName: String, onClick: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.player_pending_device, deviceName),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.player_pending_device_cancel),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
