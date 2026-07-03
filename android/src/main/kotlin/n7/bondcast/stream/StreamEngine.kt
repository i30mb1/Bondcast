package n7.bondcast.stream

import io.github.thibaultbee.streampack.ui.views.PreviewView
import n7.bondcast.settings.StreamSettings

/**
 * Шов между приложением и конкретным пайплайном стриминга (сейчас — StreamPack).
 * Меняя реализацию, не трогаем UI и контроллер.
 */
internal interface StreamEngine {

    /** Последняя ошибка пайплайна, если была. */
    val lastError: Throwable?

    /** Создаёт пайплайн и применяет конфиг кодеков. Требует уже выданных разрешений камеры и микрофона. */
    suspend fun prepare(settings: StreamSettings)

    /** Открывает SRT-соединение и начинает отправку. */
    suspend fun startStream(settings: StreamSettings)

    /** Ждёт, пока активное соединение не оборвётся. */
    suspend fun awaitDisconnect()

    suspend fun stopStream()

    /** Привязывает превью камеры к View. */
    suspend fun bindPreview(view: PreviewView)

    suspend fun release()
}
