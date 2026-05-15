package au.com.theavitgroup.qbiccontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Transparent zero-UI activity launched by CameraStreamServer when the camera is blocked
 * by the QBIC firmware's Uid mode: CAMERA: foreground policy. Making this Activity visible
 * raises the process importance to 100 (foreground), satisfying the policy check.
 *
 * Flow:
 *   1. CameraStreamServer detects CAMERA_DISABLED and launches this Activity via
 *      FLAG_ACTIVITY_NEW_TASK (requires SYSTEM_ALERT_WINDOW permission).
 *   2. This Activity sends ACTION_RETRY_CAMERA to QbicControlService, which wakes the
 *      retry loop immediately via retryNow().
 *   3. openCamera() is retried while this Activity is still visible — camera opens.
 *   4. Activity finishes after 1.5 s; camera session remains open.
 */
class BootActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("BootActivity", "onCreate — satisfying camera foreground check")
        startForegroundService(
            Intent(this, QbicControlService::class.java)
                .setAction(QbicControlService.ACTION_RETRY_CAMERA)
        )
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }
}
