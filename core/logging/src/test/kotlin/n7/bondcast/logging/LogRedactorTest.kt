package n7.bondcast.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {

    @Test
    fun `секрет из настроек вырезается везде, где встретился`() {
        val log = """
            open passphrase=my-super-secret latency=1000
            retry passphrase=my-super-secret
        """.trimIndent()

        val cleaned = redactLog(log, listOf("my-super-secret"))

        assertFalse(cleaned.contains("my-super-secret"))
        assertEquals(2, cleaned.split(REDACTED).size - 1)
    }

    @Test
    fun `пустые и слишком короткие секреты игнорируются`() {
        // пустая строка вырезала бы всё подряд, а короткая — случайные куски слов
        val log = "bound cameraId=0 size=1920x1080"

        val cleaned = redactLog(log, listOf("", "   ", "0"))

        assertEquals(log, cleaned)
    }

    @Test
    fun `ключ трансляции Twitch вырезается по шаблону, даже если его нет в списке`() {
        val log = "rtmp://ingest.twitch.tv/app/live_123456789_aBcDeFgHiJkLmNoPqRsTuVwX"

        val cleaned = redactLog(log, emptyList())

        assertFalse(cleaned.contains("aBcDeFgHiJkLmNoPqRsTuVwX"))
        assertTrue(cleaned.contains("live_$REDACTED"))
    }

    @Test
    fun `oauth-токен и Bearer вырезаются по шаблону`() {
        val log = """
            PASS oauth:qwertyuiop1234567890asdf
            Authorization: Bearer qwertyuiop1234567890asdf
        """.trimIndent()

        val cleaned = redactLog(log, emptyList())

        assertFalse(cleaned.contains("qwertyuiop1234567890asdf"))
        assertTrue(cleaned.contains("oauth:$REDACTED"))
        assertTrue(cleaned.contains("Bearer $REDACTED"))
    }

    @Test
    fun `адрес сервера остаётся — без него письмо бесполезно`() {
        val log = "connect 192.168.1.50:10080 srtla=192.168.1.50:5000"

        val cleaned = redactLog(log, emptyList())

        assertEquals(log, cleaned)
    }

    @Test
    fun `секрет вырезается даже если в нём есть спецсимволы регулярки`() {
        val log = "passphrase=a.b*c[d] ok"

        val cleaned = redactLog(log, listOf("a.b*c[d]"))

        assertEquals("passphrase=$REDACTED ok", cleaned)
    }
}
