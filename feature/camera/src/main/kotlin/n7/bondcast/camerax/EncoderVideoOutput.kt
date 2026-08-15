package n7.bondcast.camerax

import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.ConstantObservable
import androidx.camera.core.impl.Observable
import androidx.camera.video.MediaSpec
import androidx.camera.video.VideoOutput

/**
 * [VideoOutput], который вместо записи в файл (как штатный `Recorder`) отдаёт CameraX уже готовую
 * Surface — вход энкодера StreamPack.
 *
 * Зачем вообще VideoCapture, если кадр забирает энкодер и Preview справлялся: composition-режим двух
 * камер CameraX включает ТОЛЬКО для комбинации Preview + VideoCapture — см.
 * LifecycleCameraProviderImpl.bindToLifecycle(configs), проверка isDualCameraVideoCapture. Два
 * Preview молча уходят в non-composition ветку: обе камеры биндятся независимо, CompositionSettings
 * игнорируются, а врезки нет (проверено на стенде: 2xPreview → «The camera is not in concurrent
 * camera composition mode», Preview+VideoCapture → композиция есть).
 *
 * Потребитель [surface] обязан возвращать буферы, иначе после первого же кадра поток встанет —
 * у нас это настоящий MediaCodec, так что всё в порядке.
 */
internal class EncoderVideoOutput(private val surface: Surface) : VideoOutput {

    // VideoCapture.createPipeline делает requireNonNull(getMediaSpec()), а дефолт интерфейса — null
    private val mediaSpec: MediaSpec = MediaSpec.builder().build()

    override fun onSurfaceRequested(request: SurfaceRequest) {
        Log.i(TAG, "surfaceRequest ${request.resolution}")
        request.provideSurface(surface, Runnable::run) { result ->
            Log.i(TAG, "surface released, result=${result.resultCode}")
        }
    }

    override fun getMediaSpec(): Observable<MediaSpec> = ConstantObservable.withValue(mediaSpec)

    private companion object {
        const val TAG = "EncoderVideoOutput"
    }
}
