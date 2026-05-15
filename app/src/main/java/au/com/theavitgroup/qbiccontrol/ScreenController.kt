package au.com.theavitgroup.qbiccontrol

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log

class ScreenController(private val context: Context) {

  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

  /** Wakes the screen. Requires WAKE_LOCK permission (normal, no special signing). */
  fun wakeUp() {
    @Suppress("DEPRECATION")
    val wl = powerManager.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "QbicControl:wakeup",
    )
    wl.acquire(2_000L)
    Log.d(TAG, "Screen woken")
  }

  /**
   * Puts the screen to sleep via DevicePolicyManager.lockNow().
   *
   * Requires device admin to be activated once after install:
   *   adb shell dpm set-active-admin \
   *       au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver
   *
   * Without device admin this logs a warning and does nothing — it will not
   * crash the service.
   */
  fun sleep() {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, DeviceAdminReceiver::class.java)
    if (dpm.isAdminActive(admin)) {
      dpm.lockNow()
      Log.d(TAG, "Screen locked via DevicePolicyManager")
    } else {
      Log.w(TAG, "Screen sleep skipped — device admin not active. " +
        "Run: adb shell dpm set-active-admin " +
        "au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver")
    }
  }

  companion object {
    private const val TAG = "ScreenController"
  }
}
