package au.com.theavitgroup.qbiccontrol

import android.graphics.Color
import android.util.Log
import java.io.File

enum class LedLocation {
  FRONT, SIDE, FRONT_SIDE;

  companion object {
    fun from(s: String): LedLocation = when (s.uppercase()) {
      "FRONT" -> FRONT
      "SIDE"  -> SIDE
      else    -> FRONT_SIDE
    }
  }
}

/**
 * Controls the QBIC SmartPanel LEDs via sysfs.
 *
 * Hardware layout (confirmed via adb):
 *   Front LED  — single white/green status LED
 *                /sys/class/leds/status/brightness   (0 = off, 255 = full)
 *   Side LEDs  — RGB, driven by three separate nodes
 *                /sys/class/leds/red/brightness
 *                /sys/class/leds/green/brightness
 *                /sys/class/leds/blue/brightness
 *
 * Requires the app to run as a system app, OR the sysfs nodes to be
 * world-writable (which they are on this userdebug firmware build).
 */
class LedController {

  fun set(location: LedLocation, hexColor: String) {
    val color = parseHex(hexColor)
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    // Front brightness = perceptual luminance of the requested colour
    val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

    when (location) {
      LedLocation.FRONT      -> setFront(brightness)
      LedLocation.SIDE       -> setSide(r, g, b)
      LedLocation.FRONT_SIDE -> { setFront(brightness); setSide(r, g, b) }
    }
  }

  private fun setFront(brightness: Int) {
    writeSysfs("/sys/class/leds/status/brightness", brightness.toString())
  }

  private fun setSide(r: Int, g: Int, b: Int) {
    writeSysfs("/sys/class/leds/red/brightness",   r.toString())
    writeSysfs("/sys/class/leds/green/brightness", g.toString())
    writeSysfs("/sys/class/leds/blue/brightness",  b.toString())
  }

  private fun writeSysfs(path: String, value: String) {
    try {
      File(path).writeText(value)
      Log.d(TAG, "sysfs $path = $value")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to write $path: ${e.message}")
    }
  }

  private fun parseHex(hex: String): Int =
    Color.parseColor("#${hex.trimStart('#').padStart(6, '0')}")

  companion object {
    private const val TAG = "LedController"
  }
}
