package tech.capullo.audio.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import tech.capullo.audio.contracts.MediaRequest

/**
 * Engine-internal seam over the Media3 player.
 *
 * The contract-consuming queue-advance loop (`QueuePlaybackLoop`) talks to this instead of ExoPlayer
 * directly, so that loop can be unit-tested on the JVM with a fake - ExoPlayer, the snapcast FIFO and
 * `LocalSocket` don't run under `testDebugUnitTest`. This is also the ONE place Media3 meets the
 * neutral [MediaRequest]: the loop stays Media3-free, mirroring the platform's source-side philosophy
 * (the source produces a neutral request; the engine builds the `MediaItem`).
 */
internal interface MediaPlayback {
    /** Resolve a neutral [request] into a Media3 `MediaItem` and start decoding it into the FIFO. */
    fun play(request: MediaRequest)

    /** Live playback position (ms). The engine owns this clock; the loop overlays it onto NowPlaying. */
    val positionMs: Long

    /** Live playing/paused state; likewise overlaid onto the source's NowPlaying. */
    val isPlaying: Boolean

    /** Invoked when the current item finishes (`STATE_ENDED`) so a finite playlist can auto-advance. */
    var onEnded: (() -> Unit)?

    /** Invoked when `isPlaying` flips so the overlay can be re-published to snapclients / web players. */
    var onStateChanged: (() -> Unit)?

    /** Release the player and close the FIFO write end. */
    fun release()
}

/** Adapts a neutral [MediaRequest] into a Media3 [MediaItem] - the one place Media3 meets the SPI. */
@UnstableApi
internal fun MediaRequest.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .apply { mimeType?.let { setMimeType(it) } }
        .build()

/**
 * The real [MediaPlayback]: an ExoPlayer whose PCM is teed into the snapserver FIFO via
 * [FifoRenderersFactory]/[FifoAudioBufferSink]. A fresh player + sink is built per item (parity with
 * the original per-play build - the sink's `closed` flag stops a dying player's async flush from
 * dribbling stale buffers into the next item's fresh pipe).
 *
 * @param fifoPath the snapserver FIFO write end (`SnapserverProcess.pipeFilepath`), created before
 *   the first [play] so opening it O_RDWR succeeds without a reader.
 */
@UnstableApi
internal class ExoMediaPlayback(
    private val context: Context,
    private val fifoPath: String,
) : MediaPlayback {

    private var exoPlayer: ExoPlayer? = null
    private var fifoSink: FifoAudioBufferSink? = null

    override var onEnded: (() -> Unit)? = null
    override var onStateChanged: (() -> Unit)? = null

    override val positionMs: Long get() = exoPlayer?.currentPosition ?: 0L
    override val isPlaying: Boolean get() = exoPlayer?.isPlaying ?: false

    override fun play(request: MediaRequest) {
        releasePlayer()

        // Open the FIFO write end (O_RDWR) before the player starts - parity with the sink docs.
        val sink = FifoAudioBufferSink(fifoPath).also { fifoSink = it; it.open() }

        // Tuned for flaky internet radio: cross-protocol redirects, generous timeouts, and any
        // per-request headers the source supplied (e.g. auth'd streams) via MediaRequest.headers.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setUserAgent("capullo-audio")
            .apply { if (request.headers.isNotEmpty()) setDefaultRequestProperties(request.headers) }
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            // Generous retries for flaky radio streams.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()

        val player = ExoPlayer.Builder(context, FifoRenderersFactory(context, sink))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { exoPlayer = it }
        player.volume = 0f // local audio comes from the snapclient; the tee is pre-volume
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> fifoSink?.enableWrites()
                    Player.STATE_ENDED -> onEnded?.invoke()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged?.invoke()
            }
        })
        player.setMediaItem(request.toMediaItem())
        player.prepare()
        player.play()
    }

    override fun release() = releasePlayer()

    private fun releasePlayer() {
        exoPlayer?.release(); exoPlayer = null
        fifoSink?.close(); fifoSink = null
    }
}
