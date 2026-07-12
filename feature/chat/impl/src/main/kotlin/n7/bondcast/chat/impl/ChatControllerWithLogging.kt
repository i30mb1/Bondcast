package n7.bondcast.chat.impl

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import n7.bondcast.chat.ChatEvent

internal class ChatControllerWithLogging(
    private val origin: ChatController,
) : ChatController {

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        logScope.launch { origin.phase.collect { Log.i(TAG, "фаза → $it") } }
    }

    override val messages: StateFlow<List<ChatEvent.Message>> get() = origin.messages
    override val phase: StateFlow<ChatPhase> get() = origin.phase

    override fun setActive(active: Boolean) {
        Log.i(TAG, "setActive($active)")
        origin.setActive(active)
    }

    override fun close() {
        origin.close()
    }

    private companion object {
        const val TAG = "ChatController"
    }
}
