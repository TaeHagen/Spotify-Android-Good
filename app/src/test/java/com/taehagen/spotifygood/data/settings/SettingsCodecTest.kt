package com.taehagen.spotifygood.data.settings

import com.taehagen.spotifygood.model.Bitrate
import com.taehagen.spotifygood.model.NormalizePregain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun missingOrBrokenDataGivesDefaults() {
        assertEquals(Settings(), SettingsCodec.decode(null))
        assertEquals(Settings(), SettingsCodec.decode(""))
        assertEquals(Settings(), SettingsCodec.decode("{}"))
        assertEquals(Settings(), SettingsCodec.decode("not json"))
        assertEquals(Settings(), SettingsCodec.decode("""{"streamingCacheMb":"lots"}"""))
    }

    @Test
    fun defaultsAreTheDocumentedOnes() {
        val s = Settings()
        assertEquals(Bitrate.HIGH, s.streamingQuality)
        assertEquals(Bitrate.NORMAL, s.cellularQuality)
        assertEquals(true, s.normalize)
        assertEquals(NormalizePregain.NORMAL, s.normalizePregain)
        assertEquals(true, s.autoplay)
        assertEquals(false, s.offlineMode)
        assertEquals(false, s.connectPresence)
        assertEquals(1024, s.streamingCacheMb)
        assertEquals("", s.deviceName)
    }

    @Test
    fun unknownKeysAndPartialDataAreTolerated() {
        val s = SettingsCodec.decode("""{"streamingQuality":"96","removedSetting":true,"theme":"DARK"}""")
        assertEquals(Bitrate.LOW, s.streamingQuality)
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(Settings().cellularQuality, s.cellularQuality)
    }

    @Test
    fun invalidValuesFallBackPerField() {
        val s = SettingsCodec.decode("""{"streamingQuality":"999","theme":"NEON","autoplay":null,"gapless":false}""")
        assertEquals(Settings().streamingQuality, s.streamingQuality)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(true, s.autoplay)
        assertEquals(false, s.gapless)
    }

    @Test
    fun roundTrips() {
        val custom = Settings(
            streamingQuality = Bitrate.NORMAL,
            cellularQuality = Bitrate.LOW,
            normalizePregain = NormalizePregain.LOUD,
            deviceName = "Kitchen",
            offlineMode = true,
            theme = ThemeMode.LIGHT,
            libraryView = LibraryView.GRID,
            librarySort = LibrarySort.ALPHABETICAL,
        )
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
    }

    @Test
    fun encodesDefaultsExplicitly() {
        val json = SettingsCodec.encode(Settings())
        assertTrue(json.contains("\"streamingQuality\":\"320\""))
        assertTrue(json.contains("\"offlineMode\":false"))
    }

    @Test
    fun engineSettingsFollowTheNetwork() {
        val s = Settings(streamingQuality = Bitrate.HIGH, cellularQuality = Bitrate.LOW, normalizePregain = NormalizePregain.QUIET)
        assertEquals(320, s.toEngineSettings(metered = false, defaultDeviceName = "Pixel").bitrate)
        val metered = s.toEngineSettings(metered = true, defaultDeviceName = "Pixel")
        assertEquals(96, metered.bitrate)
        assertEquals(NormalizePregain.QUIET, metered.normalizePregain)
        assertEquals("Pixel", metered.deviceName)
        assertEquals(1024, metered.streamingCacheMb)
        assertEquals(false, metered.offline)
    }

    @Test
    fun engineSettingsDeviceNameAndOffline() {
        assertEquals("Kitchen", Settings(deviceName = "  Kitchen ").toEngineSettings(false, "Pixel").deviceName)
        assertEquals("Pixel", Settings(deviceName = "   ").toEngineSettings(false, "Pixel").deviceName)
        val e = Settings(offlineMode = true, autoplay = false, gapless = false, normalize = false).toEngineSettings(false, "P")
        assertEquals(true, e.offline)
        assertEquals(false, e.autoplay)
        assertEquals(false, e.gapless)
        assertEquals(false, e.normalize)
    }
}
