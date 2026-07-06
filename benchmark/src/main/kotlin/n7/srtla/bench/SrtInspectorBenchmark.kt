package n7.srtla.bench

import n7.srtla.protocol.SrtInspector
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
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
open class SrtInspectorBenchmark {

    private lateinit var data: ByteArray
    private lateinit var ack: ByteArray
    private lateinit var nakSingle: ByteArray
    private lateinit var nakRange: ByteArray
    private lateinit var srtlaAck: ByteArray

    @Setup(Level.Trial)
    fun setup() {
        data = BenchPackets.srtData(seqnum = 123456, size = 1316)
        ack = BenchPackets.srtAck(lastAck = 123456)
        nakSingle = BenchPackets.srtNakSingle(100, 200, 300, 400, 500, 600, 700, 800)
        nakRange = BenchPackets.srtNakRange(1000, 1063)
        srtlaAck = BenchPackets.srtlaAck(1, 2, 3, 4, 5, 6, 7, 8)
    }

    @Benchmark
    open fun srtDataSeqnum(bh: Blackhole) {
        bh.consume(SrtInspector.srtDataSeqnum(data, data.size))
    }

    @Benchmark
    open fun srtAckLastAck(bh: Blackhole) {
        bh.consume(SrtInspector.srtAckLastAck(ack, ack.size))
    }

    @Benchmark
    open fun nakLostSingle(bh: Blackhole) {
        bh.consume(SrtInspector.nakLostSeqnums(nakSingle, nakSingle.size))
    }

    @Benchmark
    open fun nakLostRange(bh: Blackhole) {
        bh.consume(SrtInspector.nakLostSeqnums(nakRange, nakRange.size))
    }

    @Benchmark
    open fun srtlaAckSeqnums(bh: Blackhole) {
        bh.consume(SrtInspector.srtlaAckSeqnums(srtlaAck, srtlaAck.size))
    }
}
