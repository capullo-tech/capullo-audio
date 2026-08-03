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
    fun `a client seen only once is not reported`() {
        // One reading scatters by more than the imbalance being looked for, so it is not evidence.
        val cal = calibratorWith(
            mapOf("a" to 20.0, "b" to 10.0),
            mapOf("a" to 20.0, "c" to 18.0),
        )
        val levels = cal.reduceLevels()
        assertTrue("a was seen twice", levels.containsKey("a"))
        assertTrue("b was seen once", !levels.containsKey("b"))
        assertTrue("c was seen once", !levels.containsKey("c"))
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
