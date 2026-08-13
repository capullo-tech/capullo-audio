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
        /** How loud each client actually is at the mic, INDEPENDENT of its peak z. Defaults to z so
         *  existing scenes are unchanged. Kept separate because that independence is the whole
         *  point: on the rig a speaker below the detection floor still produced a peak whose z sat
         *  near the reference's, so reading level off z reported an inaudible speaker as even. A
         *  fake that derives level from z could never catch that. */
        val level: Map<String, Double>? = null,
        val dropOnMeasureCall: Pair<String, Int>? = null,
        /** Return null (a dead capture) for every measure() call up to and including this 1-based
         *  index, to force a round to fail. Must cover ALL the baseline captures of a round, since
         *  one surviving baseline is enough for the round to proceed. */
        val nullOnMeasureCall: Int? = null,
        /** Return null for these specific 1-based measure() calls, to make individual captures
         *  inconclusive without killing a whole round (the rig's actual failure shape). */
        val nullOnCalls: Set<Int> = emptySet(),
        /** Clients MASKED BY ENERGY SHARING — the rig-measured two-speaker failure: a PHAT peak
         *  carries only its source's share of the capture energy, so a merged blob (arrivals close
         *  together) is salient while the same speaker probed apart from its sibling drops under the
         *  salience floor. Modelled as: the client's peak appears in the salient list only while
         *  another client's arrival sits within [MASK_MERGE_MS]; its arrival stays readable through
         *  [timingReader] at [maskedZ]. */
        val masked: Set<String> = emptySet(),
        val maskedZ: Double = 7.0,
        /** Masked clients whose arrival is gone from the TIMING read too (speaker genuinely absent
         *  or below even the window floor); their windows answer with noise instead. */
        val probeVanishes: Set<String> = emptySet(),
        /** z of the noise lump an empty window returns, at a lag that varies per capture — window
         *  noise never recurs at the same place, which is what the spacing quorum exploits. */
        val windowNoiseZ: Double = 4.0,
        /** 1-based measure() call -> error, in ms, added to [jitterClient]'s REPORTED peak lag
         *  while its true arrival (what the level reader answers at) stays put. Models the
         *  rig-measured failure: a quiet speaker's peak cannot be located reliably capture to
         *  capture (matched 74 ms apart in consecutive captures on 2026-08-11), so a level read at
         *  the matched lag lands on nothing. */
        val lagJitter: Map<Int, Double> = emptyMap(),
        val jitterClient: String? = null,
        /** Half-width of the fake's level read, mirroring SyncCalibrator.LEVEL_READ_HALF_MS. The
         *  default is the narrow point-read the shipped code used before 2026-08-11. */
        val levelHalfMs: Double = 8.0,
    ) : Measurer {
        var measureCalls = 0
        private fun peaks(callNo: Int): List<Dsp.Peak> {
            val live = intrinsic.mapValues { (id, base) -> base - (control.latency[id] ?: 0) }
            return intrinsic.keys.mapNotNull { id ->
                val lag = live.getValue(id)
                val merged = live.any { (o, l) -> o != id && kotlin.math.abs(l - lag) <= MASK_MERGE_MS }
                if (dropOnMeasureCall?.first == id && dropOnMeasureCall.second == callNo) {
                    null
                } else if (id in masked && !merged) {
                    null
                } else {
                    val err = if (id == jitterClient) lagJitter[callNo] ?: 0.0 else 0.0
                    Dsp.Peak(lag + err, z.getValue(id))
                }
            }
        }
        /** [sources] is recorded rather than acted on: this fake renders one peak per client by
         *  construction, so it has no cluster for the per-source search to matter to. The callers'
         *  expectation that it matches the audible-client count is asserted in
         *  `every measurement asks for as many sources as there are audible speakers`. */
        var lastSources: Int? = null
        override suspend fun measure(peakCount: Int, sources: Int): List<Dsp.Peak>? {
            measureCalls++
            lastSources = sources
            val dead = (nullOnMeasureCall != null && measureCalls <= nullOnMeasureCall) ||
                measureCalls in nullOnCalls
            return if (dead) null else peaks(measureCalls)
        }
        override suspend fun measureHalves(
            peakCount: Int,
            sources: Int,
        ): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>> {
            lastSources = sources
            val p = peaks(-1) // probe measurement; not counted against dropOnMeasureCall
            return Triple(p, p, p)
        }
        /** Answers by LAG, like the real one: find whose arrival sits at this lag and report its
         *  level. A lag nobody is playing at reads ~1.0 (the correlation floor), which is what makes
         *  an absent speaker distinguishable instead of scoring like a present one. */
        override fun levelReader(): ((Double) -> Double) {
            val live = intrinsic.mapValues { (id, base) -> base - (control.latency[id] ?: 0) }
            return { lag ->
                val who = live.entries.minByOrNull { kotlin.math.abs(it.value - lag) }
                if (who != null && kotlin.math.abs(who.value - lag) <= levelHalfMs) {
                    (level ?: z).getValue(who.key)
                } else {
                    1.0
                }
            }
        }

        /** Like the real one: the strongest lump in a ±tol window of the whitened correlation,
         *  bound to the capture at grab time. A real arrival in the window answers at its true lag
         *  (a masked client at [maskedZ] — masking hides it from the top-N list, not from a direct
         *  read); an empty window answers a noise lump at a per-capture-varying lag. */
        override fun timingReader(): ((Double, Double) -> Dsp.Peak?) {
            val call = measureCalls
            // A client dropped from this capture is gone from the timing correlation too — a
            // glitch silences the audio, not just the peak list.
            val live = intrinsic
                .filterKeys {
                    it !in probeVanishes &&
                        !(dropOnMeasureCall?.first == it && dropOnMeasureCall?.second == call)
                }
                .mapValues { (id, base) -> base - (control.latency[id] ?: 0) }
            return { lag, tol ->
                val who = live.entries.minByOrNull { kotlin.math.abs(it.value - lag) }
                if (who != null && kotlin.math.abs(who.value - lag) <= tol) {
                    Dsp.Peak(who.value, if (who.key in masked) maskedZ else z.getValue(who.key))
                } else {
                    Dsp.Peak(lag - tol + (call * 7.3) % (2 * tol), windowNoiseZ)
                }
            }
        }

        /** The pre-salience-filter list: every client's arrival, masked ones at [maskedZ] — the
         *  sub-salient baseline structure the rescue anchors its windows on. */
        override fun fullPeaks(): List<Dsp.Peak> = intrinsic.map { (id, base) ->
            Dsp.Peak(base - (control.latency[id] ?: 0), if (id in masked) maskedZ else z.getValue(id))
        }

        companion object {
            /** Arrivals closer than this render as one salient blob (the fake's stand-in for the
             *  share-of-energy physics; the rig's natural pair separation is ~17 ms). */
            const val MASK_MERGE_MS = 60.0
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
    fun `every measurement asks for as many sources as there are audible speakers`() = runTest {
        // The per-source peak search only helps if the caller tells it how many speakers to expect;
        // passing 0, or a stale count, silently restores the top-N behaviour where one loud
        // speaker's reflections fill the whole list and the quiet one is never seen. That is a
        // wiring mistake no acoustic assertion in this file would catch, because the fake renders
        // exactly one peak per client and so cannot reproduce a cluster.
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
            measurerFactory = { measurer },
        )
        cal.calibrate(clients("ref" to 0, "a" to 0, "b" to 0))
        assertEquals("batch path: reference plus both targets are audible", 3, measurer.lastSources)

        // The pair path runs with exactly two speakers audible (the others are muted).
        val pairControl = FakeControl(mapOf("ref" to 0, "t" to 0))
        val pairMeasurer = SceneMeasurer(
            pairControl,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1150.0),
            z = mapOf("ref" to 40.0, "t" to 25.0),
        )
        SyncCalibrator(
            tapArm = {},
            control = pairControl,
            readLatencies = { pairControl.latency.toMap() },
            measurerFactory = { pairMeasurer },
        ).calibrate(clients("ref" to 0, "t" to 0))
        assertEquals("pair path: reference plus one target", 2, pairMeasurer.lastSources)
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
        // No BOOST. The end-of-run balance may still write volumes (that is its job, and this scene
        // is genuinely lopsided at z 40 vs 20) — what must not happen is a client being shouted up
        // mid-run for detectability it did not need. A boost is identifiable as a write to one of
        // the ramp levels while the run is still measuring.
        assertTrue(
            "no detectability boost was applied, got ${control.volumeCalls}",
            control.volumeCalls.none { it == Triple("t", false, 60) || it == Triple("t", false, 75) },
        )
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
        assertTrue("must not jump straight to the ceiling", control.volumeCalls.none { it == Triple("t", false, 75) })
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
        assertTrue(
            "a client at a sane level is not pre-boosted",
            control.volumeCalls.none { it == Triple("b", false, BATCH_BOOST_FLOOR) },
        )
        // The pre-boost is restored. It is the LAST boost-related write, but not necessarily the
        // last write of the run: the end-of-run balance may then set persistent volumes, and a
        // pre-boosted client is excluded from that (its levels were measured boosted), so "a" must
        // end exactly where the listener had it.
        assertEquals(
            "pre-boost restored and not overwritten by the balance",
            Triple("a", false, 10),
            control.volumeCalls.last { it.first == "a" },
        )
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

    @Test
    fun `the pair path refuses an implausible correction instead of applying it`() = runTest {
        // Rig-caught 2026-08-03 calibrating from the OnePlus, whose mic sits beside its own speaker:
        // the reference came in at z~170-190, the across-room target barely registered, and
        // attribution latched a music self-similarity cluster ~2.1 s away. The pair path computed
        // delta=-2144ms and APPLIED it - it only checked the deadband, never plausibility, which the
        // batch path has always checked. Both probe samples agreed at high weight, so no amount of
        // repetition or quality weighting can catch it: a stable WRONG match is self-consistent.
        // Verify did restore it afterwards, but only after a two-second desync had been made audible.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            // The target's apparent peak sits ~2.1 s from the reference - a ghost, not a sink gap.
            intrinsic = mapOf("ref" to 1000.0, "t" to 3144.0),
            z = mapOf("ref" to 170.0, "t" to 20.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("the implausible correction must never be written", 0, control.latency["t"])
        val state = cal.state.value
        val text = (state as? SyncCalibrator.State.Done)?.summary
            ?: (state as? SyncCalibrator.State.Failed)?.reason ?: ""
        assertTrue("should report mis-attribution, got: $text", text.contains("implausible"))
    }

    @Test
    fun `the pair path harvests levels only from probed captures`() {
        // Levels come from the PROBED capture and nowhere else. An earlier version also harvested
        // baselines, to bank more samples at N=2 — that was wrong: in the baseline the speakers sit
        // at their natural arrivals, which on the rig were about a millisecond apart, and the level
        // estimator needs ~30 ms of separation. The probe offsets provide it; the baseline cannot.
        //
        // So a run yields at most one level capture per probe capture that attributed (plus one
        // from the verify probe). Here five of the six harvest probes are killed, leaving one probe
        // capture and the verify capture — two readings, still under MIN_LEVEL_SAMPLES — and the
        // balance must decline rather than act on them.
        runTest {
            val control = FakeControl(mapOf("ref" to 0, "t" to 0))
            val measurer = SceneMeasurer(
                control,
                intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
                z = mapOf("ref" to 40.0, "t" to 20.0),
                nullOnCalls = setOf(5, 6, 7, 8, 9), // 1-3 baselines, 4-9 harvest probes
            )
            val cal = SyncCalibrator(
                tapArm = {}, control = control,
                readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
            )
            cal.calibrate(
                listOf(
                    SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                    SyncCalibrator.CalClient("t", "t", 0, 100, false),
                ),
            )
            assertTrue(
                "one probe capture is under the bar, so nothing may be reported",
                cal.reduceLevels().isEmpty(),
            )
        }
    }

    @Test
    fun `a clean pair run banks one level capture per probe capture`() {
        runTest {
            val control = FakeControl(mapOf("ref" to 0, "t" to 0))
            val measurer = SceneMeasurer(
                control,
                intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
                z = mapOf("ref" to 40.0, "t" to 20.0),
                level = mapOf("ref" to 1.0, "t" to 0.5),
            )
            val cal = SyncCalibrator(
                tapArm = {}, control = control,
                readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
            )
            cal.calibrate(
                listOf(
                    SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                    SyncCalibrator.CalClient("t", "t", 0, 100, false),
                ),
            )
            val levels = cal.reduceLevels()
            assertTrue("both clients measured, got ${levels.keys}", levels.size == 2)
            assertEquals("the quieter client must read half", 0.5, levels.getValue("t") / levels.getValue("ref"), 1e-9)
        }
    }

    // ---- end-of-run volume balance --------------------------------------------------

    @Test
    fun `a lopsided room ends with the loud speaker attenuated and the quiet one on the cap`() = runTest {
        // "a" arrives at the mic twice as strong as "b" at the same gain. The balance evens them AT
        // THE MIC, which means pulling "a" down rather than pushing "b" up, and the loudest ends on
        // the headroom cap so the user's global control still has somewhere to go.
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
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        val refFinal = control.volumeCalls.last { it.first == "ref" }.third
        val tFinal = control.volumeCalls.last { it.first == "t" }.third
        assertEquals("the loudest lands on the headroom cap", VolumeBalance.HEADROOM_PERCENT, tFinal)
        assertTrue("the speaker that measured louder is attenuated ($refFinal vs $tFinal)", refFinal < tFinal)
        assertTrue("nothing is written at full scale", maxOf(refFinal, tFinal) <= VolumeBalance.HEADROOM_PERCENT)
    }

    @Test
    fun `an already-even room has its volumes left alone`() = runTest {
        // Same level at the mic: there is nothing to balance, so the listener's volumes stand.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 30.0, "t" to 30.0),
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 70, false),
                SyncCalibrator.CalClient("t", "t", 0, 70, false),
            ),
        )
        assertTrue("no volume was written, got ${control.volumeCalls}", control.volumeCalls.isEmpty())
    }

    @Test
    fun `a failed run still balances what it managed to measure`() = runTest {
        // The correction failed to verify, so no latency changed — but the levels were measured all
        // the same, and leaving a lopsided room lopsided because the SYNC half failed would waste
        // them. The balance runs off the run's finally for exactly this reason.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 20.0),
            // Pair path measure() order: 3 baselines, 6 harvest probes, verify baseline, verify
            // probe (harvest rounds take PROBE_REPEATS*2 probes). Killing the target in the verify
            // PROBE fails the verify after the levels are in hand.
            dropOnMeasureCall = "t" to 11,
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("the failed correction was restored", 0, control.latency["t"])
        assertTrue("volumes were still balanced", control.volumeCalls.isNotEmpty())
    }

    @Test
    fun `a speaker turned right down is not reported as level with the reference`() {
        // THE RIG FAILURE, 2026-08-04. The OnePlus SW gain was cut 100 -> 35 -> 15 percent; at 15 it
        // was inaudible in isolation (no peak above MIN_PEAK_Z). The balance still reported it at
        // 0.93 of the reference and said "already even", because it read the level off the matched
        // peak's z - and z comes from a top-N search, so an absent speaker gets handed the biggest
        // noise lump. Two pure-noise peaks sit at a ratio of 0.93 on average, which is precisely the
        // number the rig kept printing.
        //
        // Here z is deliberately kept EQUAL for both clients while the true level differs 20-fold:
        // any implementation reading z reports "even" and fails this test.
        runTest {
            val control = FakeControl(mapOf("ref" to 0, "t" to 0))
            val measurer = SceneMeasurer(
                control,
                intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
                z = mapOf("ref" to 12.0, "t" to 12.0), // detection looks the same for both
                level = mapOf("ref" to 20.0, "t" to 1.0), // the target is actually at the floor
            )
            val cal = SyncCalibrator(
                tapArm = {}, control = control,
                readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
            )
            cal.calibrate(
                listOf(
                    SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                    SyncCalibrator.CalClient("t", "t", 0, 100, false),
                ),
            )
            val levels = cal.reduceLevels()
            assertTrue("both clients must be measured, got ${levels.keys}", levels.size == 2)
            val ratio = levels.getValue("t") / levels.getValue("ref")
            assertTrue(
                "a speaker at the floor must not read as level with the reference (ratio $ratio)",
                ratio < 0.5,
            )
            assertTrue(
                "and the room must not be called balanced",
                !VolumeBalance.isBalanced(
                    levels.map { (id, l) -> VolumeBalance.Client(id, 100, l) },
                ),
            )
        }
    }

    @Test
    fun `levels survive a quiet speaker whose matched lag scatters between captures`() = runTest {
        // THE RIG FAILURE, 2026-08-11 01:54. With the target 12.4 dB down, its peak was matched
        // 74 ms apart in consecutive captures, and levels read AT THOSE LAGS reported -8.7 dB and
        // -1.2 dB for a room an independent capture put at -7.23 dB. The agreement gate then
        // declined — correct, but the feature never acts.
        //
        // Here the target's REPORTED lag is wrong by +-12 ms on two of the four probe captures —
        // inside MATCH_TOL_MS, so attribution still succeeds and the capture is USED, but outside
        // the level read's window, so the level lands on nothing. (A larger error would simply
        // fail to match and the capture would be discarded, which is not the defect.)
        // while its true arrival never moves. The consensus spacing (median over all samples) is
        // still right, so anchoring on the loud reference and DERIVING the target's lag must
        // recover the true level on every capture. Reading each capture's own matched lag cannot:
        // on the jittered captures it lands 60 ms away, where nothing is playing.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1200.0),
            z = mapOf("ref" to 40.0, "t" to 12.0),
            level = mapOf("ref" to 1.0, "t" to 0.25), // true room: target 12 dB down
            // Probe captures are calls 4-7 (1-3 are baselines).
            lagJitter = mapOf(4 to 12.0, 5 to -12.0, 6 to 10.0, 7 to -11.0),
            jitterClient = "t",
            levelHalfMs = 25.0, // mirrors SyncCalibrator.LEVEL_READ_HALF_MS
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        val levels = cal.reduceLevels()
        assertTrue("both clients must be measured, got ${levels.keys}", levels.size == 2)
        // The room is genuinely 12 dB lopsided. Reading each capture at its own mis-matched lag
        // finds nothing there and reports the floor for BOTH clients, i.e. "already even" — the
        // worst possible answer, since it is confidently wrong and writes nothing.
        assertEquals(
            "the true 0.25 ratio must survive the scatter",
            0.25,
            levels.getValue("t") / levels.getValue("ref"),
            0.02,
        )
        assertTrue(
            "a 12 dB lopsided room must not be called balanced",
            !VolumeBalance.isBalanced(levels.map { (id, l) -> VolumeBalance.Client(id, 100, l) }),
        )
    }

    @Test
    fun `a reference masked by energy sharing is rescued from its expected probe window`() = runTest {
        // The 2026-08-10 failing run, reconstructed: two speakers 40 ms apart at the mic. The
        // baseline blob is salient (arrivals merged), but the probe separates them and the
        // reference's correlation share drops under the salience floor — it vanishes from every
        // salient peak list while remaining plainly audible. Old behaviour: nine attribution
        // pairings with zero reference candidates, "reference not detected", nothing written.
        // With the targeted rescue the reference is read straight out of its expected window
        // (baseline leader + refOff), the spacing agrees across captures, and the pair completes
        // with the true 40 ms trim.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1040.0),
            z = mapOf("ref" to 30.0, "t" to 20.0),
            masked = setOf("ref"),
            maskedZ = 7.0, // rig-measured range for a real masked arrival: 6.5-9.0
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("the true 40ms trim must be found via the rescued reference", 40, control.latency["t"])
        val text = (cal.state.value as? SyncCalibrator.State.Done)?.summary ?: ""
        assertTrue("run should complete, got: ${cal.state.value}", text.contains("trim 40ms"))
    }

    @Test
    fun `a reference sub-salient in the baseline too is rescued via the full peak list`() = runTest {
        // The 21:32 rig failure: speakers ~150 ms apart at the mic, so there is no merged blob —
        // the reference is under the salience floor in the BASELINE as well as the probe, and a
        // rescue anchored on salient baseline leaders has nowhere to open its windows (the run
        // declined with 2 noise candidates). The full pre-filter list still carries the
        // reference's baseline region (rig: z 6.4-8.3), which is where the anchors must come from.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1150.0),
            z = mapOf("ref" to 30.0, "t" to 20.0),
            masked = setOf("ref"),
            maskedZ = 7.0,
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("the true 150ms trim must be found via sub-salient anchors", 150, control.latency["t"])
        val text = (cal.state.value as? SyncCalibrator.State.Done)?.summary ?: ""
        assertTrue("run should complete, got: ${cal.state.value}", text.contains("trim 150ms"))
    }

    @Test
    fun `a rescue candidate below the window floor is refused and nothing is written`() = runTest {
        // Same masking scene, but the reference's windowed read comes back at z 5 — inside the
        // measured noise band (±15 ms window maxima p95 5.1-6.0, absent-speaker control 5.3).
        // The rescue must refuse it: a decline is recoverable, a latency built on a noise lump
        // is not.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1040.0),
            z = mapOf("ref" to 30.0, "t" to 20.0),
            masked = setOf("ref"),
            maskedZ = 5.0,
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("no correction may be built on a sub-floor window read", 0, control.latency["t"])
    }

    @Test
    fun `window noise with a plausible z cannot fake a reference - the spacing quorum refuses it`() = runTest {
        // The adversarial case for the rescue: the reference is genuinely gone from the probed
        // audio (its window answers pure noise), and the noise lumps come back ABOVE the z floor.
        // What noise cannot do is recur at the same within-capture spacing across independent
        // captures — each capture's lump lands at a fresh lag. The quorum must refuse the rescue
        // and the run must decline rather than write a latency derived from noise.
        val control = FakeControl(mapOf("ref" to 0, "t" to 0))
        val measurer = SceneMeasurer(
            control,
            intrinsic = mapOf("ref" to 1000.0, "t" to 1040.0),
            z = mapOf("ref" to 30.0, "t" to 20.0),
            masked = setOf("ref"),
            probeVanishes = setOf("ref"),
            windowNoiseZ = 7.0, // plausible z — only the spacing test can catch this
        )
        val cal = SyncCalibrator(
            tapArm = {}, control = control,
            readLatencies = { control.latency.toMap() }, measurerFactory = { measurer },
        )
        cal.calibrate(
            listOf(
                SyncCalibrator.CalClient("ref", "ref", 0, 100, false),
                SyncCalibrator.CalClient("t", "t", 0, 100, false),
            ),
        )
        assertEquals("noise-derived spacings must not elect a reference", 0, control.latency["t"])
    }

    // ---- the sign gate distinguishes a swap from noise by GEOMETRY ------------------------
    //
    // Both scenes below have the SAME level pattern: four captures agreeing that "a" is louder and
    // one dissenting. Levels alone cannot tell them apart, which is why the gate used to decline
    // both. The lags can: a swap displaces the pair's spacing, noise leaves it intact.

    private fun signScene() = listOf(
        mapOf("a" to 1.00, "b" to 0.60),
        mapOf("a" to 1.00, "b" to 0.55),
        mapOf("a" to 0.62, "b" to 1.00), // the dissenter
        mapOf("a" to 1.00, "b" to 0.58),
        mapOf("a" to 1.00, "b" to 0.64),
    )

    @Test
    fun `sign disagreement with intact geometry is noise and does not decline`() = runTest {
        // THE RIG CASE, 2026-08-13: room ~3 dB out, per-capture spread ~5 dB, one capture of five
        // landed the other side of zero while its lags matched the round to 10 ms. Declining here
        // refuses to correct a room that genuinely needs it.
        val cal = SyncCalibrator(
            tapArm = {}, control = FakeControl(emptyMap()), readLatencies = { emptyMap() },
        )
        val levels = cal.reduceLevels(signScene(), List(5) { false })
        assertTrue("geometry was intact, so the run must not be declined as a swap", levels.isNotEmpty())
        assertTrue("both clients survive", levels.size == 2)
        assertTrue("the majority verdict stands: a is the louder one", levels.getValue("a") > levels.getValue("b"))
    }

    @Test
    fun `sign disagreement with broken geometry is still refused as a suspected swap`() = runTest {
        // Identical levels, but one capture's pair spacing departed from the round's consensus —
        // the signature of the reference and target being read at each other's arrivals. A swap
        // reports the reciprocal ratio with full confidence, so repetition cannot catch it and the
        // gate must still refuse.
        val cal = SyncCalibrator(
            tapArm = {}, control = FakeControl(emptyMap()), readLatencies = { emptyMap() },
        )
        val suspect = listOf(false, false, true, false, false)
        assertTrue(
            "a geometry mismatch must still decline",
            cal.reduceLevels(signScene(), suspect).isEmpty(),
        )
    }

    private companion object {
        /** Mirrors SyncCalibrator.BOOST_FLOOR_PERCENT (private there). */
        const val BATCH_BOOST_FLOOR = 50
    }
}
