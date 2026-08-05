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
     * the mic signal explained by the reference arriving there — bounded, and LINEAR in the
     * speaker's amplitude.
     *
     * This exists because PHAT cannot measure level. Whitening forces every frequency bin to unit
     * magnitude, which is exactly what makes it a sharp TIMING estimator and exactly what destroys
     * amplitude. Measured on synthetic scenes with two speakers 300 ms apart: the ratio of these
     * values recovers a true gain ratio of 0.50/0.25/0.10 as 0.498/0.250/0.101, while the PHAT
     * z-ratio reports 0.281/0.130/0.052 — wrong by about a factor of two throughout.
     *
     * ONLY RATIOS BETWEEN CLIENTS IN ONE CAPTURE ARE MEANINGFUL. On a solo capture the value is
     * nearly independent of gain (0.996 at full scale, 0.991 at a quarter) because the one speaker
     * present IS the mic signal, so the normalization divides out the very thing being measured.
     * Two speakers in the same capture share that normalization, so it cancels in their ratio and
     * the ratio is the real measurement. This is why the balance harvests from the PROBED capture:
     * the probe offsets (90/180/360 ms) pull the speakers apart, and accuracy needs about 30 ms of
     * separation (at 10 ms the same 0.25 truth reads 0.42; from 30 ms it reads 0.26).
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

    /** Shared FFT cross-correlation core; [phat] selects whitened (timing) or raw (level). */
    private fun crossCorrelate(ref: FloatArray, mic: FloatArray, phat: Boolean): DoubleArray {
        var n = 1
        while (n < ref.size + mic.size) n = n shl 1
        val aRe = DoubleArray(n); val aIm = DoubleArray(n)
        val bRe = DoubleArray(n); val bIm = DoubleArray(n)
        for (i in ref.indices) aRe[i] = ref[i].toDouble()
        for (i in mic.indices) bRe[i] = mic[i].toDouble()
        fft(aRe, aIm); fft(bRe, bIm)
        // R = MIC * conj(REF), then PHAT-normalize when whitening is wanted.
        for (k in 0 until n) {
            val rRe = bRe[k] * aRe[k] + bIm[k] * aIm[k]
            val rIm = bIm[k] * aRe[k] - bRe[k] * aIm[k]
            if (phat) {
                val mag = sqrt(rRe * rRe + rIm * rIm) + 1e-12
                aRe[k] = rRe / mag
                aIm[k] = rIm / mag
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
