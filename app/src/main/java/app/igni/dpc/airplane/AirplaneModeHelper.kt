package app.igni.dpc.airplane

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import app.igni.dpc.AdminReceiver

/**
 * Device Owner airplane-mode read/toggle.
 *
 * Modern/OEM Android often ignores a bare [Settings.Global.AIRPLANE_MODE_ON] write +
 * [Intent.ACTION_AIRPLANE_MODE_CHANGED] broadcast — the Global flag (and toast) can look
 * successful while radios stay up. Prefer the hidden [ConnectivityManager.setAirplaneMode]
 * SystemApi (DO can usually invoke it), then Global writes, then optional radio-power
 * reflection. Success requires read-back: [isAirplaneModeOn] == requested.
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

        val notes = mutableListOf<String>()

        // 1) Primary: ConnectivityManager.setAirplaneMode(boolean) (@SystemApi / hidden).
        //    This is what system Settings uses and actually drives radios on most builds.
        val cmOk = connectivitySetAirplaneMode(app, enable)
        notes += if (cmOk) "cm.setAirplaneMode=ok" else "cm.setAirplaneMode=fail"
        if (matchesRequested(app, enable)) {
            broadcastAirplaneChanged(app, enable)
            Log.i(TAG, "Airplane mode ok via ConnectivityManager enable=$enable detail=$notes")
            return successResult(enable, notes)
        }

        // 2) Write helpers: DPM.setGlobalSetting + Settings.Global.putInt
        val value = if (enable) "1" else "0"
        val dpmOk = dpmSetGlobalSetting(dpm, admin, Settings.Global.AIRPLANE_MODE_ON, value)
        notes += if (dpmOk) "dpm.setGlobalSetting=ok" else "dpm.setGlobalSetting=fail"
        val putOk = putGlobalAirplane(app, enable)
        notes += if (putOk) "global.putInt=ok" else "global.putInt=fail"

        // Retry CM after Global write (some OEMs need flag set first).
        if (connectivitySetAirplaneMode(app, enable)) {
            notes += "cm.setAirplaneMode=retry_ok"
        }

        // 3) Broadcast — useful on some OEMs; never sole success criterion.
        broadcastAirplaneChanged(app, enable)
        notes += "broadcast=sent"

        if (matchesRequested(app, enable)) {
            Log.i(TAG, "Airplane mode ok after Global/CM enable=$enable detail=$notes")
            return successResult(enable, notes)
        }

        // 4) Last resort: TelephonyManager / ITelephony setRadioPower (dual-SIM cautious).
        val radioOk = telephonySetRadioPower(app, enable = !enable)
        notes += if (radioOk) "telephony.setRadioPower=ok" else "telephony.setRadioPower=fail"
        // Re-assert Global + CM after radio tweak.
        putGlobalAirplane(app, enable)
        connectivitySetAirplaneMode(app, enable)
        broadcastAirplaneChanged(app, enable)

        if (matchesRequested(app, enable)) {
            Log.i(TAG, "Airplane mode ok after telephony fallback enable=$enable detail=$notes")
            return successResult(enable, notes)
        }

        val nowOn = isAirplaneModeOn(app)
        Log.w(TAG, "Airplane mode FAILED enable=$enable readBack=$nowOn detail=$notes")
        return ToggleResult(
            success = false,
            enabled = nowOn,
            messageJa = "機内モードの切替に失敗しました（端末が拒否した可能性があります）"
        )
    }

    private fun matchesRequested(context: Context, enable: Boolean): Boolean =
        isAirplaneModeOn(context) == enable

    private fun successResult(enable: Boolean, notes: List<String>): ToggleResult {
        Log.i(TAG, "Airplane success enable=$enable notes=${notes.joinToString(";")}")
        return ToggleResult(
            success = true,
            enabled = enable,
            messageJa = if (enable) "機内モードをオンにしました" else "機内モードをオフにしました"
        )
    }

    /**
     * Reflective ConnectivityManager.setAirplaneMode(boolean) — hidden @SystemApi.
     * Returns true if the method was invoked without throwing (not a read-back guarantee).
     */
    private fun connectivitySetAirplaneMode(context: Context, enable: Boolean): Boolean {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching false
            val method = ConnectivityManager::class.java.getMethod(
                "setAirplaneMode",
                Boolean::class.javaPrimitiveType
            )
            method.invoke(cm, enable)
            Log.i(TAG, "ConnectivityManager.setAirplaneMode($enable)")
            true
        }.onFailure {
            Log.w(TAG, "ConnectivityManager.setAirplaneMode($enable) unavailable/failed", it)
        }.getOrDefault(false)
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

    /**
     * Best-effort radio power via TelephonyManager / ITelephony.
     * [enable]=true means radios ON (airplane OFF); false means radios OFF (airplane ON).
     * Dual-SIM: only touches default telephony; failures are logged, not fatal.
     */
    private fun telephonySetRadioPower(context: Context, enable: Boolean): Boolean {
        var any = false
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
            // TelephonyManager.setRadioPower(boolean) on some APIs / @SystemApi
            runCatching {
                val m = TelephonyManager::class.java.getMethod(
                    "setRadioPower",
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(tm, enable)
                Log.i(TAG, "TelephonyManager.setRadioPower($enable)")
                any = true
            }.onFailure {
                Log.w(TAG, "TelephonyManager.setRadioPower unavailable/failed", it)
            }
            // ITelephony.setRadioPower via getITelephony
            runCatching {
                val getITelephony = TelephonyManager::class.java.getDeclaredMethod("getITelephony")
                getITelephony.isAccessible = true
                val iTelephony = getITelephony.invoke(tm) ?: return@runCatching
                val setRadio = iTelephony.javaClass.getMethod(
                    "setRadioPower",
                    Boolean::class.javaPrimitiveType
                )
                setRadio.invoke(iTelephony, enable)
                Log.i(TAG, "ITelephony.setRadioPower($enable)")
                any = true
            }.onFailure {
                Log.w(TAG, "ITelephony.setRadioPower unavailable/failed", it)
            }
        }.onFailure {
            Log.w(TAG, "telephonySetRadioPower failed", it)
        }
        return any
    }

    private fun broadcastAirplaneChanged(context: Context, enable: Boolean) {
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
            putExtra("state", enable)
        }
        runCatching {
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
