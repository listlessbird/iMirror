package dev.imirror.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeHeaderTest {
    private fun range(header: String?, size: Long = 1000) = parseRange(header, size)

    @Test fun `closed range`() = assertEquals(RangeResult.Partial(0, 99), range("bytes=0-99"))

    @Test fun `open ended range runs to the last byte`() = assertEquals(RangeResult.Partial(500, 999), range("bytes=500-"))

    @Test fun `end is clamped to the body`() = assertEquals(RangeResult.Partial(900, 999), range("bytes=900-5000"))

    @Test fun `suffix range takes the tail`() = assertEquals(RangeResult.Partial(900, 999), range("bytes=-100"))

    @Test fun `suffix longer than body is the whole body`() = assertEquals(RangeResult.Partial(0, 999), range("bytes=-5000"))

    @Test fun `start past the end is unsatisfiable`() = assertEquals(RangeResult.Unsatisfiable, range("bytes=1000-"))

    @Test fun `empty body is unsatisfiable for suffix`() = assertEquals(RangeResult.Unsatisfiable, range("bytes=-5", size = 0))

    @Test fun `missing, malformed and multi ranges fall back to the full body`() {
        listOf(null, "", "items=0-1", "bytes=abc", "bytes=5-2", "bytes=0-1,5-6", "bytes=").forEach {
            assertEquals(it, RangeResult.Full, range(it))
        }
    }
}
