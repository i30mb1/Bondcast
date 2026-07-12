package n7.bondcast.chat.twitch

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException

/**
 * Одно WebSocket-соединение EventSub: живёт от open() до обрыва. На реконнект менеджер
 * создаёт новый экземпляр (как ObsClient). Кадры слать НЕЛЬЗЯ — подписки только через Helix.
 */
internal class EventSubSocket(
    private val okHttp: OkHttpClient,
    private val url: String,
) {

    private val _notifications = MutableSharedFlow<EventSubNotification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<EventSubNotification> = _notifications.asSharedFlow()

    /** session_id из session_welcome. */
    val sessionId: CompletableDeferred<String> = CompletableDeferred()

    /** reconnect_url из session_reconnect (плавная миграция, подписки сохраняются). */
    val reconnectUrl: CompletableDeferred<String> = CompletableDeferred()

    /** Причина обрыва (null — штатное закрытие). */
    val closed: CompletableDeferred<Throwable?> = CompletableDeferred()

    private var webSocket: WebSocket? = null

    fun open() {
        webSocket = okHttp.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSE, null)
        finish(null)
    }

    private fun finish(cause: Throwable?) {
        if (!sessionId.isCompleted) sessionId.completeExceptionally(cause ?: IOException("сокет закрыт"))
        closed.complete(cause)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val json = JSONObject(text)
                val metadata = json.getJSONObject("metadata")
                val payload = json.optJSONObject("payload") ?: JSONObject()
                when (metadata.getString("message_type")) {
                    "session_welcome" -> sessionId.complete(payload.getJSONObject("session").getString("id"))
                    "session_keepalive" -> Unit // живость держит pingInterval OkHttp
                    "notification" -> _notifications.tryEmit(
                        EventSubNotification(
                            type = payload.getJSONObject("subscription").getString("type"),
                            event = payload.getJSONObject("event"),
                        ),
                    )
                    "session_reconnect" ->
                        reconnectUrl.complete(payload.getJSONObject("session").getString("reconnect_url"))
                    "revocation" -> Log.w(TAG, "подписка отозвана: ${payload.optJSONObject("subscription")}")
                    "session_disconnect" -> finish(IOException("session_disconnect"))
                }
            }.onFailure {
                webSocket.cancel()
                finish(it)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            finish(if (code == NORMAL_CLOSE) null else IOException("закрыт: $code $reason"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(if (code == NORMAL_CLOSE) null else IOException("закрыт: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(t)
        }
    }

    private companion object {
        const val TAG = "EventSubSocket"
        const val NORMAL_CLOSE = 1000
    }
}
