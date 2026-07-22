package n7.bondcast.chat.twitch

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class TwitchSessionWithLogging(
    private val origin: TwitchSession,
) : TwitchSession {

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        logScope.launch {
            origin.authState.collect { Log.i(TAG, "auth → $it") }
        }
    }

    override val authState: StateFlow<TwitchAuthState> get() = origin.authState
    override val userId: String? get() = origin.userId
    override val login: String? get() = origin.login

    override fun startDeviceLogin() {
        Log.i(TAG, "старт DCF-логина")
        origin.startDeviceLogin()
    }

    override fun logout() {
        Log.i(TAG, "logout")
        origin.logout()
    }

    override suspend fun accessToken(): String? = origin.accessToken()

    override suspend fun resolveUserId(login: String?): String? = origin.resolveUserId(login)

    override suspend fun chatBadges(broadcasterId: String): Map<String, String> = origin.chatBadges(broadcasterId)

    override suspend fun chatters(broadcasterId: String, moderatorId: String): TwitchChatters? = origin.chatters(broadcasterId, moderatorId)

    private companion object {
        const val TAG = "TwitchSession"
    }
}
