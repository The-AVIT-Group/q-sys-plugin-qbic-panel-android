package au.com.theavitgroup.qbiccontrol

import android.content.Context
import android.util.Base64
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet

/**
 * WebSocket server that listens for JSON commands from Q-SYS and pushes sensor
 * change events asynchronously via [SensorMonitor].
 *
 * Connection URL:
 *   ws://<panel-ip>:9090/                      (if Config.TOKEN is empty)
 *   ws://<panel-ip>:9090/?token=<secret>       (if Config.TOKEN is set)
 *
 * Pushed events (unsolicited):
 *   {"ok":true,"light":<int>,"proximity":<int>}   on sensor change
 *   {"ok":true,"browser":<bool>}                  on kiosk browser foreground change
 */
class ControlWebSocketServer(
  port: Int,
  @Volatile private var token: String,
  private val context: Context,
  cameraStream: CameraStreamServer? = null,
) : WebSocketServer(InetSocketAddress(port)) {

  private val clients = CopyOnWriteArraySet<WebSocket>()

  private fun sendToAll(msg: String) {
    for (client in clients) {
      try { client.send(msg) } catch (e: Exception) {
        Log.w(TAG, "Broadcast to ${client.remoteSocketAddress} failed: ${e.message}")
      }
    }
  }

  private val monitor = SensorMonitor(context) { values ->
    val msg = JSONObject()
      .put("ok",        true)
      .put("light",     values.light)
      .put("proximity", values.proximity)
      .toString()
    Log.d(TAG, "Broadcasting sensor change to ${clients.size} clients")
    sendToAll(msg)
  }
  private val screenMonitor = ScreenMonitor { bytes ->
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    sendToAll("""{"ok":true,"screen":"$b64"}""")
  }
  private val handler = CommandHandler(context, cameraStream, monitor, screenMonitor)
  private val browserMonitor = BrowserMonitor(context) { isOpen ->
    Log.d(TAG, "Broadcasting browser state=$isOpen to ${clients.size} clients")
    sendToAll("""{"ok":true,"browser":$isOpen}""")
  }

  init {
    isReuseAddr = true
    Log.d(TAG, "SensorMonitor instance created")
  }

  override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
    if (token.isNotEmpty()) {
      val uri         = handshake.resourceDescriptor ?: ""
      val clientToken = Regex("""[?&]token=([^&]*)""").find(uri)?.groupValues?.get(1) ?: ""
      if (clientToken != token) {
        conn.send("""{"ok":false,"error":"Unauthorized"}""")
        conn.close()
        Log.w(TAG, "Rejected ${conn.remoteSocketAddress} — bad token")
        return
      }
    }
    clients.add(conn)
    Log.i(TAG, "Connected: ${conn.remoteSocketAddress} (total: ${clients.size})")
  }

  override fun onMessage(conn: WebSocket, message: String) {
    Log.d(TAG, "→ $message")
    // set_token is handled here rather than in CommandHandler because it mutates auth state
    try {
      val json = JSONObject(message)
      if (json.optString("cmd").lowercase() == "set_token") {
        val newToken = json.optString("token")
        if (newToken.isBlank()) {
          conn.send("""{"ok":false,"error":"Token cannot be empty"}""")
          return
        }
        token = newToken
        TokenStore.set(context, newToken)
        Log.i(TAG, "Token updated via set_token — reconnection required")
        conn.send("""{"ok":true,"provisioned":true}""")
        return
      }
    } catch (_: Exception) {}
    val response = handler.handle(message)
    Log.d(TAG, "← $response")
    try { conn.send(response) } catch (e: Exception) { Log.w(TAG, "Send failed: ${e.message}") }
  }

  override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
    clients.remove(conn)
    Log.i(TAG, "Disconnected: ${conn.remoteSocketAddress} ($code)")
  }

  override fun onError(conn: WebSocket?, ex: Exception) {
    if (conn != null) clients.remove(conn)
    Log.e(TAG, "Error on ${conn?.remoteSocketAddress}", ex)
  }

  override fun onStart() {
    Log.d(TAG, "Calling monitor.start() in onStart()")
    Log.i(TAG, "WebSocket server listening on :$port")
    connectionLostTimeout = 60  // ping every 60 s
    monitor.start()
    browserMonitor.start()
  }

  fun updateToken(newToken: String) {
    token = newToken
  }

  override fun stop() {
    screenMonitor.stop()
    monitor.stop()
    browserMonitor.stop()
    super.stop()
  }

  companion object {
    private const val TAG = "QbicWS"
  }
}
