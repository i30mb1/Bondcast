package n7.bondcast.qr

import android.util.Log

internal class QrPayloadParserWithLogging(
    private val delegate: QrPayloadParser,
) : QrPayloadParser {

    override fun parse(raw: String): QrPayload {
        val result = delegate.parse(raw)
        val kind = when (result) {
            is QrPayload.ObsConnect -> "ObsConnect(${result.host}:${result.port})"
            is QrPayload.ServerConfig -> "ServerConfig(host=${result.host}, bonding=${result.bonding})"
            is QrPayload.Unknown -> "Unknown(${result.raw.take(64)})"
        }
        Log.i(TAG, "parse -> $kind")
        return result
    }

    private companion object {
        const val TAG = "QrPayloadParser"
    }
}
