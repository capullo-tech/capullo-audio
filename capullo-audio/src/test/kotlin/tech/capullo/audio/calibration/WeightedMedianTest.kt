package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the quality weighting of probe samples. Rig-measured, capture salience predicts accuracy:
 * the two baselines with the lowest top z (11.8, 9.9) produced the two worst speaker spacings
 * (71.5, 37.6 ms) while the three strong ones (30.8, 28.2, 24.7) clustered at 41-54 ms. So a
 * marginal capture must not carry the same vote as a confident one.
 */
class WeightedMedianTest {

    private val cal = SyncCalibrator(tapArm = {}, control = NoopControl)

    private object NoopControl : CalibrationControl {
        override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? = 1
        override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {}
        override suspend fun sendGetStatus() {}
    }

    @Test
    fun `equal weights behave exactly like a plain median`() {
        val s = listOf(-80 to 1.0, -60 to 1.0, -40 to 1.0)
        assertEquals(-60, cal.weightedMedian(s))
    }

    @Test
    fun `confident samples outvote marginal ones`() {
        // Two strong captures agree near -60; one weak capture disagrees wildly. The weak one must
        // not drag the answer, which is exactly the failure mode observed on the rig.
        val s = listOf(-62 to 30.0, -58 to 28.0, -10 to 9.0)
        assertEquals(-58, cal.weightedMedian(s))
    }

    @Test
    fun `a lone strong sample beats several marginal ones`() {
        val s = listOf(-20 to 9.0, -18 to 9.0, -60 to 40.0)
        assertEquals(-60, cal.weightedMedian(s))
    }

    @Test
    fun `it is still a median, so a wild outlier is ignored not averaged`() {
        // A mean of these is about -85; a weighted median must stay with the cluster.
        val s = listOf(-60 to 20.0, -58 to 20.0, -300 to 19.0)
        assertEquals(-60, cal.weightedMedian(s))
    }

    @Test
    fun `a single sample is returned unchanged`() {
        assertEquals(-42, cal.weightedMedian(listOf(-42 to 12.0)))
    }
}
