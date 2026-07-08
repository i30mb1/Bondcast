package n7.bondcast.camerax

import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory

// нормализованная фабрика (0..1) — точка не привязана к размеру конкретного вьюфайндера
private val centerPointFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)

/** Замораживает экспозицию и баланс белого — кадр не «дышит» при панораме. Стабильный путь через FocusMeteringAction. */
public fun setAeAwbLock(camera: Camera, locked: Boolean) {
    if (locked) {
        val point = centerPointFactory.createPoint(0.5f, 0.5f)
        val flags = FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        val action = FocusMeteringAction.Builder(point, flags)
            .setLockingMode(flags)
            .disableAutoCancel()
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    } else {
        camera.cameraControl.cancelFocusAndMetering()
    }
}

/** Ночной режим (Low Light Boost) — доступен не на всех устройствах, проверяй cameraInfo.isLowLightBoostSupported(). */
public fun setLowLightBoost(camera: Camera, enabled: Boolean) {
    camera.cameraControl.enableLowLightBoostAsync(enabled)
}
