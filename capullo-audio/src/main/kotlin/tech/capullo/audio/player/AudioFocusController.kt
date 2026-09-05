package tech.capullo.audio.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Governs Android audio focus for a broadcasting app's **local snapclient** - the audible on-device
 * output - so the foreground app owns the speaker.
 *
 * This is deliberately scoped to the local snapclient only: the ExoPlayer → FIFO → Snapserver
 * broadcast keeps running regardless of focus, so web/LAN listeners are never interrupted. When
 * another app takes focus, [onPause] stops this device's snapclient; when focus returns, [onResume]
 * restarts it. Two capullo apps on one device therefore no longer mix their local output - whichever
 * most recently took focus owns the speaker, while both keep broadcasting.
 *
 * ### Recovery after focus loss
 * Losses are handled by reliability of the follow-up signal:
 *  1. **App brought to foreground** ([refocus]) - always reclaims the speaker.
 *  2. **[AudioManager.AUDIOFOCUS_GAIN]** - reliably delivered only after *transient* losses (a call,
 *     a navigation prompt), so a transient loss just waits for GAIN.
 *  3. **Quiet-watcher** - after a *permanent* loss Android never redistributes focus (no GAIN when
 *     e.g. Spotify/YouTube simply stop), so this polls [foreignMediaActive] and resumes once the
 *     other player has been silent for [QUIET_RESUME_MS]. Read that function before changing the
 *     signal: the obvious [AudioManager.isMusicActive] is wrong here, and wrong in a way that
 *     leaves a broadcaster silent indefinitely.
 *
 * `CAN_DUCK` losses are ignored (kept playing at full volume): a native snapclient can't be ducked
 * cheaply and radio over a brief system beep is acceptable - hence [setWillPauseWhenDucked]`(false)`.
 *
 * All callbacks and internal state run on the main thread.
 */
class AudioFocusController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var hasFocus: Boolean = false
        private set

    /** True while the local snapclient is stopped *because* focus was lost (not a user stop). */
    private var pausedByFocusLoss = false

    @Volatile
    private var suppressing: Boolean = false

    /** The focus change a suppression window swallowed, or 0. Replayed when suppression lifts. */
    private var suppressedLoss: Int = 0

    /** While true, focus LOSSES are ignored entirely (no state transition, no onPause).
     *  Used during mic sync calibration: some OEMs (ColorOS) signal a focus loss when the
     *  app's own recorder opens, which would silence the reference speaker mid-measurement.
     *  Losses are only suppressed for the seconds a calibration runs, started deliberately
     *  by the user, so missing a genuine transient loss (a call) is an accepted trade.
     *
     *  A swallowed loss is REPLAYED when suppression lifts, and that is not optional. Dropping
     *  one outright leaves [hasFocus] true and [pausedByFocusLoss] false while focus is genuinely
     *  gone at the OS level, and a permanent loss is never redistributed, so no GAIN ever arrives,
     *  the quiet-watcher is never armed, and [refocus] returns at its first line forever. The
     *  controller would be wedged past even a foreground reopen, which is worse than the loss it
     *  was protecting the measurement from. */
    var suppressLosses: Boolean
        get() = suppressing
        set(value) {
            val was = suppressing
            suppressing = value
            if (was && !value) mainHandler.post { replaySuppressedLoss() }
        }

    private fun replaySuppressedLoss() {
        val change = suppressedLoss
        suppressedLoss = 0
        if (change == 0) return
        Log.d(TAG, "replaying focus loss $change suppressed during calibration")
        handleLoss(change)
    }

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "onAudioFocusChange: $change (hasFocus=$hasFocus, suppressLosses=$suppressLosses)")
        if (suppressLosses &&
            (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        ) {
            // LATCHED, not dropped - see [suppressLosses]. A later loss overwrites an earlier one
            // because only the most recent describes where focus actually stands.
            suppressedLoss = change
            Log.d(TAG, "focus loss ignored (calibration in progress), latched for replay")
            return@OnAudioFocusChangeListener
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handleLoss(change)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                // Resume without re-requesting: focus was just handed back to us.
                resumeLocalPlayback(reclaimFocus = false, reason = "focus gain")
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: keep playing at full volume (see class doc).
        }
    }

    private fun handleLoss(change: Int) {
        if (!hasFocus) return
        hasFocus = false
        pausedByFocusLoss = true
        onPause()
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            // Permanent loss: another app took over and Android won't redistribute focus back.
            // Arm the quiet-watcher to reclaim once it goes silent.
            armQuietWatcher()
            Log.d(TAG, "focus lost (permanent) -> local paused, quiet-watcher armed")
        } else {
            // Transient loss (call, nav prompt): stop, but DON'T arm the watcher - during a call
            // isMusicActive is false and the watcher would blast audio into the call. GAIN is
            // reliably delivered when the transient owner finishes.
            Log.d(TAG, "focus lost (transient) -> local paused, awaiting GAIN")
        }
    }

    private val request: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .setWillPauseWhenDucked(false)
                .build()
        } else {
            null
        }

    /**
     * Request media audio focus (call when the local snapclient starts). Returns true if granted
     * immediately. A failed request must never silence this device - the caller keeps playing; the
     * grant only governs who gets focus-change callbacks.
     */
    fun request(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "requestAudioFocus -> result=$result granted=$hasFocus")
        return hasFocus
    }

    /**
     * Re-assert focus for the local snapclient - call when the app returns to the foreground so the
     * focused app reclaims the speaker. No-op unless the local snapclient was paused by a focus loss;
     * in that case it reclaims focus and restarts (via [onResume]). This also doubles as recovery on
     * an explicit user "play" action.
     */
    fun refocus() = resumeLocalPlayback(reclaimFocus = true, reason = "refocus")

    /** Give up focus and cancel any pending recovery (call when broadcast / listen-in ends). */
    fun abandon() {
        cancelQuietWatcher()
        pausedByFocusLoss = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        hasFocus = false
    }

    private fun resumeLocalPlayback(reclaimFocus: Boolean, reason: String) {
        if (!pausedByFocusLoss) return
        pausedByFocusLoss = false
        cancelQuietWatcher()
        if (reclaimFocus) request() // reclaim so the next loss is detected again
        onResume()
        Log.d(TAG, "local snapclient resumed ($reason)")
    }

    // --- Quiet-watcher (permanent-loss recovery) ---

    private var quietSince = 0L

    private val quietWatcher = object : Runnable {
        override fun run() {
            if (!pausedByFocusLoss) return
            if (foreignMediaActive()) {
                quietSince = 0L
            } else {
                val now = System.currentTimeMillis()
                when {
                    quietSince == 0L -> quietSince = now
                    now - quietSince >= QUIET_RESUME_MS -> {
                        resumeLocalPlayback(reclaimFocus = true, reason = "other player quiet")
                        return
                    }
                }
            }
            mainHandler.postDelayed(this, WATCH_POLL_MS)
        }
    }

    /**
     * True while some OTHER app is producing media on this device.
     *
     * NOT [AudioManager.isMusicActive], which was the original gate and could never go false on a
     * broadcasting device. That is a global "is the music stream active" question, and a
     * broadcaster is permanently part of the answer: its ExoPlayer holds a started USAGE_MEDIA
     * AudioTrack at volume 0 for the whole session, because the FIFO tee is pre-volume and the
     * AudioTrack's backpressure is what paces the engine, so the track can be neither muted away
     * nor removed. The watcher therefore reset its timer on this app's own silence and only ever
     * resumed when the BROADCAST stopped - the exact opposite of what it is for. Rig, 2026-09-05:
     * a local snapclient stayed dead for 1h50m with nothing else playing.
     *
     * [AudioManager.getActivePlaybackConfigurations] answers the question we actually have.
     * Measured on the rig the same day, with Spotify holding focus:
     *
     *   Spotify playing  ->  isMusicActive=true   activeConfigs=1 [usage=1 content=2 flags=0]
     *   Spotify paused   ->  isMusicActive=true   activeConfigs=0 []
     *
     * Two things that measurement settles, neither of which was safe to assume. This app's own
     * volume-0 track does NOT appear in the list, so there is nothing to filter out - and there is
     * no way to filter it anyway, since the public surface of AudioPlaybackConfiguration is only
     * [android.media.AudioPlaybackConfiguration.getAudioAttributes], with no uid and no player
     * state. And a foreign app IS still recognisable through that keyhole: Spotify came back as
     * usage=USAGE_MEDIA / CONTENT_TYPE_MUSIC, only its flags sanitized, so filtering on usage does
     * not silently miss it and let us grab the speaker back mid-song.
     *
     * If a future change ever DOES put this app's own player in that list, this returns true
     * forever and recovery degrades to [refocus] - which is exactly the behaviour before this fix,
     * not something worse.
     *
     * Below API 26 the API does not exist and [AudioManager.isMusicActive] is all there is. Those
     * devices keep the old behaviour, including its flaw.
     */
    private fun foreignMediaActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return audioManager.isMusicActive
        val configs = runCatching { audioManager.activePlaybackConfigurations }.getOrNull()
            ?: return audioManager.isMusicActive
        return configs.any { it.audioAttributes.usage in CONTENT_USAGES }
    }

    private fun armQuietWatcher() {
        cancelQuietWatcher()
        quietSince = 0L
        mainHandler.postDelayed(quietWatcher, WATCH_POLL_MS)
    }

    private fun cancelQuietWatcher() = mainHandler.removeCallbacks(quietWatcher)

    companion object {
        private const val TAG = "AudioFocusController"

        /** Poll interval for the permanent-loss quiet-watcher. */
        private const val WATCH_POLL_MS = 2_000L

        /** Resume once the other player has been silent this long after a permanent loss. */
        private const val QUIET_RESUME_MS = 3_000L

        /** Usages that mean "somebody is listening to something" and so should hold the speaker.
         *  Sonification and notifications are deliberately absent: a message ping must not keep
         *  postponing the resume. USAGE_UNKNOWN is included because it is what an anonymized or
         *  attribute-less player reports, and missing a real one costs more than a short delay. */
        private val CONTENT_USAGES = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_UNKNOWN,
        )
    }
}
