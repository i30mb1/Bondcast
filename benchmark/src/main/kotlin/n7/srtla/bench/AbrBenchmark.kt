package n7.srtla.bench

import n7.srtla.abr.AbrConfig
import n7.srtla.abr.AbrController
import n7.srtla.abr.AbrSample
import n7.srtla.abr.abrController
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
open class AbrBenchmark {

    private val nowNanos = 1_000_000_000L
    private lateinit var abr: AbrController
    private lateinit var holdSample: AbrSample

    @Setup(Level.Trial)
    fun setup() {
        abr = abrController(AbrConfig(minKbps = 500, maxKbps = 8000))
        holdSample = AbrSample(nowNanos = nowNanos, sndBufferMs = 500, pktLossTotal = 0)
    }

    @Benchmark
    open fun onSampleHold(bh: Blackhole) {
        bh.consume(abr.onSample(holdSample))
    }
}
