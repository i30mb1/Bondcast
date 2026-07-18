package n7.bondcast.chat.twitch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import n7.bondcast.chat.ChatCapabilities
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatPlatform
import n7.bondcast.chat.ChatSource
import n7.bondcast.chat.ChatTarget
import n7.bondcast.chat.ConnectionState
import n7.bondcast.chat.ModerationKind
import org.json.JSONObject

internal class TwitchChatSourceImpl(
    private val session: TwitchSession,
    private val connection: EventSubConnection,
) : ChatSource {

    override val platform: ChatPlatform = ChatPlatform.TWITCH

    override val capabilities: ChatCapabilities = ChatCapabilities(
        needsAuth = true,
        anonymousRead = false,
        emoteImages = true,
        badgeImages = false,
        moderationReliable = true,
        chatHistory = false,
        polling = false,
    )

    override fun connect(target: ChatTarget): Flow<ChatEvent> = flow {
        emit(connection(ConnectionState.Connecting))
        val self = session.userId ?: session.resolveUserId(null)
        val token = session.accessToken()
        if (self == null || token == null) {
            emit(connection(ConnectionState.Unavailable("нужен вход в Twitch")))
            return@flow
        }
        val broadcasterId = session.resolveUserId(target.channel.ifBlank { null }) ?: self
        // значки канала (меч мода, саб/VIP и т.д.) — резолвим id в картинки один раз на подключение
        val badgeUrls = session.chatBadges(broadcasterId)

        connection.start()
        CHAT_SUBSCRIPTIONS.forEach { (type, version) ->
            connection.register(EventSubSubscription(type, version, condition(broadcasterId, self)))
        }
        emit(connection(ConnectionState.Live))

        val seen = LinkedHashSet<String>()
        connection.notifications
            .filter { it.event.optString("broadcaster_user_id") == broadcasterId }
            .collect { note -> map(note, seen, badgeUrls)?.let { emit(it) } }
    }

    private fun map(note: EventSubNotification, seen: LinkedHashSet<String>, badgeUrls: Map<String, String>): ChatEvent? = when (note.type) {
        "channel.chat.message" -> {
            val id = note.event.getString("message_id")
            if (seen.add(id)) {
                if (seen.size > DEDUP_LIMIT) {
                    seen.iterator().apply {
                        next()
                        remove()
                    }
                }
                ChatMessageParser.message(note.event, badgeUrls)
            } else {
                null
            }
        }

        "channel.chat.message_delete" ->
            moderation(ModerationKind.DeleteMessage(note.event.getString("message_id")))

        "channel.chat.clear_user_messages" ->
            moderation(ModerationKind.BanUser(note.event.getString("target_user_id")))

        "channel.chat.clear" -> moderation(ModerationKind.ClearChat)

        else -> null
    }

    private fun condition(broadcasterId: String, userId: String): JSONObject = JSONObject().put("broadcaster_user_id", broadcasterId).put("user_id", userId)

    private fun connection(state: ConnectionState): ChatEvent = ChatEvent.Connection(ChatPlatform.TWITCH, state)

    private fun moderation(kind: ModerationKind): ChatEvent = ChatEvent.Moderation(ChatPlatform.TWITCH, kind)

    private companion object {
        const val DEDUP_LIMIT = 512
        val CHAT_SUBSCRIPTIONS = listOf(
            "channel.chat.message" to "1",
            "channel.chat.message_delete" to "1",
            "channel.chat.clear_user_messages" to "1",
            "channel.chat.clear" to "1",
        )
    }
}
