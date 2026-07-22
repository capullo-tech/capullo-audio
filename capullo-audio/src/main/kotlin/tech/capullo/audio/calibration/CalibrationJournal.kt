package tech.capullo.audio.calibration

/**
 * Durable record of the client latencies a calibration run is about to change, so a
 * process death mid-run (e.g. ColorOS killing the app when its own mic opens) can be
 * undone. `Client.SetLatency` is server-persisted and survives server restarts, so a run
 * killed during a probe window would otherwise leave a client parked at `latency − probe`
 * forever with no record of the original.
 *
 * Contract: [save] the originals before the first mutating write; [clear] once the run has
 * finished (success or handled failure, both of which already restored/committed). If a
 * journal is still present at startup, the previous run died mid-flight — [SyncCalibrator.recover]
 * restores those originals and clears it. The host supplies the storage (it owns the
 * filesystem); the lib only defines the shape.
 */
interface CalibrationJournal {
    /** Persist the pre-run latency of every client the run may touch (client id → ms). */
    fun save(originals: Map<String, Int>)

    /** The originals from an unfinished run, or null if none is journaled. */
    fun load(): Map<String, Int>?

    /** Drop the journal — the run finished and its writes are intentional. */
    fun clear()
}
