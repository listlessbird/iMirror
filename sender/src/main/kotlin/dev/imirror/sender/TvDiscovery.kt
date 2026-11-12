package dev.imirror.sender

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

private const val AIRPLAY_PORT = 7000
private const val CONNECT_MS = 400

/**
 * Finds the TV: the last known host if it still answers as an AirPlay receiver, otherwise a scan
 * of the phone's /24 for one. mDNS is skipped on purpose: the phone never saw the TV's advert.
 */
suspend fun findTv(context: Context): TvEndpoint? = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val cached = prefs.getString("host", null)?.let { TvEndpoint(it, AIRPLAY_PORT) }
    if (cached != null && isTv(cached)) return@withContext cached

    val found = scan(phoneSubnetHosts())
    if (found != null) prefs.edit().putString("host", found.host).apply()
    found
}

private suspend fun scan(hosts: List<String>): TvEndpoint? = coroutineScope {
    hosts.map { host -> async { TvEndpoint(host, AIRPLAY_PORT).takeIf(::isTv) } }.awaitAll().firstOrNull { it != null }
}

/** Port 7000 answers and /server-info names an Apple TV model (iMirror's advertised identity). */
private fun isTv(tv: TvEndpoint): Boolean =
    runCatching { TvSession.serverInfo(tv, CONNECT_MS).contains("AppleTV") }.getOrDefault(false)

private fun phoneSubnetHosts(): List<String> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .flatMap { hostsInSlash24(it).asSequence() }
        .distinct()
        .toList()

/** Every other host address in [self]'s /24. */
internal fun hostsInSlash24(self: Inet4Address): List<String> {
    val b = self.address.map { it.toInt() and 0xFF }
    return (1..254).filter { it != b[3] }.map { "${b[0]}.${b[1]}.${b[2]}.$it" }
}
