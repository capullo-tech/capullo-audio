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
 * another app takes focus, [onPause] should stop this device's snapclient; when focus returns,
 * [onResume] should restart it. Two capullo apps on one device therefore no longer mix their local
 * output - whichever most recently took focus owns the speaker, while both keep broadcasting.
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

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "onAudioFocusChange: $change (hasFocus=$hasFocus)")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (hasFocus) {
                    hasFocus = false
                    onPause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!hasFocus) {
                    hasFocus = true
                    onResume()
                }
            }
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
                .setWillPauseWhenDucked(true)
                .build()
        } else {
            null
        }

    /** Request media audio focus. Returns true if granted immediately (the local snapclient may play). */
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

    /** Give up focus (call when broadcast / listen-in ends). */
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        hasFocus = false
    }

    companion object {
        private const val TAG = "AudioFocusController"
    }
}
