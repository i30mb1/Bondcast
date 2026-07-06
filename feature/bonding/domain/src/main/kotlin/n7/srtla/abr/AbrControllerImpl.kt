package n7.srtla.abr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class AbrControllerImpl(private val config: AbrConfig) : AbrController {

    private var target: Int = config.maxKbps.coerceIn(config.minKbps, config.maxKbps)
    private var lastDecreaseNanos: Long? = null
    private var lastIncreaseNanos: Long? = null

    override fun reset(startKbps: Int) {
        target = startKbps.coerceIn(config.minKbps, config.maxKbps)
        lastDecreaseNanos = null
        lastIncreaseNanos = null
    }

    override fun onSample(sample: AbrSample): AbrDecision {
        val previous = target
        var reason = "hold"
        if (sample.sndBufferMs > config.sndBufHighMs) {
            if (elapsed(lastDecreaseNanos, sample.nowNanos, config.decreaseCooldownNanos)) {
                target = max(config.minKbps, (target * config.decreaseFactor).roundToInt())
                lastDecreaseNanos = sample.nowNanos
                lastIncreaseNanos = sample.nowNanos
                reason = "down:sndBuf=${sample.sndBufferMs}ms"
            }
        } else if (sample.sndBufferMs < config.sndBufLowMs && target < config.maxKbps) {
            if (elapsed(lastIncreaseNanos, sample.nowNanos, config.increaseIntervalNanos)) {
                target = min(config.maxKbps, target + config.increaseStepKbps)
                lastIncreaseNanos = sample.nowNanos
                reason = "up:sndBuf=${sample.sndBufferMs}ms"
            }
        }
        return AbrDecision(targetKbps = target, changed = target != previous, reason = reason)
    }

    private fun elapsed(since: Long?, now: Long, interval: Long): Boolean =
        since == null || now - since >= interval
}
