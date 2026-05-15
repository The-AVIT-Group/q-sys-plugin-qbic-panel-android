package au.com.theavitgroup.qbiccontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the WebSocket service automatically after the panel reboots. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      context.startForegroundService(Intent(context, QbicControlService::class.java))
    }
  }
}
