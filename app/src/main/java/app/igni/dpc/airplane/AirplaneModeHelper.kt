package app.igni.dpc.airplane

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import app.igni.dpc.AdminReceiver

/**
 * Device Owner airplane-mode read/toggle with multi-path best-effort.
 *
 * Honest limitation: many OEM / stock builds require NETWORK_SETTINGS (system) for
 * ConnectivityManager.setAirplaneMode. Device Owner alone often cannot flip radios —
 * writing [Settings.Global.AIRPLANE_MODE_ON] may change the bit without turning radios.
 * Success is ONLY when Global read-back matches the requested value after a path that
 * preferably also notified ConnectivityService. If all paths fail, callers should open
 * [Settings.ACTION_AIRPLANE_MODE_SETTINGS] and show an honest Japanese message.
 *
 * Paths tried (each logged):
 * 1. Grant WRITE_SECURE_SETTINGS via DPM.setPermissionGrantState + DPM.setGlobalSetting
 * 2. Reflective ConnectivityManager.setAirplaneMode
 * 3. Binder IConnectivityManager.setAirplaneMode via ServiceManager
 * 4. Samsung / One UI SEM / Knox Custom reflective (no Knox SDK on classpath)
 * 5. TelephonyManager / ITelephony.setRadioPower for all active subs (partial airplane)
 * 6. ACTION_AIRPLANE_MODE_CHANGED broadcast via sendBroadcastAsUser(ALL)
 */
object AirplaneModeHelper {

    private const val TAG = "IgniAirplane"
    private const val PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    data class ToggleResult(
        val success: Boolean,
        val enabled: Boolean,
        val messageJa: String,
        /** True when programmatic paths failed and Admin should open system airplane Settings. */
        val openSettings: Boolean = false,
        /** Short one-line reason for Admin status (Japanese). */
        val statusLineJa: String = ""
    )

    fun isAirplaneModeOn(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) == 1
        }.getOrDefault(false)
    }

    fun openAirplaneSettings(context: Context): Boolean {
        val app = context.applicationContext
        val candidates = listOf(
            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).setPackage("com.android.settings"),
        )
        for (base in candidates) {
            val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                app.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "Opened airplane settings via ${intent.action}")
                return true
            }
        }
        Log.w(TAG, "Could not open airplane / wireless settings")
        return false
    }

    fun setAirplaneMode(context: Context, enable: Boolean): ToggleResult {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, AdminReceiver::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(app.packageName)) {
            return ToggleResult(
                success = false,
                enabled = isAirplaneModeOn(app),
                messageJa = "Device Owner ではないため機内モードを切り替えできません",
                openSettings = false,
                statusLineJa = "DOではない"
            )
        }

        val notes = mutableListOf<String>()

        // 1) Grant WRITE_SECURE_SETTINGS + DPM.setGlobalSetting
        val grantOk = grantWriteSecureSettings(dpm, admin, app.packageName)
        notes += if (grantOk) "grant.WRITE_SECURE_SETTINGS=ok" else "grant.WRITE_SECURE_SETTINGS=fail/skip"
        Log.i(TAG, "path1 grant WRITE_SECURE_SETTINGS → $grantOk")

        val value = if (enable) "1" else "0"
        val dpmOk = dpmSetGlobalSetting(dpm, admin, Settings.Global.AIRPLANE_MODE_ON, value)
        notes += if (dpmOk) "dpm.setGlobalSetting=ok" else "dpm.setGlobalSetting=fail"
        Log.i(TAG, "path1 DPM.setGlobalSetting(AIRPLANE_MODE_ON,$value) → $dpmOk")
        val putOk = putGlobalAirplane(app, enable)
        notes += if (putOk) "global.putInt=ok" else "global.putInt=fail"
        Log.i(TAG, "path1 Settings.Global.putInt → $putOk")

        // Prefer notifying ConnectivityService early when Global bit may already be set.
        val cmEarly = connectivitySetAirplaneMode(app, enable)
        notes += if (cmEarly) "cm.setAirplaneMode=early_ok" else "cm.setAirplaneMode=early_fail"
        Log.i(TAG, "path2 ConnectivityManager.setAirplaneMode($enable) early → $cmEarly")
        if (matchesRequested(app, enable) && cmEarly) {
            broadcastAirplaneChanged(app, enable)
            notes += "broadcast=sent"
            return successResult(enable, notes, "CM早期")
        }

        // 2) Reflective ConnectivityManager.setAirplaneMode (retry)
        val cmOk = connectivitySetAirplaneMode(app, enable)
        notes += if (cmOk) "cm.setAirplaneMode=ok" else "cm.setAirplaneMode=fail"
        Log.i(TAG, "path2 ConnectivityManager.setAirplaneMode($enable) → $cmOk")
        if (matchesRequested(app, enable)) {
            broadcastAirplaneChanged(app, enable)
            notes += "broadcast=sent"
            return successResult(enable, notes, if (cmOk) "CM" else "Global読戻し")
        }

        // 3) Binder IConnectivityManager via ServiceManager
        val binderOk = binderSetAirplaneMode(enable)
        notes += if (binderOk) "binder.IConnectivityManager=ok" else "binder.IConnectivityManager=fail"
        Log.i(TAG, "path3 IConnectivityManager.setAirplaneMode($enable) → $binderOk")
        if (matchesRequested(app, enable)) {
            broadcastAirplaneChanged(app, enable)
            notes += "broadcast=sent"
            return successResult(enable, notes, "Binder")
        }

        // 4) Samsung / One UI SEM / Knox Custom reflective
        val samsungOk = samsungSetAirplaneMode(app, enable)
        notes += if (samsungOk) "samsung.SEM/Knox=ok" else "samsung.SEM/Knox=fail/skip"
        Log.i(TAG, "path4 Samsung SEM/Knox Custom → $samsungOk")
        // Re-assert Global after OEM path
        putGlobalAirplane(app, enable)
        dpmSetGlobalSetting(dpm, admin, Settings.Global.AIRPLANE_MODE_ON, value)
        if (matchesRequested(app, enable) && samsungOk) {
            broadcastAirplaneChanged(app, enable)
            notes += "broadcast=sent"
            return successResult(enable, notes, "Samsung")
        }
        if (matchesRequested(app, enable)) {
            broadcastAirplaneChanged(app, enable)
            notes += "broadcast=sent"
            return successResult(enable, notes, "Global読戻し")
        }

        // 5) Telephony radio power for all active subscriptions (partial airplane)
        val radioOk = telephonySetRadioPowerAllSubs(app, radiosOn = !enable)
        notes += if (radioOk) "telephony.setRadioPower=ok" else "telephony.setRadioPower=fail"
        Log.i(TAG, "path5 telephony setRadioPower(radiosOn=${!enable}) → $radioOk (best-effort partial)")
        putGlobalAirplane(app, enable)
        dpmSetGlobalSetting(dpm, admin, Settings.Global.AIRPLANE_MODE_ON, value)
        connectivitySetAirplaneMode(app, enable)
        binderSetAirplaneMode(enable)

        // 6) Broadcast after writes
        broadcastAirplaneChanged(app, enable)
        notes += "broadcast=sent"
        Log.i(TAG, "path6 ACTION_AIRPLANE_MODE_CHANGED broadcast sent")

        if (matchesRequested(app, enable)) {
            Log.i(TAG, "Airplane mode ok after multi-path enable=$enable detail=$notes")
            val via = when {
                radioOk -> "電波OFF補完"
                else -> "複合"
            }
            return successResult(enable, notes, via)
        }

        // 7) Last resort: do NOT claim success — Admin opens Settings
        val nowOn = isAirplaneModeOn(app)
        Log.w(
            TAG,
            "Airplane mode FAILED enable=$enable readBack=$nowOn " +
                "(DO often lacks NETWORK_SETTINGS on OEM) detail=$notes"
        )
        return ToggleResult(
            success = false,
            enabled = nowOn,
            messageJa = "この端末では自動切替できないため設定画面を開きました",
            openSettings = true,
            statusLineJa = "自動不可→設定を開く"
        )
    }

    private fun matchesRequested(context: Context, enable: Boolean): Boolean =
        isAirplaneModeOn(context) == enable

    private fun successResult(enable: Boolean, notes: List<String>, via: String): ToggleResult {
        Log.i(TAG, "Airplane success enable=$enable via=$via notes=${notes.joinToString(";")}")
        return ToggleResult(
            success = true,
            enabled = enable,
            messageJa = if (enable) "機内モードをオンにしました" else "機内モードをオフにしました",
            openSettings = false,
            statusLineJa = if (enable) "オン ($via)" else "オフ ($via)"
        )
    }

    /**
     * Grant self WRITE_SECURE_SETTINGS via DPM.setPermissionGrantState when needed.
     */
    private fun grantWriteSecureSettings(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching false
            val granted = dpm.getPermissionGrantState(admin, packageName, PERM_WRITE_SECURE)
            if (granted == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                Log.i(TAG, "WRITE_SECURE_SETTINGS already granted")
                return@runCatching true
            }
            val ok = dpm.setPermissionGrantState(
                admin,
                packageName,
                PERM_WRITE_SECURE,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "setPermissionGrantState(WRITE_SECURE_SETTINGS) → $ok")
            ok
        }.onFailure {
            Log.w(TAG, "grant WRITE_SECURE_SETTINGS failed", it)
        }.getOrDefault(false)
    }

    /**
     * Reflective ConnectivityManager.setAirplaneMode(boolean) — hidden @SystemApi.
     * Often throws SecurityException (NETWORK_SETTINGS) on real devices for DO apps.
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
            Log.i(TAG, "ConnectivityManager.setAirplaneMode($enable) invoked")
            true
        }.onFailure {
            Log.w(TAG, "ConnectivityManager.setAirplaneMode($enable) unavailable/failed: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Binder path: ServiceManager.getService("connectivity") → IConnectivityManager.Stub.asInterface
     * → setAirplaneMode(boolean). Same permission as CM; try anyway.
     */
    private fun binderSetAirplaneMode(enable: Boolean): Boolean {
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "connectivity")
                ?: return@runCatching false
            val stub = Class.forName("android.net.IConnectivityManager\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val icm = asInterface.invoke(null, binder) ?: return@runCatching false
            val setAm = icm.javaClass.methods.firstOrNull { m ->
                m.name == "setAirplaneMode" && m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                        m.parameterTypes[0] == java.lang.Boolean::class.java)
            } ?: run {
                // Some builds: setAirplaneMode(boolean enable)
                icm.javaClass.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
            }
            setAm.invoke(icm, enable)
            Log.i(TAG, "IConnectivityManager.setAirplaneMode($enable) via binder")
            true
        }.onFailure {
            Log.w(TAG, "binder IConnectivityManager.setAirplaneMode failed: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Samsung / One UI best-effort without Knox SDK on classpath.
     * Tries Knox Custom SettingsManager.setFlightModeState, SemWifiManager, etc.
     * RestrictionPolicy.allowAirplaneMode only allows user toggle — not used to turn ON.
     */
    private fun samsungSetAirplaneMode(context: Context, enable: Boolean): Boolean {
        var any = false
        // Knox Custom: com.samsung.android.knox.custom.CustomDeviceManager → SettingsManager.setFlightModeState
        runCatching {
            val cdmClass = Class.forName("com.samsung.android.knox.custom.CustomDeviceManager")
            val getInstance = cdmClass.getMethod("getInstance")
            val cdm = getInstance.invoke(null) ?: return@runCatching
            val getSettings = cdmClass.getMethod("getSettingsManager")
            val settingsMgr = getSettings.invoke(cdm) ?: return@runCatching
            // setFlightModeState(int): typically 1=ON, 0=OFF (Knox Custom)
            val state = if (enable) 1 else 0
            val setFlight = settingsMgr.javaClass.methods.firstOrNull {
                it.name == "setFlightModeState" && it.parameterTypes.size == 1
            } ?: return@runCatching
            setFlight.invoke(settingsMgr, state)
            Log.i(TAG, "Knox Custom SettingsManager.setFlightModeState($state)")
            any = true
        }.onFailure {
            Log.i(TAG, "Knox Custom setFlightModeState skip: ${it.message}")
        }

        // SemAirplaneMode / SemWifiManager reflective probes
        for (className in listOf(
            "com.samsung.android.wifi.SemWifiManager",
            "com.samsung.android.knox.restriction.RestrictionPolicy",
            "com.sec.android.app.SecAirplaneMode",
        )) {
            runCatching {
                val clazz = Class.forName(className)
                Log.i(TAG, "Samsung class present: $className methods=${clazz.methods.map { it.name }.distinct().take(12)}")
                // Try setAirplaneMode / setFlightMode / setAirplaneModeOn if instance obtainable
                when {
                    className.endsWith("SemWifiManager") -> {
                        val svc = context.getSystemService("sem_wifi")
                            ?: context.getSystemService("wifi")
                        if (svc != null && svc.javaClass.name.contains("Sem", ignoreCase = true)) {
                            for (name in listOf("setAirplaneMode", "setFlightMode", "setAirplaneModeOn")) {
                                val m = svc.javaClass.methods.firstOrNull {
                                    it.name == name && it.parameterTypes.size == 1
                                } ?: continue
                                m.invoke(svc, enable)
                                Log.i(TAG, "SemWifiManager.$name($enable)")
                                any = true
                                break
                            }
                        }
                    }
                    // RestrictionPolicy.allowAirplaneMode(boolean) — does NOT toggle; skip invoke to avoid license errors
                    className.contains("RestrictionPolicy") -> {
                        Log.i(TAG, "Knox RestrictionPolicy present (allowAirplaneMode only allows toggle; not invoked)")
                    }
                }
            }.onFailure {
                Log.i(TAG, "Samsung probe $className: ${it.message}")
            }
        }

        // Settings.System / Global OEM keys some One UI builds mirror
        runCatching {
            val key = "airplane_mode_on"
            Settings.Global.putInt(context.contentResolver, key, if (enable) 1 else 0)
        }

        return any
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
     * Best-effort radio power via TelephonyManager / ITelephony for all active subscriptions.
     * [radiosOn]=true means radios ON (airplane OFF); false means radios OFF (airplane ON).
     * Documented as partial airplane when true airplane API is blocked.
     */
    private fun telephonySetRadioPowerAllSubs(context: Context, radiosOn: Boolean): Boolean {
        var any = false
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return false

            // Default telephony
            any = invokeSetRadioPower(tm, radiosOn) || any

            // Per-subscription TelephonyManager (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val subIds = runCatching {
                    sm?.activeSubscriptionInfoList?.map { it.subscriptionId } ?: emptyList()
                }.getOrDefault(emptyList())
                Log.i(TAG, "Active subscriptionIds=$subIds")
                for (subId in subIds) {
                    runCatching {
                        val subTm = tm.createForSubscriptionId(subId)
                        if (invokeSetRadioPower(subTm, radiosOn)) any = true
                    }.onFailure {
                        Log.w(TAG, "setRadioPower for subId=$subId failed", it)
                    }
                }
            }

            // ITelephony.setRadioPower via getITelephony
            runCatching {
                val getITelephony = TelephonyManager::class.java.getDeclaredMethod("getITelephony")
                getITelephony.isAccessible = true
                val iTelephony = getITelephony.invoke(tm) ?: return@runCatching
                val setRadio = iTelephony.javaClass.methods.firstOrNull {
                    it.name == "setRadioPower" && it.parameterTypes.size == 1
                } ?: return@runCatching
                setRadio.invoke(iTelephony, radiosOn)
                Log.i(TAG, "ITelephony.setRadioPower($radiosOn)")
                any = true
            }.onFailure {
                Log.w(TAG, "ITelephony.setRadioPower unavailable/failed", it)
            }
        }.onFailure {
            Log.w(TAG, "telephonySetRadioPowerAllSubs failed", it)
        }
        return any
    }

    private fun invokeSetRadioPower(tm: TelephonyManager, radiosOn: Boolean): Boolean {
        return runCatching {
            val m = TelephonyManager::class.java.getMethod(
                "setRadioPower",
                Boolean::class.javaPrimitiveType
            )
            m.invoke(tm, radiosOn)
            Log.i(TAG, "TelephonyManager.setRadioPower($radiosOn)")
            true
        }.onFailure {
            Log.w(TAG, "TelephonyManager.setRadioPower unavailable/failed: ${it.message}")
        }.getOrDefault(false)
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
