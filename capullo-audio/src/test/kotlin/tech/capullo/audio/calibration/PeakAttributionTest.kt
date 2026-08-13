package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the clustering and probe-matching rules of the simultaneous (v2) flow on
 * hand-built peak lists. Scene-level coverage (real GCC-PHAT peaks) lives in
 * DelayMeasurementTest; here the geometry is exact so the edge cases are cheap.
 */
class PeakAttributionTest {

    private fun peak(lagMs: Double, z: Double) = Dsp.Peak(lagMs, z)

    @Test
    fun `reflection within cluster width merges into direct cluster`() {
        // Direct at 1200 with a +6 ms reflection: one cluster, leader at the direct path.
        val leaders = PeakAttribution.clusterLeaders(
            listOf(peak(1200.0, 50.0), peak(1206.0, 15.0), peak(1290.0, 40.0)),
        )
        assertEquals(listOf(1200.0, 1290.0), leaders.map { it.lagMs }.sorted())
        assertEquals(50.0, leaders.first().z, 0.0) // strongest first
    }

    @Test
    fun `reference and targets are matched by displacement, reflections do not steal`() {
        // probesMs[0]=60 (reference), [1]=90 (target). Each entity has a direct path and a
        // static reflection that moves with it. The direct paths (higher z) must win, and
        // matches[0] must be the reference identified by its own +60 move.
        val baseline = listOf(
            peak(1200.0, 50.0), peak(1214.0, 12.0), // reference + reflection
            peak(1230.0, 40.0), peak(1244.0, 10.0), // target + reflection
        )
        val probed = listOf(
            peak(1260.0, 50.0), peak(1274.0, 12.0), // reference moved +60
            peak(1320.0, 40.0), peak(1334.0, 10.0), // target moved +90
        )
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0)
        assertEquals(1260.0, res.matches[0]!!.probedLagMs, 0.0)
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 0.0)
        assertEquals(1320.0, res.matches[1]!!.probedLagMs, 0.0)
    }

    @Test
    fun `an unmoved salient ghost is never elected as the reference`() {
        // THE common-mode fix. A ghost at 1100 is the STRONGEST cluster and stays fixed
        // across captures (music self-similarity). The real reference at 1200 moves +60.
        // The reference must be identified by its move, and the ghost catalogued, not
        // elected — electing it would bias every delta by 100 ms invisibly.
        val baseline = listOf(peak(1100.0, 60.0), peak(1200.0, 30.0), peak(1230.0, 25.0))
        val probed = listOf(peak(1100.0, 60.0), peak(1260.0, 30.0), peak(1320.0, 25.0))
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0) // reference, not the ghost
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 0.0)
        assertTrue("ghost must be catalogued", res.ghostLagsMs.any { abs(it - 1100.0) < 1.0 })
    }

    @Test
    fun `clock drift between measurements is absorbed by the tolerance`() {
        // Everything 9 ms later in the probed capture (rig-observed drift ~9.4 ms).
        val baseline = listOf(peak(1200.0, 50.0), peak(1230.0, 40.0))
        val probed = listOf(peak(1269.0, 50.0), peak(1329.0, 40.0)) // +60/+90, +9 drift
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0)
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 0.0)
    }

    @Test
    fun `drift pre-pass recovers a batch whose captures drifted past the tolerance`() {
        // 120 ms of global timeline drift between baseline and probe — far more than
        // MATCH_TOL=15, so without the pre-pass no shift matches its offset. With it, the
        // modal drift is removed and all entities attribute; drift is reported.
        val baseline = listOf(peak(1200.0, 50.0), peak(1230.0, 40.0), peak(1310.0, 30.0))
        val probed = listOf( // each = baseline + offset(60/90/150) + 120 drift
            peak(1380.0, 50.0), peak(1440.0, 40.0), peak(1580.0, 30.0),
        )
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90, 150), matchTolMs = 15.0)
        assertEquals(120.0, res.driftMs, 2.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0)
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 0.0)
        assertEquals(1310.0, res.matches[2]!!.baselineLagMs, 0.0)
    }

    @Test
    fun `no drift is inferred when there is none`() {
        val baseline = listOf(peak(1200.0, 50.0), peak(1230.0, 40.0))
        val probed = listOf(peak(1260.0, 50.0), peak(1320.0, 40.0)) // +60/+90, no drift
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertEquals(0.0, res.driftMs, 2.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0)
    }

    @Test
    fun `a reference that fails to move leaves the batch unidentified`() {
        // The reference should move +60 but its peak stayed (glitch) — matches[0] must be
        // null so the caller degrades to pair rounds rather than trusting a bad anchor.
        val baseline = listOf(peak(1200.0, 50.0), peak(1230.0, 40.0))
        val probed = listOf(peak(1200.0, 50.0), peak(1320.0, 40.0)) // ref unmoved, target +90
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertNull(res.matches[0])
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 0.0)
    }

    @Test
    fun `a quiet target that did not move is left unmatched`() {
        // Reference +60 identified; target should move +90 but its peak is gone.
        val baseline = listOf(peak(1200.0, 50.0), peak(1230.0, 40.0))
        val probed = listOf(peak(1260.0, 50.0)) // only the reference moved
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 0.0)
        assertNull(res.matches[1])
    }

    // ---- differential verify (confirmTracking) ------------------------------------

    @Test
    fun `verify confirms targets that landed at reference plus their offset`() {
        // Reference at 1200; targets re-probed by 40 and 90 → aligned targets sit at
        // 1240 and 1290. Ghosts and reflections present but off-grid.
        val verify = listOf(
            peak(1200.0, 30.0), // reference
            peak(1240.0, 22.0), // slot 0, offset 40 → tracked
            peak(1290.0, 18.0), // slot 1, offset 90 → tracked
            peak(1420.0, 15.0), // fixed ghost, not at any ref+offset
        )
        val conf = PeakAttribution.confirmTracking(verify, mapOf(0 to 40, 1 to 90), matchTolMs = 15.0)
        assertEquals(1200.0, conf.referenceLagMs!!, 0.0)
        assertEquals(setOf(0, 1), conf.tracked.keys)
        assertEquals(1240.0, conf.tracked[0]!!, 0.0)
    }

    @Test
    fun `reference found by offset-consensus survives a large coarse-clock shift`() {
        // Entire scene 200 ms later than the probed capture (drift). Absolute-lag
        // anchoring would fail; offset-consensus must not.
        val verify = listOf(
            peak(1400.0, 30.0), peak(1440.0, 22.0), peak(1490.0, 18.0),
        )
        val conf = PeakAttribution.confirmTracking(verify, mapOf(0 to 40, 1 to 90), matchTolMs = 15.0)
        assertEquals(1400.0, conf.referenceLagMs!!, 0.0)
        assertEquals(setOf(0, 1), conf.tracked.keys)
    }

    @Test
    fun `a target that did not track fails, it is never passed`() {
        // Slots 0 and 1 aligned (quorum met); slot 2's SetLatency was a no-op / it was
        // mis-attributed so there's nothing at ref+150. It must be absent from tracked so
        // the caller restores it, while the other two still commit.
        val verify = listOf(
            peak(1200.0, 30.0), peak(1240.0, 22.0), peak(1290.0, 18.0),
            peak(1415.0, 17.0), // stray, not at ref+150 (1350)
        )
        val conf = PeakAttribution.confirmTracking(verify, mapOf(0 to 40, 1 to 90, 2 to 150), matchTolMs = 15.0)
        assertEquals(1200.0, conf.referenceLagMs!!, 0.0)
        assertEquals(setOf(0, 1), conf.tracked.keys)
        assertTrue("slot 2 must not be reported tracked", 2 !in conf.tracked)
    }

    @Test
    fun `a lone tracked target does not reach quorum`() {
        // Only one target lands at ref+offset. A single hit cannot name a reference (a
        // spurious match would otherwise elect one), so nothing is tracked.
        val verify = listOf(peak(1200.0, 30.0), peak(1240.0, 22.0), peak(1500.0, 15.0))
        val conf = PeakAttribution.confirmTracking(verify, mapOf(0 to 40, 1 to 90), matchTolMs = 15.0)
        assertNull(conf.referenceLagMs)
        assertTrue(conf.tracked.isEmpty())
    }

    @Test
    fun `a leader matching two expected positions is ambiguous and tracks neither`() {
        // Offsets 40 and 60 are 20 ms apart; a single leader at ref+50 sits within 15 ms
        // of BOTH ref+40 and ref+60. It must not be counted for either slot. A third
        // target at ref+150 keeps a clean candidate reference present.
        val verify = listOf(
            peak(1200.0, 30.0), peak(1250.0, 22.0), peak(1350.0, 20.0),
        )
        val conf = PeakAttribution.confirmTracking(
            verify, mapOf(0 to 40, 1 to 60, 2 to 150), matchTolMs = 15.0, minHits = 1,
        )
        assertTrue("ambiguous slot 0 not tracked", 0 !in conf.tracked)
        assertTrue("ambiguous slot 1 not tracked", 1 !in conf.tracked)
        assertEquals(setOf(2), conf.tracked.keys)
    }

    @Test
    fun `dense already-aligned scene does not fabricate tracking`() {
        // Run-5 hazard: speakers already stacked near the reference (within a few ms).
        // With verify probes applied they should be at ref+offset; if instead every peak
        // clusters at ~ref (nothing at ref+offset), no target may be reported tracked.
        val verify = listOf(
            peak(1200.0, 30.0), peak(1204.0, 24.0), peak(1197.0, 20.0), peak(1208.0, 18.0),
        )
        val conf = PeakAttribution.confirmTracking(verify, mapOf(0 to 40, 1 to 90), matchTolMs = 15.0)
        assertTrue("no target should track when nothing sits at ref+offset", conf.tracked.isEmpty())
    }

    // ---- correction gating --------------------------------------------------------

    @Test
    fun `gate classifies deadband, inconsistent, implausible, and apply`() {
        // Deadband: small delta is "already aligned" regardless of stability.
        assertEquals(SyncCalibrator.Gate.ALIGNED, SyncCalibrator.gate(10, false))
        assertEquals(SyncCalibrator.Gate.ALIGNED, SyncCalibrator.gate(-15, true))
        // Implausible: huge delta reads as mis-attribution (beats the consistency check).
        assertEquals(SyncCalibrator.Gate.IMPLAUSIBLE, SyncCalibrator.gate(600, true))
        // Real-sized delta but unstable across the capture halves → deferred (run-5 noise).
        assertEquals(SyncCalibrator.Gate.DEFERRED, SyncCalibrator.gate(33, false))
        // Apply: real-sized delta whose estimate was stable across halves — even a weak but
        // steady across-room BT target (z~13) passes, which the old z=14 gate wrongly blocked.
        assertEquals(SyncCalibrator.Gate.APPLY, SyncCalibrator.gate(-141, true))
        assertEquals(SyncCalibrator.Gate.APPLY, SyncCalibrator.gate(213, true))
    }

    @Test
    fun `halfDelta re-derives the offset and flags a missing peak`() {
        // Aligned pair probed: reference at 1260 (R+60), target at 1290 (R+90) → delta 0.
        val half = listOf(peak(1260.0, 20.0), peak(1290.0, 15.0), peak(1100.0, 25.0))
        assertEquals(0, PeakAttribution.halfDelta(half, 1260.0, 1290.0, 60, 90, 30.0))
        // The target's peak is absent from this half (nearest is >tol away) → not
        // reproducible → null, so the split-half gate defers rather than trusting noise.
        val missing = listOf(peak(1255.0, 20.0), peak(1050.0, 25.0))
        assertNull(PeakAttribution.halfDelta(missing, 1255.0, 1290.0, 60, 90, 30.0))
    }

    @Test
    fun `verify pair assigns distinct peaks and reads zero residual on an aligned pair`() {
        // Aligned pair: both speakers at ~1000 (merged direct + a +13 ms reflection in the
        // baseline), re-probed +60/+90 with ~18 ms inter-capture drift → 1078 and 1108.
        // The old "strongest peak per offset" would grab one peak for both; the distinct
        // assignment must return two peaks, and the residual (drift cancels) must read 0.
        val vBase = listOf(peak(1000.0, 36.0), peak(1013.0, 26.0))
        val vProbed = listOf(peak(1078.0, 30.0), peak(1108.0, 24.0))
        val (vRef, vTgt) = PeakAttribution.assignVerifyPair(vProbed, vBase, 60, 90, 15.0)!!
        assertTrue("reference and target must be distinct peaks", vRef !== vTgt)
        assertEquals(1078.0, vRef.lagMs, 0.0)
        assertEquals(1108.0, vTgt.lagMs, 0.0)
        val residual = (vTgt.lagMs - 90) - (vRef.lagMs - 60)
        assertEquals(0.0, residual, 1.0)
    }

    @Test
    fun `verify pair reads the true residual on a still-misaligned pair`() {
        // Target still 40 ms behind the reference after the correction.
        val vBase = listOf(peak(1000.0, 36.0), peak(1040.0, 30.0))
        val vProbed = listOf(peak(1060.0, 30.0), peak(1130.0, 24.0)) // ref+60, target(+40)+90
        val (vRef, vTgt) = PeakAttribution.assignVerifyPair(vProbed, vBase, 60, 90, 15.0)!!
        val residual = (vTgt.lagMs - 90) - (vRef.lagMs - 60)
        assertEquals(40.0, residual, 1.0)
    }

    @Test
    fun `probe set is consensus-safe for the match tolerance`() {
        // For the verify offset-consensus, EVERY value in (offsets ∪ pairwise differences)
        // must be separated by >= 2*MATCH_TOL, so no shifted grid can alias another.
        val set = SyncCalibrator.PROBE_SET_MS
        val values = sortedSetOf<Int>()
        values += set
        for (i in set.indices) for (j in i + 1 until set.size) values += (set[j] - set[i])
        val sorted = values.toList()
        val minGap = sorted.zipWithNext { a, b -> b - a }.min()
        assertTrue("grid values must be >= 2*MATCH_TOL apart, min gap was $minGap", minGap >= 2 * 15)
    }

    @Test
    fun `a louder reflection does not displace the direct path`() {
        // The failure this guards: a speaker's reflection in the baseline paired with the SAME
        // reflection in the probed capture shifts by exactly the probe offset, so it is a
        // ZERO-error match that merely reports a lag later than the truth by the reflection delay.
        // Ranking candidates by salience picked it whenever the reflection out-peaked the direct
        // path, and rig reflections run 50-80ms - the scale of the correction errors seen on-rig.
        val refOff = 90
        val tgtOff = 180
        val baseline = listOf(
            Dsp.Peak(1000.0, 30.0), // reference direct
            Dsp.Peak(1200.0, 10.0), // target direct  (quiet)
            Dsp.Peak(1240.0, 25.0), // target reflection, 40ms late and LOUDER
        )
        val probed = listOf(
            Dsp.Peak(1000.0 + refOff, 30.0),
            Dsp.Peak(1200.0 + tgtOff, 10.0),
            Dsp.Peak(1240.0 + tgtOff, 25.0),
        )
        val r = PeakAttribution.attribute(baseline, probed, listOf(refOff, tgtOff), 15.0)
        val target = r.matches[1]
        assertNotNull("target must be attributed", target)
        assertEquals("must lock onto the direct path, not the louder reflection", 1380.0, target!!.probedLagMs, 0.01)
        assertEquals(1200.0, target.baselineLagMs, 0.01)
    }

    @Test
    fun `a cluster is represented by its earliest member, not its loudest`() {
        // Within one cluster the earliest arrival is the direct path and everything after it is a
        // reflection of the same sound, so taking the loudest biased the reported lag later.
        val leaders = PeakAttribution.clusterLeaders(
            listOf(Dsp.Peak(1000.0, 10.0), Dsp.Peak(1005.0, 30.0)),
        )
        assertEquals("the two peaks are one cluster", 1, leaders.size)
        assertEquals(1000.0, leaders[0].lagMs, 0.01)
    }

    @Test
    fun `a cluster that moves together beats a lone coincidence at the same offset`() {
        // The rig failure this fixes: across five identical captures the peak LAGS were stable but
        // WHICH peak won kept flipping, because a single peak sitting at the expected offset is
        // indistinguishable from a real direct path when you only look at that one peak. A probe
        // delays a source's ENTIRE pattern - direct path and every reflection - by the same amount,
        // so the real match drags its neighbours along and a coincidence brings nothing.
        val refOff = 90
        val tgtOff = 180
        val baseline = listOf(
            Dsp.Peak(800.0, 30.0), // reference
            Dsp.Peak(1000.0, 12.0), // target direct
            Dsp.Peak(1030.0, 10.0), // its reflections - quieter, but they move WITH it
            Dsp.Peak(1060.0, 9.0),
            Dsp.Peak(1500.0, 25.0), // decoy: louder, alone, no pattern behind it
        )
        val probed = listOf(
            Dsp.Peak(890.0, 30.0),
            Dsp.Peak(1180.0, 12.0),
            Dsp.Peak(1210.0, 10.0),
            Dsp.Peak(1240.0, 9.0),
            Dsp.Peak(1680.0, 25.0), // decoy shifted by exactly the target offset
        )
        val r = PeakAttribution.attribute(baseline, probed, listOf(refOff, tgtOff), 15.0)
        val target = r.matches[1]
        assertNotNull("target must be attributed", target)
        assertEquals("must take the corroborated cluster, not the louder lone peak", 1000.0, target!!.baselineLagMs, 0.01)
        assertEquals(1180.0, target.probedLagMs, 0.01)
    }

    @Test
    fun `patternSupport counts only arrivals that move by the same shift`() {
        val baseline = listOf(Dsp.Peak(1000.0, 10.0), Dsp.Peak(1040.0, 9.0), Dsp.Peak(1400.0, 9.0))
        // 1000 and 1040 both move by 180; 1400 is a different source and does not.
        val probed = listOf(Dsp.Peak(1180.0, 10.0), Dsp.Peak(1220.0, 9.0), Dsp.Peak(1400.0, 9.0))
        assertEquals(2, PeakAttribution.patternSupport(baseline, probed, 1000.0, 180.0, 15.0))
        // A shift nothing actually took has no support.
        assertEquals(0, PeakAttribution.patternSupport(baseline, probed, 1000.0, 500.0, 15.0))
    }

    // ---- assignVerifyPairWithWindows: the symmetric masked-side fallback ----------------
    //
    // Numbers from the 2026-08-10 21:51 rig run, where the blind assignment failed because the
    // TARGET's verify-probe peak sat at z 8.9 — one tenth under the salience floor — while the
    // reference was salient at z 9.1. The 21:32 run failed the mirrored way (reference masked).

    private val vBase = listOf(peak(1294.2, 13.8), peak(1307.1, 12.9), peak(1326.3, 11.5))

    @Test
    fun `verify fallback window-reads a masked target anchored on the blind-matched reference`() {
        // Salient probed list holds ONLY the reference (vBase leader 1307.1 + 90, drift +11.7).
        val probed = listOf(peak(1408.8, 9.1))
        val pair = PeakAttribution.assignVerifyPairWithWindows(
            probed, vBase, vBase, 90, 470, 15.0, 6.0,
        ) { center, tol ->
            // The real target arrival at 1792.8 (z 8.9) answers windows near it; empty windows
            // answer the correlation floor.
            if (abs(center - 1792.8) <= tol) peak(1792.8, 8.9) else peak(center, 4.0)
        }
        assertNotNull("masked target must be window-rescued", pair)
        assertEquals(1408.8, pair!!.first.lagMs, 0.01)
        assertEquals(1792.8, pair.second.lagMs, 0.01)
    }

    @Test
    fun `verify fallback window-reads a masked reference anchored on the blind-matched target`() {
        // Mirror of 21:32: only the target is salient (vBase leader 1294.2 + 470, drift ~0).
        val probed = listOf(peak(1764.5, 12.2))
        val pair = PeakAttribution.assignVerifyPairWithWindows(
            probed, vBase, vBase, 90, 470, 15.0, 6.0,
        ) { center, tol ->
            if (abs(center - 1397.3) <= tol) peak(1397.3, 7.2) else peak(center, 4.0)
        }
        assertNotNull("masked reference must be window-rescued", pair)
        assertEquals(1397.3, pair!!.first.lagMs, 0.01)
        assertEquals(1764.5, pair.second.lagMs, 0.01)
    }

    @Test
    fun `verify fallback refuses a missing side whose windows hold only noise`() {
        val probed = listOf(peak(1408.8, 9.1))
        val pair = PeakAttribution.assignVerifyPairWithWindows(
            probed, vBase, vBase, 90, 470, 15.0, 6.0,
        ) { center, _ -> peak(center, 4.9) } // everything under the window floor
        assertNull("a sub-floor window read must fail the verify", pair)
    }
}
