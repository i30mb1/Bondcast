package n7.bondcast.chat

/**
 * Кусок сообщения. Чат приходит структурными «runs», а не готовым текстом/HTML,
 * чтобы UI сам решал как рисовать эмоуты и упоминания.
 */
public sealed interface MessageFragment {

    /** Текст, отображаемый когда картинку рисовать нечем. */
    public val rawText: String

    public data class Text(override val rawText: String) : MessageFragment

    /** id/name эмоута; imageUrl null, если площадка не отдаёт картинок (напр. YouTube-шорткоды). */
    public data class Emote(
        val id: String,
        val name: String,
        val imageUrl: String? = null,
    ) : MessageFragment {
        override val rawText: String get() = name
    }

    public data class Mention(
        val userId: String?,
        val name: String,
    ) : MessageFragment {
        override val rawText: String get() = "@$name"
    }
}
