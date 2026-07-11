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
open class SchedulerControlBenchmark {

    @Param("1", "2", "3")
    @JvmField
    var links: Int = 1

    private val nowNanos = 1_000_000_000L
    private lateinit var scheduler: SrtlaScheduler
    private lateinit var ackEvent: SchedulerEvent.LinkPacket
    private lateinit var nakEvent: SchedulerEvent.LinkPacket
    private lateinit var srtlaAckEvent: SchedulerEvent.LinkPacket
    private val sink = CountingSink()

    @Setup(Level.Trial)
    fun setup() {
        scheduler = BenchPackets.establishedScheduler(links, nowNanos)
        for (sn in 0 until 300) {
            val data = BenchPackets.srtData(seqnum = sn, size = 1316)
            scheduler.onEvent(SchedulerEvent.LocalSrtPacket(data, data.size), nowNanos)
        }
        val ack = BenchPackets.srtAck(lastAck = 1_000_000)
        ackEvent = SchedulerEvent.LinkPacket(1, ack, ack.size)
        val nak = BenchPackets.srtNakSingle(999_999_990, 999_999_995)
        nakEvent = SchedulerEvent.LinkPacket(1, nak, nak.size)
        val srtlaAck = BenchPackets.srtlaAck(999_999_991, 999_999_992, 999_999_993)
        srtlaAckEvent = SchedulerEvent.LinkPacket(1, srtlaAck, srtlaAck.size)
    }

    @Benchmark
    open fun srtAck(bh: Blackhole) {
        scheduler.onEvent(ackEvent, nowNanos, sink)
        bh.consume(sink.count)
    }

    @Benchmark
    open fun srtNak(bh: Blackhole) {
        scheduler.onEvent(nakEvent, nowNanos, sink)
        bh.consume(sink.count)
    }

    @Benchmark
    open fun srtlaAck(bh: Blackhole) {
        scheduler.onEvent(srtlaAckEvent, nowNanos, sink)
        bh.consume(sink.count)
    }
}
