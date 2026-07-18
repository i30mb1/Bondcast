package n7.bondcast.obs

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.bondcast.settings.SettingsRepository
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Пульт OBS: держит соединение, пока открыта панель, крутит реконнект с backoff
 * (как StreamController) и раздаёт состояние сцен/стрима/записи/статистики.
 */
public class ObsController(private val settingsRepository: SettingsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ленивый: OkHttp не нужен, пока панель ни разу не открывали
    private val okHttp by lazy {
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS) // полумёртвый Wi-Fi всплывёт как onFailure
            .build()
    }

    private val _phase = MutableStateFlow<ObsPhase>(ObsPhase.Disconnected)
    val phase: StateFlow<ObsPhase> = _phase.asStateFlow()

    private val _scenes = MutableStateFlow<List<String>>(emptyList())
    val scenes: StateFlow<List<String>> = _scenes.asStateFlow()

    private val _currentScene = MutableStateFlow<String?>(null)
    val currentScene: StateFlow<String?> = _currentScene.asStateFlow()

    private val _streamStatus = MutableStateFlow<ObsStreamStatus?>(null)
    val streamStatus: StateFlow<ObsStreamStatus?> = _streamStatus.asStateFlow()

    private val _recordStatus = MutableStateFlow<ObsRecordStatus?>(null)
    val recordStatus: StateFlow<ObsRecordStatus?> = _recordStatus.asStateFlow()

    private val _stats = MutableStateFlow<ObsStats?>(null)
    val stats: StateFlow<ObsStats?> = _stats.asStateFlow()

    private var sessionJob: Job? = null

    @Volatile
    private var client: ObsClient? = null

    /** Панель открыта — соединяемся, закрыта — отпускаем OBS. Идемпотентно. */
    fun setPanelVisible(visible: Boolean) {
        if (visible) {
            if (sessionJob?.isActive == true) return
            sessionJob = scope.launch { runSession() }
        } else {
            sessionJob?.cancel()
            sessionJob = null
        }
    }

    fun selectScene(name: String) {
        // оптимистично: событие CurrentProgramSceneChanged подтвердит или поправит
        _currentScene.value = name
        send("SetCurrentProgramScene", JSONObject().put("sceneName", name))
    }

    fun toggleStream() {
        send(if (_streamStatus.value?.active == true) "StopStream" else "StartStream")
    }

    fun toggleRecord() {
        send(if (_recordStatus.value?.active == true) "StopRecord" else "StartRecord")
    }

    fun close() {
        scope.cancel()
    }

    private fun send(type: String, data: JSONObject? = null) {
        val current = client ?: return
        scope.launch {
            runCatching { current.request(type, data) }
                .onFailure { Log.w(TAG, "$type не прошёл: $it") }
        }
    }

    private suspend fun runSession() = coroutineScope {
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                val settings = settingsRepository.settings.first()
                if (!settings.obsEnabled || settings.obsHost.isBlank()) {
                    setPhase(ObsPhase.Disconnected)
                    return@coroutineScope
                }
                setPhase(ObsPhase.Connecting)
                val session = ObsClient(okHttp, settings.obsHost, settings.obsPort, settings.obsPassword)
                var failure: Throwable? = null
                try {
                    session.connect()
                    client = session
                    attempt = 0
                    setPhase(ObsPhase.Connected)
                    coroutineScope {
                        val watcher = launch { watchEvents(session) }
                        val poller = launch { pollStatus(session) }
                        val settingsWatcher = launch {
                            settingsRepository.settings
                                .map { Triple(it.obsHost, it.obsPort, it.obsPassword) }
                                .distinctUntilChanged()
                                .drop(1)
                                .first()
                            Log.i(TAG, "настройки OBS сменились — пересоединяемся")
                            session.close()
                        }
                        failure = session.closed.await()
                        watcher.cancel()
                        poller.cancel()
                        settingsWatcher.cancel()
                    }
                    if (failure is ObsAuthException) throw failure
                } catch (auth: ObsAuthException) {
                    Log.w(TAG, "аутентификация не прошла: ${auth.message}")
                    setPhase(ObsPhase.AuthFailed)
                    return@coroutineScope
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    failure = e
                    Log.w(TAG, "подключение к OBS не удалось: $e")
                } finally {
                    client = null
                    session.close()
                    resetData()
                }
                attempt++
                val backoffSeconds = min(30, 1 shl min(attempt, 5))
                setPhase(ObsPhase.Retrying(attempt, failure?.message))
                delay(backoffSeconds * 1_000L)
            }
        } finally {
            withContext(NonCancellable) {
                client = null
                resetData()
                setPhase(ObsPhase.Disconnected)
            }
        }
    }

    private suspend fun watchEvents(session: ObsClient) {
        session.events.collect { (type, data) ->
            when (type) {
                "CurrentProgramSceneChanged" -> _currentScene.value = eventSceneName(data)

                // в payload нет текущей сцены — перечитываем список целиком
                "SceneListChanged" -> runCatching {
                    val (scenes, current) = parseSceneList(session.request("GetSceneList"))
                    _scenes.value = scenes
                    if (current != null) _currentScene.value = current
                }

                "StreamStateChanged" -> {
                    val active = eventOutputActive(data)
                    _streamStatus.value = ObsStreamStatus(active, _streamStatus.value?.timecode ?: "00:00:00", null)
                }

                "RecordStateChanged" -> {
                    val active = eventOutputActive(data)
                    _recordStatus.value = ObsRecordStatus(active, _recordStatus.value?.timecode ?: "00:00:00")
                }
            }
        }
    }

    private suspend fun pollStatus(session: ObsClient) {
        val (scenes, current) = parseSceneList(session.request("GetSceneList"))
        _scenes.value = scenes
        _currentScene.value = current
        var prevBytes: Long? = null
        var prevAtMs = 0L
        while (currentCoroutineContext().isActive) {
            runCatching {
                val stats = parseStats(session.request("GetStats"))
                val stream = parseStreamStatus(session.request("GetStreamStatus"))
                val record = parseRecordStatus(session.request("GetRecordStatus"))
                val now = System.currentTimeMillis()
                // байты * 8 / мс = кбит/с
                val kbps = prevBytes
                    ?.takeIf { stream.active && now > prevAtMs }
                    ?.let { prev -> (((stream.outputBytes - prev) * 8) / (now - prevAtMs)).toInt().coerceAtLeast(0) }
                _stats.value = stats
                _streamStatus.value = ObsStreamStatus(stream.active, stream.timecode, kbps)
                _recordStatus.value = record
                prevBytes = stream.outputBytes
                prevAtMs = now
            }.onFailure {
                if (it is CancellationException) throw it
                // обрыв разбудит runSession через closed.await(); здесь просто не спамим
                Log.w(TAG, "опрос статуса не прошёл: $it")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun setPhase(phase: ObsPhase) {
        Log.i(TAG, "фаза → $phase")
        _phase.value = phase
    }

    private fun resetData() {
        _scenes.value = emptyList()
        _currentScene.value = null
        _streamStatus.value = null
        _recordStatus.value = null
        _stats.value = null
    }

    private companion object {
        const val TAG = "ObsControl"
        const val POLL_INTERVAL_MS = 2_000L
    }
}
