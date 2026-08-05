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
    fun `an odd capture is outvoted rather than averaged in`() {
        // Two captures agree that b is half of a; one bad capture claims they are equal. A median
        // over normalised values keeps the agreed answer.
        val cal = calibratorWith(
            mapOf("a" to 20.0, "b" to 10.0),
            mapOf("a" to 30.0, "b" to 15.0),
            mapOf("a" to 10.0, "b" to 10.0),
        )
        assertEquals(0.5, cal.reduceLevels().getValue("b"), 1e-9)
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
    fun `one capture cannot be banked twice and outvote the others`() {
        // The pair path re-pairs every baseline with every probe capture, so a baseline harvested
        // per PAIRING rather than per CAPTURE would be counted N times and drag the median onto
        // whatever that one capture happened to read. Here a single repeated capture disagrees with
        // two others; duplicated three times it would win, counted once it cannot.
        val odd = mapOf("a" to 10.0, "b" to 10.0) // claims the two are equal
        val cal = calibratorWith(
            odd,
            mapOf("a" to 20.0, "b" to 10.0), // both of these say b is half of a
            mapOf("a" to 30.0, "b" to 15.0),
        )
        assertEquals("the odd capture must not be double-counted", 0.5, cal.reduceLevels().getValue("b"), 1e-9)
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
