package tech.capullo.audio.snapcast

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The broadcaster's full-size art files, in a directory snapserver serves (see [SnapcastArtwork]).
 *
 * Files are named `<content hash>.<ext>`: a URL never changes meaning, so web players and listen-in
 * clients may cache it, and republishing the same picture rewrites nothing. The extension comes from
 * the bytes ([ArtFormat.sniff]) because snapserver picks the Content-Type from it.
 *
 * Keeps the current and the previous file and deletes everything else on each store: a client that
 * read the old track's metadata just before the change can still fetch its art, and stale files from
 * an earlier process (whose names this instance never saw) go on the first store. A file is written
 * under a temporary name and renamed into place, so the HTTP server never serves a partial image.
 *
 * Blocking file IO: call off the main thread.
 */
internal class ArtFileStore(
    private val dir: File,
    /** URL path [dir] is served under, with the trailing slash. */
    private val urlPrefix: String,
) {
    private var current: String? = null
    private var previous: String? = null

    /** URL path of the stored file (`<urlPrefix><hash>.<ext>`), or null when [bytes] are not a known image or the write failed. */
    @Synchronized
    fun store(bytes: ByteArray): String? {
        val format = ArtFormat.sniff(bytes) ?: return null
        val name = "${contentHash(bytes)}.${format.extension}"
        try {
            dir.mkdirs()
            val file = File(dir, name)
            if (!file.isFile) {
                val tmp = File(dir, ".$name.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return null
                }
            }
        } catch (_: IOException) {
            return null
        }
        if (name != current) {
            previous = current
            current = name
        }
        dir.listFiles()?.forEach { if (it.name != current && it.name != previous) it.delete() }
        return urlPrefix + name
    }

    private fun contentHash(bytes: ByteArray): String =
        // 128 bits of SHA-256: collision-free for any realistic art history, and a short URL.
        MessageDigest.getInstance("SHA-256").digest(bytes).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
