package au.com.theavitgroup.qbiccontrol

import android.content.Context
import android.provider.Settings
import java.util.UUID

object TokenStore {

  private const val PREFS_FILE = "qbic_prefs"
  private const val KEY_TOKEN  = "auth_token"

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

  /**
   * Returns the stored token. On first call, seeds it with ANDROID_ID so Q-SYS
   * can connect using the device's known identifier before auto-provisioning a UUID.
   */
  fun getOrCreate(context: Context): String {
    val p = prefs(context)
    val existing = p.getString(KEY_TOKEN, null)
    if (!existing.isNullOrEmpty()) return existing
    val initial = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      ?: UUID.randomUUID().toString()
    p.edit().putString(KEY_TOKEN, initial).apply()
    return initial
  }

  fun get(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""

  fun set(context: Context, token: String) {
    prefs(context).edit().putString(KEY_TOKEN, token).apply()
  }

  fun clear(context: Context) {
    prefs(context).edit().remove(KEY_TOKEN).apply()
  }
}
