package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how per-capture mic levels are reduced into the numbers [VolumeBalance] balances on.
 *
 * The measurement this rests on: absolute salience swings hugely between captures for reasons that
 * have nothing to do with any speaker's volume (three unchanged rig baselines read top z 22.6, 13.8
 * and 11.6), but that swing is COMMON to every client in the capture because z divides by that
 * capture's own noise floor. Normalising inside a capture removes it; comparing raw z across
 * captures does not, and would have the balance chasing capture noise.
 */
class LevelReductionTest {

    private object NoopControl : CalibrationControl {
        override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? = 1
        override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {}
        override suspend fun sendGetStatus() {}
    }

    private fun calibratorWith(vararg captures: Map<String, Double>): SyncCalibrator {
        val cal = SyncCalibrator(tapArm = {}, control = NoopControl)
        val field = SyncCalibrator::class.java.getDeclaredField("levelCaptures")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(cal) as MutableList<Map<String, Double>>).addAll(captures)
        return cal
    }

    @Test
    fun `a capture-wide level swing cancels out instead of becoming an imbalance`() {
        // Same room, same speakers: "a" is consistently twice "b". The captures differ only by an
        // overall factor - exactly the rig's observed 22.6/13.8/11.6 swing. The reduction must see
        // a 2:1 ratio and nothing else.
        val cal = calibratorWith(
            mapOf("a" to 22.0, "b" to 11.0),
            mapOf("a" to 14.0, "b" to 7.0),
            mapOf("a" to 12.0, "b" to 6.0),
        )
        val levels = cal.reduceLevels()
        assertEquals(1.0, levels.getValue("a"), 1e-9)
        assertEquals(0.5, levels.getValue("b"), 1e-9)
    }

    @Test
    fun `an odd capture makes the run decline rather than being outvoted`() {
        // Two captures agree that b is half of a; one claims they are equal. The OLD reduction
        // medianed these and wrote 0.5 with full confidence. It must now decline: only two captures
        // agree, and MIN_LEVEL_SAMPLES of them have to.
        //
        // This is the gate being deliberately strict rather than a limitation. The pair path takes
        // PROBE_REPEATS=3 captures, so on a two-speaker rig a single disagreeing capture stops the
        // balance outright. That is the intended direction of error: the levels are still logged and
        // the next run decides, whereas a wrong persistent volume is the user's to undo by hand.
        val cal = calibratorWith(
            mapOf("a" to 20.0, "b" to 10.0),
            mapOf("a" to 30.0, "b" to 15.0),
            mapOf("a" to 10.0, "b" to 10.0),
        )
        assertTrue("b's captures do not agree, so b is not balanced", cal.reduceLevels().isEmpty())
    }

    @Test
    fun `agreeing captures are reduced even when the room is far from even`() {
        // The counterpart to the test above, and the case that must keep working: a real 10 dB room
        // asymmetry, measured consistently, is exactly what the feature exists to correct. "Equal
        // gains should read 1.00" is FALSE here - the mic sits beside one speaker and across the
        // room from the other, so a large steady ratio is the expected reading, not a failure.
        val cal = calibratorWith(
            mapOf("near" to 1.00e-2, "far" to 0.31e-2),
            mapOf("near" to 0.62e-2, "far" to 0.19e-2),
            mapOf("near" to 1.40e-2, "far" to 0.44e-2),
        )
        val levels = cal.reduceLevels()
        assertEquals(1.0, levels.getValue("near"), 1e-9)
        assertEquals(0.31, levels.getValue("far"), 0.02)
    }

    @Test
    fun `a swapped attribution is rejected on the sign flip it produces`() {
        // Attribution can pair each client at the OTHER's arrival (a global drift estimate wrong by
        // the 90 ms between refOff and tgtOff), and the estimator then reports the RECIPROCAL ratio
        // with full confidence. Observed on-rig as 1.00/0.31 followed by 0.30/1.00 - one consistent
        // measurement with the labels exchanged. A spread test alone might tolerate it; the sign of
        // the centred level cannot, which is why the sign check is separate and fatal.
        val cal = calibratorWith(
            mapOf("a" to 1.00e-2, "b" to 0.31e-2),
            mapOf("a" to 0.30e-2, "b" to 1.00e-2), // labels swapped
            mapOf("a" to 1.00e-2, "b" to 0.34e-2),
        )
        assertTrue("a reciprocal reading must not be balanced on", cal.reduceLevels().isEmpty())
    }

    @Test
    fun `noise around an even room does not read as a swap`() {
        // The sign check has to survive the case it most easily mistakes: a genuinely balanced room
        // sits at 0 dB, so its captures straddle zero by noise alone. Without the deadband every
        // even room would be declined as a swap - the one false positive that matters.
        val cal = calibratorWith(
            mapOf("a" to 10.0, "b" to 10.4),
            mapOf("a" to 10.3, "b" to 10.0),
            mapOf("a" to 10.0, "b" to 10.2),
        )
        val levels = cal.reduceLevels()
        assertTrue("an even room is still measured", levels.size == 2)
        assertEquals("and reads as even", 1.0, levels.getValue("a") / levels.getValue("b"), 0.05)
    }

    @Test
    fun `a client seen too few times is not reported`() {
        // Too few readings scatter by more than the imbalance being looked for, so they are not
        // evidence. Built from the threshold rather than a hardcoded count, so raising the bar
        // (2 -> 3 after the first rig run balanced off two captures that disagreed 0.93 vs 0.51)
        // cannot leave this test quietly asserting the old rule.
        // "a" is in every capture so it clears the bar; each partner appears in one capture fewer
        // than the threshold, so none of them do. A capture needs two clients to carry a ratio at
        // all, hence the rotating partner rather than a lone "a".
        val enough = SyncCalibrator.MIN_LEVEL_SAMPLES
        val captures = (0 until enough).map { mapOf("a" to 20.0, "partner$it" to 10.0) } +
            List(enough - 1) { mapOf("a" to 20.0, "short" to 18.0) }
        val levels = calibratorWith(*captures.toTypedArray()).reduceLevels()
        assertTrue("a cleared the bar", levels.containsKey("a"))
        assertTrue("a partner seen once is dropped", !levels.containsKey("partner0"))
        assertTrue("one capture short of the bar is dropped", !levels.containsKey("short"))
    }

    @Test
    fun `a capture banked three times cannot manufacture agreement`() {
        // The pair path re-pairs every baseline with every probe capture, so a level harvested per
        // PAIRING rather than per CAPTURE would enter the list N times. Three copies of one capture
        // agree with each other perfectly, so the agreement gate is no defence against this at all -
        // it would clear MIN_LEVEL_SAMPLES on a single capture's evidence and read a zero spread as
        // confidence. The `levelled` flag in calibratePair is what prevents it, and this test records
        // that the reduction cannot: duplicates are indistinguishable from genuine repeats here.
        val odd = mapOf("a" to 10.0, "b" to 10.0) // claims the two are equal
        val cal = calibratorWith(odd, odd, odd)
        assertEquals(
            "three copies of one capture look exactly like three agreeing captures to the reduction",
            1.0,
            cal.reduceLevels().getValue("b"),
            1e-9,
        )
    }

    @Test
    fun `a single-client capture carries no ratio and is discarded`() {
        // With nothing to compare against, a capture says nothing about balance - normalising it
        // would just assert that the only client present is the loudest.
        val cal = calibratorWith(
            mapOf("a" to 20.0),
            mapOf("a" to 25.0),
            mapOf("a" to 30.0),
        )
        assertTrue(cal.reduceLevels().isEmpty())
    }

    @Test
    fun `nothing measured means nothing to balance`() {
        assertTrue(calibratorWith().reduceLevels().isEmpty())
    }
}
