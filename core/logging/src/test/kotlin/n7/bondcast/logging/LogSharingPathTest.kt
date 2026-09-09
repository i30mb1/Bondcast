package n7.bondcast.logging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Каталог, куда кладётся дамп, задан дважды: константой в коде и путём в описании FileProvider'а.
 * Разъедутся — `getUriForFile` кинет IllegalArgumentException прямо при отправке письма, и это
 * ничем не проявится до нажатия кнопки на живом телефоне. Пришиваем одно к другому здесь.
 */
class LogSharingPathTest {

    @Test
    fun `каталог дампа совпадает с путём, объявленным FileProvider'у`() {
        val paths = File("src/main/res/xml/bondcast_log_paths.xml")
        assertTrue(paths.exists(), "описание путей FileProvider'а не найдено: ${paths.absolutePath}")

        val declared = Regex("""<cache-path[^>]*path="([^"]+)"""").find(paths.readText())?.groupValues?.get(1)

        assertTrue(
            declared?.trimEnd('/') == SHARE_DIR_NAME,
            "FileProvider отдаёт '$declared', а дамп пишется в '$SHARE_DIR_NAME'",
        )
    }
}
