package n7.bondcast.chat.twitch

import android.util.Log
import kotlinx.coroutines.flow.StateFlow

internal class TwitchViewersMonitorWithLogging(
    private val origin: TwitchViewersMonitor,
) : TwitchViewersMonitor {

    override val state: StateFlow<TwitchViewersState?> get() = origin.state

    override fun setActive(active: Boolean, channel: String) {
        Log.i(TAG, "активность=$active канал=$channel")
        origin.setActive(active, channel)
    }

    override fun close() = origin.close()

    private companion object {
        const val TAG = "TwitchViewers"
    }
}
