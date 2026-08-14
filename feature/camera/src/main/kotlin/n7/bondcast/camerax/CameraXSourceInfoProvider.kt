package n7.bondcast.camerax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider

/**
 * Сообщает StreamPack ориентацию кадра, который приходит из CameraX.
 *
 * Ключевой момент: CameraX отдаёт кадр в энкодерную Surface **уже развёрнутым** — поворот сенсора
 * применён внутри его конвейера (проверено на A024: SENSOR_ORIENTATION=90, а в эффект приходит
 * frame.rotationDegrees=0 с landscape-буфером и без поворота в sensorToBufferTransform).
 *
 * Поэтому [rotationDegrees] = 0, а не SENSOR_ORIENTATION: StreamPack по этому числу *предсказывает*
 * матрицу SurfaceTexture.getTransformMatrix(), инвертирует её и вычитает из общего преобразования
 * (SurfaceOutput.calculateInvertedTextureTransform). Если сообщить сенсорные 90°, он вычтет поворот,
 * которого в матрице нет, и картинка уедет на -90°. Зеркало фронталки CameraX применяет сам — по той
 * же причине [isMirror] = false.
 */
internal class CameraXSourceInfoProvider(
    context: Context,
    cameraId: String,
) : ISourceInfoProvider {

    private val characteristics = runCatching {
        (context.getSystemService(CameraManager::class.java)).getCameraCharacteristics(cameraId)
    }.getOrNull()

    /** Ориентация сенсора — только для оверлеев, StreamPack её не получает (см. kdoc класса). */
    val sensorOrientation: Int = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /** Фронтальная ли камера — только для оверлеев, StreamPack зеркало применяет сам. */
    val isFrontFacing: Boolean =
        characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

    override val rotationDegrees: Int = 0

    override val isMirror: Boolean = false

    // кадр приходит развёрнутым и незеркальным — доворачивать нечего
    override fun getRelativeRotationDegrees(targetRotation: Int, requiredMirroring: Boolean): Int = 0

    override fun getSurfaceSize(targetResolution: Size): Size = targetResolution
}
