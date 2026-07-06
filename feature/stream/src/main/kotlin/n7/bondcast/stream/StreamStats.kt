package n7.bondcast.stream

public data class StreamStats(
    val sendRateKbps: Int,
    val rttMs: Int,
    val pktLossTotal: Int,
    val pktRetransTotal: Int,
    val pktDropTotal: Int,
    val sndBufferMs: Int,
    val bandwidthKbps: Int,
    /** Отставание видеоэнкодера от реального времени (перегрев/непосильный битрейт), мс. */
    val encoderLagMs: Int,
)
