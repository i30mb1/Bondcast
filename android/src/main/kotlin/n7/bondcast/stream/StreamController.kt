package n7.bondcast.stream

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.bondcast.service.StreamService
import n7.bondcast.settings.SettingsRepository
import kotlin.math.min

internal sealed interface StreamPhase {
    data object Idle : StreamPhase
    data object Connecting : StreamPhase
    data class Live(val sinceEpochMs: Long) : StreamPhase
    data class Retrying(val attempt: Int, val cause: String?) : StreamPhase
}

/**
 * Оркестратор стрим-сессии: держит фазу, крутит реконнект с backoff,
 * поднимает и опускает foreground-сервис.
 */
internal class StreamController(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: StreamEngine = StreamPackEngine(application)

    private val _phase = MutableStateFlow<StreamPhase>(StreamPhase.Idle)
    val phase: StateFlow<StreamPhase> = _phase.asStateFlow()

    private var sessionJob: Job? = null

    val isSessionActive: Boolean get() = sessionJob?.isActive == true

    fun start() {
        if (isSessionActive) return
        sessionJob = scope.launch { runSession() }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
    }

    private suspend fun runSession() {
        val settings = settingsRepository.settings.first()
        StreamService.start(application)
        try {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                _phase.value = StreamPhase.Connecting
                try {
                    engine.startStream(settings)
                    attempt = 0
                    _phase.value = StreamPhase.Live(System.currentTimeMillis())
                    engine.awaitDisconnect()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // соединение не удалось — уходим в retry ниже
                }
                engine.stopStream()
                attempt++
                val backoffSeconds = min(30, 1 shl min(attempt, 5))
                _phase.value = StreamPhase.Retrying(attempt, engine.lastError?.message)
                delay(backoffSeconds * 1_000L)
            }
        } finally {
            withContext(NonCancellable) {
                engine.stopStream()
                StreamService.stop(application)
                _phase.value = StreamPhase.Idle
            }
        }
    }
}
