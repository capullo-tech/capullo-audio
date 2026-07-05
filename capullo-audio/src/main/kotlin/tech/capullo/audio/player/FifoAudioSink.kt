package tech.capullo.audio.player

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Receives the decoded PCM from the TeeAudioProcessor (already forced to
 * 44100 Hz / 16-bit / stereo by the processor chain in [FifoRenderersFactory])
 * and writes it into the snapserver FIFO.
 *
 * The FIFO is opened O_RDWR, like VLC's sout file module did: O_RDWR on a
 * named FIFO succeeds immediately without a reader, so the write end can be
 * held open before snapserver starts (snapserver's read then sees EAGAIN, not
 * EOF - see technical gotchas). A plain FileOutputStream (O_WRONLY) would
 * block until snapserver opens the read end → deadlock, since snapserver is
 * only started once playback begins.
 *
 * Writes block when the FIFO fills (snapserver stalled) - that stalls the
 * playback thread and freezes player position, which the engine watchdog in
 * PlaybackService detects, same as with VLC's blocked sout.
 */
@UnstableApi
class FifoAudioBufferSink(private val fifoPath: String) : TeeAudioProcessor.AudioBufferSink {

    // Opened on the caller's thread (main), written on the playback thread
    @Volatile private var fd: FileDescriptor? = null
    @Volatile private var out: FileOutputStream? = null
    // Writes stay disabled until the player reports playing (VLC parity: sout
    // only wrote after Event.Playing). The FIFO holds only ~370ms of PCM and
    // snapserver (the reader) isn't started yet during preroll - writing early
    // fills the pipe and BLOCKS the playback thread before STATE_READY can
    // fire: deadlock, buffering stuck at 0%.
    @Volatile private var writeEnabled = false
    // A released ExoPlayer flushes its sink asynchronously; without this flag
    // the dying player's flush() would reopen the closed FIFO and dribble
    // stale buffers into the next session's fresh pipe.
    @Volatile private var closed = false

    // --- Silence keep-alive (stream priming) ---
    // The snapserver's AsioStream marks the pipe source "idle" after ~120ms with no data. Feeding the
    // FIFO only from handleBuffer (real PCM) starves it during any gap - startup buffering, between
    // tracks, a brief network stall - so the stream blips idle, which (a) flickers the stream-status /
    // web power button at startup and (b) leaves a client that JOINS during an idle blip with no audio
    // until the stream restarts. A keep-alive thread writes silence whenever real PCM hasn't flowed
    // for GAP_THRESHOLD_MS, so the stream stays continuously "playing": no startup flicker, late
    // joiners get audio immediately, and short stalls don't drop the stream. Real PCM and silence are
    // serialized on writeLock so the two writers never interleave and corrupt the PCM; during normal
    // playback handleBuffer fires well inside the threshold, so the keep-alive stays idle and the audio
    // path is untouched (silence flows only during actual gaps). The engine watchdog still fires on a
    // genuinely stuck station (position frozen ~20s) - priming only keeps the SNAPCAST stream alive, it
    // doesn't advance ExoPlayer's clock.
    private val writeLock = Any()
    @Volatile private var lastRealWriteNs = 0L
    @Volatile private var priming = false
    private var silenceThread: Thread? = null
    // 40ms of the guaranteed 44100Hz/16-bit/stereo format = 1764 frames * 4 bytes.
    private val silence = ByteArray(44100 * SILENCE_CHUNK_MS / 1000 * 2 * 2)

    fun enableWrites() {
        writeEnabled = true
        startPriming()
    }

    private fun startPriming() {
        if (priming || closed) return
        priming = true
        lastRealWriteNs = 0L // 0 = "no real PCM yet" → prime immediately until the first buffer arrives
        silenceThread = Thread {
            val buf = ByteBuffer.wrap(silence)
            while (priming && !closed) {
                try {
                    if (writeEnabled && gapExceeded()) {
                        synchronized(writeLock) {
                            if (writeEnabled && !closed && gapExceeded()) {
                                out?.channel?.let { ch ->
                                    buf.rewind()
                                    while (buf.hasRemaining()) ch.write(buf)
                                }
                            }
                        }
                    }
                    Thread.sleep(SILENCE_CHUNK_MS.toLong())
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "FIFO silence keep-alive write failed: ${e.message}")
                }
            }
        }.apply { name = "FifoSilenceKeepAlive"; isDaemon = true; start() }
    }

    // True while no real PCM has flowed for longer than the idle threshold (or none ever has).
    private fun gapExceeded(): Boolean {
        val last = lastRealWriteNs
        return last == 0L || (System.nanoTime() - last) / 1_000_000 > GAP_THRESHOLD_MS
    }

    fun open() {
        if (closed || out != null) return
        try {
            val descriptor = Os.open(fifoPath, OsConstants.O_RDWR, 0)
            fd = descriptor
            out = FileOutputStream(descriptor)
            Log.d(TAG, "FIFO write end open (O_RDWR): $fifoPath")
        } catch (e: Exception) {
            Log.e(TAG, "FIFO open failed: ${e.message}")
        }
    }

    fun close() {
        closed = true
        writeEnabled = false
        priming = false
        silenceThread?.interrupt()
        silenceThread = null
        try { out?.close() } catch (_: Exception) {}
        out = null
        fd = null // closed via the stream
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Called on (re)configure - the chain guarantees 44100/2ch/16-bit here.
        Log.d(TAG, "FIFO sink format: ${sampleRateHz}Hz ${channelCount}ch enc=$encoding")
        open()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!writeEnabled) return // preroll PCM is dropped, see writeEnabled
        // Serialize with the silence keep-alive so the two writers never interleave into the pipe.
        synchronized(writeLock) {
            val o = out ?: return
            lastRealWriteNs = System.nanoTime() // marks real audio flowing → keep-alive backs off
            try {
                val ch = o.channel
                while (buffer.hasRemaining()) ch.write(buffer)
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "FIFO write failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "FifoAudioSink"

        // Keep-alive silence chunk (~40ms) and the gap after which it kicks in. GAP_THRESHOLD_MS is
        // comfortably under the snapserver's ~120ms AsioStream idle timeout; SILENCE_CHUNK_MS paces
        // the writer at ~real-time so it never buffers meaningful silence ahead of real audio.
        private const val SILENCE_CHUNK_MS = 40
        private const val GAP_THRESHOLD_MS = 80L
    }
}

/**
 * DefaultRenderersFactory whose audio sink chain is:
 *   [ChannelMixing → 2ch] → [Sonic resample → 44100] → [Tee → FIFO] → AudioTrack
 *
 * The AudioTrack output stays (it paces playback and provides the position
 * clock) but PlaybackService sets player volume to 0 - local audio comes from
 * the snapclient, and volume is applied after the tee, so the FIFO always
 * receives full-scale PCM.
 *
 * EXTENSION_RENDERER_MODE_ON prefers platform MediaCodec decoders and falls
 * back to the bundled FFmpeg decoders (lib-media3-ffmpeg-android, loaded
 * reflectively off the classpath) for anything exotic.
 *
 * Limitation: only mono and stereo inputs are mapped (covers radio streams);
 * a >2-channel stream would fail to configure. Add explicit downmix matrices
 * here if that ever happens in the wild.
 */
@UnstableApi
class FifoRenderersFactory(
    context: Context,
    private val fifoSink: FifoAudioBufferSink,
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val mixer = ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
            putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
        }
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(44100) }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)  // keep the chain in 16-bit PCM
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    mixer, resampler, TeeAudioProcessor(fifoSink),
                )
            )
            .build()
    }
}
