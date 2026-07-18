package n7.bondcast.qr

public interface QrPayloadParser {
    public fun parse(raw: String): QrPayload
}

public fun qrPayloadParser(): QrPayloadParser = QrPayloadParserWithLogging(QrPayloadParserImpl())
