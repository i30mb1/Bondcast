package n7.bondcast.thermal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class ThermalMonitorWithLogging(
    private val origin: ThermalMonitor,
    private val log: (String) -> Unit,
) : ThermalMonitor {

    override fun states(): Flow<ThermalState> = flow {
        var last: ThermalState? = null
        origin.states().collect { state ->
            if (state != last) {
                log(
                    "thermal: ${state.statusLabel} headroom=${state.headroom} " +
                        "battery=${state.batteryTempC}°C heat=${state.heat}",
                )
                last = state
            }
            emit(state)
        }
    }
}
