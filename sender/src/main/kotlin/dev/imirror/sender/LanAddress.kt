package dev.imirror.sender

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The phone's IPv4 address on the same subnet as the TV, so the TV can reach our file server. */
fun phoneAddressFacing(tv: TvEndpoint): Inet4Address? {
    val tvAddress = runCatching { InetAddress.getByName(tv.host) }.getOrNull() as? Inet4Address ?: return null
    val local = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .filter { it.address is Inet4Address }
        .toList()
    val onSubnet = local.firstOrNull { sameSubnet(it.address, it.networkPrefixLength.toInt(), tvAddress) }
    return (onSubnet ?: local.firstOrNull { it.address.isSiteLocalAddress })?.address as? Inet4Address
}

/** True when [a] and [b] share the first [prefix] bits. */
internal fun sameSubnet(a: InetAddress, prefix: Int, b: InetAddress): Boolean {
    val x = a.address
    val y = b.address
    if (x.size != y.size) return false
    var bits = prefix
    for (i in x.indices) {
        if (bits <= 0) return true
        val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
        if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
        bits -= 8
    }
    return true
}
