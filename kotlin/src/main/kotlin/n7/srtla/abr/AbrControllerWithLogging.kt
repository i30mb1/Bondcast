package n7.srtla.abr

internal class AbrControllerWithLogging(
    private val delegate: AbrController,
    private val log: (String) -> Unit,
) : AbrController {

    override fun onSample(sample: AbrSample): AbrDecision {
        val decision = delegate.onSample(sample)
        if (decision.changed) {
            log("ABR ${decision.reason} → ${decision.targetKbps} kbps (sndBuf=${sample.sndBufferMs}ms loss=${sample.pktLossTotal})")
        }
        return decision
    }

    override fun reset(startKbps: Int) {
        log("ABR reset → $startKbps kbps")
        delegate.reset(startKbps)
    }
}
