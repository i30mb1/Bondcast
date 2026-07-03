package n7.srtla.protocol

public object SrtInspector {

    public fun srtType(buf: ByteArray, len: Int): Int {
        if (len < 2) return 0
        return Bytes.u16be(buf, 0)
    }

    public fun srtDataSeqnum(buf: ByteArray, len: Int): Int {
        if (len < 4) return -1
        val word = Bytes.i32be(buf, 0)
        return if (word < 0) -1 else word
    }

    public fun srtAckLastAck(buf: ByteArray, len: Int): Int {
        if (len < 20) return -1
        return Bytes.i32be(buf, 16)
    }

    public fun srtlaAckSeqnums(buf: ByteArray, len: Int): IntArray {
        val words = len / 4
        if (words <= 1) return IntArray(0)
        val out = IntArray(words - 1)
        for (i in 1 until words) {
            out[i - 1] = Bytes.i32be(buf, i * 4)
        }
        return out
    }

    public fun nakLostSeqnums(buf: ByteArray, len: Int): IntArray {
        val words = len / 4
        val out = ArrayList<Int>()
        var i = 4
        while (i < words) {
            val id = Bytes.i32be(buf, i * 4)
            if (id < 0) {
                val start = id and 0x7FFFFFFF
                val end = if (i + 1 < words) Bytes.i32be(buf, (i + 1) * 4) else start
                var lost = start
                while (lost <= end) {
                    out.add(lost)
                    lost++
                }
                i++
            } else {
                out.add(id)
            }
            i++
        }
        return out.toIntArray()
    }
}
