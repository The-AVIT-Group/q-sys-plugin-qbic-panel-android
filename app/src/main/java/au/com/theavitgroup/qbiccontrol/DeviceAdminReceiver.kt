package au.com.theavitgroup.qbiccontrol

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Minimal device admin receiver — grants DevicePolicyManager.lockNow() access
 * so the service can turn the screen off on command.
 *
 * Activate once after install:
 *   adb shell dpm set-active-admin \
 *       au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onEnabled(context: Context, intent: Intent) {
    Log.i(TAG, "Device admin enabled — screen-off commands will work")
  }

  override fun onDisabled(context: Context, intent: Intent) {
    Log.w(TAG, "Device admin disabled — screen-off commands will fail")
  }

  companion object {
    private const val TAG = "DeviceAdmin"
  }
}
