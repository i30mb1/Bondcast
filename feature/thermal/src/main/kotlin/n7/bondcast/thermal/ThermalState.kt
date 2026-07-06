package n7.bondcast.thermal

import android.os.PowerManager

public data class ThermalState(
    val status: Int,
    val headroom: Float?,
    val batteryTempC: Float?,
) {
    val heat: Float
        get() {
            headroom?.let { return it.coerceIn(0f, 1f) }
            if (status > PowerManager.THERMAL_STATUS_NONE) return statusHeat(status)
            batteryTempC?.let { return ((it - 25f) / 20f).coerceIn(0f, 1f) }
            return 0f
        }

    val statusLabel: String
        get() = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "норма"
            PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий нагрев"
            PowerManager.THERMAL_STATUS_MODERATE -> "умеренный нагрев"
            PowerManager.THERMAL_STATUS_SEVERE -> "сильный нагрев"
            PowerManager.THERMAL_STATUS_CRITICAL -> "критический"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "аварийный"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "отключение"
            else -> "неизвестно"
        }

    private fun statusHeat(status: Int): Float = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> 0.30f
        PowerManager.THERMAL_STATUS_MODERATE -> 0.55f
        PowerManager.THERMAL_STATUS_SEVERE -> 0.80f
        PowerManager.THERMAL_STATUS_CRITICAL -> 0.92f
        else -> 1.0f
    }

    companion object {
        val UNKNOWN = ThermalState(PowerManager.THERMAL_STATUS_NONE, null, null)
    }
}
