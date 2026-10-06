package com.taehagen.spotifygood.ui.screens.player

import android.content.Context
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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.AudioOutput
import com.taehagen.spotifygood.playback.OutputKind
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class DevicesUiState(
    val devices: DeviceList = DeviceList(),
    val outputs: List<AudioOutput> = emptyList(),
    val snapshot: PlaybackSnapshot = PlaybackSnapshot.EMPTY,
    val transferringId: String? = null,
    val refreshing: Boolean = false,
) {
    val thisDevice: ConnectDevice? get() = devices.devices.firstOrNull { it.isThisDevice }
    val thisDeviceId: String? get() = devices.thisDeviceId ?: thisDevice?.id

    /** The Connect device playing when it is not this phone. */
    val remoteActive: ConnectDevice?
        get() {
            val listed = devices.activeDevice?.takeIf { !it.isThisDevice && it.id != thisDeviceId }
            if (listed != null) return listed
            val ref = snapshot.activeDevice ?: return null
            if (snapshot.source != PlaybackSource.REMOTE) return null
            return devices.devices.firstOrNull { it.id == ref.id } ?: ConnectDevice(id = ref.id, name = ref.name, type = ref.type, isActive = true)
        }

    val currentOutput: AudioOutput? get() = outputs.firstOrNull { it.isCurrent }
    val hasPreferredOutput: Boolean get() = outputs.any { it.isPreferred }
    val otherDevices: List<ConnectDevice>
        get() = devices.devices.filter { !it.isThisDevice && it.id != thisDeviceId }.distinctBy { it.id }
    val distinctOutputs: List<AudioOutput> get() = outputs.distinctBy { it.id }
}

internal sealed interface DevicesEvent {
    data class TransferFailed(val deviceName: String, val network: Boolean) : DevicesEvent
    data object TransferSucceeded : DevicesEvent
    data object RefreshFailed : DevicesEvent
}

internal class DevicesViewModel(graph: AppGraph) : ViewModel() {
    private val devicesRepository = graph.devices
    private val outputs = graph.outputs
    private val transferring = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val eventChannel = Channel<DevicesEvent>(Channel.BUFFERED)
    private val volumeThrottle = VolumeThrottle(viewModelScope) { graph.player.setVolume(it) }

    val events: Flow<DevicesEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<DevicesUiState> = combine(
        devicesRepository.devices,
        outputs.outputs,
        graph.playback.snapshot,
        transferring,
        refreshing,
    ) { devices, outputList, snapshot, transferringId, isRefreshing ->
        DevicesUiState(devices, outputList, snapshot, transferringId, isRefreshing)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DevicesUiState(devicesRepository.devices.value, outputs.outputs.value, graph.playback.snapshot.value),
    )

    /** Refreshes the device list; failures are only reported when the user asked. */
    fun refresh(userInitiated: Boolean) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                devicesRepository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (userInitiated) eventChannel.trySend(DevicesEvent.RefreshFailed)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun transferTo(deviceId: String, deviceName: String) {
        if (transferring.value != null) return
        viewModelScope.launch {
            transferring.value = deviceId
            try {
                devicesRepository.transferTo(deviceId)
                eventChannel.trySend(DevicesEvent.TransferSucceeded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NativeException) {
                eventChannel.trySend(DevicesEvent.TransferFailed(deviceName, e.isNetwork))
            } catch (e: Exception) {
                eventChannel.trySend(DevicesEvent.TransferFailed(deviceName, network = false))
            } finally {
                transferring.value = null
            }
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

    LaunchedEffect(Unit) { viewModel.refresh(userInitiated = false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                DevicesEvent.TransferSucceeded -> scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                is DevicesEvent.TransferFailed -> {
                    val message = context.getString(
                        if (event.network) R.string.player_devices_transfer_failed_network else R.string.player_devices_transfer_failed,
                        event.deviceName,
                    )
                    scope.launch { snackbar.showSnackbar(message) }
                }
                DevicesEvent.RefreshFailed -> scope.launch { snackbar.showSnackbar(context.getString(R.string.player_devices_refresh_failed)) }
            }
        }
    }

    PlayerSurfaceTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Box {
                DevicesList(
                    state = state,
                    onRefresh = { viewModel.refresh(userInitiated = true) },
                    onTransfer = viewModel::transferTo,
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
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
        if (remote != null) {
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
                DeviceListItem(
                    title = device.name,
                    subtitle = when {
                        busy -> stringResource(R.string.player_devices_connecting)
                        active -> stringResource(R.string.player_devices_playing)
                        !device.canPlay -> stringResource(R.string.player_devices_cannot_play)
                        device.isGroup -> stringResource(R.string.player_devices_group)
                        else -> listOfNotNull(device.brand, device.model).joinToString(" ").ifBlank { null }
                    },
                    icon = device.type.icon(device.isGroup),
                    highlighted = active,
                    busy = busy,
                    enabled = device.canPlay && !active && state.transferringId == null,
                    onClick = { onTransfer(device.id, device.name) },
                )
            }
        }
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
