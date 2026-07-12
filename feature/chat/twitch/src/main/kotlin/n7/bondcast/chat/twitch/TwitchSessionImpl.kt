package n7.bondcast.chat.twitch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TwitchSessionImpl(
    private val api: TwitchApi,
    private val store: TokenStore,
) : TwitchSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    @Volatile
    private var tokens: TwitchTokens? = store.load()

    private val _authState = MutableStateFlow<TwitchAuthState>(
        tokens?.let { TwitchAuthState.LoggedIn(it.login) } ?: TwitchAuthState.LoggedOut,
    )
    override val authState: StateFlow<TwitchAuthState> = _authState.asStateFlow()

    override val userId: String? get() = tokens?.userId
    override val login: String? get() = tokens?.login

    private var loginJob: Job? = null

    override fun startDeviceLogin() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch { runDeviceLogin() }
    }

    override fun logout() {
        loginJob?.cancel()
        loginJob = null
        store.clear()
        tokens = null
        _authState.value = TwitchAuthState.LoggedOut
    }

    override suspend fun accessToken(): String? = freshAccessToken()

    override suspend fun resolveUserId(login: String?): String? {
        if (login.isNullOrBlank()) return userId
        val token = freshAccessToken() ?: return null
        return api.getUser(token, login)?.first
    }

    override suspend fun chatBadges(broadcasterId: String): Map<String, String> {
        val token = freshAccessToken() ?: return emptyMap()
        val map = HashMap<String, String>()
        runCatching { map.putAll(api.getGlobalBadges(token)) }
        // значки канала перекрывают глобальные (тот же ключ) — саб-значки специфичны для канала
        runCatching { map.putAll(api.getChannelBadges(token, broadcasterId)) }
        return map
    }

    private suspend fun runDeviceLogin() {
        val scopes = listOf(TwitchConfig.SCOPE_CHAT_READ)
        val device = runCatching { api.requestDeviceCode(scopes) }.getOrElse {
            _authState.value = TwitchAuthState.Failed(it.message ?: "не удалось получить код")
            return
        }
        _authState.value = TwitchAuthState.AwaitingCode(device.userCode, device.verificationUri)
        val deadline = System.currentTimeMillis() + device.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(device.intervalSec * 1000L)
            when (val result = runCatching { api.pollToken(device.deviceCode, scopes) }.getOrNull()) {
                is TokenResult.Success -> {
                    finishLogin(result)
                    return
                }
                is TokenResult.Failed -> {
                    _authState.value = TwitchAuthState.Failed(result.message)
                    return
                }
                else -> Unit // Pending или сетевой сбой — ждём дальше
            }
        }
        _authState.value = TwitchAuthState.Failed("код истёк")
    }

    private suspend fun finishLogin(success: TokenResult.Success) {
        val validate = api.validate(success.access) ?: run {
            _authState.value = TwitchAuthState.Failed("валидация токена не прошла")
            return
        }
        val fresh = TwitchTokens(
            accessToken = success.access,
            refreshToken = success.refresh,
            userId = validate.userId,
            login = validate.login,
            scopes = success.scopes.ifEmpty { validate.scopes },
            expiresAtMs = System.currentTimeMillis() + success.expiresInSec * 1000L,
        )
        store.save(fresh)
        tokens = fresh
        _authState.value = TwitchAuthState.LoggedIn(fresh.login)
    }

    private suspend fun freshAccessToken(): String? = refreshMutex.withLock {
        val current = tokens ?: return null
        if (System.currentTimeMillis() < current.expiresAtMs - SKEW_MS) return current.accessToken
        when (val result = runCatching { api.refresh(current.refreshToken) }.getOrNull()) {
            is TokenResult.Success -> {
                val validate = api.validate(result.access)
                val rotated = TwitchTokens(
                    accessToken = result.access,
                    refreshToken = result.refresh, // одноразовый — обязательно перезаписываем
                    userId = validate?.userId ?: current.userId,
                    login = validate?.login ?: current.login,
                    scopes = result.scopes.ifEmpty { current.scopes },
                    expiresAtMs = System.currentTimeMillis() + result.expiresInSec * 1000L,
                )
                store.save(rotated)
                tokens = rotated
                rotated.accessToken
            }
            else -> {
                store.clear()
                tokens = null
                _authState.value = TwitchAuthState.LoggedOut
                null
            }
        }
    }

    private companion object {
        const val SKEW_MS = 60_000L
    }
}
