package au.com.theavitgroup.qbiccontrol

object Config {
  /** WebSocket server port the panel listens on. */
  const val PORT = 9090

  /** MJPEG camera stream HTTP port.  Access via:  http://<panel-ip>:9091/ */
  const val CAMERA_PORT = 9091

  /** Package name of the kiosk browser to monitor for foreground state. */
  const val BROWSER_PACKAGE = "com.qbic.smilplayer"
}
