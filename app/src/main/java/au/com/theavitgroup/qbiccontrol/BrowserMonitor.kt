package au.com.theavitgroup.qbiccontrol

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class BrowserMonitor(
    private val context: Context,
    private val onChange: (Boolean) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastState: Boolean = false
    @Volatile private var running: Boolean = false

    fun start() {
        if (!hasPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — browser monitoring disabled. " +
                "Run: adb shell appops set ${context.packageName} PACKAGE_USAGE_STATS allow")
            return
        }
        running = true
        thread = HandlerThread("BrowserMonitor").also { it.start() }
        handler = Handler(thread!!.looper)
        scheduleNext()
        Log.i(TAG, "Started — watching ${Config.BROWSER_PACKAGE}")
    }

    fun stop() {
        running = false
        handler = null
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "Stopped")
    }

    private fun scheduleNext() {
        handler?.postDelayed({
            if (running) {
                poll()
                scheduleNext()
            }
        }, POLL_INTERVAL_MS)
    }

    private fun poll() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - QUERY_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp > lastTime) {
                lastTime = event.timeStamp
                lastPkg = event.packageName
            }
        }
        if (lastPkg == null) return  // no foreground transition in window — state unchanged
        val isOpen = lastPkg == Config.BROWSER_PACKAGE
        if (isOpen != lastState) {
            lastState = isOpen
            Log.d(TAG, "Browser state → $isOpen (foreground: $lastPkg)")
            onChange(isOpen)
        }
    }

    private fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val TAG = "BrowserMonitor"
        private const val POLL_INTERVAL_MS = 1000L
        private const val QUERY_WINDOW_MS = 10_000L
    }
}
