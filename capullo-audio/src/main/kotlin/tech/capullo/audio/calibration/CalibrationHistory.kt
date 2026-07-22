package tech.capullo.audio.calibration

/**
 * Append-only log of verified corrections, per sink, persisted by the host. The BT/web
 * sinks wander on a timescale longer than one capture, so a run can commit a correction
 * that is already ~25 ms stale; deciding a damping policy (skip re-corrections within a
 * sink's observed wander band, or median-of-N) needs a history to reason over. This just
 * accumulates that history — the policy is deliberately NOT built on the current handful
 * of data points. Fire-and-forget: an implementation must never throw into a run.
 */
interface CalibrationHistory {
    /** A correction that passed verification and was committed for [clientId]. */
    fun record(clientId: String, deltaMs: Int, newLatencyMs: Int)
}
