package n7.srtla.scheduler

public sealed interface SchedulerEvent {

    public data class LinkUp(
        val linkId: Int,
        val transport: Transport,
        val priority: Int = 0,
    ) : SchedulerEvent

    public data class LinkDown(val linkId: Int) : SchedulerEvent

    public class LocalSrtPacket(
        public val data: ByteArray,
        public val length: Int,
    ) : SchedulerEvent

    public class LinkPacket(
        public val linkId: Int,
        public val data: ByteArray,
        public val length: Int,
    ) : SchedulerEvent

    public data class Tick(val nowNanos: Long) : SchedulerEvent
}
