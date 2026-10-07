package com.taehagen.spotifygood.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.taehagen.spotifygood.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumeStoreTest {
    private val dir: File = Files.createTempDirectory("resume-store").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "playback_resume.preferences_pb") })
    private val store = ResumeStore(dataStore)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val state = ResumeState(
        contextUri = "spotify:playlist:p",
        trackUri = "spotify:track:t",
        positionMs = 61_000,
        title = "Song",
        artist = "Artist",
        album = null,
        artworkUrl = null,
        durationMs = 200_000,
        isEpisode = false,
    )

    @Test
    fun modesReadBack() = runBlocking {
        assertNull(store.read())
        for (saved in listOf(
            state.copy(shuffle = true, smartShuffle = true, repeat = RepeatMode.TRACK),
            state.copy(shuffle = true, repeat = RepeatMode.CONTEXT),
            state,
        )) {
            store.save(saved)
            assertEquals(saved, store.read())
        }
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun aStateStoredByAnOlderVersionReadsWithTheModesOff() = runBlocking {
        // The keys of a state saved before the modes were stored.
        dataStore.edit { p ->
            p[stringPreferencesKey("context")] = "spotify:album:a"
            p[stringPreferencesKey("track")] = "spotify:track:t"
            p[longPreferencesKey("position")] = 5_000
            p[booleanPreferencesKey("episode")] = false
        }
        val read = checkNotNull(store.read())
        assertEquals("spotify:album:a", read.contextUri)
        assertEquals(5_000L, read.positionMs)
        assertFalse(read.shuffle)
        assertFalse(read.smartShuffle)
        assertEquals(RepeatMode.OFF, read.repeat)

        // An unknown repeat value (a newer version, a damaged file) is off too.
        dataStore.edit { it[stringPreferencesKey("repeat")] = "sometimes" }
        assertEquals(RepeatMode.OFF, checkNotNull(store.read()).repeat)
    }
}
