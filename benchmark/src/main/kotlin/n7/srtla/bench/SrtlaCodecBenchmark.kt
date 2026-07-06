package n7.srtla.bench

import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtlaCodec
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
open class SrtlaCodecBenchmark {

    private lateinit var id: ByteArray
    private lateinit var reg2Packet: ByteArray

    @Setup(Level.Trial)
    fun setup() {
        id = BenchPackets.localGroupId()
        reg2Packet = BenchPackets.reg2Matching(id)
    }

    @Benchmark
    open fun keepalive(bh: Blackhole) {
        bh.consume(SrtlaCodec.keepalive())
    }

    @Benchmark
    open fun reg1(bh: Blackhole) {
        bh.consume(SrtlaCodec.reg1(id))
    }

    @Benchmark
    open fun reg2(bh: Blackhole) {
        bh.consume(SrtlaCodec.reg2(id))
    }

    @Benchmark
    open fun reg2Id(bh: Blackhole) {
        bh.consume(SrtlaCodec.reg2Id(reg2Packet, PacketType.REG2_LEN))
    }
}
