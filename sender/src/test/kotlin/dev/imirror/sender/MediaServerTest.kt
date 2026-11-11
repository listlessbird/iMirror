package dev.imirror.sender

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random

class MediaServerTest {
    private val body = Random(7).nextBytes(100_000)
    private lateinit var file: File
    private lateinit var server: MediaServer

    @Before fun setUp() {
        file = File.createTempFile("media", ".bin").apply { writeBytes(body) }
        server = MediaServer("tok", "video/mp4", body.size.toLong(), { FileChannel.open(file.toPath(), StandardOpenOption.READ) })
        server.start()
    }

    @After fun tearDown() {
        server.close()
        file.delete()
    }

    private fun request(method: String = "GET", path: String = "/tok", range: String? = null): HttpURLConnection =
        (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    @Test fun `plain GET returns the whole file`() {
        val conn = request()
        assertEquals(200, conn.responseCode)
        assertEquals("video/mp4", conn.contentType)
        assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
        assertArrayEquals(body, conn.inputStream.readBytes())
    }

    @Test fun `range GET returns 206 with exactly the requested bytes`() {
        val conn = request(range = "bytes=1000-2999")
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 1000-2999/${body.size}", conn.getHeaderField("Content-Range"))
        assertEquals(2000L, conn.contentLengthLong)
        assertArrayEquals(body.copyOfRange(1000, 3000), conn.inputStream.readBytes())
    }

    @Test fun `open ended range reads to the end`() {
        val conn = request(range = "bytes=99000-")
        assertEquals(206, conn.responseCode)
        assertArrayEquals(body.copyOfRange(99_000, body.size), conn.inputStream.readBytes())
    }

    @Test fun `range past the end is 416`() {
        val conn = request(range = "bytes=${body.size}-")
        assertEquals(416, conn.responseCode)
        assertEquals("bytes */${body.size}", conn.getHeaderField("Content-Range"))
    }

    @Test fun `HEAD reports length without a body`() {
        val conn = request(method = "HEAD")
        assertEquals(200, conn.responseCode)
        assertEquals(body.size.toLong(), conn.contentLengthLong)
    }

    @Test fun `any other path is 404`() = assertEquals(404, request(path = "/other").responseCode)

    @Test fun `concurrent range readers all get correct bytes`() {
        val failures = (0 until 8).map { i ->
            Thread {
                val start = i * 10_000
                val conn = request(range = "bytes=$start-${start + 9_999}")
                check(conn.inputStream.readBytes().contentEquals(body.copyOfRange(start, start + 10_000)))
            }.also { it.start() }
        }
        failures.forEach { it.join() }
    }
}
