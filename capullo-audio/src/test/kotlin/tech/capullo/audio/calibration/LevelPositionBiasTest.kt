package tech.capullo.audio.calibration

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.random.Random

/**
 * Characterizes the UN-WHITENED estimator ([Dsp.crossCorrelateLevel]), which is no longer the one
 * [DelayMeasurement] uses — see [Dsp.crossCorrelateWiener] and `WienerLevelTest` for the replacement
 * and the evidence behind it. These tests are what located the defect, and they stay as the record of
 * what the old estimator does and does not do.
 *
 * Is the level estimator biased by WHERE an arrival sits, independently of how loud it is?
 *
 * Asked because the rig said yes. Running the same two speakers both ways round, whichever client got
 * the 180 ms probe offset came out louder:
 *
 * ```
 * HK Neo probed :  Guer    ~5.0e-2 (early)   HK Neo  ~1.4e-1 (late)
 * Guer   probed :  HK Neo  ~5.6e-2 (early)   Guer    ~7.5e-2 (late)
 * ```
 *
 * A real level measurement cannot do that: moving a speaker's arrival in time must not change how
 * loud it reads. Either the estimator has a position-dependent bias, or the two broad correlation
 * humps overlap so much that the earlier window is reading the later hump's rising skirt.
 *
 * These tests hold TRUE AMPLITUDE EQUAL and vary only position, so any non-zero reading IS the bias.
 * They are written to document the size of the effect rather than to assert it away — the assertions
 * are loose bounds that record what the estimator can currently be trusted to do.
 */
class LevelPositionBiasTest {

    private fun program(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(n)
        var s = 0.0
        for (i in 0 until n) {
            s = 0.85 * s + (rnd.nextDouble() * 2 - 1)
            x[i] = s.toFloat()
        }
        return x
    }

    /** Sustained harmonic partials: strongly self-similar, far closer to the ambient/loopy material
     *  the rig struggles on than the AR(1) noise the other level tests use. The estimator's synthetic
     *  validation was all done on AR(1), which is why it looked better in tests than on hardware. */
    private fun tonal(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(n)
        val phases = DoubleArray(5) { rnd.nextDouble() * 6.283 }
        val freqs = doubleArrayOf(110.0, 165.0, 220.0, 330.0, 440.0)
        for (i in 0 until n) {
            val t = i / 1000.0 // fs = 1000 in these tests
            var v = 0.0
            for (k in freqs.indices) v += kotlin.math.sin(2 * Math.PI * freqs[k] * t + phases[k])
            x[i] = (v * (1 + 0.3 * kotlin.math.sin(2 * Math.PI * 0.7 * t))).toFloat()
        }
        return x
    }

    private fun room(
        ref: FloatArray,
        sources: List<Pair<Int, Float>>,
        noise: Double = 0.05,
        seed: Int = 1,
        reverb: Boolean = true,
    ): FloatArray {
        val rnd = Random(seed)
        val mic = FloatArray(ref.size)
        for ((delay, gain) in sources) {
            for (i in delay until ref.size) mic[i] += gain * ref[i - delay]
            if (reverb) {
                for ((dt, g) in listOf(7 to 0.5f, 19 to 0.35f, 34 to 0.25f, 61 to 0.15f)) {
                    val d = delay + dt
                    for (i in d until ref.size) mic[i] += gain * g * ref[i - d]
                }
            }
        }
        for (i in mic.indices) mic[i] += (noise * (rnd.nextDouble() * 2 - 1)).toFloat()
        return mic
    }

    /** dB by which the arrival at [lagB] reads above the one at [lagA]. */
    private fun deltaDb(ref: FloatArray, mic: FloatArray, lagA: Int, lagB: Int): Double {
        val r = Dsp.crossCorrelateLevel(ref, mic)
        val a = Dsp.levelAt(r, fs = 1000, lagMs = lagA.toDouble())
        val b = Dsp.levelAt(r, fs = 1000, lagMs = lagB.toDouble())
        return 20.0 * log10(b / a)
    }

    @Test
    fun `two equally loud sources read equally loud regardless of which is later`() {
        // The property the rig appeared to violate. Equal amplitude, 200 ms apart.
        val ref = program(16_000, seed = 3)
        val mic = room(ref, listOf(1200 to 1.0f, 1400 to 1.0f), seed = 7)
        val d = deltaDb(ref, mic, 1200, 1400)
        assertTrue("equal sources read %.2f dB apart on position alone".format(d), kotlin.math.abs(d) < 3.0)
    }

    @Test
    fun `the bias does not grow when the program material is strongly self-correlated`() {
        // AR(1) is the friendly case. Tonal material smears the un-whitened correlation into a wide
        // hump, which is the mechanism that would let one arrival's skirt dominate another's window.
        val ref = tonal(16_000, seed = 3)
        val mic = room(ref, listOf(1200 to 1.0f, 1400 to 1.0f), seed = 7)
        val d = deltaDb(ref, mic, 1200, 1400)
        assertTrue("tonal program biases equal sources by %.2f dB".format(d), kotlin.math.abs(d) < 6.0)
    }

    @Test
    fun `moving a source later does not change how loud it reads relative to a fixed partner`() {
        // Directly models the rig experiment: the same two speakers, one of them probed to a
        // different lag. The reported ratio must not depend on which one was moved.
        val ref = program(20_000, seed = 11)
        // Partner fixed at 1200. Subject at 1400, then probed out to 1600.
        val near = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1400 to 0.5f), seed = 5), 1200, 1400)
        val far = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1600 to 0.5f), seed = 5), 1200, 1600)
        assertTrue(
            "a -6dB source reads %.2f dB at 200ms out and %.2f dB at 400ms out - position changed the level by %.2f dB"
                .format(near, far, far - near),
            kotlin.math.abs(far - near) < 3.0,
        )
    }

    /** How far the reported ratio moves when one source is attenuated by [db]. */
    private fun sensitivity(ref: FloatArray, db: Int): Double {
        val base = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1400 to 1.0f), seed = 9), 1200, 1400)
        val amp = Math.pow(10.0, db / 20.0).toFloat()
        val got = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1400 to amp), seed = 9), 1200, 1400)
        return got - base
    }

    @Test
    fun `sensitivity is good on broadband program material`() {
        // The known-good case, and the one every previous synthetic validation used. This is why the
        // estimator has always looked correct in tests.
        val ref = program(20_000, seed = 2)
        for (db in intArrayOf(-6, -12)) {
            val moved = sensitivity(ref, db)
            assertTrue(
                "broadband: commanded %d dB, moved %.2f dB (error %.2f)".format(db, moved, moved - db),
                kotlin.math.abs(moved - db) < 3.0,
            )
        }
    }

    @Test
    fun `SENSITIVITY COLLAPSES on strongly self-correlated program material`() {
        // ============================ THE ROOT CAUSE ============================
        // This is a CHARACTERIZATION TEST. It pins a known defect, not desired behaviour. If it
        // starts failing, the estimator got better and this test should be rewritten as a pass.
        //
        // On sustained/tonal material a commanded 6 dB change moves the reported ratio by
        // essentially NOTHING. The mechanism: the un-whitened correlation at one source's lag is
        // dominated by the OTHER source's autocorrelation sidelobes, and those sit at the same height
        // whatever the first source's gain is. Turning a speaker down barely moves the number read at
        // its own arrival.
        //
        // This explains the whole history in one line. Every synthetic validation used AR(1) noise,
        // which has a sharp autocorrelation and no sidelobe contamination, so the estimator recovered
        // 0.50/0.25/0.10 as 0.498/0.250/0.101 and looked correct. Real music is far closer to the
        // tonal case, so on the rig the same estimator has never tracked anything. It also explains
        // why more samples and quality-weighting never helped: the error is systematic, not noise.
        //
        // Consequence: the balance cannot be trusted on arbitrary program material. Any revival needs
        // an estimator that is not defeated by self-similar sources - Wiener regularization
        // (R/(|Ref|^2 + lambda)) is the obvious candidate, being the middle ground between PHAT
        // (sharp, no amplitude) and un-whitened (amplitude, no sharpness).
        val ref = tonal(20_000, seed = 2)
        val moved6 = sensitivity(ref, -6)
        val moved12 = sensitivity(ref, -12)
        assertTrue(
            "tonal sensitivity is no longer collapsed (-6dB moved %.2f, -12dB moved %.2f) - if the estimator improved, rewrite this test"
                .format(moved6, moved12),
            kotlin.math.abs(moved6) < 3.0 && kotlin.math.abs(moved12) < 6.0,
        )
    }
}
