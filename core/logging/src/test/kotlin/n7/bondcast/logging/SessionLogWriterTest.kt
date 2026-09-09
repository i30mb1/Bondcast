package n7.bondcast.logging

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionLogWriterTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `строки попадают в текущий файл`() {
        val writer = SessionLogWriter(temp.root, maxFileBytes = 1024)

        writer.append("первая")
        writer.append("вторая")
        writer.close()

        val text = writer.files().single().readText()
        assertTrue(text.contains("первая"))
        assertTrue(text.contains("вторая"))
    }

    @Test
    fun `при переполнении текущий файл уезжает в прошлый, запись продолжается в новый`() {
        val writer = SessionLogWriter(temp.root, maxFileBytes = 64)

        repeat(20) { writer.append("строка номер $it, достаточно длинная чтобы перелить") }
        writer.append("последняя")
        writer.close()

        val files = writer.files()
        assertEquals(2, files.size)
        assertTrue(files.last().readText().contains("последняя"))
    }

    @Test
    fun `файлов остаётся не больше двух, сколько бы ни было ротаций`() {
        val writer = SessionLogWriter(temp.root, maxFileBytes = 64)

        repeat(200) { writer.append("строка номер $it, достаточно длинная чтобы перелить") }
        writer.close()

        // ровно двух может и не быть: если последняя строка сама вызвала ротацию, текущий
        // файл ещё не создан — важно, что каталог не растёт
        val names = temp.root.listFiles()!!.map { it.name }
        assertTrue(names.size <= 2, "накопились лишние файлы: $names")
        assertTrue(names.all { it == CURRENT_LOG_NAME || it == PREVIOUS_LOG_NAME }, "чужие файлы: $names")
    }

    @Test
    fun `files отдаёт прошлый файл первым — читать письмо надо по порядку`() {
        val writer = SessionLogWriter(temp.root, maxFileBytes = 64)

        repeat(20) { writer.append("старая строка номер $it, достаточно длинная чтобы перелить") }
        writer.append("МЕТКА-СВЕЖАЯ")
        writer.close()

        val files = writer.files()
        assertEquals(PREVIOUS_LOG_NAME, files.first().name)
        assertEquals(CURRENT_LOG_NAME, files.last().name)
        assertTrue(files.last().readText().contains("МЕТКА-СВЕЖАЯ"))
    }

    @Test
    fun `лог прошлого запуска не теряется при старте нового`() {
        SessionLogWriter(temp.root, maxFileBytes = 1024).apply {
            append("прошлый запуск")
            close()
        }

        val second = SessionLogWriter(temp.root, maxFileBytes = 1024)
        second.append("новый запуск")
        second.close()

        val all = second.files().joinToString("\n") { it.readText() }
        assertTrue(all.contains("прошлый запуск"), "лог прошлого запуска пропал: $all")
        assertTrue(all.contains("новый запуск"))
    }
}
