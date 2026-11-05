package tech.capullo.audio.player

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.tan

/**
 * ITU-R BS.1770 K-weighting, computed for any sample rate.
 *
 * Both stages are derived from the sample rate with libebur128's analog prototype, which reproduces
 * the spec's published 48 kHz coefficients exactly (stage 1 b = [1.53512485958697,
 * -2.69169618940638, 1.19839281085285], stage 2 a = [-1.99004745483398, 0.99007225036621]). Using
 * the fixed 48 kHz table at another rate, or a Butterworth Q for the RLB high-pass, measures
 * silently wrong - by ~2 dB on bass-heavy material - so every loudness measurement in the capullo
 * apps goes through this one place.
 *
 * Loudness of a K-weighted block is `LOUDNESS_OFFSET_DB + 10*log10(sum over channels of
 * meanSquare)`: channels are SUMMED (L and R each weight 1.0), not averaged. Averaging reads 3 dB low.
 */
object KWeighting {

    /** BS.1770 loudness offset: a 997 Hz 0 dBFS sine on one channel reads -3.01 LUFS. */
    const val LOUDNESS_OFFSET_DB = -0.691

    /** One biquad with a0 normalised to 1: y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2. */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** Stage 1 (high-frequency shelf) for [sampleRate]. */
    fun shelf(sampleRate: Int): Biquad {
        val f0 = 1681.974450955533
        val gainDb = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    /** Stage 2 (RLB high-pass) for [sampleRate]. The spec's numerator is unnormalised [1, -2, 1]. */
    fun highPass(sampleRate: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }
}
