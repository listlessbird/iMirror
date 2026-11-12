package dev.imirror.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSessionParsingTest {
    @Test
    fun `reads back-to-back responses with and without Content-Length`() {
        val wire = "HTTP/1.1 200 OK\r\nServer: AirTunes/220.68\r\n\r\n" +
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
        val input = wire.byteInputStream()
        assertEquals(200 to "", readResponse(input))
        assertEquals(200 to "hello", readResponse(input))
    }

    @Test
    fun `parses the receiver's playback-info plist`() {
        val playing = """
            <dict>
            	<key>duration</key>
            	<real>61.934</real>
            	<key>position</key>
            	<real>1.811</real>
            	<key>readyToPlay</key>
            	<true/>
            	<key>loadedTimeRanges</key>
            	<array><dict><key>duration</key><real>0.0</real></dict></array>
            </dict>
        """.trimIndent()
        assertEquals(PlaybackInfo(true, 1.811, 61.934), PlaybackInfo.parse(playing))
        assertEquals(PlaybackInfo(false, 0.0, 0.0), PlaybackInfo.parse("<key>readyToPlay</key>\n\t<false/>"))
    }
}
