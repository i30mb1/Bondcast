package n7.bondcast.qr

import java.net.URLDecoder

internal class QrPayloadParserImpl : QrPayloadParser {

    override fun parse(raw: String): QrPayload {
        val text = raw.trim()
        return parseObs(text) ?: parseBondcast(text) ?: QrPayload.Unknown(raw)
    }

    // obs-websocket «Показать сведения о подключении»: obsws://<ip>:<port>/<password>
    // (пароль percent-encoded; без пароля — obsws://<ip>:<port>). См. ConnectInfo.cpp.
    private fun parseObs(text: String): QrPayload? {
        if (!text.startsWith(OBS_PREFIX)) return null
        val rest = text.removePrefix(OBS_PREFIX)
        val authority = rest.substringBefore('/')
        val password = if (rest.contains('/')) decode(rest.substringAfter('/')) else ""
        val host = authority.substringBeforeLast(':', "")
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank() || port == null) return null
        return QrPayload.ObsConnect(host = host, port = port, password = password)
    }

    // свой формат под будущую генерацию: bondcast://config?host=..&port=..&name=..&...
    private fun parseBondcast(text: String): QrPayload? {
        if (!text.startsWith(BONDCAST_PREFIX)) return null
        val query = text.substringAfter('?', "")
        val params = query.split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                pair.substringBefore('=') to decode(pair.substringAfter('=', ""))
            }
        return QrPayload.ServerConfig(
            host = params["host"],
            port = params["port"]?.toIntOrNull(),
            streamName = params["name"],
            passphrase = params["pass"],
            bonding = params["bonding"]?.let { it == "1" || it.equals("true", ignoreCase = true) },
            srtlaHost = params["srtlaHost"],
            srtlaPort = params["srtlaPort"]?.toIntOrNull(),
            obsPort = params["obsPort"]?.toIntOrNull(),
            obsPassword = params["obsPass"],
        )
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private companion object {
        const val OBS_PREFIX = "obsws://"
        const val BONDCAST_PREFIX = "bondcast://"
    }
}
