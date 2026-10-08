package com.taehagen.spotifygood.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class ServiceAddressTest {
    private val v4 = InetAddress.getByName("192.168.1.20")
    private val linkLocal = InetAddress.getByName("fe80::1")
    private val ula = InetAddress.getByName("fd00::1")

    private fun scopedLinkLocal(scope: Int): Inet6Address =
        Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, scope)

    private val global = InetAddress.getByName("2a02:1234::5")

    @Test
    fun picksIpv4FirstThenUniqueLocalThenLinkLocal() {
        assertEquals(v4, ServiceAddress.pick(listOf(linkLocal, ula, v4)))
        assertEquals(ula, ServiceAddress.pick(listOf(linkLocal, ula)))
        assertEquals(linkLocal, ServiceAddress.pick(listOf(linkLocal)))
        assertNull(ServiceAddress.pick(emptyList()))
    }

    @Test
    fun neverPicksAnAddressRustRefuses() {
        assertNull(ServiceAddress.pick(listOf(global)))
        assertEquals(ula, ServiceAddress.pick(listOf(global, ula)))
        assertEquals(linkLocal, ServiceAddress.pick(listOf(global, linkLocal)))
        assertNull(ServiceAddress.pick(listOf(InetAddress.getByName("8.8.8.8"))))
        val all = listOf(global, linkLocal, InetAddress.getByName("8.8.8.8"), ula, v4)
        assertEquals(listOf(v4, ula, linkLocal), ServiceAddress.candidates(all))
    }

    @Test
    fun allowlistMirrorsRust() {
        // Same cases as Rust `zeroconf_client::http::tests::only_local_urls`.
        for (ok in listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "169.254.10.10", "127.0.0.1", "fd00::1", "::1", "fe80::1")) {
            assertEquals(ok, true, ServiceAddress.isLocal(InetAddress.getByName(ok)))
        }
        for (bad in listOf("8.8.8.8", "172.32.0.1", "2001:db8::1", "2a02:1234::5", "fec0::1")) {
            assertEquals(bad, false, ServiceAddress.isLocal(InetAddress.getByName(bad)))
        }
        // An IPv4-mapped IPv6 address counts as its IPv4 address.
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1 }
        val mappedPrivate = Inet6Address.getByAddress(null, mapped.copyOf().also { b -> byteArrayOf(192.toByte(), 168.toByte(), 1, 2).copyInto(b, 12) }, 0)
        val mappedPublic = Inet6Address.getByAddress(null, mapped.copyOf().also { b -> byteArrayOf(8, 8, 8, 8).copyInto(b, 12) }, 0)
        assertEquals(true, ServiceAddress.isLocal(mappedPrivate))
        assertEquals(false, ServiceAddress.isLocal(mappedPublic))
    }

    @Test
    fun ipv4AndRoutableIpv6NeedNoScope() {
        val target = ServiceAddress.target(v4, 4070, "/zc") { error("not asked") }
        assertEquals(ServiceTarget("http://192.168.1.20:4070/zc", null, "192.168.1.20"), target)
        val ipv6 = ServiceAddress.target(ula, 80, null) { error("not asked") }
        assertEquals(null, ipv6?.scopeId)
        assertEquals("http://[fd00:0:0:0:0:0:0:1]:80/", ipv6?.url)
    }

    @Test
    fun linkLocalKeepsItsZoneAsScopeId() {
        val target = ServiceAddress.target(scopedLinkLocal(7), 4070, "/") { error("own scope wins") }
        assertEquals(7, target?.scopeId)
        assertFalse("no zone in the URL", target!!.url.contains('%'))
        assertEquals("http://[fe80:0:0:0:0:0:0:1]:4070/", target.url)
    }

    @Test
    fun linkLocalWithoutZoneUsesTheLanInterface() {
        assertEquals(3, ServiceAddress.target(linkLocal, 4070, "/") { 3 }?.scopeId)
        // No interface known: unreachable, so not offered at all.
        assertNull(ServiceAddress.target(linkLocal, 4070, "/") { null })
        assertNull(ServiceAddress.target(linkLocal, 4070, "/") { 0 })
    }

    @Test
    fun rejectsBadPorts() {
        assertNull(ServiceAddress.target(v4, 0, "/") { null })
        assertNull(ServiceAddress.target(v4, 70000, "/") { null })
    }

    @Test
    fun cPathRecord() {
        assertEquals("/", ServiceAddress.path(null))
        assertEquals("/", ServiceAddress.path("  "))
        assertEquals("/zc", ServiceAddress.path("zc"))
        assertEquals("/spotify/zc", ServiceAddress.path("/spotify/zc"))
        assertEquals("/zc", ServiceAddress.cPath(mapOf("cpath" to "/zc".toByteArray())))
        assertEquals("/zc", ServiceAddress.cPath(mapOf("VERSION" to "1".toByteArray(), "CPath" to "/zc".toByteArray())))
        assertNull(ServiceAddress.cPath(mapOf("CPath" to null)))
        assertNull(ServiceAddress.cPath(emptyMap()))
    }
}
