package n7.bondcast.settings

internal data class StreamSettings(
    val host: String = "10.0.2.2",
    val port: Int = 10080,
    val streamName: String = "phone",
    val passphrase: String = "",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrateKbps: Int = 4500,
    val latencyMs: Int = 1500,
    val bondingEnabled: Boolean = false,
    val srtlaHost: String = "",
    val srtlaPort: Int = 5000,
) {
    /** streamid в формате SRS: поток попадёт в live/<streamName>. */
    val streamId: String get() = "#!::r=live/$streamName,m=publish"
    val url: String get() = "srt://$host:$port"
}
