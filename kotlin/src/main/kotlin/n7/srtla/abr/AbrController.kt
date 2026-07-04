package n7.srtla.abr

public interface AbrController {
    public fun onSample(sample: AbrSample): AbrDecision
    public fun reset(startKbps: Int)
}

public fun abrController(config: AbrConfig, log: (String) -> Unit = {}): AbrController =
    AbrControllerWithLogging(AbrControllerImpl(config), log)
