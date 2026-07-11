package n7.srtla.bench

import n7.srtla.scheduler.SchedulerEvent
import n7.srtla.scheduler.SrtlaScheduler
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class SchedulerSendBenchmark {

    @Param("1", "2", "3")
    @JvmField
    var links: Int = 1

    private val nowNanos = 1_000_000_000L
    private lateinit var scheduler: SrtlaScheduler
    private lateinit var event: SchedulerEvent.LocalSrtPacket
    private val sink = CountingSink()

    @Setup(Level.Trial)
    fun setup() {
        scheduler = BenchPackets.establishedScheduler(links, nowNanos)
        val data = BenchPackets.srtData(seqnum = 42, size = 1316)
        event = SchedulerEvent.LocalSrtPacket(data, data.size)
    }

    @Benchmark
    open fun sendPacket(bh: Blackhole) {
        scheduler.onEvent(event, nowNanos, sink)
        bh.consume(sink.count)
    }
}
