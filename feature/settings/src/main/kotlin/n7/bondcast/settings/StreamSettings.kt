package n7.bondcast.settings

public data class StreamSettings(
    val host: String = "10.0.2.2",
    val port: Int = 10080,
    val streamName: String = "phone",
    val passphrase: String = "",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    // сервер всегда свой (SRS 6) и умеет HEVC, поэтому кодек не настраивается в UI
    val videoCodec: VideoCodec = VideoCodec.H265,
    val videoBitrateKbps: Int = 4500,
    val abrEnabled: Boolean = true,
    val minVideoBitrateKbps: Int = 800,
    val latencyMs: Int = 2000,
    val bondingEnabled: Boolean = false,
    val srtlaHost: String = "",
    val srtlaPort: Int = 5000,
) {
    /** streamid в формате SRS: поток попадёт в live/<streamName>. */
    val streamId: String get() = "#!::r=live/$streamName,m=publish"
    val url: String get() = "srt://$host:$port"
}
