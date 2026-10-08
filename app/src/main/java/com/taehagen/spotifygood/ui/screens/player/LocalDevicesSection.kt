package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
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
import com.taehagen.spotifygood.connect.CastServices
import com.taehagen.spotifygood.connect.LocalConnectDevice
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.SessionMessenger
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class LocalDevicesUiState(
    val devices: List<LocalConnectDevice> = emptyList(),
    val discovering: Boolean = false,
    val connectingId: String? = null,
    /**
     * The session is ONLINE. Otherwise the section is hidden: there is no cluster to tell which
     * speakers are already in the account, and a LAN login needs an online session.
     */
    val online: Boolean = true,
)

/** Results for one open devices sheet ([sheet], see [DevicesEvent]); other sheets ignore them. */
internal sealed interface LocalConnectEvent {
    val sheet: String

    /** Logged in and playing there (the sheet closes). */
    data class Connected(override val sheet: String) : LocalConnectEvent

    /**
     * Logged in, but nothing was playing and there was no saved session to start there. [selected]:
     * the device became the pending target, so the next play goes there.
     */
    data class Ready(override val sheet: String, val deviceName: String, val selected: Boolean = false) : LocalConnectEvent

    /**
     * Logged in, but playback was not moved there: the user picked another device (or playback
     * moved) during the login.
     */
    data class Added(override val sheet: String, val deviceName: String) : LocalConnectEvent

    /**
     * `connect.localLogin` failed: the device did not join the account. [offline]: the session
     * wasn't online (NOT_CONNECTED), not a Wi-Fi problem.
     */
    data class Failed(
        override val sheet: String,
        val deviceName: String,
        val network: Boolean,
        val offline: Boolean = false,
    ) : LocalConnectEvent

    /** The device joined the account, but moving playback to it failed. */
    data class TransferFailed(override val sheet: String, val deviceName: String, val network: Boolean) : LocalConnectEvent
}

/** Whether [this] is shown as the section's error (else as its notice). */
internal val LocalConnectEvent.isFailure: Boolean
    get() = this is LocalConnectEvent.Failed || this is LocalConnectEvent.TransferFailed

/** What the LAN section says for [this] result; null when the sheet just closes (connected). */
internal fun LocalConnectEvent.message(): SheetMessage? = when (this) {
    is LocalConnectEvent.Connected -> null
    // The device is in the account now (listed under Connect devices): not a failure.
    is LocalConnectEvent.Ready -> SheetMessage(if (selected) R.string.player_devices_selected else R.string.local_connect_ready, listOf(deviceName))
    is LocalConnectEvent.Added -> SheetMessage(R.string.local_connect_added, listOf(deviceName))
    is LocalConnectEvent.Failed -> SheetMessage(
        when {
            offline -> R.string.local_connect_login_failed_offline
            network -> R.string.local_connect_login_failed_network
            else -> R.string.local_connect_login_failed
        },
        listOf(deviceName),
    )
    is LocalConnectEvent.TransferFailed -> SheetMessage(
        if (network) R.string.player_devices_transfer_failed_network else R.string.player_devices_transfer_failed,
        listOf(deviceName),
    )
}

/**
 * Logs a LAN device in ([login], returning its Connect id), then moves playback there
 * ([transfer], which starts the saved session when nothing is active). The two steps are judged
 * separately: a transfer with nothing to play or resume (NOT_ACTIVE_DEVICE) still means the
 * device was added, so it is [LocalConnectEvent.Ready], not a failure.
 */
internal suspend fun connectLocalDevice(
    sheet: String,
    deviceName: String,
    login: suspend () -> String,
    transfer: suspend (String) -> Unit,
    isSelected: (String) -> Boolean = { false },
    shouldTransfer: () -> Boolean = { true },
): LocalConnectEvent {
    val deviceId = try {
        login()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NativeException) {
        if (e.code == NativeErrorCode.NOT_CONNECTED) return LocalConnectEvent.Failed(sheet, deviceName, network = false, offline = true)
        return LocalConnectEvent.Failed(sheet, deviceName, e.isNetwork)
    } catch (e: Exception) {
        return LocalConnectEvent.Failed(sheet, deviceName, network = false)
    }
    // A login can take long (wake-up, polling, the cluster wait): a later choice wins.
    if (!shouldTransfer()) return LocalConnectEvent.Added(sheet, deviceName)
    return try {
        transfer(deviceId)
        LocalConnectEvent.Connected(sheet)
    } catch (e: CancellationException) {
        throw e
    } catch (e: NativeException) {
        if (e.code == NativeErrorCode.NOT_ACTIVE_DEVICE) {
            LocalConnectEvent.Ready(sheet, deviceName, selected = isSelected(deviceId))
        } else {
            LocalConnectEvent.TransferFailed(sheet, deviceName, e.isNetwork)
        }
    } catch (e: Exception) {
        LocalConnectEvent.TransferFailed(sheet, deviceName, network = false)
    }
}

internal class LocalDevicesViewModel(graph: AppGraph) : ViewModel() {
    private val discovery = graph.localDiscovery
    private val devicesRepository = graph.devices
    private val loggedIn = graph.engine.isLoggedIn
    private val online = graph.engineReachFlow().map { it == EngineReach.ONLINE }.distinctUntilChanged()
    /** A sheet is open and STARTED ([startDiscovery] / [stopDiscovery]). */
    private val started = MutableStateFlow(false)
    private val connecting = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<LocalConnectEvent>(Channel.BUFFERED)
    private val shownSheets = ShownSheets()
    private val messenger = SessionMessenger(graph.app)
    /** The sheet whose results [discovery] currently holds. */
    private var resultsSheet: String? = null

    val events: Flow<LocalConnectEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<LocalDevicesUiState> = combine(
        discovery.devices,
        devicesRepository.devices,
        discovery.discovering,
        combine(connecting, online, ::Pair),
    ) { found, cluster, discovering, (connectingId, online) ->
        LocalDevicesUiState(
            // Not the account's devices again, nor a Cast twin of a ZeroConf speaker.
            devices = CastServices.visible(found, cluster.devices),
            discovering = discovering,
            connectingId = connectingId,
            online = online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDevicesUiState())

    init {
        // Browse (mDNS, the multicast lock, the speaker probes) only while a sheet shows the
        // section and the session is ONLINE: offline nothing filters out the account's own
        // speakers and a login would fail anyway.
        combine(started, online, loggedIn) { started, online, loggedIn -> started && online && loggedIn }
            .distinctUntilChanged()
            .onEach { browse -> if (browse) discovery.start() else discovery.pause() }
            .launchIn(viewModelScope)
    }

    /**
     * Browses while [sheet] is open and STARTED. A new sheet starts from scratch; the same sheet
     * coming back (background, rotation) keeps what it found.
     */
    fun startDiscovery(sheet: String) {
        if (sheet != resultsSheet) {
            discovery.clear()
            resultsSheet = sheet
        }
        started.value = true
    }

    /** The sheet stopped or went away: browsing (and the multicast lock) ends, results stay. */
    fun stopDiscovery() {
        started.value = false
    }

    /**
     * Logs the device into the account and transfers playback to it; the result goes to [sheet]
     * only (no UI callback is held here: the sheet may be gone by then).
     */
    fun connect(device: LocalConnectDevice, sheet: String) {
        if (connecting.value != null) return
        // Another device picked (or playback moved) before the login finishes: don't move it here.
        val pick = DevicePicks.mark()
        val activeBefore = devicesRepository.devices.value.activeDeviceId
        viewModelScope.launch {
            connecting.value = device.key
            try {
                // transferTo sends the saved session as `resume`, so it starts on the device
                // when nothing is playing anywhere.
                val event = connectLocalDevice(
                    sheet = sheet,
                    deviceName = device.name,
                    login = { discovery.login(device) },
                    transfer = { id -> devicesRepository.transferTo(id) },
                    // A failed transfer with nothing to resume keeps the device for the next play.
                    isSelected = { id -> devicesRepository.pendingTarget.value == id },
                    shouldTransfer = {
                        DevicePicks.isLatest(pick) && devicesRepository.devices.value.activeDeviceId == activeBefore
                    },
                )
                deliver(event)
            } finally {
                connecting.value = null
            }
        }
    }

    /** The sheet [sheet] is on screen (its results go to it) / went away (to the app's snackbar). */
    fun sheetShown(sheet: String) = shownSheets.add(sheet)

    fun sheetGone(sheet: String) = shownSheets.remove(sheet)

    private fun deliver(event: LocalConnectEvent) {
        if (event.sheet in shownSheets) {
            eventChannel.trySend(event)
        } else {
            event.message()?.let { messenger.post(it.res, *it.args.toTypedArray()) }
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
    private val noticeValue: State<String?>,
    private val onConnect: (LocalConnectDevice) -> Unit,
) {
    val state: LocalDevicesUiState get() = stateValue.value

    /** The last login or transfer failure of this sheet, if any. */
    val error: String? get() = errorValue.value

    /** A non-error outcome to show (a device added with nothing to play yet). */
    val notice: String? get() = noticeValue.value

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
    val notice = remember(sheet) { mutableStateOf<String?>(null) }
    val currentOnConnected by rememberUpdatedState(onConnected)

    LifecycleStartEffect(viewModel, sheet) {
        viewModel.startDiscovery(sheet)
        onStopOrDispose { viewModel.stopDiscovery() }
    }
    DisposableEffect(viewModel, sheet) {
        viewModel.sheetShown(sheet)
        onDispose { viewModel.sheetGone(sheet) }
    }

    LaunchedEffect(viewModel, sheet) {
        viewModel.events.filter { it.sheet == sheet }.collect { event ->
            if (event is LocalConnectEvent.Connected) {
                currentOnConnected()
                return@collect
            }
            val text = event.message()?.let { context.getString(it.res, *it.args.toTypedArray()) } ?: return@collect
            if (event.isFailure) error.value = text else notice.value = text
        }
    }

    return remember(viewModel, sheet, state) {
        LocalDevicesHolder(state, error, notice) { device ->
            error.value = null
            notice.value = null
            viewModel.connect(device, sheet)
        }
    }
}

/**
 * "Other devices on your network": Spotify Connect receivers and Google Cast devices found on the
 * LAN that aren't in the account yet (docs/ARCHITECTURE.md §8). Rendering only;
 * [rememberLocalDevices] in the sheet owns discovery and the results. Tapping a device logs it in,
 * transfers playback and closes the sheet.
 */
@Composable
internal fun LocalDevicesSection(local: LocalDevicesHolder, transferring: Boolean = false) {
    val state = local.state
    val error = local.error
    val notice = local.notice
    // Offline the account's own speakers can't be told apart (no cluster), and adding one needs
    // the session: no section.
    if (!state.online) return
    if (state.devices.isEmpty() && error == null && notice == null && !state.discovering) return

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
                    busy = state.connectingId == device.key,
                    // Not while a Connect transfer from this sheet is running either.
                    enabled = state.connectingId == null && !transferring,
                    onClick = { local.connect(device) },
                )
            }
        }
        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
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
            val details = listOfNotNull(device.brand, device.model).joinToString(" ").ifBlank { null }
            val subtitle = when {
                busy -> stringResource(R.string.local_connect_connecting)
                // The small "Google Cast" hint: signed in through Cast, not Spotify Connect.
                device.isCast -> details?.let { stringResource(R.string.local_connect_cast_subtitle_model, it) }
                    ?: stringResource(R.string.local_connect_cast_subtitle)
                else -> details ?: stringResource(R.string.local_connect_subtitle)
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
            {
                Icon(
                    if (device.isCast) Icons.Rounded.Cast else Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}
