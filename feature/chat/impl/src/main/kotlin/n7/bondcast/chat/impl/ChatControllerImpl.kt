package n7.bondcast.chat.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatSource
import n7.bondcast.chat.ChatTarget
import n7.bondcast.chat.ConnectionState
import n7.bondcast.chat.ModerationKind
import n7.bondcast.settings.SettingsRepository

internal class ChatControllerImpl(
    private val sources: List<ChatSource>,
    private val settingsRepository: SettingsRepository,
) : ChatController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableStateFlow<List<ChatEvent.Message>>(emptyList())
    override val messages: StateFlow<List<ChatEvent.Message>> = _messages.asStateFlow()

    private val _phase = MutableStateFlow(ChatPhase.IDLE)
    override val phase: StateFlow<ChatPhase> = _phase.asStateFlow()

    // буфер общий для потока сообщений и применения настроек — под мьютексом
    private val mutex = Mutex()
    private val buffer = ArrayDeque<ChatEvent.Message>()

    @Volatile
    private var limit = DEFAULT_LIMIT

    @Volatile
    private var hideCommands = true

    @Volatile
    private var autoErase = false

    private var sessionJob: Job? = null

    override fun setActive(active: Boolean) {
        if (active) {
            if (sessionJob?.isActive == true) return
            sessionJob = scope.launch {
                launch { watchDisplayConfig() }
                launch { expireOldMessages() }
                connectionLoop()
            }
        } else {
            sessionJob?.cancel()
            sessionJob = null
            _phase.value = ChatPhase.IDLE
        }
    }

    override fun close() {
        scope.cancel()
    }

    // лимит, «прятать команды» и автостирание — чисто визуальные, применяются НА ЛЕТУ, без переподключения
    private suspend fun watchDisplayConfig() {
        settingsRepository.settings
            .map { Triple(it.chatMessageLimit.coerceIn(MIN_LIMIT, MAX_LIMIT), it.chatHideCommands, it.chatAutoEraseEnabled) }
            .distinctUntilChanged()
            .collect { (newLimit, newHide, newAutoErase) ->
                mutex.withLock {
                    var changed = false
                    if (newHide && !hideCommands) changed = buffer.removeAll { isCommand(it) } || changed
                    hideCommands = newHide
                    autoErase = newAutoErase
                    if (newLimit != limit) {
                        limit = newLimit
                        while (buffer.size > limit) {
                            buffer.removeFirst()
                            changed = true
                        }
                    }
                    if (changed) publish()
                }
            }
    }

    // независимо от лимита по количеству: пока включено, сообщения старше 30с стираются
    private suspend fun expireOldMessages() {
        while (true) {
            delay(EXPIRE_TICK_MS)
            if (!autoErase) continue
            mutex.withLock {
                val cutoff = System.currentTimeMillis() - AUTO_ERASE_AGE_MS
                if (buffer.removeAll { it.timestampMs < cutoff }) publish()
            }
        }
    }

    // переподключение (и чистка буфера) ТОЛЬКО на смену вкл/канала
    private suspend fun connectionLoop() {
        settingsRepository.settings
            .map { it.chatEnabled to it.chatChannel }
            .distinctUntilChanged()
            .collectLatest { (enabled, channel) ->
                mutex.withLock {
                    buffer.clear()
                    publish()
                }
                if (!enabled || sources.isEmpty()) {
                    _phase.value = ChatPhase.IDLE
                    return@collectLatest
                }
                _phase.value = ChatPhase.CONNECTING
                val target = ChatTarget(channel)
                sources.map { it.connect(target) }.merge().collect { handle(it) }
            }
    }

    private suspend fun handle(event: ChatEvent) {
        when (event) {
            is ChatEvent.Message -> mutex.withLock {
                if (hideCommands && isCommand(event)) return@withLock
                buffer.addLast(event)
                while (buffer.size > limit) buffer.removeFirst()
                publish()
            }

            is ChatEvent.Moderation -> mutex.withLock {
                applyModeration(event.kind)
                publish()
            }

            is ChatEvent.Connection -> _phase.value = mapPhase(event.state)

            is ChatEvent.Rich -> Unit // v1 богатые события не показываем
        }
    }

    private fun applyModeration(kind: ModerationKind) {
        when (kind) {
            is ModerationKind.DeleteMessage -> buffer.removeAll { it.id == kind.messageId }
            is ModerationKind.BanUser -> buffer.removeAll { it.author.id == kind.userId }
            ModerationKind.ClearChat -> buffer.clear()
        }
    }

    private fun isCommand(message: ChatEvent.Message): Boolean = message.text.trimStart().startsWith("!")

    // вызывается под mutex
    private fun publish() {
        _messages.value = buffer.toList()
    }

    private fun mapPhase(state: ConnectionState): ChatPhase = when (state) {
        ConnectionState.Connecting, ConnectionState.Reconnecting -> ChatPhase.CONNECTING
        ConnectionState.Live -> ChatPhase.LIVE
        ConnectionState.Ended -> ChatPhase.IDLE
        is ConnectionState.Unavailable, is ConnectionState.Error -> ChatPhase.UNAVAILABLE
    }

    private companion object {
        const val MIN_LIMIT = 5
        const val MAX_LIMIT = 500
        const val DEFAULT_LIMIT = 30
        const val EXPIRE_TICK_MS = 1_000L
        const val AUTO_ERASE_AGE_MS = 30_000L
    }
}
