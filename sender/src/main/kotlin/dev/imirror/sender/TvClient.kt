package dev.imirror.sender

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One-shot AirPlay photo calls; video goes through [TvSession]. Blocking; call off the main thread. */
object TvClient {
    fun putPhoto(tv: TvEndpoint, jpeg: ByteArray) = send(tv, "PUT", "/photo", "image/jpeg", jpeg)

    fun clearPhoto(tv: TvEndpoint) = send(tv, "DELETE", "/photo", null, null)

    private fun send(tv: TvEndpoint, method: String, path: String, contentType: String?, body: ByteArray?) {
        val conn = URL("http://${tv.host}:${tv.port}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = 15_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("TV answered $code for $method $path")
        } finally {
            conn.disconnect()
        }
    }
}
