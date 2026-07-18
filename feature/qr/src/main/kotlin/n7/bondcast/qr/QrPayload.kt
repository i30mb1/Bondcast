package n7.bondcast.qr

public sealed interface QrPayload {

    public data class ObsConnect(
        val host: String,
        val port: Int,
        val password: String,
    ) : QrPayload

    public data class ServerConfig(
        val host: String? = null,
        val port: Int? = null,
        val streamName: String? = null,
        val passphrase: String? = null,
        val bonding: Boolean? = null,
        val srtlaHost: String? = null,
        val srtlaPort: Int? = null,
        val obsPort: Int? = null,
        val obsPassword: String? = null,
    ) : QrPayload

    public data class Unknown(val raw: String) : QrPayload
}
