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
    /** Пульт OBS (obs-websocket v5): выключен — ни настроек, ни иконки на стрим-экране. */
    val obsEnabled: Boolean = false,
    val obsHost: String = "",
    val obsPort: Int = 4455,
    val obsPassword: String = "",
    /** Чат Twitch поверх экрана. channel пустой — свой канал залогиненного. */
    val chatEnabled: Boolean = false,
    val chatChannel: String = "",
    val chatShowNicknames: Boolean = true,
    val chatShowBadges: Boolean = true,
    /** Прятать сообщения-команды ботов (начинаются с «!»). */
    val chatHideCommands: Boolean = true,
    val chatFontSizeSp: Int = 14,
    val chatOpacityPercent: Int = 80,
    val chatMessageLimit: Int = 30,
    /** Сообщения выше середины оверлея гаснут по alpha к верху. */
    val chatFadeTopEnabled: Boolean = false,
    /** Стирать сообщения старше 30 секунд, независимо от лимита по количеству. */
    val chatAutoEraseEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    /** Переключатель «Новичок/Эксперт» в настройках — показывать ли продвинутые поля. */
    val expertMode: Boolean = false,
    /** Twitch напрямую по RTMP, без своего сервера — форсит H.264, бондинг недоступен. */
    val twitchDirectEnabled: Boolean = false,
    val twitchStreamKey: String = "",
    val twitchIngestUrl: String = "rtmp://live.twitch.tv/app",
) {
    /** streamid в формате SRS: поток попадёт в live/<streamName>. */
    val streamId: String get() = "#!::r=live/$streamName,m=publish"
    val url: String get() = "srt://$host:$port"
    val twitchRtmpUrl: String get() = "${twitchIngestUrl.trimEnd('/')}/$twitchStreamKey"
}
