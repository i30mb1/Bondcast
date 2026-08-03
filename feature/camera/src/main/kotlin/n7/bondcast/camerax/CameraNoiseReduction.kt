package n7.bondcast.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview

// доступные режимы шумоподавления сенсора/ISP для текущей камеры
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraInfo.noiseReductionModes(): IntArray =
    runCatching {
        Camera2CameraInfo.from(this)
            .getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
    }.getOrNull() ?: IntArray(0)

// есть ли что переключать: и OFF, и хотя бы один включённый режим
internal fun IntArray.noiseReductionSwitchable(): Boolean =
    contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) &&
        any { it != CaptureRequest.NOISE_REDUCTION_MODE_OFF }

// лучший доступный включённый режим: HIGH_QUALITY предпочтительнее FAST
internal fun IntArray.bestNoiseReductionMode(): Int = when {
    contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
    contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
    else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
}

// применяет режим к билдеру превью до бинда (NR — CaptureRequest-опция, не рантайм cameraControl)
@OptIn(ExperimentalCamera2Interop::class)
internal fun Preview.Builder.setNoiseReductionMode(mode: Int): Preview.Builder = apply {
    Camera2Interop.Extender(this).setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, mode)
}
