package n7.bondcast.camerax

import androidx.camera.core.Preview

public object CameraXPreviewBus {

    @Volatile
    var provider: Preview.SurfaceProvider? = null
        private set

    @Volatile
    var listener: (() -> Unit)? = null

    fun set(value: Preview.SurfaceProvider?) {
        provider = value
        listener?.invoke()
    }
}
