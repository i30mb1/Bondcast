package n7.bondcast.thermal

import android.content.Context
import kotlinx.coroutines.flow.Flow

public interface ThermalMonitor {
    fun states(): Flow<ThermalState>
}

public fun thermalMonitor(context: Context, log: (String) -> Unit = {}): ThermalMonitor =
    ThermalMonitorWithLogging(ThermalMonitorImpl(context), log)
