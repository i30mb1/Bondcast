package n7.bondcast.chat.twitch

/**
 * Пара токенов DCF + личность. refresh одноразовый — каждый refresh отдаёт новый,
 * его надо тут же перезаписать. expiresAtMs — абсолютное время протухания access.
 */
internal data class TwitchTokens(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val login: String,
    val scopes: Set<String>,
    val expiresAtMs: Long,
)
