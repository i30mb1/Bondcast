package n7.bondcast.stream

internal data class StreamStats(
    val sendRateKbps: Int,
    val rttMs: Int,
    val pktLossTotal: Int,
    val pktRetransTotal: Int,
    val sndBufferMs: Int,
    val bandwidthKbps: Int,
)
