package n7.bondcast.stream

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.bondcast.bonding.LinkInfo
import n7.bondcast.bonding.SrtlaClient
import n7.bondcast.bonding.SrtlaTarget
import n7.bondcast.settings.SettingsRepository
import n7.bondcast.settings.StreamSettings
import n7.bondcast.thermal.ThermalMitigations
import n7.bondcast.uvc.usbCameraMonitor
import n7.srtla.abr.AbrConfig
import n7.srtla.abr.AbrSample
import n7.srtla.abr.abrController
import kotlin.math.min
import kotlin.math.roundToInt

public sealed interface StreamPhase {
    data object Idle : StreamPhase
    data object Connecting : StreamPhase
    data class Live(val sinceEpochMs: Long) : StreamPhase
    data class Retrying(val attempt: Int, val cause: String?) : StreamPhase
}

/**
 * Оркестратор стрим-сессии: держит фазу, крутит реконнект с backoff,
 * поднимает и опускает foreground-сервис.
 */
public class StreamController(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val srtlaClient: SrtlaClient,
    private val mitigations: ThermalMitigations,
    private val foreground: StreamForeground,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: StreamEngine = StreamPackEngine(application)

    private val _phase = MutableStateFlow<StreamPhase>(StreamPhase.Idle)
    val phase: StateFlow<StreamPhase> = _phase.asStateFlow()

    private val _stats = MutableStateFlow<StreamStats?>(null)
    val stats: StateFlow<StreamStats?> = _stats.asStateFlow()

    private val _health = MutableStateFlow<StreamHealth?>(null)
    val health: StateFlow<StreamHealth?> = _health.asStateFlow()

    private val _videoBitrateKbps = MutableStateFlow(0)
    val videoBitrateKbps: StateFlow<Int> = _videoBitrateKbps.asStateFlow()

    private val usbMonitor = usbCameraMonitor(application)
    private val baseCameras = engine.availableCameras().filterNot { it.id == USB_CAMERA_ID }
    private val usbOption = CameraOption(USB_CAMERA_ID, "USB", false)

    val cameras: StateFlow<List<CameraOption>> = usbMonitor.connected
        .map { connected -> if (connected) baseCameras + usbOption else baseCameras }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (usbMonitor.connected.value) baseCameras + usbOption else baseCameras,
        )

    private val _currentCamera = MutableStateFlow(
        baseCameras.firstOrNull { !it.isFront } ?: baseCameras.firstOrNull(),
    )
    val currentCamera: StateFlow<CameraOption?> = _currentCamera.asStateFlow()

    init {
        scope.launch {
            usbMonitor.connected.collect { connected ->
                if (!connected && _currentCamera.value?.id == USB_CAMERA_ID) {
                    (baseCameras.firstOrNull { !it.isFront } ?: baseCameras.firstOrNull())
                        ?.let { selectCamera(it) }
                }
            }
        }
    }

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

    fun close() {
        val job = sessionJob
        stop()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { job?.join() }
            runCatching { engine.release() }
            scope.cancel()
        }
    }

    fun selectCamera(option: CameraOption) {
        if (option == _currentCamera.value) return
        _currentCamera.value = option
        scope.launch { engine.switchCamera(option.id) }
    }

    private suspend fun runSession() = coroutineScope {
        val settings = settingsRepository.settings.first()
        foreground.start()
        val sampler = launch { sampleStats(settings) }
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
                foreground.stop()
                setPhase(StreamPhase.Idle)
                _stats.value = null
                _health.value = null
                _videoBitrateKbps.value = 0
            }
        }
    }

    private fun setPhase(phase: StreamPhase) {
        Log.i(TAG, "фаза → $phase")
        _phase.value = phase
    }

    private suspend fun sampleStats(settings: StreamSettings) {
        val max = settings.videoBitrateKbps
        val abr = if (settings.abrEnabled) {
            abrController(
                AbrConfig(
                    minKbps = settings.minVideoBitrateKbps,
                    maxKbps = max,
                    sndBufHighMs = settings.latencyMs / 2,
                    sndBufLowMs = settings.latencyMs / 5,
                ),
            ) { Log.i(TAG, it) }
        } else {
            null
        }
        var wasLive = false
        var desiredKbps = max
        var appliedKbps = 0
        var prevStats: StreamStats? = null
        var prevAtMs = 0L
        while (currentCoroutineContext().isActive) {
            val live = _phase.value is StreamPhase.Live
            if (live) {
                val stats = engine.readStats()
                _stats.value = stats
                if (abr != null) {
                    if (!wasLive) {
                        abr.reset(max)
                        desiredKbps = max
                    }
                    if (stats != null) {
                        desiredKbps = abr.onSample(
                            AbrSample(System.nanoTime(), stats.sndBufferMs, stats.pktLossTotal),
                        ).targetKbps
                    }
                } else {
                    desiredKbps = max
                }
                val cap = mitigations.bitrateCapFraction.value?.let { (max * it).roundToInt() }
                if (abr != null || cap != null) {
                    val effective = cap?.let { min(desiredKbps, it) } ?: desiredKbps
                    if (effective != appliedKbps) {
                        engine.setVideoBitrate(effective)
                        _videoBitrateKbps.value = effective
                        appliedKbps = effective
                    }
                }
                if (stats != null) {
                    val now = System.currentTimeMillis()
                    val target = if (_videoBitrateKbps.value > 0) _videoBitrateKbps.value else max
                    _health.value = streamHealth(
                        prev = if (wasLive) prevStats else null,
                        cur = stats,
                        elapsedMs = now - prevAtMs,
                        targetKbps = target,
                        latencyMs = settings.latencyMs,
                    )
                    prevStats = stats
                    prevAtMs = now
                }
            } else {
                _stats.value = null
                _health.value = null
                prevStats = null
                appliedKbps = 0
            }
            wasLive = live
            delay(STATS_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "StreamSession"
        const val STATS_INTERVAL_MS = 1_000L
    }
}
