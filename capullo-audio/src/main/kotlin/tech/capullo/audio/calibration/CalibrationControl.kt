package tech.capullo.audio.calibration

/**
 * The snapserver control surface the calibrator needs: set a client's latency, mute/unmute
 * it, and request a status refresh. [tech.capullo.audio.snapcast.SnapcastControlClient]
 * implements this in production; tests supply a fake so the orchestration (probe → attribute
 * → verify → commit/restore → fallback) can be driven deterministically without a server.
 */
interface CalibrationControl {
    /** Set a client's server-persisted latency; returns the request id (or null if unsent). */
    suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int?

    /** Mute/unmute a client (used for pair-round isolation). */
    suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int)

    /** Ask the server for a fresh status (so a read-back can observe the latest latencies). */
    suspend fun sendGetStatus()
}

/**
 * One acoustic measurement, abstracted so tests can script peaks instead of recording a
 * room. The production implementation records the mic and cross-correlates it against the
 * reference ring; a fake returns predetermined peaks.
 */
interface Measurer {
    /** Salient peaks of a full capture, or null if inconclusive after retry. */
    suspend fun measure(peakCount: Int): List<Dsp.Peak>?

    /** Full-capture peaks plus each 6 s half's peaks (for the split-half gate), or null. */
    suspend fun measureHalves(peakCount: Int): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>>?
}
