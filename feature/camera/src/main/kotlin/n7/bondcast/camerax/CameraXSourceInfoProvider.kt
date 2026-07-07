package n7.bondcast.camerax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider

internal class CameraXSourceInfoProvider(
    context: Context,
    cameraId: String,
) : ISourceInfoProvider {

    private val characteristics = runCatching {
        (context.getSystemService(CameraManager::class.java)).getCameraCharacteristics(cameraId)
    }.getOrNull()

    override val rotationDegrees: Int =
        characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    override val isMirror: Boolean =
        characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

    override fun getSurfaceSize(targetResolution: Size): Size = targetResolution
}
