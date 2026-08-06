package tech.capullo.audio.calibration

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP core for acoustic sync calibration: GCC-PHAT cross-correlation between the
 * broadcast reference PCM and a microphone capture. Each audible speaker playing the
 * stream produces one correlation peak at its total (pipeline + acoustic) delay; the
 * SPACING between two speakers' peaks is their sync offset, independent of any clock
 * alignment between the two captures.
 */
object Dsp {

    data class Peak(val lagMs: Double, val z: Double)

    /** In-place iterative radix-2 FFT. Arrays are (re, im), length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        // bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (j >= m && m > 0) { j -= m; m = m shr 1 }
            j += m
        }
        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = sign * 2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe; im[i + k + len / 2] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (k in 0 until n) { re[k] /= n; im[k] /= n }
        }
    }

    /**
     * Two cascaded half-band stages ([1,2,1]/4 smoothing + take every 2nd sample) = 4x
     * decimation. Crude anti-aliasing is fine here: correlation integrates over seconds,
     * and aliased HF content decorrelates into the noise floor.
     */
    fun decimateBy4(x: FloatArray): FloatArray {
        var cur = x
        repeat(2) {
            val out = FloatArray(cur.size / 2)
            for (i in out.indices) {
                val c = i * 2
                val a = if (c > 0) cur[c - 1] else cur[c]
                val b = cur[c]
                val d = if (c + 1 < cur.size) cur[c + 1] else cur[c]
                out[i] = 0.25f * a + 0.5f * b + 0.25f * d
            }
            cur = out
        }
        return cur
    }

    /**
     * GCC-PHAT circular cross-correlation of [mic] against [ref]. Returns the full
     * circular array r (power-of-two length): a peak at index k means "mic matches ref
     * delayed by k samples", with negative lags wrapped to n−|lag|. PHAT whitening
     * (unit magnitude per bin) sharpens the peaks and removes the music's spectral
     * coloration. The exact lag convention is pinned by DspTest.
     */
    fun gccPhat(ref: FloatArray, mic: FloatArray): DoubleArray = crossCorrelate(ref, mic, phat = true)

    /**
     * Un-whitened normalized cross-correlation of [mic] against [ref], same lag convention as
     * [gccPhat]. Divided by sqrt(energy(ref) * energy(mic)), so a value at a lag is the fraction of
     * the mic signal explained by the reference arriving there.
     *
     * **SUPERSEDED for level measurement by [crossCorrelateWiener], and kept only as the comparison
     * baseline its tests measure against.** It is not wired into [DelayMeasurement] any more. The
     * defect is not subtle: this returns the impulse response CONVOLVED WITH THE PROGRAM'S OWN
     * AUTOCORRELATION, and on sustained or tonal music that autocorrelation is hundreds of
     * milliseconds wide. Measured on tonal material with two speakers 90 ms apart, a true 12 dB
     * difference reads as **+0.06 dB** — the two speakers reported identical, whatever their gains.
     * On hardware it turned a commanded 12 dB into 4.3 dB and produced the 2026-08-06 NO-GO.
     *
     * It looks excellent on broadband material, where the autocorrelation is nearly a delta: a true
     * 0.50/0.25/0.10 comes back as 0.498/0.250/0.101 (PHAT's z-ratio gives 0.281/0.130/0.052). Every
     * synthetic validation used exactly that kind of signal, which is why the defect survived three
     * attempts to find it. Do not re-adopt this on the strength of a broadband test.
     *
     * ONLY RATIOS BETWEEN CLIENTS IN ONE CAPTURE ARE MEANINGFUL — true of both estimators. On a solo
     * capture the value is nearly independent of gain (0.996 at full scale, 0.991 at a quarter)
     * because the one speaker present IS the mic signal, so the normalization divides out the very
     * thing being measured. Two speakers in one capture share that normalization, so it cancels in
     * their ratio.
     */
    fun crossCorrelateLevel(ref: FloatArray, mic: FloatArray): DoubleArray {
        val r = crossCorrelate(ref, mic, phat = false)
        var eRef = 0.0
        var eMic = 0.0
        for (v in ref) eRef += v.toDouble() * v
        for (v in mic) eMic += v.toDouble() * v
        val norm = sqrt(eRef * eMic) + 1e-18
        for (i in r.indices) r[i] /= norm
        return r
    }

    /**
     * REGULARIZED DECONVOLUTION of [mic] by [ref] — `R / (|Ref|^2 + lambda)` with
     * `lambda = eps * mean(|Ref|^2)`. Same lag convention as [gccPhat].
     *
     * The middle ground between the two estimators above, and the answer to why neither works.
     * PHAT divides by `|R|`, forcing every bin to unit magnitude: sharp timing, amplitude destroyed.
     * [crossCorrelateLevel] divides by nothing: amplitude kept, but the result is the true impulse
     * response CONVOLVED WITH THE PROGRAM'S OWN AUTOCORRELATION. On broadband material that
     * autocorrelation is nearly a delta and the smearing is invisible — which is why every synthetic
     * validation passed. On sustained or tonal music it is wide, so the value read at one speaker's
     * arrival is dominated by the OTHER speaker's autocorrelation sidelobes, which do not change when
     * the first speaker's gain changes. That is the measured cause of the balance's failure: on tonal
     * material a commanded 6 dB move shifted the reported ratio by 0.06 dB
     * (`LevelPositionBiasTest`), and on hardware a commanded 12 dB move produced 4.3 dB.
     *
     * Dividing by `|Ref|^2` removes exactly that autocorrelation while keeping amplitude, since
     * `R/|Ref|^2` is the impulse response itself rather than a smeared copy. The `lambda` term is
     * what stops that division exploding in bins where the program has no energy — the whole reason
     * plain deconvolution is unusable on real signals. [eps] sets how much noise is traded for how
     * much sharpening: 0 is exact deconvolution (unstable), large eps degenerates toward the
     * un-whitened correlation.
     *
     * NOT NORMALIZED, unlike [crossCorrelateLevel], and deliberately so: the deconvolution already
     * has the units of the impulse response, so a peak's height IS the arrival's amplitude rather
     * than something proportional to it through a per-capture constant. Ratios between clients in one
     * capture — the only comparison the balance ever makes — are unaffected either way.
     */
    fun crossCorrelateWiener(ref: FloatArray, mic: FloatArray, eps: Double = WIENER_EPS): DoubleArray =
        crossCorrelate(ref, mic, phat = false, wienerEps = eps)

    /**
     * Regularization strength for [crossCorrelateWiener], as a fraction of mean band power.
     *
     * 0.01, chosen from the measured sweep in `WienerLevelTest` rather than from the literature's
     * usual hand-wave, and never tuned against rig data. Sensitivity to a commanded -6/-12 dB change,
     * where the raw estimator's failure on tonal material is the whole reason this exists:
     *
     * ```
     * program     raw            eps=0.01        eps=0.1        eps=1.0
     * tonal       -0.06 / -0.11  -6.23 / -12.67  -4.43 / -8.49  -1.79 / -3.26
     * looped      -7.89 / -17.66 -5.85 / -11.57  -5.84 / -11.54 -5.88 / -11.67
     * broadband   -6.46 / -13.20 -6.09 / -12.25  -6.08 / -12.22 -6.05 / -12.15
     * ```
     *
     * Two things that decide the value. Going LOWER over-sharpens (0.001 gives tonal -6.62/-13.60,
     * overshooting) because too little regularization amplifies bins where the program has no energy.
     * Going HIGHER degenerates back toward the un-whitened estimator, and the tonal column shows that
     * happening smoothly from 0.03 onward. 0.01 also beats raw on broadband and fixes the looped
     * case's over-sensitivity, so it is not a tonal-only patch.
     */
    const val WIENER_EPS = 0.01

    /** Shared FFT cross-correlation core. [phat] selects whitened (timing); [wienerEps] non-null
     *  selects regularized deconvolution (level); neither selects raw (level, smeared). */
    private fun crossCorrelate(
        ref: FloatArray,
        mic: FloatArray,
        phat: Boolean,
        wienerEps: Double? = null,
    ): DoubleArray {
        var n = 1
        while (n < ref.size + mic.size) n = n shl 1
        val aRe = DoubleArray(n); val aIm = DoubleArray(n)
        val bRe = DoubleArray(n); val bIm = DoubleArray(n)
        for (i in ref.indices) aRe[i] = ref[i].toDouble()
        for (i in mic.indices) bRe[i] = mic[i].toDouble()
        fft(aRe, aIm); fft(bRe, bIm)
        // The regularizer is a fraction of the program's MEAN band power, so it adapts to the
        // material and to the overall level instead of being an absolute floor that means different
        // things on quiet and loud passages. Computed before the loop below overwrites the reference
        // spectrum in place.
        var lambda = 0.0
        if (wienerEps != null) {
            var sum = 0.0
            for (k in 0 until n) sum += aRe[k] * aRe[k] + aIm[k] * aIm[k]
            lambda = wienerEps * sum / n
        }
        // R = MIC * conj(REF), then whiten (PHAT), deconvolve (Wiener), or leave raw.
        for (k in 0 until n) {
            val rRe = bRe[k] * aRe[k] + bIm[k] * aIm[k]
            val rIm = bIm[k] * aRe[k] - bRe[k] * aIm[k]
            if (phat) {
                val mag = sqrt(rRe * rRe + rIm * rIm) + 1e-12
                aRe[k] = rRe / mag
                aIm[k] = rIm / mag
            } else if (wienerEps != null) {
                val den = aRe[k] * aRe[k] + aIm[k] * aIm[k] + lambda + 1e-18
                aRe[k] = rRe / den
                aIm[k] = rIm / den
            } else {
                aRe[k] = rRe
                aIm[k] = rIm
            }
        }
        fft(aRe, aIm, inverse = true)
        return aRe
    }

    /**
     * The correlation value at [lagMs], read out of a [crossCorrelateLevel] array.
     *
     * Two clients' values from the SAME capture are comparable, and their ratio is the level ratio;
     * values from different captures are not comparable (see [crossCorrelateLevel]).
     *
     * Never read a level off a peak found by [findPeaks]. That is a top-N search, so when a speaker
     * is absent or below the floor it still returns something — the largest lumps of noise — and
     * those are order statistics of one distribution, hence always close together. Measured: two
     * pure-noise peaks sit at a ratio of 0.93 on average, which is exactly what the rig reported
     * between clients and exactly what it reported for a speaker that was inaudible.
     *
     * Takes the max over ±[halfWindowMs] so a lag a fraction of a bin off still finds its own peak.
     */
    fun levelAt(
        r: DoubleArray,
        fs: Int,
        lagMs: Double,
        halfWindowMs: Double = 3.0,
    ): Double {
        val center = (lagMs * fs / 1000.0).toInt()
        val half = (fs * halfWindowMs / 1000.0).toInt().coerceAtLeast(1)
        var peak = Double.NEGATIVE_INFINITY
        for (i in (center - half).coerceAtLeast(0) until (center + half + 1).coerceAtMost(r.size)) {
            if (r[i] > peak) peak = r[i]
        }
        return if (peak == Double.NEGATIVE_INFINITY) 0.0 else peak
    }

    /**
     * Top-[count] peaks of [r] in lag range [loMs]..[hiMs], each z-scored against the
     * median absolute value of that range and separated by at least [guardMs].
     *
     * The z here is a DETECTION statistic — "is this lump distinguishable from the floor" — and is
     * used for exactly that plus ranking. It is NOT a level: see [levelAt] for why reading it as one
     * silently reports every speaker as equally loud.
     */
    fun findPeaks(
        r: DoubleArray,
        fs: Int,
        loMs: Int,
        hiMs: Int,
        count: Int = 4,
        guardMs: Double = 4.0,
    ): List<Peak> {
        val lo = (fs.toLong() * loMs / 1000).toInt().coerceIn(0, r.size - 1)
        val hi = (fs.toLong() * hiMs / 1000).toInt().coerceIn(lo + 1, r.size)
        val seg = DoubleArray(hi - lo) { r[lo + it] }
        val sorted = seg.map { abs(it) }.sorted()
        val noise = sorted[sorted.size / 2] + 1e-18
        val guard = (fs * guardMs / 1000.0).toInt().coerceAtLeast(1)
        val peaks = mutableListOf<Peak>()
        val work = seg.copyOf()
        repeat(count) {
            var best = 0
            for (i in work.indices) if (work[i] > work[best]) best = i
            if (work[best] == Double.NEGATIVE_INFINITY) return@repeat
            peaks += Peak((lo + best) * 1000.0 / fs, work[best] / noise)
            for (i in (best - guard).coerceAtLeast(0) until (best + guard).coerceAtMost(work.size)) {
                work[i] = Double.NEGATIVE_INFINITY
            }
        }
        return peaks
    }
}
