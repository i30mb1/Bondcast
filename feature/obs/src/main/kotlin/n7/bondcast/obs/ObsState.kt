package n7.bondcast.obs

/** Фаза подключения к obs-websocket — зеркалит стиль StreamPhase. */
public sealed interface ObsPhase {
    public data object Disconnected : ObsPhase
    public data object Connecting : ObsPhase
    public data object Connected : ObsPhase

    /** Неверный пароль: терминальная фаза, ретраи бессмысленны. */
    public data object AuthFailed : ObsPhase
    public data class Retrying(val attempt: Int, val cause: String?) : ObsPhase
}

public data class ObsStats(
    val cpuUsage: Double,
    val activeFps: Double,
    val renderSkippedFrames: Long,
    val renderTotalFrames: Long,
    val outputSkippedFrames: Long,
    val outputTotalFrames: Long,
)

public data class ObsStreamStatus(
    val active: Boolean,
    val timecode: String,
    /** Скорость аплоада OBS; считается по дельте байт между опросами, на первом опросе null. */
    val kbps: Int?,
)

public data class ObsRecordStatus(
    val active: Boolean,
    val timecode: String,
)

/** OBS закрыл сокет из-за неверного пароля (close code 4009). */
public class ObsAuthException(message: String) : Exception(message)

/** OBS ответил на запрос отказом (requestStatus.result == false). */
public class ObsRequestException(
    public val code: Int,
    comment: String?,
) : Exception("OBS отказал: code=$code${comment?.let { ", $it" } ?: ""}")
