package n7.bondcast.chat

/**
 * Нормализованная роль автора, общая для всех площадок.
 * priority: меньше — выше ранг (для сворачивания нескольких значков в главный).
 */
public enum class ChatRole(public val priority: Int) {
    OWNER(0),
    MODERATOR(1),
    STAFF(2),
    FOUNDER(3),
    VIP(4),
    OG(5),
    SUBSCRIBER(6),
    VERIFIED(7),
}
