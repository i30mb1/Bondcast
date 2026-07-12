package n7.bondcast.chat.twitch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Персист токенов Twitch в шифрованном сторе. Один инстанс на приложение. */
internal interface TokenStore {
    fun load(): TwitchTokens?
    fun save(tokens: TwitchTokens)
    fun clear()
}

internal class TokenStoreImpl(context: Context) : TokenStore {

    private val store = SecureBlobStore(context, PREFS)

    override fun load(): TwitchTokens? {
        val raw = store.read(KEY) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val scopes = json.getJSONArray("scopes").let { arr ->
                buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
            }
            TwitchTokens(
                accessToken = json.getString("access"),
                refreshToken = json.getString("refresh"),
                userId = json.getString("user_id"),
                login = json.getString("login"),
                scopes = scopes,
                expiresAtMs = json.getLong("expires_at"),
            )
        }.getOrNull()
    }

    override fun save(tokens: TwitchTokens) {
        val json = JSONObject()
            .put("access", tokens.accessToken)
            .put("refresh", tokens.refreshToken)
            .put("user_id", tokens.userId)
            .put("login", tokens.login)
            .put("scopes", JSONArray().apply { tokens.scopes.forEach { put(it) } })
            .put("expires_at", tokens.expiresAtMs)
        store.write(KEY, json.toString())
    }

    override fun clear() {
        store.clear(KEY)
    }

    private companion object {
        const val PREFS = "twitch_auth"
        const val KEY = "tokens"
    }
}
