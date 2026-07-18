package n7.bondcast.chat.twitch

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Фаза мультиплекс-соединения EventSub. */
public sealed interface EventSubState {
    public data object Idle : EventSubState
    public data object Connecting : EventSubState
    public data object Live : EventSubState
    public data class Reconnecting(val attempt: Int, val reason: String?) : EventSubState
}

/**
 * Один WebSocket EventSub на все фичи (чат сейчас, channel points потом). Держит
 * декларативный реестр подписок и идемпотентно переигрывает их при обрыве, крутит
 * реконнект с backoff (как ObsController). Плавный session_reconnect — без пере-создания.
 */
public class EventSubConnection(
    private val session: TwitchSession,
    okHttp: OkHttpClient,
    clientId: String = TwitchConfig.CLIENT_ID,
) {

    private val api = TwitchApi(okHttp, clientId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val socketClient = okHttp.newBuilder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS) // полумёртвый линк всплывёт как onFailure
        .build()

    private val registry = mutableListOf<EventSubSubscription>()

    private val _notifications = MutableSharedFlow<EventSubNotification>(extraBufferCapacity = 256)
    internal val notifications: SharedFlow<EventSubNotification> = _notifications.asSharedFlow()

    private val _state = MutableStateFlow<EventSubState>(EventSubState.Idle)
    public val state: StateFlow<EventSubState> = _state.asStateFlow()

    @Volatile
    private var currentSessionId: String? = null

    private var sessionJob: Job? = null

    public fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runSession() }
    }

    public fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        _state.value = EventSubState.Idle
    }

    public fun close() {
        scope.cancel()
    }

    /** Добавить подписку в реестр; если сессия жива — создать сразу. Идемпотентно по key. */
    internal fun register(subscription: EventSubSubscription) {
        synchronized(registry) {
            if (registry.any { it.key == subscription.key }) return
            registry.add(subscription)
        }
        val sessionId = currentSessionId ?: return
        scope.launch { create(subscription, sessionId) }
    }

    private suspend fun runSession() = coroutineScope {
        var url = INITIAL_URL
        var recreate = true
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = EventSubState.Connecting
            val socket = EventSubSocket(socketClient, url)
            var graceful = false
            var failure: Throwable? = null
            try {
                socket.open()
                val sessionId = withTimeout(WELCOME_TIMEOUT_MS) { socket.sessionId.await() }
                currentSessionId = sessionId
                if (recreate) createAll(sessionId)
                attempt = 0
                _state.value = EventSubState.Live
                coroutineScope {
                    val pump = launch { socket.notifications.collect { _notifications.tryEmit(it) } }
                    val outcome = select<Outcome> {
                        socket.reconnectUrl.onAwait { Outcome.Reconnect(it) }
                        socket.closed.onAwait { Outcome.Closed(it) }
                    }
                    pump.cancel()
                    when (outcome) {
                        is Outcome.Reconnect -> {
                            url = outcome.url
                            graceful = true
                        }

                        is Outcome.Closed -> failure = outcome.cause
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "сессия EventSub оборвалась: $e")
            } finally {
                currentSessionId = null
                socket.close()
            }
            if (graceful) {
                recreate = false // подписки сохраняются на новой сессии
                continue
            }
            recreate = true
            url = INITIAL_URL
            attempt++
            val backoff = min(MAX_BACKOFF, 1 shl min(attempt, BACKOFF_SHIFT_CAP))
            _state.value = EventSubState.Reconnecting(attempt, failure?.message)
            delay(backoff * 1000L)
        }
    }

    private suspend fun createAll(sessionId: String) {
        val snapshot = synchronized(registry) { registry.toList() }
        snapshot.forEach { create(it, sessionId) }
    }

    private suspend fun create(subscription: EventSubSubscription, sessionId: String) {
        val token = session.accessToken() ?: run {
            Log.w(TAG, "нет токена — подписка ${subscription.type} не создана")
            return
        }
        val ok = runCatching {
            api.createEventSubSubscription(token, subscription.type, subscription.version, subscription.condition, sessionId)
        }.getOrDefault(false)
        Log.i(TAG, "подписка ${subscription.type}: ${if (ok) "создана" else "не создана"}")
    }

    private sealed interface Outcome {
        data class Reconnect(val url: String) : Outcome
        data class Closed(val cause: Throwable?) : Outcome
    }

    private companion object {
        const val TAG = "EventSubConn"
        const val INITIAL_URL = "wss://eventsub.wss.twitch.tv/ws"
        const val WELCOME_TIMEOUT_MS = 10_000L
        const val PING_SECONDS = 15L
        const val MAX_BACKOFF = 30
        const val BACKOFF_SHIFT_CAP = 5
    }
}
