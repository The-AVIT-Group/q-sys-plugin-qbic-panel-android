package au.com.theavitgroup.qbiccontrol

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.app.Activity
import au.com.theavitgroup.qbiccontrol.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Simple status screen — shows the WebSocket address, auth token, and a start/stop toggle.
 * Not required for normal operation; the service starts automatically on boot.
 */
class MainActivity : Activity() {

  private lateinit var binding: ActivityMainBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.btnToggle.setOnClickListener {
      val svc = Intent(this, QbicControlService::class.java)
      if (QbicControlService.isRunning) stopService(svc) else startForegroundService(svc)
      binding.root.postDelayed({ refresh() }, 400)
    }

    binding.btnSaveToken.setOnClickListener {
      val newToken = binding.etToken.text.toString().trim()
      if (newToken.isEmpty()) {
        Toast.makeText(this, "Token cannot be empty", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      TokenStore.set(this, newToken)
      Toast.makeText(this, "Token saved — restart service to apply", Toast.LENGTH_LONG).show()
    }

    binding.btnNewToken.setOnClickListener {
      val generated = UUID.randomUUID().toString()
      TokenStore.set(this, generated)
      binding.etToken.setText(generated)
      Toast.makeText(this, "New token generated — restart service to apply", Toast.LENGTH_LONG).show()
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    val running = QbicControlService.isRunning
    binding.tvStatus.text  = if (running) "● Running" else "○ Stopped"
    binding.tvAddress.text = "ws://${localIp()}:${Config.PORT}"
    binding.etToken.setText(TokenStore.getOrCreate(this))
    binding.btnToggle.text = if (running) "Stop service" else "Start service"
  }

  private fun localIp(): String = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
      .flatMap { it.inetAddresses.asSequence() }
      .filterIsInstance<Inet4Address>()
      .filterNot { it.isLoopbackAddress }
      .firstOrNull()?.hostAddress ?: "unknown"
  } catch (_: Exception) { "unknown" }
}
