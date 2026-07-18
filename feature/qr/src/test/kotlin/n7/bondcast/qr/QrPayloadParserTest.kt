package n7.bondcast.qr

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrPayloadParserTest {

    private val parser = QrPayloadParserImpl()

    @Test
    fun parsesObsConnectInfo() {
        val result = parser.parse("obsws://192.168.1.50:4455/s3cretPass")
        assertEquals(QrPayload.ObsConnect("192.168.1.50", 4455, "s3cretPass"), result)
    }

    @Test
    fun parsesObsWithoutPassword() {
        val result = parser.parse("obsws://10.0.0.2:4455")
        assertEquals(QrPayload.ObsConnect("10.0.0.2", 4455, ""), result)
    }

    @Test
    fun obsPasswordIsPercentDecoded() {
        val result = parser.parse("obsws://10.0.0.2:4455/p%40ss%2Fword")
        assertEquals(QrPayload.ObsConnect("10.0.0.2", 4455, "p@ss/word"), result)
    }

    @Test
    fun obsWithoutPortIsUnknown() {
        assertTrue(parser.parse("obsws://10.0.0.2/pass") is QrPayload.Unknown)
    }

    @Test
    fun parsesBondcastFullConfig() {
        val raw = "bondcast://config?host=1.2.3.4&port=10080&name=phone&pass=abc&" +
            "bonding=1&srtlaHost=1.2.3.4&srtlaPort=5000&obsPort=4455&obsPass=xyz"
        val result = parser.parse(raw)
        assertEquals(
            QrPayload.ServerConfig(
                host = "1.2.3.4",
                port = 10080,
                streamName = "phone",
                passphrase = "abc",
                bonding = true,
                srtlaHost = "1.2.3.4",
                srtlaPort = 5000,
                obsPort = 4455,
                obsPassword = "xyz",
            ),
            result,
        )
    }

    @Test
    fun parsesBondcastPartialConfig() {
        val result = parser.parse("bondcast://config?host=10.0.0.9&bonding=false")
        assertEquals(
            QrPayload.ServerConfig(host = "10.0.0.9", bonding = false),
            result,
        )
    }

    @Test
    fun urlDecodesPasswordWithSpecialChars() {
        val result = parser.parse("bondcast://config?pass=p%40ss%20word%26")
        assertEquals("p@ss word&", (result as QrPayload.ServerConfig).passphrase)
    }

    @Test
    fun garbageIsUnknown() {
        assertTrue(parser.parse("just some text") is QrPayload.Unknown)
        assertTrue(parser.parse("https://example.com") is QrPayload.Unknown)
    }
}
