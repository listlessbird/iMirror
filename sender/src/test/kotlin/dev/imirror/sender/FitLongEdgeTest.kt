package dev.imirror.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class FitLongEdgeTest {
    @Test fun `landscape shrinks by width and keeps aspect`() = assertEquals(1920 to 1440, fitLongEdge(4000, 3000, 1920))

    @Test fun `portrait shrinks by height`() = assertEquals(1080 to 1920, fitLongEdge(3024, 5376, 1920))

    @Test fun `small images are not upscaled`() = assertEquals(800 to 600, fitLongEdge(800, 600, 1920))

    @Test fun `extreme aspect never rounds to zero`() = assertEquals(1 to 1920, fitLongEdge(1, 100_000, 1920))
}
