package n7.bondcast.chat.twitch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class TwitchViewersMonitorImpl(
    private val session: TwitchSession,
) : TwitchViewersMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<TwitchViewersState?>(null)
    override val state: StateFlow<TwitchViewersState?> = _state.asStateFlow()

    private var pollJob: Job? = null

    override fun setActive(active: Boolean, channel: String) {
        pollJob?.cancel()
        if (!active) {
            pollJob = null
            _state.value = null
            return
        }
        pollJob = scope.launch { poll(channel) }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun poll(channel: String) {
        while (true) {
            refresh(channel)
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun refresh(channel: String) {
        val self = session.userId ?: session.resolveUserId(null) ?: return
        val broadcasterId = session.resolveUserId(channel.ifBlank { null }) ?: self
        session.chatters(broadcasterId, self)?.let { _state.value = TwitchViewersState(it.total, it.names) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
