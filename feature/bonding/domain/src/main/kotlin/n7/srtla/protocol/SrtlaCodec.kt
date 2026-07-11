package n7.srtla.protocol

public object SrtlaCodec {

    // константа: keepalive шлётся на каждый idle-линк каждый тик; получатели только читают/копируют, не мутируют
    private val KEEPALIVE: ByteArray = ByteArray(2).also { Bytes.putU16be(it, 0, PacketType.SRTLA_KEEPALIVE) }

    public fun keepalive(): ByteArray = KEEPALIVE

    public fun reg1(id: ByteArray): ByteArray = reg(PacketType.SRTLA_REG1, id)

    public fun reg2(id: ByteArray): ByteArray = reg(PacketType.SRTLA_REG2, id)

    private fun reg(type: Int, id: ByteArray): ByteArray {
        require(id.size == PacketType.SRTLA_ID_LEN)
        val buf = ByteArray(2 + PacketType.SRTLA_ID_LEN)
        Bytes.putU16be(buf, 0, type)
        id.copyInto(buf, destinationOffset = 2)
        return buf
    }

    public fun reg2Id(buf: ByteArray, len: Int): ByteArray? {
        if (len != PacketType.REG2_LEN) return null
        return buf.copyOfRange(2, 2 + PacketType.SRTLA_ID_LEN)
    }
}
