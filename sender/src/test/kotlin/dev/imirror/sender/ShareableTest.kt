package dev.imirror.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareableTest {
    @Test fun `images are photos, including heic`() {
        assertEquals(MediaKind.Photo, mediaKindOf("image/jpeg"))
        assertEquals(MediaKind.Photo, mediaKindOf("image/heic"))
    }

    @Test fun `videos are videos regardless of case`() = assertEquals(MediaKind.Video, mediaKindOf("Video/MP4"))

    @Test fun `anything else is unsupported`() {
        assertNull(mediaKindOf("text/plain"))
        assertNull(mediaKindOf("*/*"))
        assertNull(mediaKindOf(null))
    }
}
