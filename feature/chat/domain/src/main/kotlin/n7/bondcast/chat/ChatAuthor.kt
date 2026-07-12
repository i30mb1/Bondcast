package n7.bondcast.chat

/**
 * Автор сообщения. color — ARGB (Int), null означает дефолтный цвет площадки;
 * держим Int, а не android.graphics.Color, чтобы домен оставался чистым JVM.
 */
public data class ChatAuthor(
    val id: String,
    val displayName: String,
    val color: Int? = null,
    val roles: Set<ChatRole> = emptySet(),
    val badges: List<ChatBadge> = emptyList(),
    val avatarUrl: String? = null,
) {
    /** Старший значок для компактной подписи. */
    public val primaryRole: ChatRole? get() = roles.minByOrNull { it.priority }
}
