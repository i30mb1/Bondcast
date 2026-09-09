package n7.bondcast.logging

import android.content.Context
import java.io.File

/**
 * Лог сеанса работы, который пользователь может приложить к письму с пожеланием.
 *
 * Пишем в свой файл, а не снимаем `logcat -d` в момент отправки: системный кольцевой буфер
 * на телефоне ротируется за минуту-две, и к моменту, когда человек дошёл до настроек и решил
 * пожаловаться, интересного в нём уже нет.
 */
public interface SessionLog {

    /** Начать писать. Повторный вызов — no-op. */
    public fun start()

    public fun stop()

    /**
     * Собирает прошлый и текущий запуск в один файл, вырезая секреты, и отдаёт его.
     *
     * @param header что дописать в начало файла: версия, устройство, настройки эфира. Всё это
     *   живёт здесь, а не в теле письма — человека сбивает с толку текст, который он не писал,
     *   в поле, куда он писал сам.
     * @param secrets значения из настроек, которых в письме быть не должно.
     * @return файл во внутреннем кэше, готовый к отдаче через [logFileUri], или null, если
     *   писать ещё нечего.
     */
    public fun dump(header: String, secrets: List<String>): File?
}

public fun sessionLog(context: Context): SessionLog = SessionLogImpl(context.applicationContext)
