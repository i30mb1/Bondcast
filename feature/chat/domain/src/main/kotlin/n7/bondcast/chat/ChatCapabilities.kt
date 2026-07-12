package n7.bondcast.chat

/**
 * Что умеет источник — по этим флагам UI и настройки адаптируются под площадку
 * (напр. прячут тоггл эмоутов, если картинок нет; показывают предупреждение о квоте).
 */
public data class ChatCapabilities(
    /** Нужен ли логин для чтения. */
    val needsAuth: Boolean,
    /** Можно ли читать анонимно (Kick/justinfan). */
    val anonymousRead: Boolean,
    /** Отдаёт ли площадка картинки эмоутов (Twitch/Kick — да, YouTube — нет). */
    val emoteImages: Boolean,
    /** Отдаёт ли картинки значков. */
    val badgeImages: Boolean,
    /** Надёжны ли события модерации (Kick — best-effort). */
    val moderationReliable: Boolean,
    /** Есть ли история чата (у всех сейчас — нет, только live). */
    val chatHistory: Boolean,
    /** Опрос с квотой (YouTube) вместо push-сокета. */
    val polling: Boolean,
)
