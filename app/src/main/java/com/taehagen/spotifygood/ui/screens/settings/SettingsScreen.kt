package com.taehagen.spotifygood.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.BuildConfig
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.data.settings.LibraryView
import com.taehagen.spotifygood.data.settings.Settings
import com.taehagen.spotifygood.data.settings.ThemeMode
import com.taehagen.spotifygood.engine.defaultDeviceName
import com.taehagen.spotifygood.model.Bitrate
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.NormalizePregain
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.formatBytes
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.navigation.openSleepTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Settings state and actions. Mutations run in the app scope so they finish after navigation. */
class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<Settings> = graph.settings.settings
    val user: StateFlow<User?> = graph.engine.user
    val sleepTimer: StateFlow<SleepTimerState> = graph.sleepTimer.state

    val usedBytes: StateFlow<Long> = graph.downloads.usedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val downloadedCount: StateFlow<Int> = graph.downloads.downloadedUris
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.downloads.downloadedUris.value.size)
    val failedCount: StateFlow<Int> = graph.downloads.items
        .map { items -> items.count { it.state == DownloadState.FAILED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Audio session of the local player, for the system equalizer. */
    val audioSessionId: Int get() = graph.audioSink.audioSessionId

    fun update(transform: (Settings) -> Settings) {
        graph.appScope.launch { graph.settings.update(transform) }
    }

    fun removeAllDownloads(onDone: () -> Unit) = runInApp(onDone) { graph.downloads.removeAll() }

    fun retryFailed(onDone: () -> Unit) = runInApp(onDone) { graph.downloads.retryFailed() }

    fun clearCache(context: Context, onDone: () -> Unit) = runInApp(onDone) {
        graph.responseCache.clear()
        val loader = SingletonImageLoader.get(context.applicationContext)
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }

    fun logout() = runInApp({}) { graph.logout() }

    private fun runInApp(onDone: () -> Unit, block: suspend () -> Unit) {
        graph.appScope.launch {
            try {
                block()
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("Settings", "Settings action failed", t)
            }
        }
    }
}

private val CACHE_SIZES_MB = listOf(256, 512, 1024, 2048, 3072, 4096)

private data class LicenseEntry(val name: String, val license: String)

private val LICENSES = listOf(
    LicenseEntry("librespot (core, connect, playback, audio, metadata, oauth, protocol)", "MIT"),
    LicenseEntry("Symphonia", "MPL-2.0"),
    LicenseEntry("Tokio", "MIT"),
    LicenseEntry("hyper", "MIT"),
    LicenseEntry("reqwest", "MIT OR Apache-2.0"),
    LicenseEntry("rustls", "Apache-2.0 OR ISC OR MIT"),
    LicenseEntry("ring", "Apache-2.0 AND ISC"),
    LicenseEntry("serde, serde_json", "MIT OR Apache-2.0"),
    LicenseEntry("rust-protobuf", "MIT"),
    LicenseEntry("jni-rs", "MIT OR Apache-2.0"),
    LicenseEntry("Android Jetpack (Compose, Media3, Navigation, Room, WorkManager, DataStore, Palette)", "Apache-2.0"),
    LicenseEntry("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
    LicenseEntry("OkHttp, Okio", "Apache-2.0"),
    LicenseEntry("Coil", "Apache-2.0"),
    LicenseEntry("Material Symbols", "Apache-2.0"),
)

private sealed interface SettingsDialog {
    data object Logout : SettingsDialog
    data object RemoveDownloads : SettingsDialog
    data object DeviceName : SettingsDialog
    data object PresenceWarning : SettingsDialog
    data object Licenses : SettingsDialog
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val vm = appViewModel { SettingsViewModel(it) }
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    val downloadedCount by vm.downloadedCount.collectAsStateWithLifecycle()
    val failedCount by vm.failedCount.collectAsStateWithLifecycle()
    val sleepTimer by vm.sleepTimer.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // System equalizer (only offered when an app can handle it).
    val equalizerIntent = remember(vm) {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, vm.audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
    }
    val equalizerAvailable by produceState(initialValue = false, equalizerIntent) {
        value = withContext(Dispatchers.IO) { equalizerIntent.resolveActivity(context.packageManager) != null }
    }
    val equalizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val bitrateOptions = listOf(
        Bitrate.LOW to stringResource(R.string.shell_quality_low),
        Bitrate.NORMAL to stringResource(R.string.shell_quality_normal),
        Bitrate.HIGH to stringResource(R.string.shell_quality_high),
    )
    val msgCacheCleared = stringResource(R.string.shell_msg_cache_cleared)
    val msgDownloadsRemoved = stringResource(R.string.shell_msg_downloads_removed)
    val msgRetrying = stringResource(R.string.shell_msg_retrying_downloads)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.shell_settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.shell_cd_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // ---- Account
            item(key = "h_account") { PrefHeader(stringResource(R.string.shell_settings_account)) }
            item(key = "account") {
                val name = user?.let { it.displayName ?: it.username } ?: stringResource(R.string.shell_settings_account_unknown)
                val product = user?.product?.replaceFirstChar { it.uppercase() }
                ListItem(
                    headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            listOfNotNull(product, user?.country).joinToString(" • ")
                                .ifEmpty { stringResource(R.string.shell_settings_view_profile) },
                        )
                    },
                    leadingContent = {
                        Artwork(user?.images?.best(120), null, Modifier.size(48.dp), CircleShape, Icons.Rounded.Person)
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    colors = transparentListItem(),
                    modifier = Modifier.clickableRow { navigator.navigate(Route.Profile(null)) },
                )
            }
            item(key = "logout") {
                PrefItem(
                    title = stringResource(R.string.shell_logout),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    summary = stringResource(R.string.shell_settings_logout_summary),
                    onClick = { dialog = SettingsDialog.Logout },
                    destructive = true,
                )
            }

            // ---- Playback
            item(key = "h_playback") { PrefHeader(stringResource(R.string.shell_settings_playback)) }
            item(key = "quality_wifi") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_quality_wifi),
                    icon = Icons.Rounded.Wifi,
                    options = bitrateOptions,
                    selected = settings.streamingQuality,
                    onSelect = { q -> vm.update { it.copy(streamingQuality = q) } },
                )
            }
            item(key = "quality_cell") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_quality_cellular),
                    icon = Icons.Rounded.SignalCellularAlt,
                    options = bitrateOptions,
                    selected = settings.cellularQuality,
                    onSelect = { q -> vm.update { it.copy(cellularQuality = q) } },
                )
            }
            item(key = "gapless") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_gapless),
                    summary = stringResource(R.string.shell_settings_gapless_summary),
                    icon = Icons.Rounded.GraphicEq,
                    checked = settings.gapless,
                    onCheckedChange = { v -> vm.update { it.copy(gapless = v) } },
                )
            }
            item(key = "normalize") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_normalize),
                    summary = stringResource(R.string.shell_settings_normalize_summary),
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    checked = settings.normalize,
                    onCheckedChange = { v -> vm.update { it.copy(normalize = v) } },
                )
            }
            item(key = "normalize_level") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_volume_level),
                    icon = Icons.Rounded.Tune,
                    enabled = settings.normalize,
                    options = listOf(
                        NormalizePregain.QUIET to stringResource(R.string.shell_level_quiet),
                        NormalizePregain.NORMAL to stringResource(R.string.shell_level_normal),
                        NormalizePregain.LOUD to stringResource(R.string.shell_level_loud),
                    ),
                    selected = settings.normalizePregain,
                    onSelect = { level -> vm.update { it.copy(normalizePregain = level) } },
                )
            }
            item(key = "autoplay") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_autoplay),
                    summary = stringResource(R.string.shell_settings_autoplay_summary),
                    icon = Icons.Rounded.SpatialAudioOff,
                    checked = settings.autoplay,
                    onCheckedChange = { v -> vm.update { it.copy(autoplay = v) } },
                )
            }
            item(key = "explicit") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_hide_explicit),
                    summary = stringResource(R.string.shell_settings_hide_explicit_summary),
                    icon = Icons.Rounded.Explicit,
                    checked = settings.hideExplicit,
                    onCheckedChange = { v -> vm.update { it.copy(hideExplicit = v) } },
                )
            }
            item(key = "sleep") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_sleep_timer),
                    summary = sleepTimerSummary(sleepTimer),
                    icon = Icons.Rounded.Bedtime,
                    onClick = { navigator.openSleepTimer() },
                )
            }

            // ---- Spotify Connect
            item(key = "h_connect") { PrefHeader(stringResource(R.string.shell_settings_connect)) }
            item(key = "device_name") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_device_name),
                    summary = settings.deviceName.ifBlank { stringResource(R.string.shell_settings_device_name_default, defaultDeviceName()) },
                    icon = Icons.Rounded.Devices,
                    onClick = { dialog = SettingsDialog.DeviceName },
                )
            }
            item(key = "presence") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_presence),
                    summary = stringResource(R.string.shell_settings_presence_summary),
                    icon = Icons.Rounded.CellTower,
                    checked = settings.connectPresence,
                    onCheckedChange = { v ->
                        if (v) dialog = SettingsDialog.PresenceWarning else vm.update { it.copy(connectPresence = false) }
                    },
                )
            }

            // ---- Downloads
            item(key = "h_downloads") { PrefHeader(stringResource(R.string.shell_settings_downloads)) }
            item(key = "download_quality") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_download_quality),
                    icon = Icons.Rounded.HighQuality,
                    options = bitrateOptions,
                    selected = settings.downloadQuality,
                    onSelect = { q -> vm.update { it.copy(downloadQuality = q) } },
                )
            }
            item(key = "download_cellular") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_download_cellular),
                    summary = stringResource(R.string.shell_settings_download_cellular_summary),
                    icon = Icons.Rounded.Download,
                    checked = settings.downloadOverCellular,
                    onCheckedChange = { v -> vm.update { it.copy(downloadOverCellular = v) } },
                )
            }
            item(key = "storage_used") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_storage_used),
                    summary = stringResource(R.string.shell_settings_storage_used_summary, formatBytes(context, usedBytes), downloadedCount),
                    icon = Icons.Rounded.SdStorage,
                    onClick = { navigator.navigate(Route.Downloads) },
                )
            }
            if (failedCount > 0) {
                item(key = "retry_failed") {
                    PrefItem(
                        title = stringResource(R.string.shell_settings_retry_failed),
                        summary = stringResource(R.string.shell_settings_retry_failed_summary, failedCount),
                        icon = Icons.Rounded.Refresh,
                        onClick = { vm.retryFailed { navigator.showMessage(msgRetrying) } },
                    )
                }
            }
            item(key = "remove_downloads") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_remove_downloads),
                    summary = stringResource(R.string.shell_settings_remove_downloads_summary),
                    icon = Icons.Rounded.DeleteSweep,
                    enabled = downloadedCount > 0 || usedBytes > 0,
                    onClick = { dialog = SettingsDialog.RemoveDownloads },
                    destructive = true,
                )
            }

            // ---- Offline
            item(key = "h_offline") { PrefHeader(stringResource(R.string.shell_settings_offline)) }
            item(key = "offline_mode") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_offline_mode),
                    summary = stringResource(R.string.shell_settings_offline_mode_summary),
                    icon = Icons.Rounded.CloudOff,
                    checked = settings.offlineMode,
                    onCheckedChange = { v -> vm.update { it.copy(offlineMode = v) } },
                )
            }

            // ---- Storage
            item(key = "h_storage") { PrefHeader(stringResource(R.string.shell_settings_storage)) }
            item(key = "cache_size") {
                CacheSizePref(
                    valueMb = settings.streamingCacheMb,
                    onValueChange = { mb -> vm.update { it.copy(streamingCacheMb = mb) } },
                )
            }
            item(key = "clear_cache") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_clear_cache),
                    summary = stringResource(R.string.shell_settings_clear_cache_summary),
                    icon = Icons.Rounded.CleaningServices,
                    onClick = { vm.clearCache(context) { navigator.showMessage(msgCacheCleared) } },
                )
            }

            // ---- Display
            item(key = "h_display") { PrefHeader(stringResource(R.string.shell_settings_display)) }
            item(key = "theme") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_theme),
                    icon = Icons.Rounded.DarkMode,
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.shell_theme_system),
                        ThemeMode.DARK to stringResource(R.string.shell_theme_dark),
                        ThemeMode.LIGHT to stringResource(R.string.shell_theme_light),
                    ),
                    selected = settings.theme,
                    onSelect = { mode -> vm.update { it.copy(theme = mode) } },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "dynamic_color") {
                    SwitchPref(
                        title = stringResource(R.string.shell_settings_dynamic_color),
                        summary = stringResource(R.string.shell_settings_dynamic_color_summary),
                        icon = Icons.Rounded.ColorLens,
                        checked = settings.dynamicColor,
                        onCheckedChange = { v -> vm.update { it.copy(dynamicColor = v) } },
                    )
                }
            }
            item(key = "lyrics") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_lyrics),
                    summary = stringResource(R.string.shell_settings_lyrics_summary),
                    icon = Icons.Rounded.Lyrics,
                    checked = settings.showLyricsOnNowPlaying,
                    onCheckedChange = { v -> vm.update { it.copy(showLyricsOnNowPlaying = v) } },
                )
            }
            item(key = "library_view") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_library_view),
                    icon = Icons.Rounded.GridView,
                    options = listOf(
                        LibraryView.LIST to stringResource(R.string.shell_library_view_list),
                        LibraryView.GRID to stringResource(R.string.shell_library_view_grid),
                    ),
                    selected = settings.libraryView,
                    onSelect = { v -> vm.update { it.copy(libraryView = v) } },
                )
            }
            item(key = "library_sort") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_library_sort),
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    options = listOf(
                        LibrarySort.RECENT to stringResource(R.string.shell_sort_recent),
                        LibrarySort.RECENTLY_ADDED to stringResource(R.string.shell_sort_recently_added),
                        LibrarySort.ALPHABETICAL to stringResource(R.string.shell_sort_alphabetical),
                        LibrarySort.CREATOR to stringResource(R.string.shell_sort_creator),
                    ),
                    selected = settings.librarySort,
                    onSelect = { v -> vm.update { it.copy(librarySort = v) } },
                )
            }

            // ---- Audio
            item(key = "h_audio") { PrefHeader(stringResource(R.string.shell_settings_audio)) }
            item(key = "equalizer") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_equalizer),
                    summary = stringResource(
                        if (equalizerAvailable) R.string.shell_settings_equalizer_summary else R.string.shell_settings_equalizer_unavailable,
                    ),
                    icon = Icons.Rounded.Equalizer,
                    enabled = equalizerAvailable,
                    onClick = {
                        openEqualizerSession(context, vm.audioSessionId)
                        runCatching { equalizerLauncher.launch(equalizerIntent) }
                    },
                )
            }

            // ---- About
            item(key = "h_about") { PrefHeader(stringResource(R.string.shell_settings_about)) }
            item(key = "version") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_version),
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · debug" else "",
                    icon = Icons.Rounded.Info,
                )
            }
            item(key = "licenses") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_licenses),
                    summary = stringResource(R.string.shell_settings_licenses_summary),
                    icon = Icons.Rounded.Code,
                    onClick = { dialog = SettingsDialog.Licenses },
                )
            }
            item(key = "disclaimer") {
                Text(
                    text = stringResource(R.string.shell_settings_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }

    when (dialog) {
        SettingsDialog.Logout -> ConfirmDialog(
            title = stringResource(R.string.shell_logout_confirm_title),
            message = stringResource(R.string.shell_logout_confirm_message),
            confirm = stringResource(R.string.shell_logout),
            onConfirm = {
                dialog = null
                vm.logout()
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.RemoveDownloads -> ConfirmDialog(
            title = stringResource(R.string.shell_remove_downloads_confirm_title),
            message = stringResource(R.string.shell_remove_downloads_confirm_message, formatBytes(context, usedBytes)),
            confirm = stringResource(R.string.shell_remove),
            onConfirm = {
                dialog = null
                vm.removeAllDownloads { navigator.showMessage(msgDownloadsRemoved) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.PresenceWarning -> ConfirmDialog(
            title = stringResource(R.string.shell_presence_confirm_title),
            message = stringResource(R.string.shell_presence_confirm_message),
            confirm = stringResource(R.string.shell_turn_on),
            destructive = false,
            onConfirm = {
                dialog = null
                vm.update { it.copy(connectPresence = true) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DeviceName -> DeviceNameDialog(
            current = settings.deviceName,
            onSave = { name ->
                dialog = null
                vm.update { it.copy(deviceName = name) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Licenses -> LicensesDialog(onDismiss = { dialog = null })
        null -> Unit
    }
}

/** Lets audio-effect apps attach to our (stable) session before their panel opens. */
private fun openEqualizerSession(context: Context, sessionId: Int) {
    runCatching {
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }
}

@Composable
private fun sleepTimerSummary(state: SleepTimerState): String {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (state is SleepTimerState.Running) {
        LaunchedEffect(state, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    delay(15_000)
                }
            }
        }
    }
    return when (state) {
        SleepTimerState.Off -> stringResource(R.string.shell_sleep_off)
        SleepTimerState.EndOfTrack -> stringResource(R.string.shell_sleep_end_of_track)
        is SleepTimerState.Running -> {
            val minutes = ((state.endsAtElapsedMs - now).coerceAtLeast(0) + 59_999) / 60_000
            stringResource(R.string.shell_sleep_running, minutes.toInt())
        }
    }
}

// ---- Preference building blocks ------------------------------------------------------------------

@Composable
private fun transparentListItem() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)

private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 56.dp)

@Composable
private fun PrefHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun PrefItem(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    val titleColor = (if (destructive) colors.error else colors.onSurface).copy(alpha = alpha)
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = summary?.let { { Text(it, color = colors.onSurfaceVariant.copy(alpha = alpha)) } },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = (if (destructive) colors.error else colors.onSurfaceVariant).copy(alpha = alpha))
        },
        colors = transparentListItem(),
        modifier = if (onClick != null) Modifier.clickableRow(enabled = enabled, onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchPref(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title, color = colors.onSurface.copy(alpha = alpha)) },
        supportingContent = summary?.let { { Text(it, color = colors.onSurfaceVariant.copy(alpha = alpha)) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = alpha)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = transparentListItem(),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun <T> ChoicePref(
    title: String,
    icon: ImageVector,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PrefItem(
        title = title,
        icon = icon,
        summary = options.firstOrNull { it.first == selected }?.second,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = value == selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        open = false
                                        if (value != selected) onSelect(value)
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.shell_cancel)) }
            },
        )
    }
}

@Composable
private fun CacheSizePref(valueMb: Int, onValueChange: (Int) -> Unit) {
    val context = LocalContext.current
    val currentIndex = CACHE_SIZES_MB.indexOfFirst { it >= valueMb }.let { if (it < 0) CACHE_SIZES_MB.lastIndex else it }
    var position by remember(valueMb) { mutableFloatStateOf(currentIndex.toFloat()) }
    val shownMb = CACHE_SIZES_MB[position.roundToInt().coerceIn(0, CACHE_SIZES_MB.lastIndex)]
    val label = formatBytes(context, shownMb * 1024L * 1024L)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.shell_settings_cache_size), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.shell_settings_cache_size_summary, label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                val mb = CACHE_SIZES_MB[position.roundToInt().coerceIn(0, CACHE_SIZES_MB.lastIndex)]
                if (mb != valueMb) onValueChange(mb)
            },
            valueRange = 0f..CACHE_SIZES_MB.lastIndex.toFloat(),
            steps = CACHE_SIZES_MB.size - 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
                .semantics { stateDescription = label },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}

@Composable
private fun DeviceNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val save = { onSave(text.trim().take(MAX_DEVICE_NAME)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_settings_device_name)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.shell_settings_device_name_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_DEVICE_NAME) },
                    singleLine = true,
                    placeholder = { Text(defaultDeviceName()) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.shell_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_settings_licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.shell_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LICENSES.forEach { entry ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(entry.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_close)) } },
    )
}

private const val MAX_DEVICE_NAME = 64
