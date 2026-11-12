package dev.imirror.sender

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 file server for one body, reachable only at `/<token>`. The TV's MediaPlayer
 * needs Range support (it seeks to the moov atom) and opens several connections at once.
 *
 * [openChannel] is called per GET and the channel is closed after the response.
 */
class MediaServer(
    private val token: String,
    private val mime: String,
    private val length: Long,
    private val openChannel: () -> FileChannel,
) : Closeable {
    private val serverSocket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "media-server").apply { isDaemon = true } }

    val port: Int get() = serverSocket.localPort

    fun start() {
        pool.execute {
            while (!serverSocket.isClosed) {
                val socket = try { serverSocket.accept() } catch (_: IOException) { return@execute }
                pool.execute { socket.use(::serve) }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private class Request(val method: String, val path: String, val http10: Boolean, val headers: Map<String, String>)

    private fun serve(socket: Socket) {
        socket.soTimeout = IDLE_CONNECTION_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            while (true) {
                val request = readRequest(input) ?: return
                if (!respond(request, output)) return
            }
        } catch (_: IOException) {
            // Client hung up mid-response, or the keep-alive timed out.
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input)?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return Request(parts[0], parts[1], parts[2] == "HTTP/1.0", headers)
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    /** Writes the response; returns whether the connection can serve another request. */
    private fun respond(request: Request, out: BufferedOutputStream): Boolean {
        val keepAlive = !request.http10 && !request.headers["connection"].equals("close", ignoreCase = true)
        val connection = if (keepAlive) "keep-alive" else "close"

        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, "405 Method Not Allowed", listOf("Allow: GET, HEAD", "Content-Length: 0", "Connection: close"))
            return false
        }
        if (request.path.substringBefore('?') != "/$token") {
            writeHead(out, "404 Not Found", listOf("Content-Length: 0", "Connection: $connection"))
            return keepAlive
        }

        val (status, start, end) = when (val range = parseRange(request.headers["range"], length)) {
            RangeResult.Full -> Triple("200 OK", 0L, length - 1)
            is RangeResult.Partial -> Triple("206 Partial Content", range.start, range.end)
            RangeResult.Unsatisfiable -> {
                writeHead(out, "416 Range Not Satisfiable", listOf("Content-Range: bytes */$length", "Content-Length: 0", "Connection: $connection"))
                return keepAlive
            }
        }
        val count = end - start + 1
        val headers = mutableListOf("Content-Type: $mime", "Content-Length: $count", "Accept-Ranges: bytes", "Connection: $connection")
        if (status.startsWith("206")) headers += "Content-Range: bytes $start-$end/$length"
        writeHead(out, status, headers)

        if (request.method == "GET" && count > 0) {
            if (!copyRange(out, start, count)) return false
        }
        out.flush()
        return keepAlive
    }

    private fun writeHead(out: BufferedOutputStream, status: String, headers: List<String>) {
        out.write(("HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Streams [count] bytes from [start]; false if the source ran short (connection must then close). */
    private fun copyRange(out: BufferedOutputStream, start: Long, count: Long): Boolean {
        openChannel().use { channel ->
            channel.position(start)
            val buffer = ByteBuffer.allocate(CHUNK)
            var remaining = count
            while (remaining > 0) {
                buffer.clear().limit(minOf(CHUNK.toLong(), remaining).toInt())
                val n = channel.read(buffer)
                if (n < 0) return false
                out.write(buffer.array(), 0, n)
                remaining -= n
            }
        }
        return true
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_LINE = 8192
        const val MAX_HEADERS = 100
        const val IDLE_CONNECTION_MS = 30_000
    }
}
