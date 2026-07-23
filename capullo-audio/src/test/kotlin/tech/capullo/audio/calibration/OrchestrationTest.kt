package tech.capullo.audio.calibration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the full [SyncCalibrator] orchestration (probe → attribute → split-half → verify →
 * commit/restore → read-back, and the fallback cascade) with a fake control and a
 * scene-driven measurer, so the routing branches the rig can't exercise deterministically
 * are pinned. Uses runTest, so the SETTLE/prime/read-back delays are virtual (instant).
 */
class OrchestrationTest {

    /** Fake snapserver control: an in-memory per-client latency map + a call log. */
    private class FakeControl(initial: Map<String, Int>) : CalibrationControl {
        val latency = initial.toMutableMap()
        override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? {
            latency[clientId] = latencyMs
            return 1
        }
        override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {}
        override suspend fun sendGetStatus() {}
    }

    private class FakeJournal(var stored: Map<String, Int>? = null) : CalibrationJournal {
        override fun save(originals: Map<String, Int>) { stored = originals }
        override fun load(): Map<String, Int>? = stored
        override fun clear() { stored = null }
    }

    /**
     * Renders each client's correlation peak from the LIVE latency map: peak lag =
     * intrinsic − latency (negative latency = plays later = larger lag). Halves are
     * identical (perfectly stable → split-half consistent). [dropOnMeasureCall] omits a
     * client's peak on a chosen 1-based measure() call, to simulate a glitch in verify.
     */
    private class SceneMeasurer(
        val control: FakeControl,
        val intrinsic: Map<String, Double>,
        val z: Map<String, Double>,
        val dropOnMeasureCall: Pair<String, Int>? = null,
    ) : Measurer {
        var measureCalls = 0
        private fun peaks(callNo: Int): List<Dsp.Peak> = intrinsic.mapNotNull { (id, base) ->
            if (dropOnMeasureCall?.first == id && dropOnMeasureCall.second == callNo) null
            else Dsp.Peak(base - (control.latency[id] ?: 0), z.getValue(id))
        }
        override suspend fun measure(peakCount: Int): List<Dsp.Peak> = peaks(++measureCalls)
        override suspend fun measureHalves(peakCount: Int): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>> {
            val p = peaks(-1) // probe measurement; not counted against dropOnMeasureCall
            return Triple(p, p, p)
        }
    }

    private fun clients(vararg ids: Pair<String, Int>) = ids.map { (id, lat) ->
        SyncCalibrator.CalClient(id, id, lat, 100, false)
    }

    @Test
    fun `batch aligns both targets, commits, and reconciles`() = runTest {
        // ref at 1000; targets 150 and 220 ms behind it, both starting at latency 0.
        val control = FakeControl(mapOf("ref" to 0, "a" to 0, "b" to 0))
        val journal = FakeJournal()
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "a" to 1150.0, "b" to 1220.0),
            z = mapOf("ref" to 40.0, "a" to 25.0, "b" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {},
            control = control,
            readLatencies = { control.latency.toMap() },
            journal = journal,
            measurerFactory = { measurer },
        )
        val ok = cal.calibrate(clients("ref" to 0, "a" to 0, "b" to 0))

        assertTrue("run should succeed, state=${cal.state.value}", ok)
        assertTrue(cal.state.value is SyncCalibrator.State.Done)
        // Each target moved onto the reference; the reference is untouched.
        assertEquals(0, control.latency["ref"])
        assertEquals(150, control.latency["a"])
        assertEquals(220, control.latency["b"])
        assertNull("journal cleared after a completed run", journal.load())
    }

    @Test
    fun `an already-aligned pair reports aligned instead of failing verify`() {
        // Two clients only (pair path), target ~12 ms from the reference — within the
        // deadband. Their probed peaks would overlap, so the verify can't tell them apart;
        // the deadband must short-circuit to "already aligned" and leave latencies untouched.
        runTest {
            val control = FakeControl(mapOf("ref" to 0, "t" to 0))
            val measurer = SceneMeasurer(
                control,
                intrinsic = mapOf("ref" to 1000.0, "t" to 1012.0),
                z = mapOf("ref" to 40.0, "t" to 30.0),
            )
            val cal = SyncCalibrator(
                tapArm = {},
                control = control,
                readLatencies = { control.latency.toMap() },
                measurerFactory = { measurer },
            )
            cal.calibrate(clients("ref" to 0, "t" to 0))
            val state = cal.state.value
            assertTrue("state=$state", state is SyncCalibrator.State.Done)
            assertTrue((state as SyncCalibrator.State.Done).summary.contains("already aligned"))
            assertEquals(0, control.latency["ref"])
            assertEquals(0, control.latency["t"])
        }
    }

    @Test
    fun `a target that glitches in verify falls back and the batch is not wasted`() = runTest {
        // Same scene, but target b's peak vanishes on the verify measurement (2nd measure()
        // call: 1=baseline, 2=verify). Quorum (>=2) is then unreachable, so BOTH route to
        // the muted pair path, where they are re-measured cleanly and still commit.
        val control = FakeControl(mapOf("ref" to 0, "a" to 0, "b" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "a" to 1150.0, "b" to 1220.0),
            z = mapOf("ref" to 40.0, "a" to 25.0, "b" to 20.0),
            dropOnMeasureCall = "b" to 2,
        )
        val cal = SyncCalibrator(
            tapArm = {},
            control = control,
            readLatencies = { control.latency.toMap() },
            measurerFactory = { measurer },
        )
        val ok = cal.calibrate(clients("ref" to 0, "a" to 0, "b" to 0))

        // Fell out of the batch, but the pair-path fallback still aligned both.
        assertTrue("state=${cal.state.value}", cal.state.value is SyncCalibrator.State.Done)
        assertEquals(0, control.latency["ref"])
        assertEquals(150, control.latency["a"])
        assertEquals(220, control.latency["b"])
        assertTrue(ok)
    }
}
