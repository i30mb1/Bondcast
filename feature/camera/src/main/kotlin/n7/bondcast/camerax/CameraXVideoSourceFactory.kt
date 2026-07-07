package n7.bondcast.camerax

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider

public class CameraXVideoSourceFactory(private val cameraId: String) : IVideoSourceInternal.Factory {

    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider,
    ): IVideoSourceInternal = CameraXVideoSource(context.applicationContext, cameraId)

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean =
        source is CameraXVideoSource && source.cameraId == cameraId
}
