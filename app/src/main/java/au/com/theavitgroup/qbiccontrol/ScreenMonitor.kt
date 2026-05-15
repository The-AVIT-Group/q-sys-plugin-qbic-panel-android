package au.com.theavitgroup.qbiccontrol

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

class ScreenMonitor(private val onChange: (ByteArray) -> Unit) {

    private val monitoring = AtomicBoolean(false)
    @Volatile private var intervalMs    = 1000L
    @Volatile private var scaleDivisor  = 3

    fun start(intervalMs: Long) {
        this.intervalMs = intervalMs.coerceAtLeast(200L)
        if (!monitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Already running — interval updated to ${this.intervalMs}ms")
            return
        }
        Thread(::loop, "ScreenMonitor").apply { isDaemon = true }.start()
        Log.i(TAG, "Started (interval=${this.intervalMs}ms)")
    }

    fun stop() {
        if (monitoring.compareAndSet(true, false)) Log.i(TAG, "Stopped")
    }

    fun updateInterval(intervalMs: Long) {
        this.intervalMs = intervalMs.coerceAtLeast(200L)
    }

    fun updateScale(divisor: Int) {
        this.scaleDivisor = divisor.coerceIn(1, 6)
    }

    val isRunning: Boolean get() = monitoring.get()

    // ScreenCaptureService and captureScreen() require API 30. At runtime on pre-30 devices
    // the service instance is always null so this path is never reached.
    @SuppressLint("NewApi")
    private fun loop() {
        var lastHash = 0L
        while (monitoring.get()) {
            val cycleStart = System.currentTimeMillis()
            try {
                val service = ScreenCaptureService.instance
                if (service != null) {
                    val bytes = service.captureScreen(
                        scaleDivisor = scaleDivisor,
                        format = Bitmap.CompressFormat.JPEG,
                        quality = 60,
                    ).get(8, TimeUnit.SECONDS)
                    if (bytes != null) {
                        val hash = crc32(bytes)
                        if (hash != lastHash) {
                            lastHash = hash
                            onChange(bytes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Capture failed: ${e.message}")
            }
            val elapsed = System.currentTimeMillis() - cycleStart
            val sleep = intervalMs - elapsed
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    companion object {
        private const val TAG = "ScreenMonitor"
    }
}
