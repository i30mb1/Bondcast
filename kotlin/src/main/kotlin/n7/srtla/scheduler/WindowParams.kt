package n7.srtla.scheduler

public data class WindowParams(
    val windowMin: Int = 1,
    val windowDef: Int = 20,
    val windowMax: Int = 60,
    val windowMult: Int = 1000,
    val windowIncr: Int = 30,
    val windowDecr: Int = 100,
    val pktLogSize: Int = 256,
    val connTimeoutNanos: Long = 4_000_000_000L,
    val idleNanos: Long = 1_000_000_000L,
) {
    public companion object {
        public val DEFAULT: WindowParams = WindowParams()
    }
}
