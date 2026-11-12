package dev.imirror.sender

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AirPlay calls to the iMirror receiver over raw sockets we close ourselves. The receiver serves one
 * connection at a time, so a pooled keep-alive connection (HttpURLConnection) blocks every later call.
 * A video is one [TvSession] kept open for the whole playback: the receiver ends URL playback when
 * the sender's connection closes. Blocking; call off the main thread.
 */
class TvSession private constructor(private val socket: Socket) : Closeable {
    private val input = socket.getInputStream().buffered()
    private val output = socket.getOutputStream()

    /** Polls the TV; IOException means the TV dropped the session. */
    fun playbackInfo(): PlaybackInfo = PlaybackInfo.parse(request("GET", "/playback-info"))

    override fun close() {
        runCatching { socket.close() }
    }

    private fun request(method: String, path: String, contentType: String? = null, body: ByteArray = ByteArray(0)): String {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII) + body)
        output.flush()
        return readResponse(input).let { (code, text) ->
            if (code !in 200..299) throw IOException("TV answered $code for $method $path")
            text
        }
    }

    companion object {
        /** Shows a photo; one request on its own connection (the photo stays up after it closes). */
        /** GET /server-info body, for telling the receiver apart from other things on port 7000. */
        fun serverInfo(tv: TvEndpoint, connectTimeoutMs: Int): String =
            open(tv, connectTimeoutMs).use { it.request("GET", "/server-info") }

        fun putPhoto(tv: TvEndpoint, jpeg: ByteArray) {
            open(tv).use { it.request("PUT", "/photo", "image/jpeg", jpeg) }
        }

        fun clearPhoto(tv: TvEndpoint) {
            open(tv).use { it.request("DELETE", "/photo") }
        }

        /** Connects and asks the TV to play [mediaUrl]; the receiver reads this legacy text body. */
        fun play(tv: TvEndpoint, mediaUrl: String): TvSession =
            open(tv).also {
                try {
                    it.request("POST", "/play", "text/parameters", "Content-Location: $mediaUrl\r\nStart-Position: 0\r\n".toByteArray())
                } catch (e: IOException) {
                    it.close()
                    throw e
                }
            }

        private fun open(tv: TvEndpoint, connectTimeoutMs: Int = 3_000) = TvSession(
            Socket().apply {
                connect(InetSocketAddress(tv.host, tv.port), connectTimeoutMs)
                soTimeout = 15_000
            },
        )
    }
}

/** Status code and body of one HTTP response; the receiver omits Content-Length on empty bodies. */
internal fun readResponse(input: InputStream): Pair<Int, String> {
    val head = ByteArrayOutputStream()
    while (!head.toByteArray().endsWithCrlfCrlf()) {
        val b = input.read()
        if (b < 0) throw IOException("TV closed the connection")
        head.write(b)
    }
    val lines = head.toString(Charsets.US_ASCII.name()).split("\r\n")
    val code = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: ${lines.first()}")
    val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        ?.substringAfter(":")?.trim()?.toInt() ?: 0
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) throw IOException("TV closed the connection")
        read += n
    }
    return code to String(body, Charsets.UTF_8)
}

private fun ByteArray.endsWithCrlfCrlf() =
    size >= 4 && this[size - 4] == '\r'.code.toByte() && this[size - 3] == '\n'.code.toByte() &&
        this[size - 2] == '\r'.code.toByte() && this[size - 1] == '\n'.code.toByte()

/** What GET /playback-info reports. Not ready while preparing, and again once playback has ended. */
data class PlaybackInfo(val readyToPlay: Boolean, val positionSec: Double, val durationSec: Double) {
    companion object {
        /** Reads the receiver's XML plist; missing keys mean "not ready". */
        fun parse(plist: String): PlaybackInfo {
            fun real(key: String) = Regex("<key>$key</key>\\s*<real>([-\\d.eE]+)</real>").find(plist)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val ready = Regex("<key>readyToPlay</key>\\s*<(true|false)\\s*/>").find(plist)?.groupValues?.get(1) == "true"
            return PlaybackInfo(ready, real("position"), real("duration"))
        }
    }
}
