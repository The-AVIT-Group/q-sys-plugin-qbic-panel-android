package au.com.theavitgroup.qbiccontrol

import android.util.Log

/**
 * Reads ambient light and proximity sensor values from the input event subsystem.
 *
 * Uses `getevent -p`, which prints the last-known ABS axis value for every input
 * device and exits immediately — no sensor framework subscription or blocking read.
 *
 * Hardware confirmed via ADB on QBIC SmartPanel (TD-1070, firmware v2.12.4):
 *
 *   Device            Node               ABS code   Range
 *   ────────────────  ─────────────────  ─────────  ────────────
 *   lightsensor-level /dev/input/event1  0x001c     10 – 255
 *   proximity         /dev/input/event5  0x0019     0  – 150 000
 *
 * Both nodes are world-readable (crw-rw-rw-), so no root or special permission
 * is required.
 */
class SensorController {

    data class SensorValues(
        val light: Int?,      // lux-ish unit, 10–255; null if read failed
        val proximity: Int?,  // 0 = near, higher = further; null if read failed
    )

    fun read(): SensorValues {
        val output = runGetevent() ?: return SensorValues(null, null)
        return SensorValues(
            light     = parseAbs(output, "lightsensor-level", "001c"),
            proximity = parseAbs(output, "proximity",         "0019"),
        )
    }

    private fun runGetevent(): String? = try {
        Runtime.getRuntime()
            .exec("getevent -p")
            .inputStream
            .bufferedReader()
            .readText()
    } catch (e: Exception) {
        Log.w(TAG, "getevent -p failed: ${e.message}")
        null
    }

    // Split by "add device" blocks, find the one containing the device name,
    // then extract the integer after "absCode : value ".
    private fun parseAbs(text: String, deviceName: String, absCode: String): Int? {
        val block = text.split("add device")
            .firstOrNull { it.contains("\"$deviceName\"") } ?: return null
        return Regex("""$absCode\s*:\s*value\s+(\d+)""")
            .find(block)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private const val TAG = "SensorController"
    }
}
