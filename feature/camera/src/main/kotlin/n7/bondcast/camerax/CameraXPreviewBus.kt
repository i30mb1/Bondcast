package n7.bondcast.camerax

import androidx.camera.core.SurfaceRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public object CameraXPreviewBus {

    private val _request = MutableStateFlow<SurfaceRequest?>(null)
    public val request: StateFlow<SurfaceRequest?> = _request.asStateFlow()

    @Volatile
    var wantPreview: Boolean = false
        private set

    @Volatile
    var onWantChanged: (() -> Unit)? = null

    fun setWantPreview(value: Boolean) {
        if (wantPreview == value) return
        wantPreview = value
        onWantChanged?.invoke()
    }

    fun offerRequest(value: SurfaceRequest?) {
        _request.value = value
    }
}
