package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapclientStatsTest {

    @Test
    fun `parses the on-device Stats line and converts tenths to ms`() {
        // Verbatim shape observed on the OPPO rig (tab-separated, aixlog timestamp prefix).
        val line = "2026-07-21 12:00:01.123 [Debug] (Stats) Chunk: -3\t-1\t0\t0\t500\t308\t0"
        val stats = SnapclientStats.parse(line)!!
        assertEquals(-0.3, stats.chunkErrorMs, 1e-9)
        assertEquals(-0.1, stats.miniMedianErrorMs, 1e-9)
        assertEquals(0.0, stats.shortMedianErrorMs, 1e-9)
        assertEquals(0.0, stats.medianErrorMs, 1e-9)
        assertEquals(500, stats.statsWindowFill)
        assertEquals(308.0, stats.reportedOutputLatencyMs, 1e-9)
        assertEquals(0, stats.frameDelta)
    }

    @Test
    fun `parses space-separated fields too`() {
        val stats = SnapclientStats.parse("[Debug] (Stats) Chunk: 12 4 2 1 137 41 -3")!!
        assertEquals(1.2, stats.chunkErrorMs, 1e-9)
        assertEquals(137, stats.statsWindowFill)
        assertEquals(41.0, stats.reportedOutputLatencyMs, 1e-9)
        assertEquals(-3, stats.frameDelta)
    }

    @Test
    fun `non-stats lines return null`() {
        assertNull(SnapclientStats.parse("[Notice] (Connection) Connected to 192.168.1.2"))
        assertNull(SnapclientStats.parse("[Info] (Stream) pBuffer->full() && (abs(median_) > 2): 2500"))
    }

    @Test
    fun `malformed stats lines return null`() {
        assertNull(SnapclientStats.parse("[Debug] (Stats) Chunk: -3 -1 0 0 500 308")) // 6 fields
        assertNull(SnapclientStats.parse("[Debug] (Stats) Chunk: -3 -1 x 0 500 308 0")) // non-numeric
    }
}
