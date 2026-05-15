package au.com.theavitgroup.qbiccontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Parses incoming JSON commands and dispatches them to the appropriate controller.
 *
 * Command schema
 * ──────────────
 * LED:    {"cmd":"led",    "location":"FRONT_SIDE", "color":"FF0000"}
 * Screen: {"cmd":"screen", "state":"on"}   or  {"cmd":"screen","state":"off"}
 * Launch: {"cmd":"launch", "package":"au.com.theavitgroup.app"}
 * Home:   {"cmd":"home"}                   →  {"ok":true}  (requires su, userdebug firmware)
 * Status: {"cmd":"status"}
 * Sensor: {"cmd":"sensor"}
 * Camera: {"cmd":"camera"}                →  {"ok":true,"streaming":<bool>,"port":9091}
 *         {"cmd":"camera","state":"on"}   →  start stream, same response
 *         {"cmd":"camera","state":"off"}  →  stop stream,  same response
 * CamLED: {"cmd":"camera_led","state":"on"}   →  enable  Android privacy LED (default)
 *         {"cmd":"camera_led","state":"off"}  →  disable Android privacy LED
 *         {"cmd":"camera_led"}               →  {"ok":true,"enabled":<bool>}
 *
 * All responses: {"ok":true}  or  {"ok":false,"error":"<reason>"}
 */
class CommandHandler(
  private val context: Context,
  private val cameraStream: CameraStreamServer? = null,
  private val sensorMonitor: SensorMonitor? = null,
  private val screenMonitor: ScreenMonitor? = null,
) {

  private val led    = LedController()
  private val screen = ScreenController(context)

  fun handle(message: String): String { return try {
    val json = JSONObject(message)
    when (val cmd = json.getString("cmd").lowercase()) {
      "led" -> {
        val location = LedLocation.from(json.optString("location", "FRONT_SIDE"))
        val color    = json.optString("color", "000000")
        led.set(location, color)
        ok()
      }
      "screen" -> {
        when (json.optString("state", "on").lowercase()) {
          "on"  -> screen.wakeUp()
          "off" -> screen.sleep()
        }
        ok()
      }
      "launch" -> {
        val pkg = json.getString("package")
        val url = json.optString("url", "")
        val intent = if (url.isNotEmpty()) {
          android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .setPackage(pkg)
        } else {
          context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return error("Package not found: $pkg")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ok()
      }
      "home" -> {
        try {
          val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
          val inject = InputManager::class.java.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.java
          )
          val now = SystemClock.uptimeMillis()
          inject.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), 0)
          inject.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_HOME, 0), 0)
          ok()
        } catch (e: Exception) {
          error("home inject failed: ${e.message}")
        }
      }
      "status" -> JSONObject()
        .put("ok", true)
        .put("status", "running")
        .put("version", BuildConfig.VERSION_NAME)
        .toString()
      "sensor" -> {
        val values = sensorMonitor?.lastValues ?: SensorController.SensorValues(null, null)
        JSONObject()
          .put("ok",        true)
          .put("light",     values.light)
          .put("proximity", values.proximity)
          .toString()
      }
      "camera" -> {
        when (json.optString("state", "").lowercase()) {
          "on"  -> cameraStream?.start()
          "off" -> cameraStream?.stop()
        }
        JSONObject()
          .put("ok",        true)
          .put("streaming", cameraStream?.isStreaming ?: false)
          .put("port",      Config.CAMERA_PORT)
          .toString()
      }
      "camera_led" -> {
        val hasPermission = context.checkSelfPermission(
          android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return error("WRITE_SECURE_SETTINGS not granted — run: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        when (json.optString("state", "").lowercase()) {
          "on"  -> Settings.Global.putInt(context.contentResolver, "camera_mic_icons_disabled", 0)
          "off" -> Settings.Global.putInt(context.contentResolver, "camera_mic_icons_disabled", 1)
        }
        val disabled = Settings.Global.getInt(context.contentResolver, "camera_mic_icons_disabled", 0)
        JSONObject()
          .put("ok",      true)
          .put("enabled", disabled == 0)
          .toString()
      }
      "screen_capture" -> {
        val sm = screenMonitor ?: return error("Screen capture unavailable (API < 30)")
        val scale = json.optInt("scale", 3).coerceIn(1, 6)
        when (json.optString("state", "").lowercase()) {
          "on" -> {
            val intervalMs = (json.optDouble("interval", 1.0) * 1000).toLong().coerceAtLeast(200L)
            sm.updateScale(scale)
            if (sm.isRunning) sm.updateInterval(intervalMs) else sm.start(intervalMs)
          }
          "off" -> sm.stop()
        }
        JSONObject().put("ok", true).put("running", sm.isRunning).toString()
      }
      else -> error("Unknown command: $cmd")
    }
  } catch (e: Exception) {
    Log.e(TAG, "Command error for: $message", e)
    error(e.message ?: "Parse error")
  } }

  private fun ok() = """{"ok":true}"""

  private fun error(msg: String) = JSONObject()
    .put("ok", false)
    .put("error", msg)
    .toString()

  companion object {
    private const val TAG = "CommandHandler"
  }
}
