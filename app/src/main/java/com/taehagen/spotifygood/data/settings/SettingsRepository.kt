package com.taehagen.spotifygood.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.taehagen.spotifygood.model.Bitrate
import com.taehagen.spotifygood.model.EngineSettings
import com.taehagen.spotifygood.model.NormalizePregain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

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

/**
 * Engine settings for these app settings: the bitrate depends on whether the current network is
 * [metered] (data saver quality), a blank device name falls back to [defaultDeviceName].
 */
fun Settings.toEngineSettings(metered: Boolean, defaultDeviceName: String): EngineSettings = EngineSettings(
    bitrate = (if (metered) cellularQuality else streamingQuality).kbps,
    normalize = normalize,
    normalizePregain = normalizePregain,
    autoplay = autoplay,
    gapless = gapless,
    deviceName = deviceName.trim().ifBlank { defaultDeviceName },
    streamingCacheMb = streamingCacheMb,
    offline = offlineMode,
)

/**
 * JSON codec of the persisted [Settings]. Lenient on purpose: unknown keys (settings removed in a
 * later version) are ignored and invalid values fall back to their defaults, so a settings file
 * can never brick the app.
 */
internal object SettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        // Persist every value, so a future change of a default never silently changes a choice
        // the user made.
        encodeDefaults = true
    }

    fun decode(raw: String?): Settings {
        if (raw.isNullOrBlank()) return Settings()
        return try {
            json.decodeFromString<Settings>(raw)
        } catch (e: SerializationException) {
            Settings()
        } catch (e: IllegalArgumentException) {
            Settings()
        }
    }

    fun encode(settings: Settings): String = json.encodeToString(Settings.serializer(), settings)
}

/** DataStore-backed settings. [settings] is hot (always has a value; defaults until loaded). */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    // The factory does no I/O; the file is produced lazily on the DataStore's own IO scope.
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            Log.w(TAG, "Settings file corrupt, resetting to defaults", e)
            emptyPreferences()
        },
        produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) },
    )

    /** Settings as stored on disk (never the pre-load placeholder). Cold; DataStore caches reads. */
    val persisted: Flow<Settings> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Reading settings failed, using defaults", e)
            emit(emptyPreferences())
        }
        .map { prefs -> SettingsCodec.decode(prefs[KEY_SETTINGS]) }
        .distinctUntilChanged()

    val settings: StateFlow<Settings> = persisted.stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Suspends until the settings have been read from disk once and returns them. */
    suspend fun awaitLoaded(): Settings = persisted.first()

    suspend fun update(transform: (Settings) -> Settings) {
        try {
            dataStore.edit { prefs ->
                val current = SettingsCodec.decode(prefs[KEY_SETTINGS])
                val next = transform(current)
                if (next != current) prefs[KEY_SETTINGS] = SettingsCodec.encode(next)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Writing settings failed", e)
        }
    }

    /** Logout: back to the defaults. Throws when the settings file can't be written. */
    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    /** Engine settings for the current network type. */
    fun engineSettings(metered: Boolean, defaultDeviceName: String): EngineSettings =
        settings.value.toEngineSettings(metered, defaultDeviceName)

    private companion object {
        const val TAG = "SettingsRepository"
        const val FILE_NAME = "settings"
        val KEY_SETTINGS = stringPreferencesKey("settings_json")
    }
}
