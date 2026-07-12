package n7.bondcast.chat.twitch

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import n7.bondcast.chat.ChatCapabilities
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatPlatform
import n7.bondcast.chat.ChatSource
import n7.bondcast.chat.ChatTarget

internal class TwitchChatSourceWithLogging(
    private val origin: ChatSource,
) : ChatSource {

    override val platform: ChatPlatform get() = origin.platform
    override val capabilities: ChatCapabilities get() = origin.capabilities

    override fun connect(target: ChatTarget): Flow<ChatEvent> = origin.connect(target)
        .onStart { Log.i(TAG, "connect → ${target.channel}") }
        .onCompletion { Log.i(TAG, "disconnect ← ${target.channel} (${it?.message ?: "штатно"})") }

    private companion object {
        const val TAG = "TwitchChatSource"
    }
}
