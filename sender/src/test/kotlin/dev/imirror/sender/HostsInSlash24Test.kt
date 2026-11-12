package dev.imirror.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class HostsInSlash24Test {
    @Test
    fun `lists the subnet without self, network or broadcast`() {
        val hosts = hostsInSlash24(InetAddress.getByName("192.168.1.10") as Inet4Address)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.10" in hosts)
    }
}
