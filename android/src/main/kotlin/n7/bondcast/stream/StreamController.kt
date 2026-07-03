package n7.bondcast.stream

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.bondcast.bonding.LinkInfo
import n7.bondcast.bonding.SrtlaClient
import n7.bondcast.bonding.SrtlaTarget
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
    private val srtlaClient: SrtlaClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: StreamEngine = StreamPackEngine(application)

    private val _phase = MutableStateFlow<StreamPhase>(StreamPhase.Idle)
    val phase: StateFlow<StreamPhase> = _phase.asStateFlow()

    private val _stats = MutableStateFlow<StreamStats?>(null)
    val stats: StateFlow<StreamStats?> = _stats.asStateFlow()

    /** Живые линки бондинга (пусто, когда бондинг выключен или не запущен). */
    val links: StateFlow<List<LinkInfo>> get() = srtlaClient.links

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

    private suspend fun runSession() = coroutineScope {
        val settings = settingsRepository.settings.first()
        StreamService.start(application)
        val sampler = launch { sampleStats() }
        val bonding = settings.bondingEnabled
        Log.i(
            TAG,
            "сессия: bonding=$bonding " +
                if (bonding) "srtla=${settings.srtlaHost}:${settings.srtlaPort}" else "srt=${settings.host}:${settings.port}",
        )
        try {
            var localPort: Int? = null
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                setPhase(StreamPhase.Connecting)
                var failure: Throwable? = null
                try {
                    val effective = if (bonding) {
                        // relay стартует лениво и переживает SRT-реконнекты;
                        // если старт упал — попробуем снова на следующей итерации
                        val port = localPort
                            ?: srtlaClient.start(SrtlaTarget(settings.srtlaHost, settings.srtlaPort))
                                .also { localPort = it }
                        settings.copy(host = "127.0.0.1", port = port)
                    } else {
                        settings
                    }
                    engine.startStream(effective)
                    attempt = 0
                    setPhase(StreamPhase.Live(System.currentTimeMillis()))
                    engine.awaitDisconnect()
                    Log.w(TAG, "SRT-соединение оборвалось: ${engine.lastError?.message ?: "без ошибки"}")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    failure = e // старт не удался — уходим в retry ниже
                    Log.w(TAG, "старт не удался: $e")
                }
                engine.stopStream()
                attempt++
                val backoffSeconds = min(30, 1 shl min(attempt, 5))
                setPhase(StreamPhase.Retrying(attempt, failure?.message ?: engine.lastError?.message))
                delay(backoffSeconds * 1_000L)
            }
        } finally {
            withContext(NonCancellable) {
                sampler.cancelAndJoin()
                engine.stopStream()
                if (bonding) srtlaClient.stop()
                StreamService.stop(application)
                setPhase(StreamPhase.Idle)
                _stats.value = null
            }
        }
    }

    private fun setPhase(phase: StreamPhase) {
        Log.i(TAG, "фаза → $phase")
        _phase.value = phase
    }

    private companion object {
        const val TAG = "StreamSession"
    }

    private suspend fun sampleStats() {
        while (currentCoroutineContext().isActive) {
            _stats.value = if (_phase.value is StreamPhase.Live) engine.readStats() else null
            delay(STATS_INTERVAL_MS)
        }
    }

    private companion object {
        const val STATS_INTERVAL_MS = 1_000L
    }
}
