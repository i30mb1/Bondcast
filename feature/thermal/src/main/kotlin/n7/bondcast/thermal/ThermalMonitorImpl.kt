package n7.bondcast.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ThermalMonitorImpl(private val context: Context) : ThermalMonitor {

    override fun states(): Flow<ThermalState> = callbackFlow {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        var status = powerManager.currentThermalStatus
        var headroom = readHeadroom(powerManager)
        var batteryTempC = readBatteryTemp()

        fun push() {
            trySend(ThermalState(status, headroom, batteryTempC))
        }

        val thermalListener = PowerManager.OnThermalStatusChangedListener {
            status = it
            push()
        }
        powerManager.addThermalStatusListener(thermalListener)

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                batteryTempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    .takeIf { it > 0 }?.div(10f)
                push()
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        push()

        val poller = launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                headroom = readHeadroom(powerManager)
                push()
            }
        }

        awaitClose {
            poller.cancel()
            powerManager.removeThermalStatusListener(thermalListener)
            context.unregisterReceiver(batteryReceiver)
        }
    }

    private fun readHeadroom(powerManager: PowerManager): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { powerManager.getThermalHeadroom(0) }.getOrNull()
            ?.takeIf { !it.isNaN() && it > 0f }
    }

    private fun readBatteryTemp(): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return raw.takeIf { it > 0 }?.div(10f)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}
