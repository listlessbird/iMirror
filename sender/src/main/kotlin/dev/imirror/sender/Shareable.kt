package dev.imirror.sender

import android.net.Uri

/** What the user shared, parsed once at the boundary so the rest of the code never sniffs MIME types. */
sealed interface Shareable {
    data class Photo(val uri: Uri) : Shareable
    data class Video(val uri: Uri, val mime: String) : Shareable

    companion object {
        /** Null when [mime] or [uri] is missing or not image/video. */
        fun from(mime: String?, uri: Uri?): Shareable? {
            if (uri == null) return null
            return when (mediaKindOf(mime)) {
                MediaKind.Photo -> Photo(uri)
                MediaKind.Video -> Video(uri, mime!!.lowercase())
                null -> null
            }
        }
    }
}

enum class MediaKind { Photo, Video }

/** Pure MIME classification, kept free of android.net.Uri so it unit-tests on the JVM. */
fun mediaKindOf(mime: String?): MediaKind? {
    val type = mime?.lowercase() ?: return null
    return when {
        type.startsWith("image/") -> MediaKind.Photo
        type.startsWith("video/") -> MediaKind.Video
        else -> null
    }
}

data class TvEndpoint(val host: String, val port: Int)
