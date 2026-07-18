package n7.srtla.bench

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Сравнение стратегий отправки в LinkIoLoop.dispatch: ByteBuffer.wrap на каждый пакет
 * против переиспользуемого direct-буфера. Мерить с -prof gc: gc.alloc.rate.norm (bytes/op).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class DispatchWriteBenchmark {

    private val packet = ByteArray(1316) { it.toByte() }
    private val reused: ByteBuffer = ByteBuffer.allocateDirect(1500 + 512)

    @Benchmark
    open fun wrapEachPacket(bh: Blackhole) {
        val buf = ByteBuffer.wrap(packet, 0, packet.size)
        bh.consume(buf.remaining())
        bh.consume(buf)
    }

    @Benchmark
    open fun reusedDirectBuffer(bh: Blackhole) {
        reused.clear()
        reused.put(packet, 0, packet.size)
        reused.flip()
        bh.consume(reused.remaining())
        bh.consume(reused)
    }
}
