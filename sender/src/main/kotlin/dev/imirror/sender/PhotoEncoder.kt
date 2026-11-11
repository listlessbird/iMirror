package dev.imirror.sender

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

private const val MAX_EDGE = 1920
private const val JPEG_QUALITY = 90

/** Decodes any format ImageDecoder knows (HEIC included) into a TV-sized JPEG. */
fun encodePhotoForTv(resolver: ContentResolver, uri: Uri): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val (w, h) = fitLongEdge(info.size.width, info.size.height, MAX_EDGE)
        decoder.setTargetSize(w, h)
        // Software bitmaps only: hardware ones cannot be compressed.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return ByteArrayOutputStream().also { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
    }.toByteArray()
}
