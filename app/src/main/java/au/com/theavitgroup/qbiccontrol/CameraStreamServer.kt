package au.com.theavitgroup.qbiccontrol

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.os.Build
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CameraStreamServer(private val context: Context, private val port: Int) {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile private var serverSocket: ServerSocket? = null

    private val latestFrame = AtomicReference<ByteArray>(null)
    private val retrySignal = Object()

    @Volatile var isStreaming = false
        private set
    private val serverRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var wantStreaming = false

    fun start() {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAMERA permission not granted — stream unavailable. Grant with: adb shell pm grant ${context.packageName} android.permission.CAMERA")
            return
        }
        wantStreaming = true
        Thread(::startWithRetry, "CameraStartThread").apply { isDaemon = true }.start()
    }

    fun startServer() {
        if (!serverRunning.compareAndSet(false, true)) return
        Thread(::serveHttp, "MjpegHttpServer").apply { isDaemon = true }.start()
    }

    private fun startWithRetry() {
        var attempt = 0
        while (wantStreaming) {
            attempt++
            val ht = HandlerThread("CameraThread").also { it.start() }
            handlerThread = ht
            handler = Handler(ht.looper)
            isStreaming = true
            try {
                openCamera()
                return
            } catch (e: android.hardware.camera2.CameraAccessException) {
                val delaySec = if (attempt < 5) 10L else 30L
                Log.w(TAG, "Camera disabled by policy (reason=${e.reason}), attempt $attempt — retrying in ${delaySec}s")
                cleanupCamera()
                if (attempt == 1 || attempt % 10 == 0) {
                    Log.i(TAG, "Launching BootActivity to satisfy foreground camera requirement (attempt $attempt)")
                    try {
                        context.startActivity(
                            Intent(context, BootActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "BootActivity launch failed: ${e.message}")
                    }
                }
                synchronized(retrySignal) {
                    retrySignal.wait(delaySec * 1000L)
                }
            }
        }
    }

    fun retryNow() {
        synchronized(retrySignal) {
            retrySignal.notifyAll()
        }
    }

    fun stop() {
        wantStreaming = false
        isStreaming = false
        retryNow()
        cleanupCamera()
    }

    fun stopServer() {
        serverRunning.set(false)
        stop()
        serverSocket?.close()
        serverSocket = null
    }

    private fun cleanupCamera() {
        isStreaming = false
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader     = null
        handlerThread?.quitSafely(); handlerThread = null; handler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val h = handler ?: return
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
            Log.e(TAG, "No camera found")
            return
        }

        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).also {
            imageReader = it
        }
        reader.setOnImageAvailableListener({ ir ->
            ir.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                latestFrame.set(bytes)
            }
        }, h)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                camera.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val request = camera
                                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(reader.surface) }
                                    .build()
                                session.setRepeatingRequest(request, null, h)
                                Log.i(TAG, "Camera capture started")
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera capture request failed: ${e.message}")
                                cleanupCamera()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "CaptureSession configure failed")
                            cleanupCamera()
                        }
                    },
                    h,
                )
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                if (error == ERROR_CAMERA_DISABLED && wantStreaming) {
                    Log.w(TAG, "Camera disabled by policy (async) — retry loop will re-open")
                }
            }
        }, h)
    }

    private fun serveHttp() {
        var ss: ServerSocket? = null
        var attempt = 0
        while (serverRunning.get() && ss == null) {
            try {
                ss = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(port)) }
            } catch (e: java.net.BindException) {
                if (++attempt > 10) { Log.e(TAG, "serveHttp: gave up binding :$port after $attempt attempts"); return }
                Log.w(TAG, "serveHttp: port $port busy (attempt $attempt), retrying in 1s")
                Thread.sleep(1000)
            }
        }
        val bound = ss ?: return
        serverSocket = bound
        Log.i(TAG, "MJPEG server listening on :$port")
        try {
            while (serverRunning.get()) {
                try {
                    val client = bound.accept()
                    Thread({ handleClient(client) }, "MjpegClient").apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    if (serverRunning.get()) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        } finally {
            bound.close()
            if (serverSocket === bound) serverSocket = null
        }
    }

    private fun readRequestPath(socket: Socket): String {
        val sb = StringBuilder()
        val input = socket.getInputStream()
        while (sb.length < 4096) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) break
        }
        return sb.lineSequence().firstOrNull()?.trim()?.split(" ")?.getOrNull(1) ?: "/"
    }

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isEmpty()) emptyMap()
        else query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()

    private fun handleClient(socket: Socket) {
        try {
            val full  = readRequestPath(socket)          // may include ?query
            val path  = full.substringBefore("?")
            val query = full.substringAfter("?", "")
            val out   = socket.getOutputStream()
            when {
                path == "/screen"   -> serveScreen(out, parseQuery(query))
                path == "/snapshot" -> serveSnapshot(out)
                else                -> serveMjpeg(out, socket)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun serveSnapshot(out: java.io.OutputStream) {
        val frame = latestFrame.get()
        if (frame == null) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(frame)
        }
        out.flush()
    }

    private fun serveScreen(out: java.io.OutputStream, params: Map<String, String> = emptyMap()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\nRequires Android 11+".toByteArray())
            out.flush()
            return
        }
        val service = ScreenCaptureService.instance
        if (service == null) {
            Log.w(TAG, "serveScreen: ScreenCaptureService not connected")
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\nScreen capture service not connected".toByteArray())
            out.flush()
            return
        }
        val scale = params["scale"]?.toIntOrNull()?.coerceIn(1, 6) ?: 1
        val (format, mime) = if (scale == 1)
            android.graphics.Bitmap.CompressFormat.PNG to "image/png"
        else
            android.graphics.Bitmap.CompressFormat.JPEG to "image/jpeg"
        val bytes = try {
            service.captureScreen(scaleDivisor = scale, format = format, quality = 80).get(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "serveScreen: capture timed out or failed: ${e.message}")
            null
        }
        if (bytes == null) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\nScreen capture failed".toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        out.flush()
    }

    private fun serveMjpeg(out: java.io.OutputStream, socket: Socket) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=frame\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        while (isStreaming && !socket.isClosed) {
            val frame = latestFrame.get()
            if (frame != null) {
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            Thread.sleep(100) // ~10 fps
        }
    }

    companion object {
        private const val TAG = "CameraStreamServer"
        private const val ERROR_CAMERA_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
    }
}
