package n7.bondcast.chat

/**
 * Куда подключаться. channel — логин/slug/id, трактует сам источник
 * (Twitch — login, Kick — slug, YouTube — id канала/трансляции).
 */
public data class ChatTarget(
    val channel: String,
)
