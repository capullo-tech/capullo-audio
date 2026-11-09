package tech.capullo.audio.snapcast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Album art for the snapserver now-playing push (`artData`), as base64 of a small JPEG.
 *
 * Never send the original. Snapserver parses the whole metadata JSON on the same thread that reads
 * the audio pipe, so a large image stalls the broadcast: on a OnePlus 3 a full-size cover (an 800 px
 * JPEG is ~300 KB of base64, FLAC rips embed several MB) stopped the pipe for 150-290 ms at every
 * track change and every client heard a skip (snapserver `onResync`). Without the image: no resync.
 * [MAX_PX] keeps the push to a few tens of KB, still sharp in the web player.
 *
 * Decodes and compresses: call off the main thread. Null when [bytes] is not a decodable image.
 */
object SnapcastArtwork {
    const val MAX_PX = 256
    private const val JPEG_QUALITY = 80

    fun base64(bytes: ByteArray, maxPx: Int = MAX_PX): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Power-of-two subsampling while decoding (cheap, bounded memory), exact scale after.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxPx.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        // JPEG has no alpha and decoded bitmaps are premultiplied, so transparent pixels would encode
        // as black. Flatten transparent art (station favicons often are) onto white first.
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { flat ->
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
            }
        } else {
            bitmap
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        opaque.recycle()
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }
}
