package app.igni.dpc.airplane

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver

/**
 * Device Owner airplane-mode read/toggle.
 * Prefers [DevicePolicyManager.setGlobalSetting]; falls back to [Settings.Global.putInt].
 * Always broadcasts [Intent.ACTION_AIRPLANE_MODE_CHANGED] after a successful write.
 */
object AirplaneModeHelper {

    private const val TAG = "IgniAirplane"

    data class ToggleResult(val success: Boolean, val enabled: Boolean, val messageJa: String)

    fun isAirplaneModeOn(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) == 1
        }.getOrDefault(false)
    }

    fun setAirplaneMode(context: Context, enable: Boolean): ToggleResult {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, AdminReceiver::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(app.packageName)) {
            return ToggleResult(
                success = false,
                enabled = isAirplaneModeOn(app),
                messageJa = "Device Owner ではないため機内モードを切り替えできません"
            )
        }

        val value = if (enable) "1" else "0"
        val wrote = dpmSetGlobalSetting(dpm, admin, Settings.Global.AIRPLANE_MODE_ON, value) ||
            putGlobalAirplane(app, enable)

        if (!wrote) {
            Log.w(TAG, "Failed to write AIRPLANE_MODE_ON=$value")
            return ToggleResult(
                success = false,
                enabled = isAirplaneModeOn(app),
                messageJa = "機内モードの切替に失敗しました"
            )
        }

        broadcastAirplaneChanged(app, enable)

        val nowOn = isAirplaneModeOn(app)
        // Prefer read-back; if OEM lags, trust the requested state after successful write.
        val effective = if (nowOn == enable) nowOn else enable
        return ToggleResult(
            success = true,
            enabled = effective,
            messageJa = if (effective) "機内モードをオンにしました" else "機内モードをオフにしました"
        )
    }

    private fun dpmSetGlobalSetting(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        value: String
    ): Boolean {
        return runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setGlobalSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            Log.i(TAG, "DPM.setGlobalSetting($key, $value)")
            true
        }.onFailure {
            Log.w(TAG, "DPM.setGlobalSetting($key) unavailable/failed", it)
        }.getOrDefault(false)
    }

    private fun putGlobalAirplane(context: Context, enable: Boolean): Boolean {
        return runCatching {
            val ok = Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enable) 1 else 0
            )
            Log.i(TAG, "Settings.Global.AIRPLANE_MODE_ON put=$ok enable=$enable")
            ok
        }.onFailure {
            Log.w(TAG, "Settings.Global.putInt(AIRPLANE_MODE_ON) failed", it)
        }.getOrDefault(false)
    }

    private fun broadcastAirplaneChanged(context: Context, enable: Boolean) {
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
            putExtra("state", enable)
        }
        runCatching {
            // Prefer all-users broadcast when available (DO / system-ish).
            val method = Context::class.java.getMethod(
                "sendBroadcastAsUser",
                Intent::class.java,
                UserHandle::class.java
            )
            val allUsers = runCatching {
                UserHandle::class.java.getField("ALL").get(null) as UserHandle
            }.getOrElse {
                android.os.Process.myUserHandle()
            }
            method.invoke(context, intent, allUsers)
            Log.i(TAG, "sendBroadcastAsUser ACTION_AIRPLANE_MODE_CHANGED state=$enable")
        }.onFailure {
            Log.w(TAG, "sendBroadcastAsUser failed; falling back to sendBroadcast", it)
            runCatching {
                context.sendBroadcast(intent)
                Log.i(TAG, "sendBroadcast ACTION_AIRPLANE_MODE_CHANGED state=$enable")
            }.onFailure { e2 ->
                Log.w(TAG, "sendBroadcast ACTION_AIRPLANE_MODE_CHANGED failed", e2)
            }
        }
    }
}
