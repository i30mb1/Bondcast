package n7.bondcast.chat

/**
 * Значок автора. Площадки отдают только идентификаторы (setId/version) —
 * картинку резолвит реализация источника, поэтому imageUrl опционален.
 */
public data class ChatBadge(
    val setId: String,
    val version: String? = null,
    val label: String? = null,
    val imageUrl: String? = null,
)
