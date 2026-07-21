package n7.bondcast.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stream_settings")

public class SettingsRepository(private val context: Context) {

    val settings: Flow<StreamSettings> = context.dataStore.data.map { preferences ->
        val default = StreamSettings()
        StreamSettings(
            host = preferences[HOST] ?: default.host,
            port = preferences[PORT] ?: default.port,
            streamName = preferences[STREAM_NAME] ?: default.streamName,
            passphrase = preferences[PASSPHRASE] ?: default.passphrase,
            width = preferences[WIDTH] ?: default.width,
            height = preferences[HEIGHT] ?: default.height,
            fps = preferences[FPS] ?: default.fps,
            videoCodec = preferences[VIDEO_CODEC]
                ?.let { name -> VideoCodec.entries.firstOrNull { it.name == name } }
                ?: default.videoCodec,
            videoBitrateKbps = preferences[VIDEO_BITRATE_KBPS] ?: default.videoBitrateKbps,
            abrEnabled = preferences[ABR_ENABLED] ?: default.abrEnabled,
            minVideoBitrateKbps = preferences[MIN_VIDEO_BITRATE_KBPS] ?: default.minVideoBitrateKbps,
            latencyMs = preferences[LATENCY_MS] ?: default.latencyMs,
            bondingEnabled = preferences[BONDING_ENABLED] ?: default.bondingEnabled,
            srtlaHost = preferences[SRTLA_HOST] ?: default.srtlaHost,
            srtlaPort = preferences[SRTLA_PORT] ?: default.srtlaPort,
            obsEnabled = preferences[OBS_ENABLED] ?: default.obsEnabled,
            obsHost = preferences[OBS_HOST] ?: default.obsHost,
            obsPort = preferences[OBS_PORT] ?: default.obsPort,
            obsPassword = preferences[OBS_PASSWORD] ?: default.obsPassword,
            chatEnabled = preferences[CHAT_ENABLED] ?: default.chatEnabled,
            chatChannel = preferences[CHAT_CHANNEL] ?: default.chatChannel,
            chatShowNicknames = preferences[CHAT_SHOW_NICKNAMES] ?: default.chatShowNicknames,
            chatShowBadges = preferences[CHAT_SHOW_BADGES] ?: default.chatShowBadges,
            chatHideCommands = preferences[CHAT_HIDE_COMMANDS] ?: default.chatHideCommands,
            chatFontSizeSp = preferences[CHAT_FONT_SIZE_SP] ?: default.chatFontSizeSp,
            chatOpacityPercent = preferences[CHAT_OPACITY_PERCENT] ?: default.chatOpacityPercent,
            chatMessageLimit = preferences[CHAT_MESSAGE_LIMIT] ?: default.chatMessageLimit,
            onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: default.onboardingCompleted,
        )
    }

    suspend fun save(settings: StreamSettings) {
        context.dataStore.edit { preferences ->
            preferences[HOST] = settings.host
            preferences[PORT] = settings.port
            preferences[STREAM_NAME] = settings.streamName
            preferences[PASSPHRASE] = settings.passphrase
            preferences[WIDTH] = settings.width
            preferences[HEIGHT] = settings.height
            preferences[FPS] = settings.fps
            preferences[VIDEO_CODEC] = settings.videoCodec.name
            preferences[VIDEO_BITRATE_KBPS] = settings.videoBitrateKbps
            preferences[ABR_ENABLED] = settings.abrEnabled
            preferences[MIN_VIDEO_BITRATE_KBPS] = settings.minVideoBitrateKbps
            preferences[LATENCY_MS] = settings.latencyMs
            preferences[BONDING_ENABLED] = settings.bondingEnabled
            preferences[SRTLA_HOST] = settings.srtlaHost
            preferences[SRTLA_PORT] = settings.srtlaPort
            preferences[OBS_ENABLED] = settings.obsEnabled
            preferences[OBS_HOST] = settings.obsHost
            preferences[OBS_PORT] = settings.obsPort
            preferences[OBS_PASSWORD] = settings.obsPassword
            preferences[CHAT_ENABLED] = settings.chatEnabled
            preferences[CHAT_CHANNEL] = settings.chatChannel
            preferences[CHAT_SHOW_NICKNAMES] = settings.chatShowNicknames
            preferences[CHAT_SHOW_BADGES] = settings.chatShowBadges
            preferences[CHAT_HIDE_COMMANDS] = settings.chatHideCommands
            preferences[CHAT_FONT_SIZE_SP] = settings.chatFontSizeSp
            preferences[CHAT_OPACITY_PERCENT] = settings.chatOpacityPercent
            preferences[CHAT_MESSAGE_LIMIT] = settings.chatMessageLimit
            preferences[ONBOARDING_COMPLETED] = settings.onboardingCompleted
        }
    }

    private companion object {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val STREAM_NAME = stringPreferencesKey("stream_name")
        val PASSPHRASE = stringPreferencesKey("passphrase")
        val WIDTH = intPreferencesKey("width")
        val HEIGHT = intPreferencesKey("height")
        val FPS = intPreferencesKey("fps")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_BITRATE_KBPS = intPreferencesKey("video_bitrate_kbps")
        val ABR_ENABLED = booleanPreferencesKey("abr_enabled")
        val MIN_VIDEO_BITRATE_KBPS = intPreferencesKey("min_video_bitrate_kbps")
        val LATENCY_MS = intPreferencesKey("latency_ms")
        val BONDING_ENABLED = booleanPreferencesKey("bonding_enabled")
        val SRTLA_HOST = stringPreferencesKey("srtla_host")
        val SRTLA_PORT = intPreferencesKey("srtla_port")
        val OBS_ENABLED = booleanPreferencesKey("obs_enabled")
        val OBS_HOST = stringPreferencesKey("obs_host")
        val OBS_PORT = intPreferencesKey("obs_port")
        val OBS_PASSWORD = stringPreferencesKey("obs_password")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_CHANNEL = stringPreferencesKey("chat_channel")
        val CHAT_SHOW_NICKNAMES = booleanPreferencesKey("chat_show_nicknames")
        val CHAT_SHOW_BADGES = booleanPreferencesKey("chat_show_badges")
        val CHAT_HIDE_COMMANDS = booleanPreferencesKey("chat_hide_commands")
        val CHAT_FONT_SIZE_SP = intPreferencesKey("chat_font_size_sp")
        val CHAT_OPACITY_PERCENT = intPreferencesKey("chat_opacity_percent")
        val CHAT_MESSAGE_LIMIT = intPreferencesKey("chat_message_limit")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
