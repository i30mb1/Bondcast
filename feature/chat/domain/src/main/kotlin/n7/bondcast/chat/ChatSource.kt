package n7.bondcast.chat

import kotlinx.coroutines.flow.Flow

/**
 * Источник чата одной площадки. Весь разброс между Twitch/YouTube/Kick
 * (транспорт push vs опрос, авторизация, квоты, неофиц. эндпоинты)
 * спрятан здесь; наружу — единый холодный поток [ChatEvent].
 *
 * Авторизацию НЕ кладём в этот интерфейс: токен живёт в фабрике конкретного
 * источника, поэтому чтение выглядит одинаково у анонимного и залогиненного.
 */
public interface ChatSource {

    public val platform: ChatPlatform

    public val capabilities: ChatCapabilities

    /**
     * Подключиться и стримить события. Поток холодный: сбор запускает транспорт
     * (сокет или петлю опроса), отмена — гасит его. Реконнект — забота источника.
     */
    public fun connect(target: ChatTarget): Flow<ChatEvent>
}
