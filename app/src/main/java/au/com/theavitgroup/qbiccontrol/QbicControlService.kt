package au.com.theavitgroup.qbiccontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

class QbicControlService : Service() {

  private var server: ControlWebSocketServer? = null
  private var cameraStream: CameraStreamServer? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID, buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    } else {
      startForeground(NOTIFICATION_ID, buildNotification())
    }
    cameraStream = CameraStreamServer(applicationContext, Config.CAMERA_PORT).also {
      it.startServer()  // HTTP server up immediately, independent of camera state
      it.start()        // camera stream (as before)
    }
    server = ControlWebSocketServer(
      port         = Config.PORT,
      token        = TokenStore.getOrCreate(applicationContext),
      context      = applicationContext,
      cameraStream = cameraStream,
    )
    server?.start()
    enableAccessibilityService()
    isRunning = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_RETRY_CAMERA -> cameraStream?.retryNow()
      ACTION_SET_TOKEN    -> {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_STICKY
        TokenStore.set(applicationContext, token)
        server?.updateToken(token)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    cameraStream?.stopServer()  // stops HTTP server and camera
    cameraStream = null
    server?.stop()
    server    = null
    isRunning = false
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun enableAccessibilityService() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val comp = ComponentName(this, ScreenCaptureService::class.java).flattenToString()
    try {
      val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
      if (comp !in current) {
        val updated = if (current.isEmpty()) comp else "$current:$comp"
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i(TAG, "Accessibility service enabled: $comp")
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "Cannot enable accessibility service — WRITE_SECURE_SETTINGS not granted: ${e.message}")
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "QbicControl",
      NotificationManager.IMPORTANCE_LOW,
    )
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val tap = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("QbicControl")
      .setContentText("WebSocket server on :${Config.PORT}")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(tap)
      .setOngoing(true)
      .build()
  }

  companion object {
    @Volatile var isRunning = false
    private const val TAG               = "QbicControlService"
    const val ACTION_RETRY_CAMERA      = "au.com.theavitgroup.qbiccontrol.RETRY_CAMERA"
    const val ACTION_SET_TOKEN         = "au.com.theavitgroup.qbiccontrol.SET_TOKEN"
    const val EXTRA_TOKEN              = "token"
    private const val CHANNEL_ID       = "qbic_control"
    private const val NOTIFICATION_ID  = 1
  }
}
