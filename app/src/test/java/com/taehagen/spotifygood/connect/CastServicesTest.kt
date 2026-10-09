package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class CastServicesTest {
    private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.associate { (k, v) -> k to v?.toByteArray(Charsets.UTF_8) }

    /** A TXT record shaped like a Nest Audio's (values as NSD reports them). */
    private val nestAudio = txt(
        "id" to "8c1b2a3d4e5f60718293a4b5c6d7e8f9",
        "cd" to "0A1B2C3D4E5F60718293A4B5C6D7E8F9",
        "rm" to "",
        "ve" to "05",
        "md" to "Google Nest Audio",
        "ic" to "/setup/icon.png",
        "fn" to "Living Room speaker",
        "ca" to "199172",
        "st" to "0",
        "bs" to "FA8FCA7EE8C2",
        "nf" to "1",
        "rs" to null,
    )

    @Test
    fun parsesTheTxtRecord() {
        val record = CastServices.parse(nestAudio)!!
        assertEquals("Living Room speaker", record.friendlyName)
        assertEquals("Google Nest Audio", record.model)
        assertEquals("8c1b2a3d4e5f60718293a4b5c6d7e8f9", record.id)
        assertEquals(199172, record.capabilities)
        assertFalse(CastServices.isGroup(record))
        assertEquals("audio only", DeviceType.SPEAKER, CastServices.deviceType(record))
    }

    @Test
    fun keysAreCaseInsensitiveAndValuesTrimmed() {
        val record = CastServices.parse(txt("FN" to "  Kitchen display ", "MD" to "Google Nest Hub", "Ca" to "4101"))!!
        assertEquals("Kitchen display", record.friendlyName)
        assertNull("no id record", record.id)
        assertEquals("video out", DeviceType.TV, CastServices.deviceType(record))
    }

    @Test
    fun aServiceWithoutAFriendlyNameIsSkipped() {
        assertNull(CastServices.parse(txt("md" to "Chromecast", "id" to "abc")))
        assertNull(CastServices.parse(txt("fn" to "   ")))
        assertNull(CastServices.parse(txt("fn" to null)))
        assertNull(CastServices.parse(emptyMap()))
        // Unparseable capabilities are just unknown.
        assertNull(CastServices.parse(txt("fn" to "TV", "ca" to "lots"))!!.capabilities)
    }

    @Test
    fun groupsAreRecognised() {
        val byModel = CastServices.parse(txt("fn" to "Downstairs", "md" to "Google Cast Group", "ca" to "199204"))!!
        assertTrue(CastServices.isGroup(byModel))
        val byCapability = CastServices.parse(txt("fn" to "Everywhere", "ca" to "32"))!!
        assertTrue(CastServices.isGroup(byCapability))
        val device = CastServices.device(byModel, "Google-Cast-Group-1", "192.168.1.40", 32187, null)
        assertTrue(device.isGroup)
        assertNull("a group shows no model", device.model)
        assertEquals(LocalEndpoint.Cast("192.168.1.40", 32187, "Google-Cast-Group-1"), device.endpoint)
    }

    @Test
    fun connectDeviceIdMatchesRust() {
        // Same vectors as Rust `cast_client::tests::connect_device_id_is_the_md5_of_the_name`.
        assertEquals("3c31e63dda8e146428f7b25084e98915", CastServices.connectDeviceId("Living Room speaker"))
        assertEquals("9f1d6780e10dc626a9777fb3f8513c94", CastServices.connectDeviceId("Küche"))
        assertEquals("trimmed", "3c31e63dda8e146428f7b25084e98915", CastServices.connectDeviceId(" Living Room speaker "))
    }

    @Test
    fun aCastEntryIsKeyedByItsCastId() {
        val device = CastServices.device(CastServices.parse(nestAudio)!!, "Google-Nest-Audio-8c1b", "192.168.1.30", 8009, null)
        assertEquals("3c31e63dda8e146428f7b25084e98915", device.deviceId)
        assertEquals("cast:8c1b2a3d4e5f60718293a4b5c6d7e8f9", device.key)
        assertTrue(device.isCast)
        assertEquals("Google Nest Audio", device.model)
        // Without an id record the service name keys it.
        val noId = CastServices.device(CastServices.parse(txt("fn" to "TV"))!!, "Chromecast-1234", "192.168.1.31", 8009, null)
        assertEquals("cast:Chromecast-1234", noId.key)
    }

    @Test
    fun hostAndScopeHaveNoZone() {
        assertEquals("192.168.1.30" to null, ServiceAddress.hostAndScope(InetAddress.getByName("192.168.1.30")) { error("not asked") })
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 5)
        assertEquals("fe80:0:0:0:0:0:0:1" to 5, ServiceAddress.hostAndScope(scoped) { error("own scope wins") })
        assertEquals(3, ServiceAddress.hostAndScope(InetAddress.getByName("fe80::1")) { 3 }?.second)
        assertNull("link-local without an interface", ServiceAddress.hostAndScope(InetAddress.getByName("fe80::1")) { null })
    }

    // --- dedupe ------------------------------------------------------------------------------------

    private fun cast(name: String, host: String = "192.168.1.30", group: Boolean = false, castId: String = name) = LocalConnectDevice(
        deviceId = CastServices.connectDeviceId(name),
        name = name,
        type = DeviceType.SPEAKER,
        endpoint = LocalEndpoint.Cast(host, 8009, castId),
        isGroup = group,
    )

    private fun zeroconf(id: String, name: String, host: String = "192.168.1.50") = LocalConnectDevice(
        deviceId = id,
        name = name,
        type = DeviceType.SPEAKER,
        endpoint = LocalEndpoint.ZeroConf("http://$host:4070/zc", host),
    )

    private fun inCluster(id: String, name: String = "Other") = ConnectDevice(id = id, name = name)

    @Test
    fun castDevicesAlreadyInTheClusterAreHidden() {
        val speaker = cast("Living Room speaker")
        // By the Connect id the receiver registers as (any case).
        assertEquals(emptyList<LocalConnectDevice>(), CastServices.visible(listOf(speaker), listOf(inCluster(speaker.deviceId.uppercase()))))
        // By name, where the id differs (it joined under another id).
        assertEquals(emptyList<LocalConnectDevice>(), CastServices.visible(listOf(speaker), listOf(inCluster("other-id", " living room SPEAKER"))))
        // Not in the cluster: shown.
        assertEquals(listOf(speaker), CastServices.visible(listOf(speaker), listOf(inCluster("x", "Kitchen"))))
    }

    @Test
    fun aSpeakerAdvertisingBothShowsOnlyItsZeroConfEntry() {
        val soundbar = zeroconf("4f1e0c0de", "Soundbar Q90", host = "192.168.1.30")
        val castTwin = cast("Living room TV", host = "192.168.1.30")
        val other = cast("Bedroom speaker", host = "192.168.1.31")
        // Same address, different names and ids: one device.
        assertEquals(listOf(soundbar, other), CastServices.visible(listOf(soundbar, castTwin, other), emptyList()))
        // Same name on another address (IPv6 vs IPv4): one device.
        val sameName = cast("Soundbar Q90", host = "fd00:0:0:0:0:0:0:30")
        assertEquals(listOf(soundbar), CastServices.visible(listOf(sameName, soundbar), emptyList()))
    }

    @Test
    fun theZeroConfTwinInTheClusterHidesTheCastEntryToo() {
        val soundbar = zeroconf("4f1e0c0de", "Soundbar Q90", host = "192.168.1.30")
        val castTwin = cast("Living room TV", host = "192.168.1.30")
        // The soundbar is already in the account under its native id: neither entry is shown.
        assertEquals(emptyList<LocalConnectDevice>(), CastServices.visible(listOf(soundbar, castTwin), listOf(inCluster("4f1e0c0de", "Soundbar Q90"))))
    }

    @Test
    fun aGroupIsNotHiddenByItsHostsAddress() {
        // A Cast group runs on one of its members, which may also be a ZeroConf speaker.
        val member = zeroconf("4f1e0c0de", "Soundbar Q90", host = "192.168.1.30")
        val group = cast("Downstairs", host = "192.168.1.30", group = true)
        assertEquals(listOf(member, group), CastServices.visible(listOf(member, group), emptyList()))
    }

    @Test
    fun zeroConfDevicesAreFilteredByClusterIdAsBefore() {
        val kitchen = zeroconf("abc", "Kitchen")
        val study = zeroconf("def", "Study")
        // Only the id counts for ZeroConf entries (their getInfo id is the cluster id).
        assertEquals(listOf(study), CastServices.visible(listOf(kitchen, study), listOf(inCluster("abc"), inCluster("zzz", "Study "))))
    }
}
