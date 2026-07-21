package tech.capullo.audio.calibration

/**
 * Pure math for one acoustic measurement: given a reference-ring snapshot and a mic
 * capture, return the per-speaker total delays (pipeline + acoustic) as correlation
 * peaks. Wall-clock alignment between the two captures only needs to be coarse — an
 * alignment error shifts every speaker's peak equally, and calibration only uses peak
 * spacing and probe-induced movement.
 */
object DelayMeasurement {

    /** Total delay search range. Snapcast end-to-end sits near bufferMs (~1 s); 4 s covers
     *  any buffer setting plus route latency with margin. */
    const val MAX_DELAY_MS = 4_000

    fun estimateSpeakerDelays(
        ref: ReferencePcmRing.Snapshot,
        mic: MicCapture.Capture,
        peakCount: Int = 4,
    ): List<Dsp.Peak> {
        require(ref.sampleRate == mic.sampleRate) { "rate mismatch" }
        val fsFull = ref.sampleRate

        // Ring index (snapshot coordinates, oldest sample = 0) of the sample written at the
        // wall instant the mic capture started.
        val lastIdx = ref.pcm.size - 1L
        val nanosBack = ref.lastSampleNanos - mic.firstSampleNanos
        val iMicStart = lastIdx - (nanosBack * fsFull / 1_000_000_000L)

        val maxDelay = fsFull.toLong() * MAX_DELAY_MS / 1000
        val w0 = (iMicStart - maxDelay).coerceAtLeast(0L)
        val w1 = (iMicStart + mic.pcm.size).coerceAtMost(ref.pcm.size.toLong())
        if (w1 - w0 < fsFull) return emptyList() // ring didn't cover the capture
        val window = ref.pcm.copyOfRange(w0.toInt(), w1.toInt())

        // Decimate 4x (48k → 12k): sub-ms lag resolution at a 16x cheaper FFT.
        val fs = fsFull / 4
        val refD = Dsp.decimateBy4(window)
        val micD = Dsp.decimateBy4(mic.pcm)
        // How far the window reaches into the past before the mic's first sample, in
        // decimated samples: a speaker with total delay D peaks at circular lag (D − pre).
        val pre = ((iMicStart - w0) / 4).toInt()

        val r = Dsp.gccPhat(refD, micD) // full circular correlation, size n (power of two)
        val n = r.size

        // Rotate so index j == total delay of j decimated samples: delayed[j] = r[(j − pre) mod n].
        val span = pre.coerceAtMost(n - 1)
        val delayed = DoubleArray(span) { j -> r[Math.floorMod(j - pre, n)] }
        return Dsp.findPeaks(delayed, fs, loMs = 0, hiMs = span * 1000 / fs, count = peakCount)
    }
}
