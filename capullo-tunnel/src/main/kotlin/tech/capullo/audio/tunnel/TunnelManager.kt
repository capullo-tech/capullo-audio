package tech.capullo.audio.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * Public-link tunnel: forwards the snapserver HTTP port (web player + /stream + /jsonrpc)
 * through a Cloudflare quick tunnel so anyone with the URL can join - no LAN, no install.
 *
 * Driven by the consuming app's playback service from its own tunnel setting + broadcast
 * state; the resolved public URL (random per connection on quick tunnels) is surfaced via
 * [state] for the public-link dialog.
 *
 * No DI framework here on purpose - the library uses plain constructors throughout, so an app
 * binds this in whatever graph it already has. [context] must be the application context: the
 * manager outlives any activity.
 */
class TunnelManager(private val context: Context) {

    sealed interface TunnelState {
        data object Off : TunnelState
        data object Starting : TunnelState
        data class Active(val publicUrl: String) : TunnelState
        data class Error(val message: String) : TunnelState
    }

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var backend: TunnelBackend? = null
    private var currentPort = 0

    /** Idempotent; restarts only if the target port changed. */
    @Synchronized
    fun start(localPort: Int) {
        if (job != null && currentPort == localPort) return
        stop()
        currentPort = localPort
        _state.value = TunnelState.Starting
        job = scope.launch { runLoop(localPort) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        backend?.stop()
        backend = null
        currentPort = 0
        _state.value = TunnelState.Off
    }

    private suspend fun runLoop(localPort: Int) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val b = CloudflaredBackend(context)
            backend = b
            try {
                // Blocks while the tunnel is up; returns (or throws) when it drops.
                b.run(localPort) { url ->
                    _state.value = TunnelState.Active(url)
                    backoffMs = INITIAL_BACKOFF_MS
                    Log.d(TAG, "Tunnel up: $url")
                }
                if (!currentCoroutineContext().isActive) break
                Log.d(TAG, "Tunnel dropped, reconnecting")
                _state.value = TunnelState.Starting
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                Log.w(TAG, "Tunnel error: ${e.message}")
                _state.value = TunnelState.Error(e.message ?: "tunnel failed")
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            if (currentCoroutineContext().isActive) _state.value = TunnelState.Starting
        }
    }

    companion object {
        private const val TAG = "TunnelManager"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}

interface TunnelBackend {
    /** Connects, forwards [localPort], calls [onUp] with the public URL once the tunnel is
     *  established, then blocks (keeping the tunnel alive) until it drops or [stop] is called. */
    suspend fun run(localPort: Int, onUp: (String) -> Unit)

    fun stop()
}
