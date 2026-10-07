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

    @Test
    fun picksIpv4FirstThenRoutableIpv6() {
        assertEquals(v4, ServiceAddress.pick(listOf(linkLocal, ula, v4)))
        assertEquals(ula, ServiceAddress.pick(listOf(linkLocal, ula)))
        assertEquals(linkLocal, ServiceAddress.pick(listOf(linkLocal)))
        assertNull(ServiceAddress.pick(emptyList()))
    }

    @Test
    fun ipv4AndRoutableIpv6NeedNoScope() {
        val target = ServiceAddress.target(v4, 4070, "/zc") { error("not asked") }
        assertEquals(ServiceTarget("http://192.168.1.20:4070/zc", null), target)
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
