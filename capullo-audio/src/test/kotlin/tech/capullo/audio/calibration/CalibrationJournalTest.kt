package tech.capullo.audio.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crash-recovery contract: an interrupted run leaves originals journaled, and
 * [SyncCalibrator.recover] restores exactly those (latency AND volume) and clears the journal;
 * a finished run leaves nothing to recover.
 */
class CalibrationJournalTest {

    /** In-memory journal standing in for the host's file-backed one. */
    private class FakeJournal(var stored: Map<String, ClientSnapshot>? = null) : CalibrationJournal {
        override fun save(originals: Map<String, ClientSnapshot>): Boolean { stored = originals; return true }
        override fun load(): Map<String, ClientSnapshot>? = stored
        override fun clear() { stored = null }
    }

    private fun snap(latency: Int, percent: Int = 100, muted: Boolean = false) =
        ClientSnapshot(latency, percent, muted)

    @Test
    fun `recover restores the journaled latencies and volumes and clears the journal`() = runBlocking {
        val journal = FakeJournal(
            mapOf("oneplus" to snap(-145), "iphone" to snap(219), "ref" to snap(0)),
        )
        val latency = mutableListOf<Pair<String, Int>>()
        val volume = mutableListOf<Triple<String, Boolean, Int>>()
        val restored = SyncCalibrator.recover(
            journal,
            { id, v -> latency += id to v },
            { id, m, p -> volume += Triple(id, m, p) },
        )
        assertEquals(setOf("oneplus", "iphone", "ref"), restored.toSet())
        assertEquals(setOf("oneplus" to -145, "iphone" to 219, "ref" to 0), latency.toSet())
        assertEquals(
            setOf(Triple("oneplus", false, 100), Triple("iphone", false, 100), Triple("ref", false, 100)),
            volume.toSet(),
        )
        assertTrue("journal must be cleared after recovery", journal.load() == null)
    }

    @Test
    fun `recover un-mutes a client a killed pair round left muted`() = runBlocking {
        // The muted-pair fallback silences "other" clients; a crash mid-round strands them
        // muted forever unless the journal restored the pre-run volume. This is the bug the
        // volume snapshot exists to fix.
        val journal = FakeJournal(mapOf("other" to snap(latency = 30, percent = 80, muted = false)))
        val volume = mutableListOf<Triple<String, Boolean, Int>>()
        SyncCalibrator.recover(journal, { _, _ -> }, { id, m, p -> volume += Triple(id, m, p) })
        assertEquals(listOf(Triple("other", false, 80)), volume)
    }

    @Test
    fun `recover is a no-op when no run was interrupted`() = runBlocking {
        val journal = FakeJournal(null)
        val latency = mutableListOf<Pair<String, Int>>()
        val volume = mutableListOf<Triple<String, Boolean, Int>>()
        val restored = SyncCalibrator.recover(
            journal,
            { id, v -> latency += id to v },
            { id, m, p -> volume += Triple(id, m, p) },
        )
        assertTrue(restored.isEmpty())
        assertTrue(latency.isEmpty())
        assertTrue(volume.isEmpty())
    }
}
