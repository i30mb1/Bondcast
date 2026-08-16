package n7.bondcast.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class StepsCounterImpl(context: Context) :
    StepsCounter,
    SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _rawSteps = MutableStateFlow(0)

    override val rawSteps: StateFlow<Int> = _rawSteps.asStateFlow()
    override val available: Boolean = sensor != null

    private var listening = false

    override fun start() {
        val sensor = sensor ?: return
        if (listening) return
        // NORMAL, а не FASTEST: датчик всё равно шлёт событие на шаг, а телефон в эфире и так
        // на пределе по батарее и нагреву — лишние пробуждения тут ничего не дают
        listening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        _rawSteps.value = event.values.firstOrNull()?.toInt() ?: return
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int): Unit = Unit
}
