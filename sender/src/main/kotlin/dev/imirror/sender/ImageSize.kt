package dev.imirror.sender

/** Scales (w, h) down so the long edge is at most [maxEdge], keeping aspect. Never upscales. */
fun fitLongEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}
