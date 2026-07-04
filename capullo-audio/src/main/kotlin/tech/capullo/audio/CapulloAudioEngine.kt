package tech.capullo.audio

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.audio.player.FifoRenderersFactory
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverProcess

/** Adapts a neutral [MediaRequest] into a Media3 [MediaItem] - the one place Media3 meets the SPI. */
@UnstableApi
public fun MediaRequest.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .apply { mimeType?.let { setMimeType(it) } }
        .build()

/**
 * The delivery engine's reusable core: decodes whatever the [MediaSourceProvider] resolves through
 * ExoPlayer, tees the PCM into the snapserver FIFO, and exposes now-playing + transport to the
 * Snapcast control plugin. This owns the *player*; the app's `MediaSessionService` owns the
 * Android Service shell, notifications, and app-specific watchdog/UI (the app/engine boundary).
 *
 * Broadcasting flow:  source → ExoPlayer(FifoRenderersFactory) → FIFO → SnapserverProcess → LAN.
 *
 * Not yet wired here (TODO):
 *  - **prefetch feedback**: forward `Player` position/index → [MediaSourceProvider.onQueueAdvanced]
 *    for Telecloud's 2-track lookahead.
 *  - **snapclient** local playback + listen-in mode (SnapclientProcess) and channel switching.
 *  - app-owned concerns stay in the Service: buffering timeout, station-error fallback, notifications.
 *
 * @param streamName the Snapcast stream identity broadcast to web players and snapclients - each app
 *   supplies its own (e.g. "QuantumCast", "Telecloud") so instances stay distinguishable on a LAN.
 */
@UnstableApi
public class CapulloAudioEngine(
    private val context: Context,
    private val source: MediaSourceProvider,
    private val nowPlaying: NowPlayingSource,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
    private val streamName: String = SnapserverProcess.DEFAULT_STREAM_NAME,
) {
    private var exoPlayer: ExoPlayer? = null
    private var fifoSink: FifoAudioBufferSink? = null
    private var snapserver: SnapserverProcess? = null
    private var plugin: SnapcontrolPlugin? = null

    /** Bring up snapserver (creates the FIFO), the control plugin, and metadata forwarding. */
    public fun startBroadcast(parentJob: Job) {
        val server = SnapserverProcess(context, streamName).also { snapserver = it }
        scope.launch { server.start() }

        val p = SnapcontrolPlugin(nowPlaying.nowPlaying, controller, parentJob).also { plugin = it }
        p.start()

        // Push every now-playing change out to web players / snapclients.
        scope.launch {
            nowPlaying.nowPlaying.collect { plugin?.notifyPropertiesChanged() }
        }
    }

    /** Resolve [id] via the source and start decoding it into the FIFO. */
    public suspend fun play(id: String) {
        val fifoPath = snapserver?.pipeFilepath ?: error("startBroadcast() must run before play()")
        val request = source.mediaRequestFor(id)

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
                if (state == Player.STATE_READY) fifoSink?.enableWrites()
            }
        })
        player.setMediaItem(request.toMediaItem())
        player.prepare()
        player.play()
    }

    public fun stop() {
        exoPlayer?.release(); exoPlayer = null
        fifoSink?.close(); fifoSink = null
        plugin?.stop(); plugin = null
        snapserver = null // process teardown handled by its own lifecycle
    }
}
