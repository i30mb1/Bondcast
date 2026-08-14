package n7.bondcast.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.camera2Interop
import androidx.camera.camera2.interop.cameraCharacteristics
import androidx.camera.core.CameraInfo
import androidx.camera.core.SessionConfig

// доступные режимы шумоподавления сенсора/ISP для текущей камеры
internal fun CameraInfo.noiseReductionModes(): IntArray = runCatching {
    cameraCharacteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
}.getOrNull() ?: IntArray(0)

// есть ли что переключать: и OFF, и хотя бы один включённый режим
internal fun IntArray.noiseReductionSwitchable(): Boolean = contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) &&
    any { it != CaptureRequest.NOISE_REDUCTION_MODE_OFF }

// лучший доступный включённый режим: HIGH_QUALITY предпочтительнее FAST
internal fun IntArray.bestNoiseReductionMode(): Int = when {
    contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
    contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
    else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
}

// применяет режим ко всей сессии до бинда (NR — CaptureRequest-опция, не рантайм cameraControl).
// В новом interop-API CaptureRequest-опции живут на уровне сессии, а не use-case: у
// UseCaseCamera2Interop только OutputConfiguration-настройки.
internal fun SessionConfig.Builder.setNoiseReductionMode(mode: Int): SessionConfig.Builder = camera2Interop { setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, mode) }
