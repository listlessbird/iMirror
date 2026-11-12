package dev.imirror.sender

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.security.SecureRandom

private const val POLL_MS = 2_000L
private const val PREPARE_LIMIT_MS = 60_000L

/**
 * One video on the TV: the phone-side file server the TV pulls from, the locks that keep it
 * reachable with the screen off, and the kept-open TV connection. [close] releases all three,
 * which also ends playback on the TV (the receiver stops when the connection drops).
 */
class VideoPlayback private constructor(
    private val server: MediaServer,
    private val awake: AwakeLocks,
    private val session: TvSession,
) : Closeable {
    /** Returns once the TV has played the video through. Throws if it never starts or drops us. */
    suspend fun awaitEnd() {
        val startedAt = SystemClock.elapsedRealtime()
        var started = false
        while (true) {
            delay(POLL_MS)
            // Not ready while preparing, and again once the video has ended.
            val info = withContext(Dispatchers.IO) { session.playbackInfo() }
            when {
                info.readyToPlay -> started = true
                started -> return
                SystemClock.elapsedRealtime() - startedAt > PREPARE_LIMIT_MS -> throw ShareError("TV couldn't play this video")
            }
        }
    }

    override fun close() {
        session.close()
        server.close()
        awake.close()
    }

    companion object {
        /** Blocking. Serves [video] and asks [tv] to play it; on failure nothing is left open. */
        fun start(context: Context, tv: TvEndpoint, video: Shareable.Video): VideoPlayback {
            val resolver = context.contentResolver
            val length = resolver.openFileDescriptor(video.uri, "r")?.use { it.statSize }
                ?.takeIf { it >= 0 } ?: throw ShareError("Can't read this video")
            val phone = phoneAddressFacing(tv) ?: throw ShareError("Not on the TV's Wi-Fi")
            val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

            val server = MediaServer(token, video.mime, length, openChannel = { openChannel(resolver, video.uri) })
            val awake = AwakeLocks(context)
            try {
                server.start()
                val session = TvSession.play(tv, "http://${phone.hostAddress}:${server.port}/$token")
                return VideoPlayback(server, awake, session)
            } catch (e: Throwable) {
                server.close()
                awake.close()
                throw e
            }
        }

        /** AutoCloseInputStream closes the descriptor together with the channel. */
        private fun openChannel(resolver: ContentResolver, uri: Uri) =
            ParcelFileDescriptor.AutoCloseInputStream(
                checkNotNull(resolver.openFileDescriptor(uri, "r")) { "provider returned no file" },
            ).channel
    }
}

/** The TV pulls video from us; with the screen off the CPU and Wi-Fi would otherwise doze mid-stream. */
@Suppress("DEPRECATION", "WakelockTimeout") // WIFI_MODE_FULL_HIGH_PERF is deprecated but still the lock that prevents Wi-Fi power save.
private class AwakeLocks(context: Context) : Closeable {
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShareToTv:serve").apply { acquire() }
    private val wifi = context.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShareToTv:serve").apply { acquire() }

    override fun close() {
        if (wake.isHeld) wake.release()
        if (wifi.isHeld) wifi.release()
    }
}

/** A failure whose message is already fit to show the user. */
class ShareError(message: String) : Exception(message)
