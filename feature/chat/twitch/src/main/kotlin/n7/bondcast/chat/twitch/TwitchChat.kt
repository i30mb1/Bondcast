package n7.bondcast.chat.twitch

import android.content.Context
import n7.bondcast.chat.ChatSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Сборка Twitch-стороны чата на одном общем OkHttp: сессия (DCF), мультиплекс-EventSub
 * и агностичный [source]. Наружу отдаёт только площадко-нейтральные типы, чтобы okhttp
 * не протекал в app-модуль.
 */
public class TwitchChat internal constructor(
    public val session: TwitchSession,
    public val eventSub: EventSubConnection,
    public val source: ChatSource,
    public val viewers: TwitchViewersMonitor,
)

public fun twitchChat(context: Context, clientId: String = TwitchConfig.CLIENT_ID): TwitchChat {
    val okHttp = OkHttpClient.Builder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()
    val session = twitchSession(context, okHttp, clientId)
    val eventSub = EventSubConnection(session, okHttp, clientId)
    val source = twitchChatSource(session, eventSub)
    val viewers = twitchViewersMonitor(session)
    return TwitchChat(session, eventSub, source, viewers)
}

private const val PING_SECONDS = 15L
