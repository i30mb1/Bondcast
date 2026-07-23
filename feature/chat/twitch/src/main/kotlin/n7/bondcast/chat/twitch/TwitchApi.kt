package n7.bondcast.chat.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Разовые HTTP-вызовы Twitch: DCF-логин (id.twitch.tv) и Helix (api.twitch.tv). */
internal class TwitchApi(
    private val okHttp: OkHttpClient,
    private val clientId: String,
) {

    suspend fun requestDeviceCode(scopes: List<String>): DeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", scopes.joinToString(" "))
            .build()
        val (code, text) = execute(Request.Builder().url(ID_DEVICE).post(body).build())
        if (code !in 200..299) throw IOException("device code: $code $text")
        val json = JSONObject(text)
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresInSec = json.getInt("expires_in"),
            intervalSec = json.optInt("interval", DEFAULT_INTERVAL),
        )
    }

    suspend fun pollToken(deviceCode: String, scopes: List<String>): TokenResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", scopes.joinToString(" "))
            .add("device_code", deviceCode)
            .add("grant_type", GRANT_DEVICE_CODE)
            .build()
        parseTokenResponse(execute(Request.Builder().url(ID_TOKEN).post(body).build()))
    }

    suspend fun refresh(refreshToken: String): TokenResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        parseTokenResponse(execute(Request.Builder().url(ID_TOKEN).post(body).build()))
    }

    suspend fun validate(accessToken: String): ValidateResult? = withContext(Dispatchers.IO) {
        val (code, text) = execute(
            Request.Builder().url(ID_VALIDATE).header("Authorization", "OAuth $accessToken").build(),
        )
        if (code != 200) return@withContext null
        val json = JSONObject(text)
        ValidateResult(
            userId = json.getString("user_id"),
            login = json.getString("login"),
            scopes = json.optJSONArray("scopes")?.let { arr ->
                buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
            } ?: emptySet(),
            expiresInSec = json.optLong("expires_in", 0),
        )
    }

    /** Ключ трансляции канала (helix/streams/key) — нужен скоуп channel:read:stream_key. */
    suspend fun getStreamKey(accessToken: String, broadcasterId: String): String? = withContext(Dispatchers.IO) {
        val (code, text) = execute(helixGet("$HELIX_STREAM_KEY?broadcaster_id=$broadcasterId", accessToken))
        if (code != 200) return@withContext null
        val data = JSONObject(text).optJSONArray("data") ?: return@withContext null
        if (data.length() == 0) return@withContext null
        data.getJSONObject(0).optString("stream_key").ifBlank { null }
    }

    /** id+login пользователя. login == null — сам залогиненный пользователь. */
    suspend fun getUser(accessToken: String, login: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        val url = if (login.isNullOrBlank()) HELIX_USERS else "$HELIX_USERS?login=$login"
        val (code, text) = execute(helixGet(url, accessToken))
        if (code != 200) return@withContext null
        val data = JSONObject(text).optJSONArray("data") ?: return@withContext null
        if (data.length() == 0) return@withContext null
        val user = data.getJSONObject(0)
        user.getString("id") to user.getString("login")
    }

    /** Создаёт EventSub-подписку на текущую WebSocket-сессию. */
    suspend fun createEventSubSubscription(
        accessToken: String,
        type: String,
        version: String,
        condition: JSONObject,
        sessionId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", type)
            .put("version", version)
            .put("condition", condition)
            .put("transport", JSONObject().put("method", "websocket").put("session_id", sessionId))
        val request = Request.Builder()
            .url(HELIX_EVENTSUB)
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Id", clientId)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val (code, _) = execute(request)
        code in 200..299
    }

    /** Значки чата (set_id/version → url картинки): глобальные + канала. */
    suspend fun getGlobalBadges(accessToken: String): Map<String, String> = withContext(Dispatchers.IO) {
        val (code, text) = execute(helixGet(HELIX_BADGES_GLOBAL, accessToken))
        if (code == 200) parseBadges(text) else emptyMap()
    }

    suspend fun getChannelBadges(accessToken: String, broadcasterId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val (code, text) = execute(helixGet("$HELIX_BADGES?broadcaster_id=$broadcasterId", accessToken))
        if (code == 200) parseBadges(text) else emptyMap()
    }

    /** Кто сейчас в чате канала: страница по 100 + курсор пагинации. */
    suspend fun getChatters(
        accessToken: String,
        broadcasterId: String,
        moderatorId: String,
        cursor: String?,
    ): ChattersPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(HELIX_CHATTERS)
            append("?broadcaster_id=").append(broadcasterId)
            append("&moderator_id=").append(moderatorId)
            append("&first=100")
            if (!cursor.isNullOrBlank()) append("&after=").append(cursor)
        }
        val (code, text) = execute(helixGet(url, accessToken))
        if (code != 200) return@withContext ChattersPage(0, emptyList(), null)
        val json = JSONObject(text)
        val data = json.optJSONArray("data")
        val names = buildList {
            if (data != null) for (i in 0 until data.length()) add(data.getJSONObject(i).getString("user_name"))
        }
        val nextCursor = json.optJSONObject("pagination")?.optString("cursor")?.ifBlank { null }
        ChattersPage(total = json.optInt("total", names.size), logins = names, cursor = nextCursor)
    }

    private fun parseBadges(text: String): Map<String, String> {
        val data = JSONObject(text).optJSONArray("data") ?: return emptyMap()
        val map = HashMap<String, String>()
        for (i in 0 until data.length()) {
            val set = data.getJSONObject(i)
            val setId = set.getString("set_id")
            val versions = set.optJSONArray("versions") ?: continue
            for (j in 0 until versions.length()) {
                val version = versions.getJSONObject(j)
                val url = version.optString("image_url_2x").ifBlank { version.optString("image_url_1x") }
                if (url.isNotBlank()) map["$setId/${version.getString("id")}"] = url
            }
        }
        return map
    }

    private fun helixGet(url: String, accessToken: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .header("Client-Id", clientId)
        .build()

    private fun parseTokenResponse(response: Pair<Int, String>): TokenResult {
        val (code, text) = response
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code in 200..299 && json != null) {
            return TokenResult.Success(
                access = json.getString("access_token"),
                refresh = json.getString("refresh_token"),
                expiresInSec = json.optLong("expires_in", DEFAULT_EXPIRES),
                scopes = json.optJSONArray("scope")?.let { arr ->
                    buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
                } ?: emptySet(),
            )
        }
        val message = json?.optString("message").orEmpty()
        return if (message.contains("authorization_pending")) TokenResult.Pending else TokenResult.Failed(message.ifBlank { "код $code" })
    }

    private fun execute(request: Request): Pair<Int, String> = okHttp.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }

    private companion object {
        const val ID_DEVICE = "https://id.twitch.tv/oauth2/device"
        const val ID_TOKEN = "https://id.twitch.tv/oauth2/token"
        const val ID_VALIDATE = "https://id.twitch.tv/oauth2/validate"
        const val HELIX_USERS = "https://api.twitch.tv/helix/users"
        const val HELIX_STREAM_KEY = "https://api.twitch.tv/helix/streams/key"
        const val HELIX_EVENTSUB = "https://api.twitch.tv/helix/eventsub/subscriptions"
        const val HELIX_BADGES = "https://api.twitch.tv/helix/chat/badges"
        const val HELIX_BADGES_GLOBAL = "https://api.twitch.tv/helix/chat/badges/global"
        const val HELIX_CHATTERS = "https://api.twitch.tv/helix/chat/chatters"
        const val GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_INTERVAL = 5
        const val DEFAULT_EXPIRES = 14400L
        val JSON = "application/json".toMediaType()
    }
}

internal data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

internal data class ChattersPage(
    val total: Int,
    val logins: List<String>,
    val cursor: String?,
)

internal data class ValidateResult(
    val userId: String,
    val login: String,
    val scopes: Set<String>,
    val expiresInSec: Long,
)

internal sealed interface TokenResult {
    data class Success(
        val access: String,
        val refresh: String,
        val expiresInSec: Long,
        val scopes: Set<String>,
    ) : TokenResult

    data object Pending : TokenResult
    data class Failed(val message: String) : TokenResult
}
