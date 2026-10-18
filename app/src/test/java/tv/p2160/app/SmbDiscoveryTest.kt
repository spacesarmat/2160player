package tv.p2160.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbDiscoveryTest {
    @Test
    fun prefersIpv4OverLinkLocalIpv6() {
        val hosts = listOf(
            HostCandidate("fe80::e5da:d771:331a:a55a", ipv4 = false, linkLocal = true),
            HostCandidate("192.168.1.20", ipv4 = true, linkLocal = false),
        )
        assertEquals("192.168.1.20", SmbDiscovery.preferredHost(hosts, "ZimaOS"))
    }

    @Test
    fun prefersHostnameOverLinkLocalIpv6() {
        val hosts = listOf(
            HostCandidate("fe80::1", ipv4 = false, linkLocal = true, hostName = "zimaos.local"),
        )
        assertEquals("zimaos.local", SmbDiscovery.preferredHost(hosts, "ZimaOS"))
    }

    @Test
    fun fallsBackToServiceLocalName() {
        val hosts = listOf(
            HostCandidate("fe80::e5da:d771:331a:a55a", ipv4 = false, linkLocal = true, hostName = "fe80::e5da:d771:331a:a55a"),
        )
        assertEquals("ZimaOS.local", SmbDiscovery.preferredHost(hosts, "ZimaOS"))
    }

    @Test
    fun skipsBareIpv6WhenNothingBetterExistsAndNameIsUnusable() {
        assertNull(SmbDiscovery.preferredHost(listOf(HostCandidate("fe80::1", ipv4 = false, linkLocal = true)), ":::"))
    }

    @Test
    fun linkLocalIpv4StillBeatsIpv6() {
        val hosts = listOf(
            HostCandidate("fe80::1", ipv4 = false, linkLocal = true),
            HostCandidate("169.254.4.8", ipv4 = true, linkLocal = true),
        )
        assertEquals("169.254.4.8", SmbDiscovery.preferredHost(hosts, "disk"))
    }
}
