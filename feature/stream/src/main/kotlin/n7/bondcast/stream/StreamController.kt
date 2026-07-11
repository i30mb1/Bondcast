package n7.bondcast.stream

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import n7.bondcast.bonding.LinkInfo
import n7.bondcast.bonding.SrtlaClient
import n7.bondcast.bonding.SrtlaTarget
import n7.bondcast.overlay.OverlayCompositor
import n7.bondcast.settings.SettingsRepository
import n7.bondcast.settings.StreamSettings
import n7.bondcast.thermal.ThermalMitigations
import n7.bondcast.uvc.usbCameraMonitor
import n7.srtla.abr.AbrConfig
import n7.srtla.abr.AbrController
import n7.srtla.abr.AbrSample
import n7.srtla.abr.abrController
import kotlin.math.max
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
    overlayCompositor: OverlayCompositor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: StreamEngine = StreamPackEngine(application, overlayCompositor) { _desiredCamera.value?.id }

    private val _phase = MutableStateFlow<StreamPhase>(StreamPhase.Idle)
    val phase: StateFlow<StreamPhase> = _phase.asStateFlow()

    private val _stats = MutableStateFlow<StreamStats?>(null)
    val stats: StateFlow<StreamStats?> = _stats.asStateFlow()

    private val _health = MutableStateFlow<StreamHealth?>(null)
    val health: StateFlow<StreamHealth?> = _health.asStateFlow()

    private val _videoBitrateKbps = MutableStateFlow(0)
    val videoBitrateKbps: StateFlow<Int> = _videoBitrateKbps.asStateFlow()

    private val _latencyMs = MutableStateFlow(StreamSettings().latencyMs)
    val latencyMs: StateFlow<Int> = _latencyMs.asStateFlow()

    private val _maxBitrateKbps = MutableStateFlow(StreamSettings().videoBitrateKbps)
    val maxBitrateKbps: StateFlow<Int> = _maxBitrateKbps.asStateFlow()

    private val _abrEnabled = MutableStateFlow(StreamSettings().abrEnabled)
    val abrEnabled: StateFlow<Boolean> = _abrEnabled.asStateFlow()

    private val _minBitrateKbps = MutableStateFlow(StreamSettings().minVideoBitrateKbps)
    val minBitrateKbps: StateFlow<Int> = _minBitrateKbps.asStateFlow()

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

    private val defaultCamera = baseCameras.firstOrNull { !it.isFront } ?: baseCameras.firstOrNull()

    // desired — намерение пользователя (последний тап побеждает), current — фактически применённая
    private val _desiredCamera = MutableStateFlow(defaultCamera)
    private val _currentCamera = MutableStateFlow(defaultCamera)
    val currentCamera: StateFlow<CameraOption?> = _currentCamera.asStateFlow()

    init {
        // единственная корутина применения: collectLatest отменяет устаревший свитч, финальным
        // всегда остаётся последний desired — гонки параллельных scope.launch на каждый тап больше нет
        scope.launch {
            _desiredCamera.collectLatest { option ->
                option ?: return@collectLatest
                // на старте desired==current (стартовый источник ставит prepare) — лишний свитч не нужен;
                // так же гасим повторный тап той же камеры
                if (option == _currentCamera.value) return@collectLatest
                engine.switchCamera(option.id)
                    .onSuccess { _currentCamera.value = option }
                    .onFailure { Log.w(TAG, "переключение камеры не удалось: $it") }
            }
        }
        scope.launch {
            usbMonitor.connected.collect { connected ->
                if (!connected && _desiredCamera.value?.id == USB_CAMERA_ID) {
                    defaultCamera?.let { _desiredCamera.value = it }
                }
            }
        }
        scope.launch {
            val s = settingsRepository.settings.first()
            _latencyMs.value = s.latencyMs
            _maxBitrateKbps.value = s.videoBitrateKbps
            _abrEnabled.value = s.abrEnabled
            _minBitrateKbps.value = s.minVideoBitrateKbps
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
            runCatching { usbMonitor.close() }
            scope.cancel()
        }
    }

    fun selectCamera(option: CameraOption) {
        _desiredCamera.value = option
    }

    fun setLatency(ms: Int) {
        val clamped = ms.coerceIn(LATENCY_MIN_MS, LATENCY_MAX_MS)
        if (clamped == _latencyMs.value) return
        _latencyMs.value = clamped
        scope.launch {
            runCatching { settingsRepository.save(settingsRepository.settings.first().copy(latencyMs = clamped)) }
        }
    }

    fun setMaxBitrate(kbps: Int) {
        val clamped = kbps.coerceIn(MAX_BITRATE_MIN_KBPS, MAX_BITRATE_MAX_KBPS)
        if (clamped == _maxBitrateKbps.value) return
        _maxBitrateKbps.value = clamped
        scope.launch {
            runCatching { settingsRepository.save(settingsRepository.settings.first().copy(videoBitrateKbps = clamped)) }
        }
    }

    fun setAbrEnabled(enabled: Boolean) {
        if (enabled == _abrEnabled.value) return
        _abrEnabled.value = enabled
        scope.launch {
            runCatching { settingsRepository.save(settingsRepository.settings.first().copy(abrEnabled = enabled)) }
        }
    }

    fun setMinBitrate(kbps: Int) {
        val clamped = kbps.coerceIn(MIN_BITRATE_FLOOR_KBPS, _maxBitrateKbps.value)
        if (clamped == _minBitrateKbps.value) return
        _minBitrateKbps.value = clamped
        scope.launch {
            runCatching { settingsRepository.save(settingsRepository.settings.first().copy(minVideoBitrateKbps = clamped)) }
        }
    }

    private suspend fun runSession() = coroutineScope {
        val settings = settingsRepository.settings.first()
        _latencyMs.value = settings.latencyMs
        _maxBitrateKbps.value = settings.videoBitrateKbps
        _abrEnabled.value = settings.abrEnabled
        _minBitrateKbps.value = settings.minVideoBitrateKbps
        foreground.start()
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
                    val latency = _latencyMs.value
                    val maxKbps = _maxBitrateKbps.value
                    val effective = if (bonding) {
                        // relay стартует лениво и переживает SRT-реконнекты;
                        // если старт упал — попробуем снова на следующей итерации
                        val port = localPort
                            ?: srtlaClient.start(SrtlaTarget(settings.srtlaHost, settings.srtlaPort))
                                .also { localPort = it }
                        settings.copy(host = "127.0.0.1", port = port, latencyMs = latency, videoBitrateKbps = maxKbps)
                    } else {
                        settings.copy(latencyMs = latency, videoBitrateKbps = maxKbps)
                    }
                    engine.startStream(effective)
                    attempt = 0
                    setPhase(StreamPhase.Live(System.currentTimeMillis()))
                    if (awaitDisconnectOrLatencyChange(latency)) {
                        engine.stopStream()
                        continue
                    }
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

    private fun buildAbr(enabled: Boolean, minKbps: Int, maxKbps: Int, latencyMs: Int): AbrController? =
        if (enabled) {
            abrController(
                AbrConfig(
                    minKbps = minKbps.coerceAtMost(maxKbps),
                    maxKbps = maxKbps,
                    sndBufHighMs = latencyMs / 2,
                    sndBufLowMs = latencyMs / 5,
                    // шаг подъёма растёт вместе с потолком, иначе от минимума до 20000 ползти минуту+
                    increaseStepKbps = max(500, maxKbps / 25),
                ),
            ) { Log.i(TAG, it) }
        } else {
            null
        }

    private suspend fun awaitDisconnectOrLatencyChange(current: Int): Boolean = coroutineScope {
        val disconnect = async { engine.awaitDisconnect() }
        val latency = async { _latencyMs.first { it != current } }
        try {
            select {
                disconnect.onAwait { false }
                latency.onAwait { true }
            }
        } finally {
            disconnect.cancel()
            latency.cancel()
        }
    }

    private suspend fun sampleStats() {
        var curMax = _maxBitrateKbps.value
        var abrLatency = _latencyMs.value
        var abrOn = _abrEnabled.value
        var curMin = _minBitrateKbps.value
        var abr = buildAbr(abrOn, curMin, curMax, abrLatency)
        var wasLive = false
        var desiredKbps = curMax
        var appliedKbps = 0
        var prevStats: StreamStats? = null
        var prevAtMs = 0L
        while (currentCoroutineContext().isActive) {
            if (_latencyMs.value != abrLatency || _maxBitrateKbps.value != curMax ||
                _abrEnabled.value != abrOn || _minBitrateKbps.value != curMin
            ) {
                abrLatency = _latencyMs.value
                curMax = _maxBitrateKbps.value
                abrOn = _abrEnabled.value
                curMin = _minBitrateKbps.value
                abr = buildAbr(abrOn, curMin, curMax, abrLatency)
                desiredKbps = desiredKbps.coerceAtMost(curMax)
            }
            val live = _phase.value is StreamPhase.Live
            if (live) {
                if (!wasLive) {
                    wasLive = true
                    _videoBitrateKbps.value = curMax
                    abr?.reset(curMax)
                    desiredKbps = curMax
                    appliedKbps = 0
                    prevStats = null
                    engine.readStats()
                    delay(STATS_INTERVAL_MS)
                    continue
                }
                val stats = engine.readStats()
                _stats.value = stats
                if (stats == null) _health.value = null
                if (abr != null) {
                    if (stats != null) {
                        desiredKbps = abr.onSample(
                            AbrSample(System.nanoTime(), stats.sndBufferMs, stats.pktLossTotal),
                        ).targetKbps
                    }
                } else {
                    desiredKbps = curMax
                }
                val cap = mitigations.bitrateCapFraction.value?.let { (curMax * it).roundToInt() }
                val effective = cap?.let { min(desiredKbps, it) } ?: desiredKbps
                if (effective != appliedKbps) {
                    engine.setVideoBitrate(effective)
                    _videoBitrateKbps.value = effective
                    appliedKbps = effective
                }
                if (stats != null) {
                    val now = System.currentTimeMillis()
                    val target = if (_videoBitrateKbps.value > 0) _videoBitrateKbps.value else curMax
                    _health.value = streamHealth(
                        prev = prevStats,
                        cur = stats,
                        elapsedMs = now - prevAtMs,
                        targetKbps = target,
                        latencyMs = abrLatency,
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
        const val LATENCY_MIN_MS = 250
        const val LATENCY_MAX_MS = 8_000
        const val MAX_BITRATE_MIN_KBPS = 500
        const val MAX_BITRATE_MAX_KBPS = 20_000
        const val MIN_BITRATE_FLOOR_KBPS = 300
    }
}
