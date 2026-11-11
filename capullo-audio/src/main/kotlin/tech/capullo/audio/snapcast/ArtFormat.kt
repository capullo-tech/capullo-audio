package tech.capullo.audio.snapcast

/**
 * The image formats album art is published in, recognized by their magic bytes - never by a URL's
 * extension or a caller's label, which are often wrong (a radio favicon URL ending in `.png` that
 * serves JPEG, an IP address read as `.123`). The [extension] names the served file and the
 * `artData.extension` sent to snapserver, so it has to match the bytes.
 */
enum class ArtFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    ;

    companion object {
        /** Bytes [sniff] needs to see: the 12-byte RIFF/WEBP header is the longest signature. */
        internal const val HEADER_BYTES = 12

        /** Null when [bytes] start with none of the four signatures (SVG, ICO, BMP, HEIF, garbage). */
        fun sniff(bytes: ByteArray): ArtFormat? {
            fun at(i: Int, vararg sig: Int) = bytes.size >= i + sig.size &&
                sig.indices.all { (bytes[i + it].toInt() and 0xFF) == sig[it] }
            return when {
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                at(0, 0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> GIF
                at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> WEBP
                else -> null
            }
        }

        /**
         * [sniff] on base64 text, decoding only the first 16 characters (12 bytes), so labelling an
         * embedded image costs nothing however large it is. Pure Kotlin on purpose: android.util.Base64
         * is a stub on the JVM unit-test classpath and java.util.Base64 needs API 26.
         */
        fun sniffBase64(base64: String): ArtFormat? = sniff(decodeBase64Prefix(base64))

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private fun decodeBase64Prefix(base64: String): ByteArray {
            val quads = minOf(base64.length, HEADER_BYTES / 3 * 4) / 4
            val out = ByteArray(quads * 3)
            for (q in 0 until quads) {
                var v = 0
                for (k in 0 until 4) {
                    val d = ALPHABET.indexOf(base64[q * 4 + k])
                    // A non-alphabet char (padding, URL-safe variant, junk) can't start a real
                    // signature; zero bits make the sniff miss instead of throwing.
                    v = (v shl 6) or (if (d < 0) 0 else d)
                }
                out[q * 3] = (v shr 16).toByte()
                out[q * 3 + 1] = (v shr 8).toByte()
                out[q * 3 + 2] = v.toByte()
            }
            return out
        }
    }
}
