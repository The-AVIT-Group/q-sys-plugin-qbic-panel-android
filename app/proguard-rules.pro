# Java-WebSocket — internal TLS/SSL classes loaded by name
-keep class org.java_websocket.** { *; }

# Android framework classes used via reflection in ScreenCaptureService
-keepclassmembers class android.hardware.HardwareBuffer { *; }
