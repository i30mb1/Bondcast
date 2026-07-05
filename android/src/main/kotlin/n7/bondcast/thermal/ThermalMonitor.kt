package n7.bondcast.thermal

import android.content.Context
import kotlinx.coroutines.flow.Flow

internal interface ThermalMonitor {
    fun states(): Flow<ThermalState>
}

internal fun thermalMonitor(context: Context, log: (String) -> Unit = {}): ThermalMonitor =
    ThermalMonitorWithLogging(ThermalMonitorImpl(context), log)
