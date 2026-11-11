package dev.imirror.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_airplay._tcp."
private const val DISCOVERY_TIMEOUT_MS = 4_000L
private const val CONNECT_CHECK_MS = 700

/**
 * Finds the TV: the last known endpoint if it still accepts connections, otherwise the first
 * `_airplay._tcp` service mDNS resolves. Null when nothing answers.
 */
suspend fun findTv(context: Context): TvEndpoint? {
    val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val cached = prefs.getString("host", null)?.let { TvEndpoint(it, prefs.getInt("port", 7000)) }
    if (cached != null && withContext(Dispatchers.IO) { accepts(cached) }) return cached

    val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { discover(context.getSystemService(NsdManager::class.java)) }
    if (found != null) prefs.edit().putString("host", found.host).putInt("port", found.port).apply()
    return found
}

private fun accepts(tv: TvEndpoint): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(tv.host, tv.port), CONNECT_CHECK_MS) }
}.isSuccess

@Suppress("DEPRECATION") // resolveService is the only single-shot resolve on API 29-33.
private suspend fun discover(nsd: NsdManager): TvEndpoint? = suspendCancellableCoroutine { cont ->
    val resolving = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)

    lateinit var listener: NsdManager.DiscoveryListener
    fun stop() {
        if (stopped.compareAndSet(false, true)) runCatching { nsd.stopServiceDiscovery(listener) }
    }

    listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            // One resolve at a time; resolveService rejects overlapping calls.
            if (!resolving.compareAndSet(false, true)) return
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    if (host == null) { resolving.set(false); return }
                    stop()
                    if (cont.isActive) cont.resume(TvEndpoint(host, resolved.port))
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolving.set(false)
            })
        }

        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            stopped.set(true)
            if (cont.isActive) cont.resume(null)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    cont.invokeOnCancellation { stop() }
    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
}
