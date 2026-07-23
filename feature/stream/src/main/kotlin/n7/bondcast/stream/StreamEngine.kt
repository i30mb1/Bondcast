package n7.bondcast.stream

import n7.bondcast.settings.StreamSettings

/**
 * Шов между приложением и конкретным пайплайном стриминга (сейчас — StreamPack).
 * Меняя реализацию, не трогаем UI и контроллер.
 */
public interface StreamEngine {

    /** Последняя ошибка пайплайна, если была. */
    val lastError: Throwable?

    /** Создаёт пайплайн и применяет конфиг кодеков. Требует уже выданных разрешений камеры и микрофона. */
    suspend fun prepare(settings: StreamSettings)

    /** Открывает соединение (SRT на свой сервер или RTMP напрямую в Twitch) и начинает отправку. */
    suspend fun startStream(settings: StreamSettings)

    /** Ждёт, пока активное соединение не оборвётся. */
    suspend fun awaitDisconnect()

    suspend fun readStats(): StreamStats?

    suspend fun setVideoBitrate(kbps: Int)

    fun availableCameras(): List<CameraOption>

    /** Меняет источник видео. Result.failure — источник не открылся (UVC/CameraX), камеру не сменили. */
    suspend fun switchCamera(cameraId: String): Result<Unit>

    suspend fun stopStream()

    suspend fun release()
}
