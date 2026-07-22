package tech.capullo.audio.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crash-recovery contract: an interrupted run leaves originals journaled, and
 * [SyncCalibrator.recover] restores exactly those and clears the journal; a finished run
 * leaves nothing to recover.
 */
class CalibrationJournalTest {

    /** In-memory journal standing in for the host's file-backed one. */
    private class FakeJournal(var stored: Map<String, Int>? = null) : CalibrationJournal {
        override fun save(originals: Map<String, Int>) { stored = originals }
        override fun load(): Map<String, Int>? = stored
        override fun clear() { stored = null }
    }

    @Test
    fun `recover restores the journaled originals and clears the journal`() = runBlocking {
        val journal = FakeJournal(mapOf("oneplus" to -145, "iphone" to 219, "ref" to 0))
        val applied = mutableListOf<Pair<String, Int>>()
        val restored = SyncCalibrator.recover(journal) { id, v -> applied += id to v }
        assertEquals(setOf("oneplus", "iphone", "ref"), restored.toSet())
        assertEquals(setOf("oneplus" to -145, "iphone" to 219, "ref" to 0), applied.toSet())
        assertTrue("journal must be cleared after recovery", journal.load() == null)
    }

    @Test
    fun `recover is a no-op when no run was interrupted`() = runBlocking {
        val journal = FakeJournal(null)
        val applied = mutableListOf<Pair<String, Int>>()
        val restored = SyncCalibrator.recover(journal) { id, v -> applied += id to v }
        assertTrue(restored.isEmpty())
        assertTrue(applied.isEmpty())
    }
}
