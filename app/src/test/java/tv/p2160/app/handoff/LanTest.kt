package tv.p2160.app.handoff

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanTest {
    @Test
    fun privateAddressesOnly() {
        assertTrue(Lan.isPrivate(InetAddress.getByName("10.1.2.3")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("192.168.1.5")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("172.16.0.1")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("172.31.255.1")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("169.254.1.1")))
        assertFalse(Lan.isPrivate(InetAddress.getByName("172.15.0.1")))
        assertFalse(Lan.isPrivate(InetAddress.getByName("172.32.0.1")))
        assertFalse(Lan.isPrivate(InetAddress.getByName("8.8.8.8")))
        assertFalse(Lan.isPrivate(InetAddress.getByName("127.0.0.1")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("fe80::1")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("fd00::1")))
        assertTrue(Lan.isPrivate(InetAddress.getByName("fc00::1")))
        assertFalse(Lan.isPrivate(InetAddress.getByName("2001:db8::1")))
    }
}
