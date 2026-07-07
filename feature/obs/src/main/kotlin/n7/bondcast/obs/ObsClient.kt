package n7.bondcast.obs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Одно WebSocket-соединение с obs-websocket v5: живёт от connect() до обрыва.
 * На каждый реконнект контроллер создаёт новый экземпляр — так нет протухшего состояния.
 */
internal class ObsClient(
    private val okHttp: OkHttpClient,
    private val host: String,
    private val port: Int,
    private val password: String,
) {
    private val _events = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)

    /** События OBS (op 5): eventType → eventData. */
    val events: SharedFlow<Pair<String, JSONObject>> = _events.asSharedFlow()

    /** Аналог engine.awaitDisconnect(): завершается причиной обрыва (null — штатное закрытие). */
    val closed: CompletableDeferred<Throwable?> = CompletableDeferred()

    private val identified = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var webSocket: WebSocket? = null

    /** Подключение и рукопожатие Hello → Identify → Identified. */
    suspend fun connect() {
        val request = Request.Builder().url("http://$host:$port").build()
        webSocket = okHttp.newWebSocket(request, listener)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { identified.await() }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Запрос op 6 → ответ op 7; кидает ObsRequestException, если OBS отказал. */
    suspend fun request(type: String, data: JSONObject? = null): JSONObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val socket = webSocket ?: throw IOException("нет соединения")
            if (!socket.send(requestJson(type, id, data))) throw IOException("сокет закрыт")
            val responseData = withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
            return unwrapResponse(responseData)
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSE, null)
        finish(null)
    }

    private fun finish(cause: Throwable?) {
        identified.completeExceptionally(cause ?: IOException("соединение закрыто"))
        closed.complete(cause)
        pending.values.forEach { it.completeExceptionally(cause ?: IOException("соединение закрыто")) }
        pending.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val (op, d) = parseEnvelope(text)
                when (op) {
                    OP_HELLO -> webSocket.send(identifyJson(d, password))
                    OP_IDENTIFIED -> identified.complete(Unit)
                    OP_RESPONSE -> pending.remove(d.getString("requestId"))?.complete(d)
                    OP_EVENT -> _events.tryEmit(d.getString("eventType") to d.getJSONObject("eventData"))
                }
            } catch (e: Exception) {
                // битый протокол или пустой пароль при требуемой аутентификации
                webSocket.cancel()
                finish(e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            finish(closeCause(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(closeCause(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(t)
        }
    }

    private fun closeCause(code: Int, reason: String): Throwable? = when (code) {
        CLOSE_AUTH_FAILED -> ObsAuthException("OBS отверг пароль")
        NORMAL_CLOSE -> null
        else -> IOException("OBS закрыл соединение: $code ${reason.ifEmpty { "" }}".trim())
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
