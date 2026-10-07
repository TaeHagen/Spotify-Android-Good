package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.connect.LocalConnectDevice
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.ui.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class LocalDevicesUiState(
    val devices: List<LocalConnectDevice> = emptyList(),
    val discovering: Boolean = false,
    val connectingId: String? = null,
)

/** Results for one open devices sheet ([sheet], see [DevicesEvent]); other sheets ignore them. */
internal sealed interface LocalConnectEvent {
    val sheet: String

    data class Connected(override val sheet: String) : LocalConnectEvent
    data class Failed(override val sheet: String, val deviceName: String, val network: Boolean) : LocalConnectEvent
}

internal class LocalDevicesViewModel(graph: AppGraph) : ViewModel() {
    private val discovery = graph.localDiscovery
    private val devicesRepository = graph.devices
    private val loggedIn = graph.engine.isLoggedIn
    private val connecting = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<LocalConnectEvent>(Channel.BUFFERED)
    /** The sheet whose results [discovery] currently holds. */
    private var resultsSheet: String? = null

    val events: Flow<LocalConnectEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<LocalDevicesUiState> = combine(
        discovery.devices,
        devicesRepository.devices,
        discovery.discovering,
        connecting,
    ) { found, cluster, discovering, connectingId ->
        val clusterIds = cluster.devices.map { it.id }.toSet()
        LocalDevicesUiState(
            devices = found.filter { it.deviceId !in clusterIds },
            discovering = discovering,
            connectingId = connectingId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDevicesUiState())

    /**
     * Browses while [sheet] is open and STARTED. A new sheet starts from scratch; the same sheet
     * coming back (background, rotation) keeps what it found.
     */
    fun startDiscovery(sheet: String) {
        if (sheet != resultsSheet) {
            discovery.clear()
            resultsSheet = sheet
        }
        if (loggedIn.value) discovery.start()
    }

    /** The sheet stopped or went away: browsing (and the multicast lock) ends, results stay. */
    fun stopDiscovery() = discovery.pause()

    /**
     * Logs the device into the account and transfers playback to it; the result goes to [sheet]
     * only (no UI callback is held here: the sheet may be gone by then).
     */
    fun connect(device: LocalConnectDevice, sheet: String) {
        if (connecting.value != null) return
        viewModelScope.launch {
            connecting.value = device.deviceId
            try {
                val deviceId = discovery.login(device)
                devicesRepository.transferTo(deviceId)
                eventChannel.trySend(LocalConnectEvent.Connected(sheet))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NativeException) {
                eventChannel.trySend(LocalConnectEvent.Failed(sheet, device.name, e.isNetwork))
            } catch (e: Exception) {
                eventChannel.trySend(LocalConnectEvent.Failed(sheet, device.name, network = false))
            } finally {
                connecting.value = null
            }
        }
    }

    override fun onCleared() {
        discovery.stop()
    }
}

/**
 * The local-network section's state for one devices sheet. Created by [rememberLocalDevices] at
 * sheet level, so it lives exactly as long as the sheet, not as long as a list row.
 */
@Stable
internal class LocalDevicesHolder(
    private val stateValue: State<LocalDevicesUiState>,
    private val errorValue: State<String?>,
    private val onConnect: (LocalConnectDevice) -> Unit,
) {
    val state: LocalDevicesUiState get() = stateValue.value

    /** The last login failure of this sheet, if any. */
    val error: String? get() = errorValue.value

    fun connect(device: LocalConnectDevice) = onConnect(device)
}

/**
 * Drives "Other devices on your network" for the devices sheet [sheet]. Call it from the sheet
 * itself (not from a LazyColumn item: those are disposed when scrolled away). Discovery and the
 * Wi-Fi multicast lock run while this is in composition and the lifecycle is STARTED; results
 * and login outcomes for [sheet] are collected here, so none is lost while the row is off screen.
 * [onConnected] closes the sheet after a successful login + transfer.
 */
@Composable
internal fun rememberLocalDevices(sheet: String, onConnected: () -> Unit): LocalDevicesHolder {
    val viewModel = appViewModel { graph -> LocalDevicesViewModel(graph) }
    val state = viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val error = remember(sheet) { mutableStateOf<String?>(null) }
    val currentOnConnected by rememberUpdatedState(onConnected)

    LifecycleStartEffect(viewModel, sheet) {
        viewModel.startDiscovery(sheet)
        onStopOrDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(viewModel, sheet) {
        viewModel.events.filter { it.sheet == sheet }.collect { event ->
            when (event) {
                is LocalConnectEvent.Connected -> currentOnConnected()
                is LocalConnectEvent.Failed -> {
                    error.value = context.getString(
                        if (event.network) R.string.local_connect_login_failed_network else R.string.local_connect_login_failed,
                        event.deviceName,
                    )
                }
            }
        }
    }

    return remember(viewModel, sheet, state) {
        LocalDevicesHolder(state, error) { device ->
            error.value = null
            viewModel.connect(device, sheet)
        }
    }
}

/**
 * "Other devices on your network": Spotify Connect receivers found on the LAN that aren't in the
 * account yet (docs/ARCHITECTURE.md §8). Rendering only; [rememberLocalDevices] in the sheet owns
 * discovery and the results. Tapping a device logs it in, transfers playback and closes the sheet.
 */
@Composable
internal fun LocalDevicesSection(local: LocalDevicesHolder) {
    val state = local.state
    val error = local.error
    if (state.devices.isEmpty() && error == null && !state.discovering) return

    Column {
        Text(
            text = stringResource(R.string.local_connect_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
                .semantics { heading() },
        )
        if (state.devices.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.local_connect_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        } else {
            state.devices.forEach { device ->
                LocalDeviceRow(
                    device = device,
                    busy = state.connectingId == device.deviceId,
                    enabled = state.connectingId == null,
                    onClick = { local.connect(device) },
                )
            }
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.local_connect_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LocalDeviceRow(
    device: LocalConnectDevice,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val subtitle = if (busy) {
                stringResource(R.string.local_connect_connecting)
            } else {
                listOfNotNull(device.brand, device.model).joinToString(" ").ifBlank { null }
                    ?: stringResource(R.string.local_connect_subtitle)
            }
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(device.type.icon(device.isGroup), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        },
        trailingContent = if (busy) {
            { Box(Modifier.size(24.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
        } else {
            { Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}
