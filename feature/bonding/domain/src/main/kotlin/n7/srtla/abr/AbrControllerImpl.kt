package n7.srtla.abr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class AbrControllerImpl(private val config: AbrConfig) : AbrController {

    private var target: Int = config.maxKbps.coerceIn(config.minKbps, config.maxKbps)
    private var lastDecreaseNanos: Long? = null
    private var lastIncreaseNanos: Long? = null
    private var highStreak: Int = 0
    private var lastLossTotal: Int? = null
    private var lastLossNanos: Long = 0L

    override fun reset(startKbps: Int) {
        target = startKbps.coerceIn(config.minKbps, config.maxKbps)
        lastDecreaseNanos = null
        lastIncreaseNanos = null
        highStreak = 0
        lastLossTotal = null
        lastLossNanos = 0L
    }

    override fun onSample(sample: AbrSample): AbrDecision {
        // libsrt изредка отдаёт мусорный msSndBuf (в т.ч. отрицательный) — такой такт пропускаем
        if (sample.sndBufferMs < 0) {
            return AbrDecision(targetKbps = target, changed = false, reason = "skip:sndBuf=${sample.sndBufferMs}ms")
        }

        val lossPerSec = lossPerSec(sample)
        val previous = target
        var reason = "hold"

        if (sample.sndBufferMs > config.sndBufHighMs) {
            highStreak++
            // одиночный выброс не считаем перегрузкой: ждём подтверждения вторым семплом,
            // но буфер вдвое выше порога — явная перегрузка, режем сразу
            val confirmed = highStreak >= 2 || sample.sndBufferMs >= config.sndBufHighMs * 2
            if (confirmed && elapsed(lastDecreaseNanos, sample.nowNanos, config.decreaseCooldownNanos)) {
                decrease(sample.nowNanos)
                reason = "down:sndBuf=${sample.sndBufferMs}ms"
            }
        } else {
            highStreak = 0
            if (lossPerSec > config.lossHighPerSec) {
                if (elapsed(lastDecreaseNanos, sample.nowNanos, config.decreaseCooldownNanos)) {
                    decrease(sample.nowNanos)
                    reason = "down:loss=$lossPerSec/s"
                }
            } else if (sample.sndBufferMs < config.sndBufLowMs && target < config.maxKbps) {
                if (elapsed(lastIncreaseNanos, sample.nowNanos, config.increaseIntervalNanos)) {
                    target = min(config.maxKbps, target + config.increaseStepKbps)
                    lastIncreaseNanos = sample.nowNanos
                    reason = "up:sndBuf=${sample.sndBufferMs}ms"
                }
            }
        }
        return AbrDecision(targetKbps = target, changed = target != previous, reason = reason)
    }

    private fun decrease(nowNanos: Long) {
        target = max(config.minKbps, (target * config.decreaseFactor).roundToInt())
        lastDecreaseNanos = nowNanos
        lastIncreaseNanos = nowNanos
        highStreak = 0
    }

    /** Скорость роста счётчика потерь; первый семпл — базовая точка, реконнект (счётчик упал) — тоже. */
    private fun lossPerSec(sample: AbrSample): Int {
        val prevTotal = lastLossTotal
        val prevNanos = lastLossNanos
        lastLossTotal = sample.pktLossTotal
        lastLossNanos = sample.nowNanos
        if (prevTotal == null || sample.pktLossTotal < prevTotal) return 0
        val elapsedNanos = sample.nowNanos - prevNanos
        if (elapsedNanos <= 0) return 0
        return ((sample.pktLossTotal - prevTotal) * 1_000_000_000.0 / elapsedNanos).roundToInt()
    }

    private fun elapsed(since: Long?, now: Long, interval: Long): Boolean = since == null || now - since >= interval
}
