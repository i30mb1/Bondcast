package n7.bondcast.chat.twitch

/**
 * Публичный (без секрета) Twitch-клиент. Client-ID не секрет и живёт в приложении.
 *
 * ВАЖНО: подставь свой Client-ID из https://dev.twitch.tv/console
 * (тип приложения — Public, OAuth-флоу — Device Code Flow).
 */
public object TwitchConfig {

    public const val CLIENT_ID: String = "ttdy2ki9ifcvexqh9n7rdab7prpqyx"

    /** Скоуп чтения чата через EventSub (не legacy IRC chat:read). */
    public const val SCOPE_CHAT_READ: String = "user:read:chat"

    /** Скоуп «кто сейчас в чате» (helix/chat/chatters) — счётчик и список зрителей. */
    public const val SCOPE_CHATTERS: String = "moderator:read:chatters"

    public val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()
}
