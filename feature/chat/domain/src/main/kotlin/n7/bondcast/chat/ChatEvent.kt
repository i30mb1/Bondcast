package n7.bondcast.chat

/**
 * Платформо-нейтральное событие чата. Все площадки сводятся к этой иерархии:
 * UI/шина знают только её, транспорт и авторизация спрятаны в реализации источника.
 */
public sealed interface ChatEvent {

    public val platform: ChatPlatform

    /** Обычное сообщение (в т.ч. Super Chat/kicks приходят как [Rich], не сюда). */
    public data class Message(
        override val platform: ChatPlatform,
        val id: String,
        val author: ChatAuthor,
        val fragments: List<MessageFragment>,
        val timestampMs: Long,
        val action: Boolean = false,
        val replyToId: String? = null,
    ) : ChatEvent {
        /** Плоский текст для поиска/фильтра команд. */
        public val text: String get() = fragments.joinToString(separator = "") { it.rawText }
    }

    /** Действие модерации — применяется к уже показанным сообщениям. */
    public data class Moderation(
        override val platform: ChatPlatform,
        val kind: ModerationKind,
    ) : ChatEvent

    /** Богатое событие (донат/членство/фоллоу/рейд/награда) — общий задел под все площадки. */
    public data class Rich(
        override val platform: ChatPlatform,
        val kind: RichKind,
    ) : ChatEvent

    /** Смена состояния соединения источника. */
    public data class Connection(
        override val platform: ChatPlatform,
        val state: ConnectionState,
    ) : ChatEvent
}

public sealed interface ModerationKind {
    /** Удалить одно сообщение. */
    public data class DeleteMessage(val messageId: String) : ModerationKind

    /** Бан/таймаут: снести сообщения пользователя (durationSec null — навсегда). */
    public data class BanUser(val userId: String, val durationSec: Int? = null) : ModerationKind

    /** Полная очистка чата. */
    public data object ClearChat : ModerationKind
}

public sealed interface RichKind {
    /** Награда за баллы канала (Twitch) / аналог. */
    public data class Reward(val userName: String, val title: String, val userInput: String?) : RichKind

    /** Донат: Super Chat / биты / kicks. amountMinor — в минимальных единицах валюты. */
    public data class Donation(
        val userName: String,
        val amountMinor: Long,
        val currency: String,
        val tier: String?,
        val message: String?,
    ) : RichKind

    /** Подписка/членство (gift = подарена). */
    public data class Membership(
        val userName: String,
        val tier: String?,
        val months: Int?,
        val gift: Boolean,
    ) : RichKind

    public data class Follow(val userName: String) : RichKind

    public data class Raid(val fromChannel: String, val viewers: Int) : RichKind
}

public sealed interface ConnectionState {
    public data object Connecting : ConnectionState
    public data object Live : ConnectionState
    public data object Reconnecting : ConnectionState
    public data object Ended : ConnectionState

    /** Чат недоступен (выключен, эфир не идёт, нет прав). */
    public data class Unavailable(val reason: String) : ConnectionState

    public data class Error(val message: String) : ConnectionState
}
