package au.com.theavitgroup.qbiccontrol

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class SensorMonitor(
    context: Context,
    private val onChanged: (SensorController.SensorValues) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    @Volatile var lastLight: Int? = null
        private set
    @Volatile var lastProximity: Int? = null
        private set
    @Volatile private var lastBroadcastMs: Long = 0

    val lastValues: SensorController.SensorValues
        get() = SensorController.SensorValues(lastLight, lastProximity)
    private val throttleMs: Long = 200

    fun start() {
        Log.d("SensorMonitor", "Registering sensor listeners")
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        Log.d("SensorMonitor", "Unregistering sensor listeners")
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        var changed = false
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val value = event.values[0].toInt()
                if (value != lastLight) {
                    lastLight = value
                    changed = true
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val value = event.values[0].toInt()
                if (value != lastProximity) {
                    lastProximity = value
                    changed = true
                }
            }
        }
        if (changed) {
            val now = System.currentTimeMillis()
            if (now - lastBroadcastMs >= throttleMs) {
                lastBroadcastMs = now
                Log.d("SensorMonitor", "Sensor changed: light=$lastLight, proximity=$lastProximity")
                onChanged(SensorController.SensorValues(lastLight, lastProximity))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
