package n7.bondcast.logging

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/** Лог текущего запуска. */
public const val CURRENT_LOG_NAME: String = "session.log"

/** Лог прошлого запуска — то, что нужно после падения, когда текущий уже пустой. */
public const val PREVIOUS_LOG_NAME: String = "session.1.log"

/**
 * Пишет строки в файл с ротацией на два поколения.
 *
 * Двух хватает и по делу, и по размеру письма: текущий сеанс плюс предыдущий. Больше — это
 * уже мегабайты вложения, которые почтовик может и не пропустить.
 *
 * Класс намеренно ничего не знает про Android — так его поведение проверяется обычным
 * JVM-тестом, а не на устройстве.
 */
public class SessionLogWriter(
    private val dir: File,
    private val maxFileBytes: Long,
) {

    private val current = File(dir, CURRENT_LOG_NAME)
    private val previous = File(dir, PREVIOUS_LOG_NAME)
    private var out: Writer? = null

    init {
        dir.mkdirs()
        // лог прошлого запуска сдвигаем, а не дописываем: после падения важно видеть,
        // где именно оборвался предыдущий сеанс, не смешивая его с новым
        if (current.length() > 0) rotate()
    }

    public fun append(line: String) {
        val stream = out ?: openStream()
        runCatching {
            stream.write(line)
            stream.write("\n")
            // сбрасываем сразу: если приложение упадёт, самое интересное — последние строки
            stream.flush()
        }
        if (current.length() >= maxFileBytes) rotate()
    }

    /** Прошлый запуск первым, текущий последним — читать письмо надо по порядку. */
    public fun files(): List<File> = listOf(previous, current).filter { it.length() > 0 }

    public fun close() {
        closeStream()
    }

    private fun openStream(): Writer =
        OutputStreamWriter(FileOutputStream(current, /* append = */ true), Charsets.UTF_8)
            .buffered()
            .also { out = it }

    private fun rotate() {
        closeStream()
        previous.delete()
        current.renameTo(previous)
    }

    private fun closeStream() {
        runCatching { out?.close() }
        out = null
    }
}
