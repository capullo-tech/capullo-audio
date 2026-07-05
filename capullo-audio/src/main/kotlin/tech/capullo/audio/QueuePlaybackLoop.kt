package tech.capullo.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.player.MediaPlayback

/**
 * The engine's contract-consuming core: drives the
 * `queue().idAt(i) → mediaRequestFor(id) → play → onQueueAdvanced(i)` loop against the SPI.
 *
 * It is **Media3-free** - it talks only to [MediaSourceProvider]/[NowPlayingSource] and the
 * engine-internal [MediaPlayback] seam (the only thing that builds a `MediaItem`). That is what lets
 * this exact loop be unit-tested on the JVM with a fake playback - the fake-driven driver
 * pattern, now exercised from the **engine** side, so `capullo-audio-contracts` is validated
 * bidirectionally.
 *
 * Obligations honoured (surfaced by `capullo-source-telegram`'s driver test):
 *  1. **Ordering.** `mediaRequestFor(idAt(i))` is called BEFORE `onQueueAdvanced(i)` - structurally,
 *     in the single [resolveAndPlay] method - because a fetch-based source's now-playing enrichment
 *     (ID3 tags + art) lives in `mediaRequestFor` and is only readable post-download.
 *  2. **Skip on throw.** A throw from `mediaRequestFor` is the source's only "skip me" signal (e.g. a
 *     deleted Telegram track → `UnresolvableTrackException`); it is caught and the track skipped,
 *     bounded by the queue size so an all-unresolvable queue can't spin forever.
 *  3. **Clock overlay.** The engine overlays its own live `positionMs`/`isPlaying` (it owns the
 *     ExoPlayer clock) onto the source's [NowPlaying] before the plugin publishes it - the source
 *     only sets `isPlaying=true`/`positionMs=0`. `canGoNext`/`canGoPrevious` stay the source's (it
 *     owns queue position).
 */
internal class QueuePlaybackLoop(
    private val source: MediaSourceProvider,
    nowPlayingSource: NowPlayingSource,
    private val playback: MediaPlayback,
    private val scope: CoroutineScope,
) {
    private val sourceNowPlaying = nowPlayingSource.nowPlaying

    private val _nowPlaying = MutableStateFlow(overlay(sourceNowPlaying.value))

    /** The source's now-playing with the engine's real clock overlaid - what the plugin publishes. */
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    /** Queue index currently playing, or `-1` before the first successful advance. */
    var currentIndex: Int = -1
        private set

    init {
        // Re-publish the overlay when the player's play/pause state flips (the engine owns isPlaying)…
        playback.onStateChanged = { republish() }
        // …and auto-advance a FINITE playlist when the current item ends. A rotating source (live
        // radio) never really "ends", and replaying the same station on a stray STATE_ENDED is the
        // app's call, not the engine's - so honour the contract's [PlaybackQueue.isRotating] here
        // (the flag the source sets precisely for this finite-vs-rotation decision).
        playback.onEnded = { scope.launch { if (!source.queue().isRotating) advance() } }
    }

    /** Overlay the engine's live clock onto a source snapshot. Called by the engine on every source
     *  metadata emission and internally on play/pause + advance. */
    fun republish() { _nowPlaying.value = overlay(sourceNowPlaying.value) }

    private fun overlay(base: NowPlaying): NowPlaying =
        base.copy(positionMs = playback.positionMs, isPlaying = playback.isPlaying)

    /**
     * Play a specific id - the app-driven selection path (QuantumCast picks a station and calls
     * `engine.play(uuid)`). Resolves the id's queue index (best-effort) so prefetch stays consistent,
     * then plays it. The id is authoritative even if it isn't in the current queue window.
     */
    suspend fun play(id: String) {
        val q = source.queue()
        val index = (0 until q.size).firstOrNull { q.idAt(it) == id } ?: -1
        resolveAndPlay(index, id)
    }

    /** Start playing from the queue's current index (the queue-driven entry - Telecloud playlists). */
    suspend fun start() = advanceTo(source.queue().currentIndex.coerceAtLeast(0))

    /** Advance to the index after the one currently playing (auto-advance on end / user "next"). */
    suspend fun advance() = advanceTo(currentIndex + 1)

    /**
     * Resolve queue index [index] and play it. On an unresolvable track (`mediaRequestFor` throws)
     * skip forward, bounded by the queue size so an all-unresolvable queue can't spin forever. A
     * `null` id (past the end of a finite queue) stops; a rotating queue never returns `null`.
     */
    suspend fun advanceTo(index: Int) {
        val q = source.queue()
        if (q.size == 0) return
        var i = index
        var attempts = 0
        while (attempts < q.size) {
            val id = q.idAt(i) ?: return // end of a finite queue → stop
            if (resolveAndPlay(i, id)) return
            i++
            attempts++
        }
    }

    /** The one resolve→play→report path. Returns `true` on success, `false` if unresolvable. */
    private suspend fun resolveAndPlay(index: Int, id: String): Boolean {
        val request = try {
            source.mediaRequestFor(id) // may suspend (download) + enrich NowPlaying
        } catch (e: Exception) {
            return false // the source's only "skip me" signal
        }
        playback.play(request)
        currentIndex = index
        source.onQueueAdvanced(index) // AFTER mediaRequestFor - so the source can prefetch N+1/N+2
        republish()
        return true
    }
}
