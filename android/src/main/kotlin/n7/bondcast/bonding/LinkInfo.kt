package n7.bondcast.bonding

import n7.srtla.scheduler.RegState
import n7.srtla.scheduler.Transport

internal data class LinkInfo(
    val id: Int,
    val transport: Transport,
    val reg: RegState,
    val sendRateKbps: Int,
    val inFlight: Int,
    val window: Int,
)
