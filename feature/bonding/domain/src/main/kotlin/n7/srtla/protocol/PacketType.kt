package n7.srtla.protocol

public object PacketType {
    public const val SRT_HANDSHAKE: Int = 0x8000
    public const val SRT_ACK: Int = 0x8002
    public const val SRT_NAK: Int = 0x8003
    public const val SRT_SHUTDOWN: Int = 0x8005

    public const val SRTLA_KEEPALIVE: Int = 0x9000
    public const val SRTLA_ACK: Int = 0x9100
    public const val SRTLA_REG1: Int = 0x9200
    public const val SRTLA_REG2: Int = 0x9201
    public const val SRTLA_REG3: Int = 0x9202
    public const val SRTLA_REG_ERR: Int = 0x9210
    public const val SRTLA_REG_NGP: Int = 0x9211
    public const val SRTLA_REG_NAK: Int = 0x9212

    public const val SRT_MIN_LEN: Int = 16
    public const val SRTLA_ID_LEN: Int = 256
    public const val REG1_LEN: Int = 2 + SRTLA_ID_LEN
    public const val REG2_LEN: Int = 2 + SRTLA_ID_LEN
    public const val REG3_LEN: Int = 2
    public const val MTU: Int = 1500
}
