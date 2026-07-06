package n7.srtla.scheduler

public sealed interface SchedulerAction {

    public class SendOnLink(
        public val linkId: Int,
        public val data: ByteArray,
        public val length: Int = data.size,
    ) : SchedulerAction

    public class SendToLocal(
        public val data: ByteArray,
        public val length: Int = data.size,
    ) : SchedulerAction
}
