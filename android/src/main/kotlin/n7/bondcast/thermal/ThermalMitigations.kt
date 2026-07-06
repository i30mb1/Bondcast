package n7.bondcast.thermal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public class ThermalMitigations {

    private val _screenBrightness = MutableStateFlow<Float?>(null)
    val screenBrightness: StateFlow<Float?> = _screenBrightness.asStateFlow()

    private val _previewEnabled = MutableStateFlow(true)
    val previewEnabled: StateFlow<Boolean> = _previewEnabled.asStateFlow()

    private val _bitrateCapFraction = MutableStateFlow<Float?>(null)
    val bitrateCapFraction: StateFlow<Float?> = _bitrateCapFraction.asStateFlow()

    fun setScreenBrightness(value: Float?) {
        _screenBrightness.value = value
    }

    fun setPreviewEnabled(enabled: Boolean) {
        _previewEnabled.value = enabled
    }

    fun setBitrateCapFraction(fraction: Float?) {
        _bitrateCapFraction.value = fraction
    }
}
