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
        val volumeCalls = mutableListOf<Triple<String, Boolean, Int>>() // (id, muted, percent), in order
        override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? {
            latency[clientId] = latencyMs
            return 1
        }
        override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {
            volumeCalls += Triple(clientId, muted, percent)
        }
        override suspend fun sendGetStatus() {}
    }

    private class FakeJournal(
        var stored: Map<String, ClientSnapshot>? = null,
        private val saveOk: Boolean = true,
    ) : CalibrationJournal {
        override fun save(originals: Map<String, ClientSnapshot>): Boolean {
            if (saveOk) stored = originals
            return saveOk
        }
        override fun load(): Map<String, ClientSnapshot>? = stored
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
        /** Return null (a dead capture) for every measure() call up to and including this 1-based
         *  index, to force a round to fail. Must cover ALL the baseline captures of a round, since
         *  one surviving baseline is enough for the round to proceed. */
        val nullOnMeasureCall: Int? = null,
    ) : Measurer {
        var measureCalls = 0
        private fun peaks(callNo: Int): List<Dsp.Peak> = intrinsic.mapNotNull { (id, base) ->
            if (dropOnMeasureCall?.first == id && dropOnMeasureCall.second == callNo) null
            else Dsp.Peak(base - (control.latency[id] ?: 0), z.getValue(id))
        }
        override suspend fun measure(peakCount: Int): List<Dsp.Peak>? {
            measureCalls++
            val dead = nullOnMeasureCall != null && measureCalls <= nullOnMeasureCall
            return if (dead) null else peaks(measureCalls)
        }
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

    @Test
    fun `a failed journal save aborts before any client is mutated`() = runTest {
        // If the pre-run state can't be journaled, a crash mid-run would be unrecoverable, so
        // the run must not touch a single client's latency or volume.
        val control = FakeControl(mapOf("ref" to 0, "a" to 0, "b" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "a" to 1150.0, "b" to 1220.0),
            z = mapOf("ref" to 40.0, "a" to 25.0, "b" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {},
            control = control,
            readLatencies = { control.latency.toMap() },
            journal = FakeJournal(saveOk = false),
            measurerFactory = { measurer },
        )
        val ok = cal.calibrate(clients("ref" to 0, "a" to 0, "b" to 0))

        assertTrue(!ok)
        assertTrue("state=${cal.state.value}", cal.state.value is SyncCalibrator.State.Failed)
        // No measurement ran, no latency was written.
        assertEquals(0, measurer.measureCalls)
        assertEquals(mapOf("ref" to 0, "a" to 0, "b" to 0), control.latency)
    }

    @Test
    fun `the 2-client pair path does not boost when the first attempt succeeds`() = runTest {
        // At N=2 the pair path is PRIMARY and mutes nothing: the listener is hearing the room, so a
        // routine calibration must not shout. Boost buys only detectability, so if the target was
        // already detectable its volume must be left exactly where the listener set it.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 50, false),
            ),
        )
        assertTrue("no volume was touched", control.volumeCalls.isEmpty())
    }

    @Test
    fun `a failed pair round retries with a boost and restores the volume after`() = runTest {
        // Only a target that actually failed pays the disruption of being boosted.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
            nullOnMeasureCall = 3, // all three baseline captures dead -> the round fails
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 50, false), // starts at 50%
            ),
        )
        // The ramp's FIRST level, not the cap: past the detection floor, extra level lifts room
        // reflections over the floor too and degrades attribution (rig-measured).
        assertTrue("retry boosted to the ramp's first level", control.volumeCalls.contains(Triple("t", false, 60)))
        assertTrue("must not jump straight to the cap", control.volumeCalls.none { it == Triple("t", false, 100) })
        assertEquals("last volume write restores the original", Triple("t", false, 50), control.volumeCalls.last())
    }

    @Test
    fun `the no-mute batch lifts a too-quiet client to the floor and restores it`() = runTest {
        // The batch mutes nothing, so a pre-boost is its only loudness lever — and for a web client
        // (skipped by the muted fallback) it is the only rescue there is. It must lift to the floor,
        // not to the cap, and put the volume back afterwards.
        val control = FakeControl(mapOf("ref" to 0, "a" to 0, "b" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "a" to 1150.0, "b" to 1220.0),
            z = mapOf("ref" to 40.0, "a" to 25.0, "b" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("a", "a", 0, 10, false), // left very quiet
                SyncCalibrator.CalClient("b", "b", 0, 100, false), // already fine
            ),
        )
        assertTrue("quiet client raised to the floor", control.volumeCalls.contains(Triple("a", false, 50)))
        assertTrue("a client at a sane level is left alone", control.volumeCalls.none { it.first == "b" })
        assertEquals("pre-boost restored", Triple("a", false, 10), control.volumeCalls.last())
    }

    @Test
    fun `a target already at 100 percent SW gain is boosted via its device OS volume`() = runTest {
        // The hole this closes: SW gain is 100% by DEFAULT, so a "boost" to 100 is a no-op and the
        // quiet speaker stays unmeasurable. The only remaining headroom is the client's own OS
        // media volume, which must be leased to it and then explicitly released.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
            nullOnMeasureCall = 3, // kill every baseline so the boosted retry runs
        )
        val osCalls = mutableListOf<Pair<Map<String, Int>, Long>>()
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
            publishOsBoost = { targets, lease -> osCalls += targets to lease },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false), // SW already maxed
            ),
        )
        assertEquals("target leased at the ramp's first level", 60, osCalls.first().first["t"])
        // The reference must be findable too, or nothing can be measured against it — rig-observed
        // a reference at 25% device volume produced zero above-floor peaks and failed every round.
        assertEquals("reference boosted in the same lease", 60, osCalls.first().first["ref"])
        assertTrue("lease must be finite", osCalls.first().second > 0)
        assertTrue("released explicitly, not left to the lease", osCalls.last().first.isEmpty())
        assertTrue("SW gain must not be touched — it has no headroom", control.volumeCalls.none { it.first == "t" })
    }

    @Test
    fun `SW gain is preferred over the device OS volume when it has headroom`() = runTest {
        // OS volume is the intrusive knob (it changes the user's system volume), so it is only used
        // when the free digital one is exhausted.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
            nullOnMeasureCall = 3, // kill every baseline so a boost is attempted at all
        )
        val osCalls = mutableListOf<Pair<Map<String, Int>, Long>>()
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
            publishOsBoost = { targets, lease -> osCalls += targets to lease },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 50, false), // SW has room
            ),
        )
        assertTrue("target used its SW headroom", control.volumeCalls.contains(Triple("t", false, 60)))
        // The intrusive knob is not used on a client that still had digital headroom. (The
        // reference, already at 100% SW, legitimately does get a device lease so it can be found.)
        assertTrue(
            "target's device volume left alone",
            osCalls.none { it.first.containsKey("t") },
        )
    }

    @Test
    fun `a muted target is skipped up front, not probed for minutes then failed`() = runTest {
        // A muted speaker emits nothing, so it can never be attributed, and we must not unmute it.
        // Left in the run it burns a full pair round plus both boost escalations before reporting a
        // misleading "target peak did not move by the probe".
        val control = FakeControl(mapOf("ref" to 0, "a" to 0, "m" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "a" to 1150.0),
            z = mapOf("ref" to 40.0, "a" to 25.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("a", "a", 0, 100, false),
                SyncCalibrator.CalClient("m", "muted-one", 0, 100, true),
            ),
        )
        val state = cal.state.value
        assertTrue("state=$state", state is SyncCalibrator.State.Done)
        assertTrue(
            "the summary must say it was skipped for being muted",
            (state as SyncCalibrator.State.Done).summary.contains("skipped (muted): muted-one"),
        )
        assertEquals("a muted client's latency is never touched", 0, control.latency["m"])
        assertTrue("and its volume is never touched", control.volumeCalls.none { it.first == "m" })
    }

    @Test
    fun `a muted reference fails fast with an actionable reason`() = runTest {
        // Everything is measured against the reference, so if it is silent nothing can work.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        val ok = cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, true), // muted reference
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertTrue(!ok)
        val state = cal.state.value
        assertTrue("state=$state", state is SyncCalibrator.State.Failed)
        assertTrue(
            "must name the reference and say to unmute it",
            (state as SyncCalibrator.State.Failed).reason.contains("reference speaker ref is muted"),
        )
        assertEquals("nothing measured", 0, measurer.measureCalls)
    }

    @Test
    fun `a user-muted target is never boosted or unmuted`() = runTest {
        // R6: a mute is explicit user intent. The round must not write this client's volume at
        // all — no boost, and never an unmute.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 50, true), // muted
            ),
        )
        assertTrue("no volume write to a muted client", control.volumeCalls.none { it.first == "t" })
    }
}
