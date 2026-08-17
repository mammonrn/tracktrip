package app.ptrip.tracktrip.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Bytes ready to upload, and what they are. */
data class AvatarBytes(val bytes: ByteArray, val contentType: String) {
    // Data classes compare arrays by reference; these two exist so that
    // equality means what it says if this ever lands in a state object.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AvatarBytes && contentType == other.contentType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}

/** The longest edge an avatar is stored at. It is shown at 44–120dp. */
private const val MAX_EDGE_PX = 1024

/** JPEG quality for the re-encode. 85 is the usual point of diminishing returns. */
private const val JPEG_QUALITY = 85

/**
 * Reads a picked image and prepares it for upload.
 *
 * Downscaled and re-encoded rather than sent as picked. A modern phone camera
 * produces 4–12 MB files, which the server refuses over 5 MB — so without this
 * the gallery picker would hand back perfectly good photos that fail to
 * upload, and the rider would have no way to tell which ones will work.
 *
 * Re-encoding also drops the EXIF block, and with it the GPS tag on the
 * original photo. That is a welcome side effect on a location-sharing app.
 *
 * Returns null if the content can't be decoded as an image at all.
 */
suspend fun readAvatarBytes(resolver: ContentResolver, uri: Uri): AvatarBytes? =
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext null
        }

        // inSampleSize only honours powers of two, so this halves until the
        // longest edge is within budget — decoding a 12 MP image at full size
        // just to scale it down is how an OutOfMemoryError happens.
        var sample = 1
        while (
            maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX * 2
        ) {
            sample *= 2
        }

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return@withContext null

        val scaled = scaleToFit(decoded)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        AvatarBytes(bytes = output.toByteArray(), contentType = "image/jpeg")
    }

private fun scaleToFit(bitmap: Bitmap): Bitmap {
    val longestEdge = maxOf(bitmap.width, bitmap.height)
    if (longestEdge <= MAX_EDGE_PX) {
        return bitmap
    }
    val ratio = MAX_EDGE_PX.toFloat() / longestEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
