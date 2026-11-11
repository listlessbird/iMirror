package dev.imirror.sender

sealed interface RangeResult {
    /** No usable Range header: serve the whole body with 200. */
    data object Full : RangeResult

    /** Inclusive byte offsets, already clamped to the body size. */
    data class Partial(val start: Long, val end: Long) : RangeResult

    /** Valid syntax but outside the body: 416. */
    data object Unsatisfiable : RangeResult
}

/**
 * Parses a single `bytes=` range against a body of [size] bytes. Per RFC 9110 a malformed or
 * multi-range header is ignored (full body), since multipart ranges are not worth supporting here.
 */
fun parseRange(header: String?, size: Long): RangeResult {
    val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }
        ?.substring(6)?.trim() ?: return RangeResult.Full
    if (',' in spec) return RangeResult.Full
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeResult.Full
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()

    if (first.isEmpty()) {
        // Suffix range: the final N bytes.
        val n = last.toLongOrNull()?.takeIf { it >= 0 } ?: return RangeResult.Full
        if (n == 0L || size == 0L) return RangeResult.Unsatisfiable
        return RangeResult.Partial(maxOf(0, size - n), size - 1)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return RangeResult.Full
    val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return RangeResult.Full
    if (end < start) return RangeResult.Full
    if (start >= size) return RangeResult.Unsatisfiable
    return RangeResult.Partial(start, minOf(end, size - 1))
}
