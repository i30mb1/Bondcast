package n7.bondcast.chat.impl

import kotlinx.coroutines.flow.StateFlow
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatSource
import n7.bondcast.settings.SettingsRepository

/** Фаза чата для UI (агрегат по всем источникам). */
public enum class ChatPhase { IDLE, CONNECTING, LIVE, UNAVAILABLE }

/**
 * Оркестратор чата: мерджит события всех [ChatSource] в один ограниченный буфер
 * сообщений, применяет модерацию и раздаёт состояние. Площадко-агностичен —
 * знает только домен, поэтому YouTube/Kick добавляются как новые элементы списка.
 */
public interface ChatController {

    public val messages: StateFlow<List<ChatEvent.Message>>

    public val phase: StateFlow<ChatPhase>

    /** Включить/выключить поток чата (привязывается к панели/эфиру). */
    public fun setActive(active: Boolean)

    public fun close()
}

public fun chatController(
    sources: List<ChatSource>,
    settingsRepository: SettingsRepository,
): ChatController = ChatControllerWithLogging(ChatControllerImpl(sources, settingsRepository))
