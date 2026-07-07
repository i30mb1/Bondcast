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

    /** Открывает SRT-соединение и начинает отправку. */
    suspend fun startStream(settings: StreamSettings)

    /** Ждёт, пока активное соединение не оборвётся. */
    suspend fun awaitDisconnect()

    suspend fun readStats(): StreamStats?

    suspend fun setVideoBitrate(kbps: Int)

    fun availableCameras(): List<CameraOption>

    suspend fun switchCamera(cameraId: String)

    suspend fun stopStream()

    suspend fun release()
}
