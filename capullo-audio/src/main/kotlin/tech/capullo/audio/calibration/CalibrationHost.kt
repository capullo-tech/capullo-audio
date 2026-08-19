package tech.capullo.audio.calibration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything an app must assemble to offer the mic calibration, assembled once.
 *
 * **Why this exists.** Every app on this platform shares the same structure and differs only in its
 * audio source, so the ~120 lines of wiring around [SyncCalibrator] are duplicated by construction
 * rather than by accident. The visible symptom: telecloud renders the same control sheet as
 * quantumcast but passes no calibrate callback, so it silently has no calibration — not because
 * anyone decided against it, but because the button is the last one percent of a much larger job.
 * A host that has to be reproduced per app is a host that will not be.
 *
 * The app keeps exactly what is genuinely app-shaped: where its journal files live, how it publishes
 * metadata, and which clients its server currently reports. Everything else — building the
 * calibrator, ordering the client list so this device is the reference, suppressing audio-focus
 * losses for the duration, mirroring state to the UI, logging the program material, undo, and
 * crash recovery — is identical everywhere and lives here.
 *
 * ## Reference PCM
 *
 * The calibration correlates a room recording against the exact PCM being played, and there are two
 * ways to hold that PCM: a BROADCASTER mirrors the buffers already going into its snapserver FIFO,
 * a CLIENT starts a silent second snapclient because its own hands PCM straight to oboe with
 * nothing to tap. [ReferenceSource] is that choice, and it is the app's to make because only the
 * app knows which role it is in.
 */
class CalibrationHost(
    private val context: Context,
    /** Live server control. Null when no connection is up, which fails the run with a clear reason
     *  rather than throwing. */
    private val control: () -> CalibrationControl?,
    /** The connected clients as the server currently reports them, in any order. */
    private val connectedClients: () -> List<SyncCalibrator.CalClient>,
    /** This device's own snapclient id. The reference speaker is always this device, because its
     *  sink is the one co-located with the microphone. */
    private val localClientId: () -> String,
    /** Arms a reference PCM source for the ring and returns the disarm action, or null when this
     *  device has none. See [ReferenceSource]. */
    private val reference: ReferenceSource,
    /** Broadcasts an OS-volume boost lease. The knob for targets already at full SW gain; null
     *  simply leaves such a target unboostable. */
    private val publishOsBoost: (suspend (Map<String, Int>, Long) -> Unit)? = null,
    /** Called with true for the duration of a run. ColorOS signals a focus loss when this app's own
     *  recorder opens, which would stop the local snapclient — the reference speaker — mid
     *  measurement. */
    private val suppressAudioFocusLosses: (Boolean) -> Unit = {},
    /** A one-line description of what is playing, polled during a run. The correlation is a matched
     *  filter against the broadcast PCM, so program material is a real variable: sustained or tonal
     *  music self-correlates and throws ghosts at fixed lags. Without this, run-to-run spread cannot
     *  be separated into "the room" versus "the song that happened to be playing". */
    private val nowPlaying: (() -> String)? = null,
    /** Asks the server for fresh status once a run has written its latencies, so the UI stops
     *  showing stale values. */
    private val refreshStatus: (suspend () -> Unit)? = null,
    private val journal: CalibrationJournal? = null,
    private val history: CalibrationHistory? = null,
    private val volumeUndo: VolumeUndo? = null,
) {

    /** Arms a reference PCM source, returning the action that disarms it, or null when this device
     *  cannot produce one. Implementations are trivial; the decision is not, which is why it stays
     *  with the app. */
    fun interface ReferenceSource {
        fun arm(ring: ReferencePcmRing): (() -> Unit)?
    }

    private val _state = MutableStateFlow<SyncCalibrator.State>(SyncCalibrator.State.Idle)

    /** Mirrors the running calibration for the UI. Idle between runs. */
    val state: StateFlow<SyncCalibrator.State> = _state.asStateFlow()

    private var job: Job? = null

    /** True while a run is in progress; a second [start] is ignored rather than queued. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Start a calibration in [scope], or publish a [SyncCalibrator.State.Failed] naming the reason
     * it cannot start.
     *
     * The guards fail LOUDLY on purpose. They run before the calibrator exists, so a silent return
     * produces a run with no log output at all — indistinguishable from a lost intent, and it has
     * cost real debugging time on the rig.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "calibrate ignored: a run is already in progress")
            return
        }
        fun fail(reason: String) {
            Log.w(TAG, "calibrate refused: $reason")
            _state.value = SyncCalibrator.State.Failed(reason)
        }
        val ctl = control() ?: return fail("no server control connection")
        val localId = localClientId().takeIf { it.isNotEmpty() }
            ?: return fail("local snapclient id unknown")
        val connected = connectedClients()
        // The reference is THIS DEVICE: its sink is the one co-located with the microphone, so its
        // arrival is the fixed point everything else is measured against.
        val self = connected.firstOrNull { it.id == localId || it.id.contains(localId) }
            ?: return fail("local snapclient not connected")
        val ordered = listOf(self) + connected.filter { it.id != self.id }
        if (ordered.size < 2) return fail("need a second connected client to calibrate against")

        var disarm: (() -> Unit)? = null
        val calibrator = SyncCalibrator(
            tapArm = { ring ->
                disarm?.invoke()
                disarm = if (ring != null) reference.arm(ring) else null
            },
            mic = MicCapture(context),
            control = ctl,
            readLatencies = { connectedClients().associate { it.id to it.latencyMs } },
            journal = journal,
            history = history,
            volumeUndo = volumeUndo,
            publishOsBoost = publishOsBoost,
        )
        job = scope.launch {
            val mirror = launch { calibrator.state.collect { _state.value = it } }
            val trackLog = nowPlaying?.let { now ->
                launch {
                    var last = ""
                    while (true) {
                        val s = now()
                        if (s != last) {
                            Log.i(TAG, "track: $s")
                            last = s
                        }
                        delay(TRACK_POLL_MS)
                    }
                }
            }
            suppressAudioFocusLosses(true)
            try {
                calibrator.calibrate(ordered)
                refreshStatus?.invoke()
            } finally {
                suppressAudioFocusLosses(false)
                // Belt and braces: tapArm(null) already disarmed, but a death between arming and
                // the calibrator's own finally would otherwise leave a client-side reference tap
                // connected to the server for the lifetime of the process — and a stray tap is a
                // phantom client that disables the balance on the NEXT run rather than this one.
                disarm?.invoke()
                disarm = null
                mirror.cancel()
                trackLog?.cancel()
            }
        }
    }

    /** True when [undoBalance] has volumes to put back. */
    fun canUndoBalance(): Boolean = volumeUndo?.load() != null

    /** Put back the volumes the last balance overwrote; returns the clients restored. */
    suspend fun undoBalance(): List<String> {
        val ctl = control() ?: return emptyList()
        val previous = volumeUndo?.load() ?: return emptyList()
        Log.i(TAG, "undoing balance for ${previous.size} client(s): $previous")
        previous.forEach { (id, percent) -> ctl.sendSetVolume(id, muted = false, percent = percent) }
        volumeUndo.clear()
        return previous.keys.toList()
    }

    /**
     * Undo a run that a process death interrupted. Call once at host startup, after the control
     * connection is up and BEFORE any new run — a killed pair round can leave a client muted or
     * sitting at latency−probe, and only the journal knows what it was before.
     */
    suspend fun recoverInterrupted(): List<String> {
        val j = journal ?: return emptyList()
        val ctl = control() ?: return emptyList()
        return SyncCalibrator.recover(
            j,
            { id, latencyMs -> ctl.sendSetLatency(id, latencyMs) },
            { id, muted, percent -> ctl.sendSetVolume(id, muted, percent) },
        )
    }

    companion object {
        private const val TAG = "SyncCalibrator"
        private const val TRACK_POLL_MS = 5_000L
    }
}
