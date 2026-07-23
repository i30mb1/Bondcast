package n7.bondcast.chat.twitch

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** Состояние авторизации Twitch для UI. */
public sealed interface TwitchAuthState {
    public data object LoggedOut : TwitchAuthState

    /** Показать пользователю код и ссылку (twitch.tv/activate). */
    public data class AwaitingCode(val userCode: String, val verificationUri: String) : TwitchAuthState

    public data class LoggedIn(val login: String) : TwitchAuthState

    public data class Failed(val message: String) : TwitchAuthState
}

/** Кто сейчас в чате канала: общее число + имена (Twitch не отдаёт список зрителей видео — только чата). */
public data class TwitchChatters(
    val total: Int,
    val names: List<String>,
)

/**
 * Единый авторитет по токену Twitch (Device Code Flow). Владеет access/refresh,
 * обновляет их прозрачно и переиспользуется будущими фичами (channel points и т.д.).
 */
public interface TwitchSession {

    public val authState: StateFlow<TwitchAuthState>

    /** id залогиненного пользователя (для условий EventSub-подписок). */
    public val userId: String?

    public val login: String?

    /** Запускает DCF-логин: обновит [authState] на AwaitingCode, затем LoggedIn/Failed. */
    public fun startDeviceLogin()

    public fun logout()

    /** Валидный access-токен (обновит при необходимости) или null, если не залогинен. */
    public suspend fun accessToken(): String?

    /** id пользователя по логину; login == null — сам залогиненный. */
    public suspend fun resolveUserId(login: String?): String?

    /** Ключ трансляции канала — null, если не залогинен или токен без scope channel:read:stream_key. */
    public suspend fun streamKey(): String?

    /** Значки чата канала (set_id/version → url картинки): глобальные + канала. */
    public suspend fun chatBadges(broadcasterId: String): Map<String, String>

    /** Кто сейчас в чате: [moderatorId] — id залогиненного (должен быть бродкастер или мод канала). */
    public suspend fun chatters(broadcasterId: String, moderatorId: String): TwitchChatters?
}

public fun twitchSession(
    context: Context,
    okHttp: OkHttpClient,
    clientId: String = TwitchConfig.CLIENT_ID,
): TwitchSession = TwitchSessionWithLogging(
    TwitchSessionImpl(TwitchApi(okHttp, clientId), TokenStoreImpl(context)),
)
