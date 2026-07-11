package tech.capullo.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.ExoMediaPlayback
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverProcess

/**
 * The delivery engine's reusable core: consumes a [MediaSourceProvider] through the
 * [QueuePlaybackLoop] (`idAt → mediaRequestFor → play → onQueueAdvanced`), decodes the resolved item
 * through ExoPlayer, tees the PCM into the snapserver FIFO, and publishes now-playing + transport to
 * the Snapcast control plugin. This owns the *player*; the app's `MediaSessionService` owns the
 * Android Service shell, notifications, and app-specific watchdog/UI (the app/engine boundary).
 *
 * Broadcasting flow:  source → ExoPlayer(FifoRenderersFactory) → FIFO → SnapserverProcess → LAN.
 *
 * Not yet wired here (TODO):
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
    private var snapserver: SnapserverProcess? = null
    private var playback: ExoMediaPlayback? = null
    private var loop: QueuePlaybackLoop? = null
    private var plugin: SnapcontrolPlugin? = null

    /** Bring up snapserver (creates the FIFO), the playback loop, the control plugin, and metadata
     *  forwarding. Call before [play]/[playQueue]. */
    public fun startBroadcast(parentJob: Job) {
        // Per-app abstract control-socket name (avoids the device-global "snapcontrol" collision when
        // two capullo apps broadcast at once). Feed the SAME value into the plugin below.
        val server = SnapserverProcess(
            context, streamName,
            controlSocketName = SnapserverProcess.controlSocketName(context),
        ).also { snapserver = it }
        scope.launch { server.start() }

        // The FIFO exists as soon as SnapserverProcess is constructed (mkfifo in its init).
        val mediaPlayback = ExoMediaPlayback(context, server.pipeFilepath).also { playback = it }
        val playbackLoop = QueuePlaybackLoop(source, nowPlaying, mediaPlayback, scope)
            .also { loop = it }

        // The plugin reads the OVERLAID flow (source metadata + the engine's real clock), both in its
        // constructor and on every GetProperties - so both wiring points must be the overlaid flow.
        val p = SnapcontrolPlugin(
            playbackLoop.nowPlaying, controller, parentJob,
            socketName = server.controlSocketName,
        ).also { plugin = it }
        p.start()

        // Push every overlaid now-playing change out to web players / snapclients…
        scope.launch { playbackLoop.nowPlaying.collect { plugin?.notifyPropertiesChanged() } }
        // …and keep the overlay fed by the source's own metadata emissions (new title/art post-fetch).
        scope.launch { nowPlaying.nowPlaying.collect { playbackLoop.republish() } }
    }

    /** Resolve [id] via the source and start decoding it - the app-driven selection path (radio). */
    public suspend fun play(id: String) {
        val l = loop ?: error("startBroadcast() must run before play()")
        l.play(id)
    }

    /** Start from the queue's current index and auto-advance through a finite playlist (Telecloud). */
    public suspend fun playQueue() {
        val l = loop ?: error("startBroadcast() must run before playQueue()")
        l.start()
    }

    public fun stop() {
        playback?.release(); playback = null
        loop = null
        plugin?.stop(); plugin = null
        snapserver = null // process teardown handled by its own lifecycle
    }
}
