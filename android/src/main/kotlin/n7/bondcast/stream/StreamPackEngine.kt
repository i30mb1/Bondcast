package n7.bondcast.stream

import android.content.Context
import android.util.Size
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.flow.first
import n7.bondcast.settings.StreamSettings

internal class StreamPackEngine(private val context: Context) : StreamEngine {

    private var streamer: SingleStreamer? = null
    private var appliedSettings: StreamSettings? = null

    override val lastError: Throwable?
        get() = streamer?.throwableFlow?.value

    override suspend fun prepare(settings: StreamSettings) {
        val current = streamer ?: cameraSingleStreamer(context).also { streamer = it }
        if (appliedSettings == settings) return
        if (current.isStreamingFlow.value) return
        current.setConfig(
            AudioConfig(startBitrate = 128_000),
            VideoConfig(
                startBitrate = settings.videoBitrateKbps * 1000,
                resolution = Size(settings.width, settings.height),
                fps = settings.fps,
            ),
        )
        appliedSettings = settings
    }

    override suspend fun startStream(settings: StreamSettings) {
        prepare(settings)
        val current = requireNotNull(streamer)
        current.open(
            SrtMediaDescriptor(
                host = settings.host,
                port = settings.port,
                streamId = settings.streamId,
                passPhrase = settings.passphrase.ifBlank { null },
                latency = settings.latencyMs,
            ),
        )
        current.startStream()
    }

    override suspend fun awaitDisconnect() {
        val current = streamer ?: return
        current.isOpenFlow.first { isOpen -> !isOpen }
    }

    override suspend fun stopStream() {
        val current = streamer ?: return
        runCatching { current.stopStream() }
        runCatching { current.close() }
    }

    override suspend fun bindPreview(view: PreviewView) {
        view.setVideoSourceProvider(streamer)
    }

    override suspend fun release() {
        streamer?.release()
        streamer = null
        appliedSettings = null
    }
}
