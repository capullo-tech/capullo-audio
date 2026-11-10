package tech.capullo.audio.snapcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Album art as published for one track: where this device's snapserver serves the full image, and
 * the tiny embedded fallback. Goes into the now-playing as
 * `NowPlaying(artworkBase64 = thumbnailBase64, extras = mapOf("artUrl" to urlPath))`.
 */
data class SnapcastArt(
    /** Path on the snapserver's HTTP server, e.g. `/art/3f2a….jpg`: the metadata `artUrl`. */
    val urlPath: String,
    /** Base64 JPEG of at most [SnapcastArtwork.THUMBNAIL_PX] px (metadata `artData`); null when the image could not be decoded. */
    val thumbnailBase64: String?,
)

/**
 * Publishes album art for the snapserver now-playing push without putting the image in the push.
 *
 * Snapserver parses the now-playing JSON on the thread that reads the audio pipe, so an embedded image
 * (`artData`) of real size stalls the broadcast at every track change: on a OnePlus 3 full-size art
 * cost 150-290 ms of `onResync` per change, none without art. So [publish] writes the full image into
 * the snapserver doc_root (`<doc_root>/art/<hash>.<ext>`, see [ArtFileStore]); snapserver's own HTTP
 * server serves it, the web player loads it same-origin (so through the public tunnel too) and
 * listen-in clients fetch it from `http://<host>:<httpPort><path>` ([SnapcastRemoteArt]). The push
 * carries only that path (`artUrl`) plus a thumbnail of at most [THUMBNAIL_PX] px for clients that
 * read nothing but embedded art. [SnapcontrolMetadataMapper] enforces the size limit on `artData`
 * independently, so an app that hands it anything bigger loses the embed rather than stalling.
 *
 * One instance per app process: it remembers which files are current (see [ArtFileStore]).
 */
class SnapcastArtwork(context: Context) {

    private val store = ArtFileStore(File(SnapserverProcess.docRoot(context), ART_DIR), "/$ART_DIR/")

    /**
     * Stores [bytes] for snapserver to serve and builds the thumbnail. Runs on [Dispatchers.IO]
     * whatever the caller's thread (file write + image decode). Null when [bytes] are not a
     * JPEG/PNG/WebP/GIF or the file could not be written: then there is no art to announce.
     */
    suspend fun publish(bytes: ByteArray): SnapcastArt? = withContext(Dispatchers.IO) {
        val path = store.store(bytes) ?: return@withContext null
        SnapcastArt(path, thumbnailBase64(bytes))
    }

    companion object {
        private const val TAG = "SnapcastArtwork"
        private const val ART_DIR = "art"

        /** Longest side of the embedded fallback: enough for a list icon or a notification. */
        const val THUMBNAIL_PX = 96

        /** The web player's art box (`#art-wrap`): transparent art is flattened onto it, not onto
         *  white (JPEG has no alpha) - a white square would flash behind every transparent logo. */
        private const val THUMBNAIL_BACKGROUND = 0xFF171B26.toInt()

        private val JPEG_QUALITIES = intArrayOf(80, 60, 40)

        /**
         * A JPEG of at most [THUMBNAIL_PX] px, base64, within [SnapcontrolMetadataMapper.MAX_ART_DATA_CHARS]
         * (lower qualities are tried if a busy image overshoots). Null when the image can't be decoded
         * or memory runs out: the push then simply has no `artData`, never a crash.
         */
        @OptIn(UnstableApi::class)
        internal fun thumbnailBase64(bytes: ByteArray): String? {
            var decoded: Bitmap? = null
            var thumb: Bitmap? = null
            try {
                // Power-of-two subsampling while decoding (bounded memory even for multi-MB art) down
                // to under 2x the target, then a filtered scale; BitmapUtil also applies the EXIF
                // rotation, which BitmapFactory ignores.
                val src = BitmapUtil.decode(bytes, bytes.size, null, THUMBNAIL_PX * 2).also { decoded = it }
                val scale = minOf(1f, THUMBNAIL_PX.toFloat() / maxOf(src.width, src.height))
                val w = Math.round(src.width * scale).coerceAtLeast(1)
                val h = Math.round(src.height * scale).coerceAtLeast(1)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { thumb = it }
                Canvas(out).apply {
                    drawColor(THUMBNAIL_BACKGROUND)
                    drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val buf = ByteArrayOutputStream()
                for (quality in JPEG_QUALITIES) {
                    buf.reset()
                    out.compress(Bitmap.CompressFormat.JPEG, quality, buf)
                    val b64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
                    if (b64.length <= SnapcontrolMetadataMapper.MAX_ART_DATA_CHARS) return b64
                }
                return null
            } catch (e: Exception) {
                Log.w(TAG, "No art thumbnail: ${e.message}")
                return null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "No art thumbnail: out of memory decoding ${bytes.size} bytes")
                return null
            } finally {
                thumb?.recycle()
                decoded?.recycle()
            }
        }
    }
}
