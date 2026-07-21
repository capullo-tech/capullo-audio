package tech.capullo.audio.calibration

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records the room at 48 kHz mono 16-bit for calibration. Prefers the UNPROCESSED
 * source (no AGC/NS mangling the correlation), falling back to VOICE_RECOGNITION.
 * Caller is responsible for RECORD_AUDIO being granted; [record] returns null if the
 * recorder cannot be opened.
 */
class MicCapture(private val context: Context) {

    class Capture(val pcm: FloatArray, val firstSampleNanos: Long, val sampleRate: Int)

    @SuppressLint("MissingPermission") // documented caller contract
    suspend fun record(durationMs: Int): Capture? = withContext(Dispatchers.IO) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = "true" ==
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        val source = if (unprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = try {
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed: ${e.message}")
            return@withContext null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (source=$source)")
            recorder.release()
            return@withContext null
        }
        try {
            val total = SAMPLE_RATE.toLong() * durationMs / 1000
            val out = FloatArray(total.toInt())
            val chunk = ShortArray(SAMPLE_RATE / 10)
            recorder.startRecording()
            var written = 0
            var firstSampleNanos = 0L
            while (written < out.size) {
                val n = recorder.read(chunk, 0, minOf(chunk.size, out.size - written))
                if (n <= 0) {
                    Log.e(TAG, "AudioRecord read returned $n")
                    return@withContext null
                }
                if (firstSampleNanos == 0L) {
                    // First data just left the driver: now-minus-chunk approximates sample 0.
                    // ±50 ms accuracy is plenty; alignment error cancels in peak spacing.
                    firstSampleNanos = System.nanoTime() - n * 1_000_000_000L / SAMPLE_RATE
                }
                for (i in 0 until n) out[written + i] = chunk[i] / 32768.0f
                written += n
            }
            Capture(out, firstSampleNanos, SAMPLE_RATE)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }

    companion object {
        private const val TAG = "MicCapture"
        const val SAMPLE_RATE = 48_000
    }
}
