package n7.bondcast.stream

import kotlin.math.roundToInt

public enum class HealthLevel { OK, WARN, BAD }

public data class StreamHealth(
    val lossPerSec: Int,
    val retransPerSec: Int,
    val dropPerSec: Int,
    val rttLevel: HealthLevel,
    val bufLevel: HealthLevel,
    val lossLevel: HealthLevel,
    val retransLevel: HealthLevel,
    val dropLevel: HealthLevel,
    val rateLevel: HealthLevel,
    val encoderLevel: HealthLevel,
    val overall: HealthLevel,
)

public fun streamHealth(
    prev: StreamStats?,
    cur: StreamStats,
    elapsedMs: Long,
    targetKbps: Int,
    latencyMs: Int,
): StreamHealth {
    val secs = (elapsedMs / 1000.0).coerceAtLeast(0.001)
    val lossPerSec = perSec(prev?.pktLossTotal, cur.pktLossTotal, secs)
    val retransPerSec = perSec(prev?.pktRetransTotal, cur.pktRetransTotal, secs)
    val dropPerSec = perSec(prev?.pktDropTotal, cur.pktDropTotal, secs)

    val rttLevel = when {
        cur.rttMs < 120 -> HealthLevel.OK
        cur.rttMs < 300 -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }
    val bufFraction = if (latencyMs > 0) cur.sndBufferMs.toFloat() / latencyMs else 0f
    val bufLevel = when {
        bufFraction < 0.4f -> HealthLevel.OK
        bufFraction < 0.8f -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }
    val lossLevel = when {
        lossPerSec <= 0 -> HealthLevel.OK
        lossPerSec <= 20 -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }
    val retransLevel = when {
        retransPerSec <= 0 -> HealthLevel.OK
        retransPerSec <= 50 -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }
    val dropLevel = if (dropPerSec > 0) HealthLevel.BAD else HealthLevel.OK
    val rateFraction = if (targetKbps > 0) cur.sendRateKbps.toFloat() / targetKbps else 1f
    // на первом Live-сэмпле bistats(clear=true) занижает mbpsSendRate (окно = от старта сессии),
    // поэтому не судим о rate без предыдущего сэмпла — иначе ложный overall=BAD на старте
    val rateLevel = when {
        prev == null -> HealthLevel.OK
        rateFraction >= 0.9f -> HealthLevel.OK
        rateFraction >= 0.6f -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }
    // отставание энкодера: телефон не успевает кодировать (перегрев/битрейт не по силам)
    val encoderLevel = when {
        cur.encoderLagMs < 300 -> HealthLevel.OK
        cur.encoderLagMs < 1_000 -> HealthLevel.WARN
        else -> HealthLevel.BAD
    }

    val overall = listOf(rttLevel, bufLevel, lossLevel, retransLevel, dropLevel, rateLevel, encoderLevel)
        .maxByOrNull { it.ordinal } ?: HealthLevel.OK

    return StreamHealth(
        lossPerSec = lossPerSec,
        retransPerSec = retransPerSec,
        dropPerSec = dropPerSec,
        rttLevel = rttLevel,
        bufLevel = bufLevel,
        lossLevel = lossLevel,
        retransLevel = retransLevel,
        dropLevel = dropLevel,
        rateLevel = rateLevel,
        encoderLevel = encoderLevel,
        overall = overall,
    )
}

private fun perSec(prev: Int?, cur: Int, secs: Double): Int {
    if (prev == null) return 0
    val delta = (cur - prev).coerceAtLeast(0)
    return (delta / secs).roundToInt()
}
