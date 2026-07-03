package n7.bondcast.bonding.io

import android.net.Network
import n7.srtla.scheduler.Transport
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey

internal class BondingLink(
    val id: Int,
    val network: Network,
    val transport: Transport,
    val channel: DatagramChannel,
    val key: SelectionKey,
)
