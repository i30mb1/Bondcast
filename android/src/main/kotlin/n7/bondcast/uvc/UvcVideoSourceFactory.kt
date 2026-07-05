package n7.bondcast.uvc

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider

internal class UvcVideoSourceFactory : IVideoSourceInternal.Factory {

    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider,
    ): IVideoSourceInternal = UvcVideoSource(context.applicationContext)

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean = source is UvcVideoSource
}
