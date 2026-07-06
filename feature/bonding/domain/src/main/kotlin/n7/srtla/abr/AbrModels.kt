package n7.srtla.abr

public data class AbrSample(
    val nowNanos: Long,
    val sndBufferMs: Int,
    val pktLossTotal: Int,
)

public data class AbrDecision(
    val targetKbps: Int,
    val changed: Boolean,
    val reason: String,
)

public data class AbrConfig(
    val minKbps: Int,
    val maxKbps: Int,
    val sndBufHighMs: Int = 750,
    val sndBufLowMs: Int = 300,
    val decreaseFactor: Double = 0.8,
    val increaseStepKbps: Int = 500,
    val increaseIntervalNanos: Long = 2_000_000_000L,
    val decreaseCooldownNanos: Long = 1_000_000_000L,
    /** Порог скорости роста счётчика потерь, при котором режем битрейт даже без роста буфера. */
    val lossHighPerSec: Int = 50,
)
