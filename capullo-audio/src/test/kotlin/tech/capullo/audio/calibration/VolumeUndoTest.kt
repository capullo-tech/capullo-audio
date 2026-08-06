package tech.capullo.audio.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the undo path for the balance's persistent volume writes.
 *
 * Why this is a safety feature and not a convenience: the balance writes server-persisted volumes off
 * a mic estimator whose hardware sensitivity is not yet established. The response to that is to bound
 * the mistake rather than to demand certainty first, and this is half of the bound (the other half is
 * [VolumeBalance.MAX_CORRECTION_DB]). Being wrong has to cost one action instead of re-levelling every
 * speaker by hand.
 */
class VolumeUndoTest {

    private class MemoryUndo : VolumeUndo {
        var stored: Map<String, Int>? = null
        override fun save(previous: Map<String, Int>) { stored = previous }
        override fun load(): Map<String, Int>? = stored
        override fun clear() { stored = null }
    }

    private class RecordingControl : CalibrationControl {
        val volumeWrites = mutableListOf<Triple<String, Boolean, Int>>()
        override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? = 1
        override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {
            volumeWrites += Triple(clientId, muted, percent)
        }
        override suspend fun sendGetStatus() {}
    }

    @Test
    fun `undo puts back exactly the volumes that were recorded`() = runBlocking {
        val undo = MemoryUndo().apply { save(mapOf("near" to 100, "far" to 65)) }
        val control = RecordingControl()
        val cal = SyncCalibrator(tapArm = {}, control = control, volumeUndo = undo)

        assertTrue("an outstanding balance must be offerable as an undo", cal.canUndoBalance())
        val restored = cal.undoBalance()

        assertEquals(setOf("near", "far"), restored.toSet())
        assertEquals(
            mapOf("near" to 100, "far" to 65),
            control.volumeWrites.associate { it.first to it.third },
        )
    }

    @Test
    fun `undo unmutes, because the balance itself wrote unmuted`() = runBlocking {
        // The balance writes muted=false along with every percentage. Restoring the number while
        // leaving a mute it had cleared would hand back a state the user never had.
        val undo = MemoryUndo().apply { save(mapOf("a" to 80)) }
        val control = RecordingControl()
        SyncCalibrator(tapArm = {}, control = control, volumeUndo = undo).undoBalance()
        assertTrue("restore must not leave a client muted", control.volumeWrites.all { !it.second })
    }

    @Test
    fun `undo is consumed, so pressing it twice does not walk further back`() = runBlocking {
        val undo = MemoryUndo().apply { save(mapOf("a" to 80)) }
        val control = RecordingControl()
        val cal = SyncCalibrator(tapArm = {}, control = control, volumeUndo = undo)

        assertEquals(listOf("a"), cal.undoBalance())
        assertTrue("the record is spent", !cal.canUndoBalance())
        assertEquals("a second undo must write nothing", emptyList<String>(), cal.undoBalance())
        assertEquals(1, control.volumeWrites.size)
    }

    @Test
    fun `nothing to undo is not an error`() = runBlocking {
        val control = RecordingControl()
        val cal = SyncCalibrator(tapArm = {}, control = control, volumeUndo = MemoryUndo())
        assertTrue(!cal.canUndoBalance())
        assertEquals(emptyList<String>(), cal.undoBalance())
        assertTrue(control.volumeWrites.isEmpty())
    }

    @Test
    fun `a host that supplies no undo store still calibrates`() = runBlocking {
        // volumeUndo is optional: the balance stays one-way rather than refusing to run.
        val cal = SyncCalibrator(tapArm = {}, control = RecordingControl(), volumeUndo = null)
        assertTrue(!cal.canUndoBalance())
        assertEquals(emptyList<String>(), cal.undoBalance())
    }
}
