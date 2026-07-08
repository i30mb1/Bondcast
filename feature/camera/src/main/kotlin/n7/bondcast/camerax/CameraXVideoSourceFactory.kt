package n7.bondcast.camerax

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import n7.bondcast.overlay.OverlayCompositor

public class CameraXVideoSourceFactory(
    private val cameraId: String,
    private val compositor: OverlayCompositor,
) : IVideoSourceInternal.Factory {

    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider,
    ): IVideoSourceInternal = CameraXVideoSource(context.applicationContext, cameraId, compositor)

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean =
        source is CameraXVideoSource && source.cameraId == cameraId
}
