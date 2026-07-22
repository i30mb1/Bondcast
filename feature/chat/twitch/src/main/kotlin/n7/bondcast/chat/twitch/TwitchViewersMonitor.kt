package n7.bondcast.chat.twitch

import kotlinx.coroutines.flow.StateFlow

/** Снимок «кто сейчас в чате» для UI — прокси на «сколько зрителей смотрят». */
public data class TwitchViewersState(
    val total: Int,
    val names: List<String>,
)

/** Периодически опрашивает Twitch «кто в чате», пока включён. */
public interface TwitchViewersMonitor {

    public val state: StateFlow<TwitchViewersState?>

    /** [channel] — как в chatChannel настроек (пусто = свой канал); переключение канала перезапускает опрос. */
    public fun setActive(active: Boolean, channel: String)

    public fun close()
}

public fun twitchViewersMonitor(session: TwitchSession): TwitchViewersMonitor = TwitchViewersMonitorWithLogging(TwitchViewersMonitorImpl(session))
