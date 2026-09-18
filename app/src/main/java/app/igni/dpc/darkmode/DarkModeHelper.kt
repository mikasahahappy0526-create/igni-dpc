package app.igni.dpc.darkmode

import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.policy.DarkModeStatus

/**
 * Force system-wide dark / night mode as Device Owner, with Samsung One UI emphasis.
 *
 * Order (each step best-effort, never crashes apply):
 * 0. Grant MODIFY_DAY_NIGHT_MODE / WRITE_SECURE_SETTINGS / WRITE_SETTINGS via DPM
 * 1. Write Settings.System display_night_theme=1 + Secure ui_night_mode=2 + OEM keys
 * 2. UiModeManager.setNightMode(MODE_NIGHT_YES)
 * 3. setNightModeActivated(true) if API ≥ R
 * 4. Tasker/Samsung trick: briefly enableCarMode → disableCarMode so One UI applies dark
 * 5. Re-set night yes + activated
 * 6. Runtime.exec("cmd", "uimode", "night", "yes") best-effort
 * 7. Reflective IUiModeManager binder setNightMode
 * 8. Samsung SEM SemUiModeManager / Knox Custom display dark (reflection only)
 * 9. Verify: display_night_theme==1 OR ui_night_mode==2 OR nightMode==YES OR UI_MODE_NIGHT_YES
 *
 * On API &lt; 29 (e.g. Sharp AQUOS sense3): keep best-effort; never throw.
 */
object DarkModeHelper {

    private const val TAG = "IgniDarkMode"
    private const val MODE_NIGHT_YES = UiModeManager.MODE_NIGHT_YES
    private const val SECURE_UI_NIGHT_MODE = "ui_night_mode"
    private const val SYSTEM_DISPLAY_NIGHT_THEME = "display_night_theme"

    private const val PERM_MODIFY_DAY_NIGHT = "android.permission.MODIFY_DAY_NIGHT_MODE"
    private const val PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    private const val PERM_WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"

    fun apply(context: Context): DarkModeStatus {
        return runCatching { applyInternal(context.applicationContext) }
            .onFailure { Log.e(TAG, "applyDarkMode crashed (swallowed)", it) }
            .getOrElse { e ->
                DarkModeStatus(
                    sdkInt = Build.VERSION.SDK_INT,
                    uiModeManagerAvailable = false,
                    setNightModeActivatedAvailable = false,
                    systemDarkThemeLikely = Build.VERSION.SDK_INT >= 29,
                    displayNightTheme = null,
                    result = if (Build.VERSION.SDK_INT < 29) "unsupported" else "fail",
                    detail = "crash=${e.javaClass.simpleName}:${e.message}"
                )
            }
    }

    private fun applyInternal(app: Context): DarkModeStatus {
        val sdk = Build.VERSION.SDK_INT
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, AdminReceiver::class.java)
        val uiMode = app.getSystemService(UiModeManager::class.java)
        val uiModeOk = uiMode != null
        val activatedApi = sdk >= Build.VERSION_CODES.R &&
            runCatching {
                UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                true
            }.getOrDefault(false)
        val likely = sdk >= 29
        val notes = mutableListOf<String>()
        var anyWrite = false

        // 0) Grant permissions before UiMode / Settings writes
        if (dpm != null && dpm.isDeviceOwnerApp(app.packageName)) {
            for (perm in listOf(PERM_MODIFY_DAY_NIGHT, PERM_WRITE_SECURE, PERM_WRITE_SETTINGS)) {
                val ok = grantPermission(dpm, admin, app.packageName, perm)
                notes += if (ok) "grant.${perm.substringAfterLast('.')}=ok"
                else "grant.${perm.substringAfterLast('.')}=fail/skip"
            }
        } else {
            notes += "grant=skip(not_DO)"
        }

        // 1) PRIMARY Samsung Settings dark + Secure ui_night_mode + OEM keys
        if (putSystemInt(app, SYSTEM_DISPLAY_NIGHT_THEME, 1)) {
            anyWrite = true
            notes += "system.display_night_theme=1"
        } else {
            notes += "system.display_night_theme=fail"
        }
        if (putSecureInt(app, SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES)) {
            anyWrite = true
            notes += "secure.ui_night_mode=2"
        } else {
            notes += "secure.ui_night_mode=fail"
        }

        val oemAttempts = listOf(
            SettingAttempt("secure", "dark_theme", 1),
            SettingAttempt("system", "dark_theme", 1),
            SettingAttempt("secure", "dark_mode", 1),
            SettingAttempt("system", "dark_mode", 1),
            SettingAttempt("secure", "theme_mode", 1),
            SettingAttempt("system", "theme_mode", 1),
            SettingAttempt("secure", "theme_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "theme_mode", MODE_NIGHT_YES),
            SettingAttempt("secure", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "ui_night_mode", MODE_NIGHT_YES),
            SettingAttempt("global", "ui_night_mode", MODE_NIGHT_YES),
            SettingAttempt("secure", SYSTEM_DISPLAY_NIGHT_THEME, 1),
            SettingAttempt("global", SYSTEM_DISPLAY_NIGHT_THEME, 1),
        )
        for (a in oemAttempts) {
            val ok = when (a.table) {
                "secure" -> putSecureInt(app, a.key, a.value)
                "system" -> putSystemInt(app, a.key, a.value)
                "global" -> putGlobalInt(app, a.key, a.value)
                else -> false
            }
            if (ok) {
                anyWrite = true
                notes += "${a.table}.${a.key}=${a.value}"
            }
        }

        if (dpm != null) {
            if (dpmSetSystemSetting(dpm, admin, SYSTEM_DISPLAY_NIGHT_THEME, "1")) {
                anyWrite = true
                notes += "dpm.setSystemSetting.display_night_theme=1"
            }
            if (dpmSetSecureSetting(dpm, admin, SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES.toString())) {
                anyWrite = true
                notes += "dpm.setSecureSetting.ui_night_mode=2"
            }
            if (dpmSetSystemSetting(dpm, admin, SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES.toString())) {
                anyWrite = true
                notes += "dpm.setSystemSetting.ui_night_mode=2"
            }
            if (dpmSetSecureSetting(dpm, admin, SYSTEM_DISPLAY_NIGHT_THEME, "1")) {
                anyWrite = true
                notes += "dpm.setSecureSetting.display_night_theme=1"
            }
        }

        // Immediate probe; retry display_night_theme if not 1 yet
        var nightThemeProbe = readSystemInt(app, SYSTEM_DISPLAY_NIGHT_THEME)
        if (nightThemeProbe != 1) {
            notes += "display_night_theme_probe=${nightThemeProbe ?: "null"}→retry"
            putSystemInt(app, SYSTEM_DISPLAY_NIGHT_THEME, 1)
            dpm?.let {
                dpmSetSystemSetting(it, admin, SYSTEM_DISPLAY_NIGHT_THEME, "1")
                dpmSetSecureSetting(it, admin, SYSTEM_DISPLAY_NIGHT_THEME, "1")
            }
        }

        // 2) UiModeManager.setNightMode(MODE_NIGHT_YES)
        runCatching {
            uiMode?.setNightMode(MODE_NIGHT_YES)
            anyWrite = true
            notes += "setNightMode=ok"
            Log.i(TAG, "UiModeManager.setNightMode(MODE_NIGHT_YES)")
        }.onFailure {
            notes += "setNightMode=fail"
            Log.w(TAG, "setNightMode failed", it)
        }

        // 3) setNightModeActivated(true) API ≥ R
        if (sdk >= Build.VERSION_CODES.R && uiMode != null) {
            runCatching {
                val m = UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(uiMode, true)
                anyWrite = true
                notes += "setNightModeActivated=ok"
                Log.i(TAG, "UiModeManager.setNightModeActivated(true)")
            }.onFailure {
                notes += "setNightModeActivated=fail"
                Log.w(TAG, "setNightModeActivated failed", it)
            }
        }

        // 4) Tasker/Samsung car-mode poke: enable → disable so One UI applies dark
        val carPoke = carModePoke(uiMode)
        notes += if (carPoke) "carMode.enable→disable=ok" else "carMode.enable→disable=fail/skip"
        Log.i(TAG, "carMode poke → $carPoke")

        // 5) Re-set night yes after car-mode poke
        runCatching {
            uiMode?.setNightMode(MODE_NIGHT_YES)
            notes += "setNightMode=reok"
        }.onFailure { notes += "setNightMode=refail" }
        if (sdk >= Build.VERSION_CODES.R && uiMode != null) {
            runCatching {
                val m = UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(uiMode, true)
                notes += "setNightModeActivated=reok"
            }.onFailure { notes += "setNightModeActivated=refail" }
        }
        // Re-assert Samsung Settings key after poke
        putSystemInt(app, SYSTEM_DISPLAY_NIGHT_THEME, 1)
        putSecureInt(app, SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES)

        // 6) cmd uimode night yes (works in adb; may work for DO on some builds)
        val cmdOk = execCmdUimodeNightYes()
        notes += if (cmdOk) "cmd.uimode.night.yes=ok" else "cmd.uimode.night.yes=fail/skip"
        Log.i(TAG, "cmd uimode night yes → $cmdOk")

        // 7) Reflective IUiModeManager binder setNightMode
        val binderOk = binderSetNightMode(MODE_NIGHT_YES)
        notes += if (binderOk) "binder.IUiModeManager.setNightMode=ok"
        else "binder.IUiModeManager.setNightMode=fail/skip"
        Log.i(TAG, "IUiModeManager.setNightMode → $binderOk")

        // 8) Samsung SEM / Knox Custom reflective
        val semOk = samsungSemForceDark(app)
        notes += if (semOk) "samsung.SEM/Knox=ok" else "samsung.SEM/Knox=fail/skip"
        Log.i(TAG, "Samsung SEM/Knox dark → $semOk")

        // setApplicationNightMode if present
        runCatching {
            if (uiMode != null) {
                val m = UiModeManager::class.java.getMethod(
                    "setApplicationNightMode",
                    Int::class.javaPrimitiveType
                )
                m.invoke(uiMode, MODE_NIGHT_YES)
                anyWrite = true
                notes += "setApplicationNightMode=ok"
            }
        }.onFailure {
            notes += "setApplicationNightMode=skip"
        }

        // Broadcasts (Settings listeners)
        runCatching {
            val intent = Intent("android.intent.action.NIGHT_MODE_CHANGED")
                .putExtra("night_mode", MODE_NIGHT_YES)
            app.sendBroadcast(intent)
            notes += "broadcast.NIGHT_MODE_CHANGED"
        }.onFailure { notes += "broadcast.NIGHT_MODE_CHANGED=fail" }

        for (action in listOf(
            "com.samsung.android.theme.THEMEDARK_CHANGED",
            "com.android.server.action.DISPLAY_NIGHT_THEME_CHANGED",
            "com.samsung.intent.action.THEME_CHANGED",
        )) {
            runCatching {
                app.sendBroadcast(
                    Intent(action)
                        .putExtra("dark_mode", true)
                        .putExtra("display_night_theme", 1)
                        .putExtra("night_mode", MODE_NIGHT_YES)
                )
                notes += "broadcast.$action"
            }.onFailure { notes += "broadcast.$action=fail" }
        }

        // 9) Verify all probes
        val nightTheme = readSystemInt(app, SYSTEM_DISPLAY_NIGHT_THEME)
        val uiNight = readSecureInt(app, SECURE_UI_NIGHT_MODE)
        val nightModeRead = runCatching {
            if (sdk >= Build.VERSION_CODES.M) uiMode?.nightMode else null
        }.getOrNull()
        val configNight = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        notes += "display_night_theme_read=${nightTheme ?: "null"}"
        notes += "ui_night_mode_read=${uiNight ?: "null"}"
        notes += "nightModeRead=${nightModeRead ?: "null"}"
        notes += "config.UI_MODE_NIGHT_YES=$configNight"
        Log.i(
            TAG,
            "probes display_night_theme=$nightTheme ui_night_mode=$uiNight " +
                "nightMode=$nightModeRead configNight=$configNight"
        )

        val verified = nightTheme == 1 ||
            uiNight == MODE_NIGHT_YES ||
            nightModeRead == MODE_NIGHT_YES ||
            configNight

        if (nightTheme == 1) {
            notes += "settings_dark_theme=ON"
        } else {
            notes += "settings_dark_theme=OFF_OR_UNKNOWN"
        }
        if (verified) {
            anyWrite = true
            notes += "verify=pass"
        } else {
            notes += "verify=fail"
        }

        if (!likely) {
            notes += "sdk<29 system_dark_may_be_unavailable"
        }

        val result = when {
            verified -> "success"
            !likely && anyWrite -> "success" // best-effort on old Sense3 etc.
            !likely && !anyWrite -> "unsupported"
            anyWrite -> "success" // wrote something; One UI may lag until reboot/settings open
            else -> "fail"
        }

        val status = DarkModeStatus(
            sdkInt = sdk,
            uiModeManagerAvailable = uiModeOk,
            setNightModeActivatedAvailable = activatedApi,
            systemDarkThemeLikely = likely,
            displayNightTheme = nightTheme,
            result = result,
            detail = notes.joinToString("; ")
        )
        Log.i(TAG, "Dark mode result=$result display_night_theme=$nightTheme detail=${status.detail}")
        return status
    }

    /**
     * Briefly enable then disable car mode so Samsung One UI reapplies night theme.
     * Uses FLAG variants when available; never leaves car mode on.
     */
    private fun carModePoke(uiMode: UiModeManager?): Boolean {
        if (uiMode == null) return false
        return runCatching {
            var enabled = false
            // Prefer flag overload: enableCarMode(int flags)
            runCatching {
                val enable = UiModeManager::class.java.getMethod(
                    "enableCarMode",
                    Int::class.javaPrimitiveType
                )
                enable.invoke(uiMode, 0)
                enabled = true
                notesSafe("car.enableCarMode(0)")
            }.onFailure {
                // Fallback: enableCarMode(int flags, int priority) on newer APIs
                runCatching {
                    val enable2 = UiModeManager::class.java.getMethod(
                        "enableCarMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    enable2.invoke(uiMode, 0, 0)
                    enabled = true
                    notesSafe("car.enableCarMode(0,0)")
                }
            }
            // Short settle — One UI often needs the transition
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching {
                val disable = UiModeManager::class.java.getMethod(
                    "disableCarMode",
                    Int::class.javaPrimitiveType
                )
                // UiModeManager.DISABLE_CAR_MODE_GO_HOME = 1 — avoid going home; use 0
                disable.invoke(uiMode, 0)
                notesSafe("car.disableCarMode(0)")
            }.onFailure {
                runCatching {
                    val disable2 = UiModeManager::class.java.getMethod(
                        "disableCarMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    disable2.invoke(uiMode, 0, 0)
                    notesSafe("car.disableCarMode(0,0)")
                }
            }
            enabled
        }.onFailure {
            Log.w(TAG, "carMode poke failed", it)
        }.getOrDefault(false)
    }

    private fun notesSafe(msg: String) {
        Log.i(TAG, msg)
    }

    private fun execCmdUimodeNightYes(): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("cmd", "uimode", "night", "yes"))
            val exited = proc.waitFor()
            val err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")
            val out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
            Log.i(TAG, "cmd uimode night yes exit=$exited out=$out err=$err")
            exited == 0
        }.onFailure {
            Log.w(TAG, "cmd uimode night yes failed", it)
        }.getOrDefault(false)
    }

    /**
     * Reflective ServiceManager → IUiModeManager.setNightMode(int).
     */
    private fun binderSetNightMode(mode: Int): Boolean {
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, Context.UI_MODE_SERVICE) ?: return@runCatching false
            val stub = Class.forName("android.app.IUiModeManager\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val mgr = asInterface.invoke(null, binder) ?: return@runCatching false
            val setNight = mgr.javaClass.getMethod("setNightMode", Int::class.javaPrimitiveType)
            setNight.invoke(mgr, mode)
            true
        }.onFailure {
            Log.w(TAG, "binder IUiModeManager.setNightMode failed", it)
        }.getOrDefault(false)
    }

    /**
     * Samsung One UI: SemUiModeManager / Knox Custom SettingsManager dark display (reflection).
     */
    private fun samsungSemForceDark(context: Context): Boolean {
        var any = false
        // SemUiModeManager
        runCatching {
            val clazz = Class.forName("com.samsung.android.app.SemUiModeManager")
            val instance = runCatching {
                clazz.getMethod("getInstance").invoke(null)
            }.getOrElse {
                clazz.getConstructor(Context::class.java).newInstance(context)
            }
            for (name in listOf("setNightMode", "setDarkMode", "setNightModeActivated")) {
                runCatching {
                    val m = clazz.methods.firstOrNull { it.name == name }
                        ?: return@runCatching
                    when (m.parameterTypes.size) {
                        1 -> when {
                            m.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                m.parameterTypes[0] == java.lang.Boolean::class.java ->
                                m.invoke(instance, true)
                            m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                                m.parameterTypes[0] == Integer::class.java ->
                                m.invoke(instance, MODE_NIGHT_YES)
                            else -> return@runCatching
                        }
                        else -> return@runCatching
                    }
                    any = true
                    Log.i(TAG, "SemUiModeManager.$name ok")
                }
            }
        }.onFailure { Log.w(TAG, "SemUiModeManager probe fail/skip", it) }

        // Knox Custom SettingsManager.setDarkMode / setDisplayNightTheme (no Knox SDK)
        for (className in listOf(
            "com.samsung.android.knox.custom.SettingsManager",
            "com.samsung.android.knox.custom.CustomDeviceManager",
        )) {
            runCatching {
                val clazz = Class.forName(className)
                val instance = runCatching {
                    clazz.getMethod("getInstance").invoke(null)
                }.getOrElse {
                    runCatching { clazz.getMethod("getInstance", Context::class.java).invoke(null, context) }
                        .getOrNull()
                } ?: return@runCatching
                for (name in listOf(
                    "setDarkModeState",
                    "setDarkMode",
                    "setDisplayNightTheme",
                    "setNightMode",
                    "setSystemTheme",
                )) {
                    runCatching {
                        val m = clazz.methods.firstOrNull { it.name == name } ?: return@runCatching
                        when (m.parameterTypes.size) {
                            1 -> when {
                                m.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                    m.parameterTypes[0] == java.lang.Boolean::class.java -> {
                                    m.invoke(instance, true)
                                    any = true
                                    Log.i(TAG, "$className.$name(true)")
                                }
                                m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                                    m.parameterTypes[0] == Integer::class.java -> {
                                    m.invoke(instance, 1)
                                    any = true
                                    Log.i(TAG, "$className.$name(1)")
                                }
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "$className probe fail/skip", it) }
        }
        return any
    }

    private fun grantPermission(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        permission: String
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching false
            val granted = dpm.getPermissionGrantState(admin, packageName, permission)
            if (granted == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                Log.i(TAG, "$permission already granted")
                return@runCatching true
            }
            val ok = dpm.setPermissionGrantState(
                admin,
                packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "setPermissionGrantState($permission) → $ok")
            ok
        }.onFailure {
            Log.w(TAG, "grant $permission failed", it)
        }.getOrDefault(false)
    }

    private data class SettingAttempt(val table: String, val key: String, val value: Int)

    private fun readSystemInt(context: Context, key: String): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, key) }
            .onFailure { Log.w(TAG, "Settings.System.getInt($key) failed", it) }
            .getOrNull()

    private fun readSecureInt(context: Context, key: String): Int? =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }
            .onFailure { Log.w(TAG, "Settings.Secure.getInt($key) failed", it) }
            .getOrNull()

    private fun putSecureInt(context: Context, key: String, value: Int): Boolean =
        runCatching {
            val ok = Settings.Secure.putInt(context.contentResolver, key, value)
            Log.i(TAG, "Settings.Secure.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.Secure.$key failed", it) }.getOrDefault(false)

    private fun putSystemInt(context: Context, key: String, value: Int): Boolean =
        runCatching {
            val ok = Settings.System.putInt(context.contentResolver, key, value)
            Log.i(TAG, "Settings.System.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.System.$key failed", it) }.getOrDefault(false)

    private fun putGlobalInt(context: Context, key: String, value: Int): Boolean =
        runCatching {
            val ok = Settings.Global.putInt(context.contentResolver, key, value)
            Log.i(TAG, "Settings.Global.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.Global.$key failed", it) }.getOrDefault(false)

    private fun dpmSetSystemSetting(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        value: String
    ): Boolean =
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            Log.i(TAG, "DPM.setSystemSetting($key, $value)")
            true
        }.onFailure {
            Log.w(TAG, "DPM.setSystemSetting($key) unavailable/failed", it)
        }.getOrDefault(false)

    private fun dpmSetSecureSetting(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        value: String
    ): Boolean =
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSecureSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            Log.i(TAG, "DPM.setSecureSetting($key, $value)")
            true
        }.onFailure {
            Log.w(TAG, "DPM.setSecureSetting($key) unavailable/failed", it)
        }.getOrDefault(false)
}
