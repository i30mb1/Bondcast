package n7.srtla.protocol

import kotlin.random.Random

public object GroupId {

    public fun random(random: Random = Random.Default): ByteArray =
        random.nextBytes(PacketType.SRTLA_ID_LEN)

    public fun firstHalfMatches(a: ByteArray, b: ByteArray): Boolean {
        val half = PacketType.SRTLA_ID_LEN / 2
        if (a.size < half || b.size < half) return false
        for (i in 0 until half) {
            if (a[i] != b[i]) return false
        }
        return true
    }
}
