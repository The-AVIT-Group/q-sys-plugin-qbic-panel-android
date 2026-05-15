package au.com.theavitgroup.qbiccontrol

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@android.annotation.TargetApi(Build.VERSION_CODES.R)
class ScreenCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "ScreenCaptureService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "ScreenCaptureService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * @param scaleDivisor divide both dimensions by this value (1 = full resolution)
     * @param format       compress format; JPEG for smaller payloads, PNG for lossless
     * @param quality      JPEG quality 0–100 (ignored for PNG)
     */
    fun captureScreen(
        scaleDivisor: Int = 1,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100,
    ): CompletableFuture<ByteArray?> {
        val future = CompletableFuture<ByteArray?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = bitmapFromResult(screenshot)
                        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        val bitmap = if (scaleDivisor > 1) {
                            val s = Bitmap.createScaledBitmap(
                                soft, soft.width / scaleDivisor, soft.height / scaleDivisor, true
                            )
                            soft.recycle()
                            s
                        } else soft
                        val out = ByteArrayOutputStream()
                        bitmap.compress(format, quality, out)
                        bitmap.recycle()
                        future.complete(out.toByteArray())
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot encode failed: ${e.message}")
                        future.complete(null)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                    future.complete(null)
                }
            }
        )
        return future
    }

    // ScreenshotResult changed between API 33 and 34:
    //   API 30–33: getHardwareBitmap() → hardware-backed Bitmap
    //   API 34+:   getAcquirableBuffer() → HardwareBuffer  (Bitmap.wrapHardwareBuffer, API 31+)
    // Reflection lets us compile against SDK 34 while running correctly on API 31.
    // ScreenshotResult method names changed across Android versions:
    //   API 30–31: getHardwareBitmap() → Bitmap (hardware-backed)
    //   API 32–33: getHardwareBuffer() → HardwareBuffer
    //   API 34+:   getAcquirableBuffer() → HardwareBuffer
    @SuppressLint("NewApi")
    private fun bitmapFromResult(screenshot: ScreenshotResult): Bitmap {
        for (methodName in listOf("getHardwareBitmap", "getHardwareBuffer", "getAcquirableBuffer")) {
            val result = try {
                screenshot.javaClass.getMethod(methodName).invoke(screenshot)
            } catch (e: NoSuchMethodException) {
                null
            } ?: continue

            if (result is Bitmap) return result
            if (result is android.hardware.HardwareBuffer) {
                val cs = screenshot.javaClass.getMethod("getColorSpace").invoke(screenshot)
                        as android.graphics.ColorSpace
                val hw = Bitmap.wrapHardwareBuffer(result, cs)!!
                result.close()
                return hw
            }
        }
        throw IllegalStateException(
            "No usable ScreenshotResult method on API ${android.os.Build.VERSION.SDK_INT}. " +
            "Available: ${screenshot.javaClass.methods.joinToString { it.name }}"
        )
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        @Volatile var instance: ScreenCaptureService? = null
    }
}
