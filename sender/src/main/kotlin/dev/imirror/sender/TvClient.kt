package dev.imirror.sender

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The three AirPlay HTTP calls the iMirror receiver needs. Blocking; call off the main thread. */
object TvClient {
    fun putPhoto(tv: TvEndpoint, jpeg: ByteArray) = send(tv, "PUT", "/photo", "image/jpeg", jpeg)

    /** Asks the TV to play [mediaUrl]; the receiver reads this legacy text body. */
    fun play(tv: TvEndpoint, mediaUrl: String) =
        send(tv, "POST", "/play", "text/parameters", "Content-Location: $mediaUrl\r\nStart-Position: 0\r\n".toByteArray())

    fun stop(tv: TvEndpoint) = send(tv, "POST", "/stop", "text/parameters", ByteArray(0))

    private fun send(tv: TvEndpoint, method: String, path: String, contentType: String, body: ByteArray) {
        val conn = URL("http://${tv.host}:${tv.port}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("TV answered $code for $method $path")
        } finally {
            conn.disconnect()
        }
    }
}
