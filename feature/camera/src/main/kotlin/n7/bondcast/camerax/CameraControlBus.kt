package n7.bondcast.camerax

import androidx.camera.core.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Мост между CameraXVideoSource (живёт внутри StreamPack) и UI: текущая Camera и стабилизация превью. */
public object CameraControlBus {

    private val _camera = MutableStateFlow<Camera?>(null)
    public val camera: StateFlow<Camera?> = _camera.asStateFlow()

    // фактически включена ли стабилизация после разрешения feature group (preferred — может не выйти)
    private val _stabilizationActive = MutableStateFlow(false)
    public val stabilizationActive: StateFlow<Boolean> = _stabilizationActive.asStateFlow()

    // умеет ли текущая камера в принципе (напр. фронталка часто не умеет) — проверяется до бинда
    private val _stabilizationSupported = MutableStateFlow(false)
    public val stabilizationSupported: StateFlow<Boolean> = _stabilizationSupported.asStateFlow()

    private val _stabilizationWanted = MutableStateFlow(false)
    public val stabilizationWanted: StateFlow<Boolean> = _stabilizationWanted.asStateFlow()

    // умеет ли текущая камера переключать шумодав (есть OFF и хотя бы один включённый режим)
    private val _noiseReductionSupported = MutableStateFlow(false)
    public val noiseReductionSupported: StateFlow<Boolean> = _noiseReductionSupported.asStateFlow()

    // по умолчанию включён — как у штатной камеры; выключение делает шум заметным (наглядный тест)
    private val _noiseReductionWanted = MutableStateFlow(true)
    public val noiseReductionWanted: StateFlow<Boolean> = _noiseReductionWanted.asStateFlow()

    // умеет ли устройство две камеры одновременно (одна разрешённая комбинация тыл+фронт на A024)
    private val _pipSupported = MutableStateFlow(false)
    public val pipSupported: StateFlow<Boolean> = _pipSupported.asStateFlow()

    private val _pipWanted = MutableStateFlow(false)
    public val pipWanted: StateFlow<Boolean> = _pipWanted.asStateFlow()

    /** Позиция и размер врезки в долях кадра — общая для превью и эфира. */
    private val _pipLayout = MutableStateFlow(PipLayout())
    public val pipLayout: StateFlow<PipLayout> = _pipLayout.asStateFlow()

    @Volatile
    public var onStabilizationChanged: (() -> Unit)? = null

    @Volatile
    public var onNoiseReductionChanged: (() -> Unit)? = null

    @Volatile
    public var onPipChanged: (() -> Unit)? = null

    /** Меняет только композицию — применяется без ребинда камеры (setCompositionSettings). */
    @Volatile
    public var onPipLayoutChanged: ((PipLayout) -> Unit)? = null

    public fun setPipWanted(value: Boolean) {
        if (_pipWanted.value == value) return
        _pipWanted.value = value
        onPipChanged?.invoke()
    }

    /** Перетаскивание/масштаб врезки: без ребинда, иначе камера мигала бы на каждый кадр жеста. */
    public fun setPipLayout(layout: PipLayout) {
        if (_pipLayout.value == layout) return
        _pipLayout.value = layout
        onPipLayoutChanged?.invoke(layout)
    }

    /**
     * Какая камера основная (на весь кадр), вторая уходит во врезку. В PiP этим управляют обычные
     * кнопки «Фронт»/«Осн» — отдельная «поменять местами» не нужна.
     */
    public fun setPipMainIsBack(value: Boolean) {
        if (_pipLayout.value.mainIsBack == value) return
        _pipLayout.value = _pipLayout.value.copy(mainIsBack = value)
        // смена основной камеры меняет набор конфигов — нужен полный ребинд, не setCompositionSettings
        onPipChanged?.invoke()
    }

    // При смене камеры StreamPack создаёт новый CameraXVideoSource и биндит его РАНЬШЕ, чем
    // вызывает resetOutput()/release() у старого — без owner-гварда старый источник затирал
    // camera/колбэк, которые новый только что опубликовал. owner — просто identity-токен инстанса.
    @Volatile
    private var owner: Any? = null

    public fun setStabilizationWanted(value: Boolean) {
        if (_stabilizationWanted.value == value) return
        _stabilizationWanted.value = value
        onStabilizationChanged?.invoke()
    }

    public fun setNoiseReductionWanted(value: Boolean) {
        if (_noiseReductionWanted.value == value) return
        _noiseReductionWanted.value = value
        onNoiseReductionChanged?.invoke()
    }

    /** Источник становится текущим владельцем шины — вызывать при setOutput(). */
    public fun claim(owner: Any) {
        this.owner = owner
    }

    public fun publishCamera(owner: Any, camera: Camera?) {
        if (this.owner !== owner) return
        _camera.value = camera
    }

    public fun publishStabilizationActive(owner: Any, active: Boolean) {
        if (this.owner !== owner) return
        _stabilizationActive.value = active
    }

    public fun publishStabilizationSupported(owner: Any, supported: Boolean) {
        if (this.owner !== owner) return
        _stabilizationSupported.value = supported
    }

    public fun publishNoiseReductionSupported(owner: Any, supported: Boolean) {
        if (this.owner !== owner) return
        _noiseReductionSupported.value = supported
    }

    public fun publishPipSupported(owner: Any, supported: Boolean) {
        if (this.owner !== owner) return
        _pipSupported.value = supported
        // устройство не умеет — гасим тумблер, иначе UI обещает несуществующее
        if (!supported) _pipWanted.value = false
    }

    /** Отдаёт шину, только если её всё ещё держит именно этот источник (иначе — устаревший вызов, игнор). */
    public fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onStabilizationChanged = null
        onNoiseReductionChanged = null
        onPipChanged = null
        onPipLayoutChanged = null
        _camera.value = null
        _stabilizationActive.value = false
        _stabilizationSupported.value = false
        _noiseReductionSupported.value = false
        _pipSupported.value = false
    }
}
