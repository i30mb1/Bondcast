package n7.bondcast.uvc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public interface UsbCameraMonitor {
    val connected: StateFlow<Boolean>
}

public fun usbCameraMonitor(context: Context): UsbCameraMonitor =
    UsbCameraMonitorImpl(context.applicationContext)

private class UsbCameraMonitorImpl(private val context: Context) : UsbCameraMonitor {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connected = MutableStateFlow(hasUvc())
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            _connected.value = hasUvc()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun hasUvc(): Boolean = usbManager.deviceList.values.any(::isUvc)

    private fun isUvc(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
