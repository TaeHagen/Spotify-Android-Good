package com.taehagen.spotifygood.data.settings

import android.content.Context
import com.taehagen.spotifygood.model.Bitrate
import com.taehagen.spotifygood.model.EngineSettings
import com.taehagen.spotifygood.model.NormalizePregain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
enum class LibraryView { LIST, GRID }

@Serializable
enum class LibrarySort { RECENT, RECENTLY_ADDED, ALPHABETICAL, CREATOR }

@Serializable
data class Settings(
    val streamingQuality: Bitrate = Bitrate.HIGH,
    /** Quality used on metered networks (data saver). */
    val cellularQuality: Bitrate = Bitrate.NORMAL,
    val downloadQuality: Bitrate = Bitrate.HIGH,
    val normalize: Boolean = true,
    val normalizePregain: NormalizePregain = NormalizePregain.NORMAL,
    val autoplay: Boolean = true,
    val gapless: Boolean = true,
    val hideExplicit: Boolean = false,
    val downloadOverCellular: Boolean = false,
    /** Spotify Connect device name; empty = device model. */
    val deviceName: String = "",
    /** Keep the phone visible to Spotify Connect while idle (connectedDevice FGS; battery cost). */
    val connectPresence: Boolean = false,
    val offlineMode: Boolean = false,
    val streamingCacheMb: Int = 1024,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val libraryView: LibraryView = LibraryView.LIST,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val showLyricsOnNowPlaying: Boolean = true,
)

/** DataStore-backed settings. [settings] is hot (always has a value; defaults until loaded). */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    val settings: StateFlow<Settings> get() = TODO()

    suspend fun update(transform: (Settings) -> Settings): Unit = TODO()

    /** Engine settings for the current network type. */
    fun engineSettings(metered: Boolean, defaultDeviceName: String): EngineSettings = TODO()
}
