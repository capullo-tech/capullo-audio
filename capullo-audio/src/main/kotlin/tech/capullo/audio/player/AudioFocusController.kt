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
 *     e.g. Spotify/YouTube simply stop), so this polls [AudioManager.isMusicActive] and resumes once
 *     the other player has been silent for [QUIET_RESUME_MS].
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

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "onAudioFocusChange: $change (hasFocus=$hasFocus)")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss: another app took over and Android won't redistribute focus back.
                // Stop the local snapclient and arm the quiet-watcher to reclaim once it goes silent.
                if (hasFocus) {
                    hasFocus = false
                    pausedByFocusLoss = true
                    onPause()
                    armQuietWatcher()
                    Log.d(TAG, "focus lost (permanent) -> local paused, quiet-watcher armed")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Transient loss (call, nav prompt): stop, but DON'T arm the watcher - during a call
                // isMusicActive is false and the watcher would blast audio into the call. GAIN is
                // reliably delivered when the transient owner finishes.
                if (hasFocus) {
                    hasFocus = false
                    pausedByFocusLoss = true
                    onPause()
                    Log.d(TAG, "focus lost (transient) -> local paused, awaiting GAIN")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                // Resume without re-requesting: focus was just handed back to us.
                resumeLocalPlayback(reclaimFocus = false, reason = "focus gain")
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: keep playing at full volume (see class doc).
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
            if (audioManager.isMusicActive) {
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
    }
}
