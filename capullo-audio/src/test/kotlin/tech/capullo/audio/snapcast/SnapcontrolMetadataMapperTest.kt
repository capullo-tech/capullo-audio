package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class SnapcontrolMetadataMapperTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `the mapper embeds art up to the cap and labels it from its bytes`() {
        // A PNG whose base64 is exactly at the cap: embedded, and labelled png whatever the caller thought.
        val atCap = b64(png + ByteArray(SnapcontrolMetadataMapper.MAX_ART_DATA_CHARS / 4 * 3 - png.size))
        assertEquals(SnapcontrolMetadataMapper.MAX_ART_DATA_CHARS, atCap.length)
        val art = SnapcontrolMetadataMapper.embeddedArt(atCap)
        assertEquals(atCap, art?.data)
        assertEquals("png", art?.extension)
    }

    @Test
    fun `the mapper drops art over the cap and art it cannot label`() {
        val overCap = b64(jpeg + ByteArray(SnapcontrolMetadataMapper.MAX_ART_DATA_CHARS / 4 * 3))
        assertNull(SnapcontrolMetadataMapper.embeddedArt(overCap))
        assertNull(SnapcontrolMetadataMapper.embeddedArt(b64(ascii("<svg/>"))))
        assertNull(SnapcontrolMetadataMapper.embeddedArt(""))
        assertNull(SnapcontrolMetadataMapper.embeddedArt(null))
    }
}
