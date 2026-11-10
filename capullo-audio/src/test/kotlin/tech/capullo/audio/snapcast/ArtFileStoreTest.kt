package tech.capullo.audio.snapcast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtFileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "webui/art") }
    private val store by lazy { ArtFileStore(dir, "/art/") }

    private fun jpeg(seed: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), seed.toByte())
    private fun png(seed: Int) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, seed.toByte())
    private fun files() = dir.list()!!.sorted()
    private fun nameOf(path: String?) = path!!.removePrefix("/art/")

    @Test
    fun `stores under a content hash with the extension of the bytes`() {
        val path = store.store(png(1))!!
        assertTrue(path, Regex("/art/[0-9a-f]{32}\\.png").matches(path))
        assertArrayEquals(png(1), File(dir, nameOf(path)).readBytes())
        assertEquals("same bytes, same URL", path, ArtFileStore(dir, "/art/").store(png(1)))
        assertNotEquals(path, store.store(png(2)))
        assertTrue(store.store(jpeg(1))!!.endsWith(".jpg"))
    }

    @Test
    fun `keeps only the current and the previous file`() {
        val a = nameOf(store.store(jpeg(1)))
        val b = nameOf(store.store(jpeg(2)))
        assertEquals(listOf(a, b).sorted(), files())
        val c = nameOf(store.store(jpeg(3)))
        assertEquals(listOf(b, c).sorted(), files())
        // Back to an earlier picture: it becomes current, the one just replaced stays as previous.
        val b2 = nameOf(store.store(jpeg(2)))
        assertEquals(b, b2)
        assertEquals(listOf(b, c).sorted(), files())
        // Republishing the current picture keeps the previous one too.
        store.store(jpeg(2))
        assertEquals(listOf(b, c).sorted(), files())
    }

    @Test
    fun `clears files left by an earlier process on the first store`() {
        dir.mkdirs()
        File(dir, "0123456789abcdef0123456789abcdef.jpg").writeBytes(jpeg(9))
        File(dir, ".stale.jpg.tmp").writeBytes(jpeg(8))
        val a = nameOf(store.store(png(1)))
        assertEquals(listOf(a), files())
    }

    @Test
    fun `refuses bytes that are not a published image format`() {
        assertNull(store.store("<svg/>".toByteArray()))
        assertNull(store.store(ByteArray(0)))
        assertTrue(!dir.exists() || files().isEmpty())
    }
}
