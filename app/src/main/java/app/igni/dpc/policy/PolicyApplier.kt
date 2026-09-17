package app.igni.dpc.policy

import android.app.PendingIntent
import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.BuildConfig
import app.igni.dpc.HomeActivity
import app.igni.dpc.UninstallStatusReceiver

data class ApplyResult(
    val success: Boolean,
    val hiddenCount: Int,
    val newlyHidden: Int = 0,
    val uninstallRequested: Int = 0,
    val message: String? = null,
    val screenTimeoutMs: Int? = null,
    val cameraPackages: List<String> = emptyList(),
    val darkMode: DarkModeStatus? = null
)

/**
 * Result of night / dark-mode apply for Admin UI and logs.
 * [result]: success | fail | unsupported | never
 */
data class DarkModeStatus(
    val sdkInt: Int,
    val uiModeManagerAvailable: Boolean,
    val setNightModeActivatedAvailable: Boolean,
    val systemDarkThemeLikely: Boolean,
    val result: String,
    val detail: String
)

/**
 * Idempotent Device Owner policy:
 * - Uninstall **user** apps that are not on the keep / allowlist (frees storage).
 * - Hide **system** apps that are not kept (cannot safely uninstall).
 * - Prefer dock-style [HomeActivity] as HOME via persistent preferred activity.
 * - Apply display defaults: stronger dark mode + 30-minute screen timeout.
 *
 * Lock-task is opt-in via [BuildConfig.ENABLE_LOCK_TASK] (default false).
 */
class PolicyApplier(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
    private val admin = AdminReceiver.componentName(appContext)
    private val store = HiddenStore(appContext)
    private val keep = KeepPackages(appContext)
    private val darkPrefs = appContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(DARK_PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    fun hiddenCount(): Int = store.snapshot().size

    fun hasApplied(): Boolean = store.hasApplied()

    fun allowlistForDisplay(): List<String> = keep.describeKeepReasons()

    /** Detected camera packages for Admin UI verification. */
    fun detectedCameraPackages(): List<String> = keep.detectCameraPackages().sorted()

    /** Current Settings.System.SCREEN_OFF_TIMEOUT, or null if unreadable. */
    fun currentScreenTimeoutMs(): Int? {
        return runCatching {
            Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            )
        }.getOrNull()
    }

    /** Last dark-mode apply status (persisted), or a never-applied snapshot. */
    fun darkModeStatus(): DarkModeStatus {
        val stored = darkPrefs.getString(KEY_DARK_RESULT, null)
        if (stored == null) {
            return probeDarkModeCapabilities(result = "never", detail = "未適用")
        }
        return DarkModeStatus(
            sdkInt = darkPrefs.getInt(KEY_DARK_SDK, Build.VERSION.SDK_INT),
            uiModeManagerAvailable = darkPrefs.getBoolean(KEY_DARK_UIM_OK, true),
            setNightModeActivatedAvailable = darkPrefs.getBoolean(KEY_DARK_ACTIVATED_OK, false),
            systemDarkThemeLikely = darkPrefs.getBoolean(KEY_DARK_LIKELY, Build.VERSION.SDK_INT >= 29),
            result = stored,
            detail = darkPrefs.getString(KEY_DARK_DETAIL, "").orEmpty()
        )
    }

    fun apply(): ApplyResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner; skip apply")
            return ApplyResult(
                success = false,
                hiddenCount = store.snapshot().size,
                message = "not_device_owner",
                cameraPackages = detectedCameraPackages(),
                darkMode = darkModeStatus()
            )
        }

        runCatching { dpm.setUninstallBlocked(admin, appContext.packageName, true) }

        val cameras = keep.detectCameraPackages()
        Log.i(TAG, "Camera keep/unhide set (${cameras.size}): ${cameras.sorted().joinToString()}")

        val hidden = store.mutableCopy()

        // Explicitly unhide every detected camera app and drop it from HiddenStore,
        // even if a previous incomplete static list had hidden it.
        var camerasUnhidden = 0
        for (pkg in cameras) {
            val wasHidden = dpm.isApplicationHidden(admin, pkg)
            val restored = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
            if (hidden.remove(pkg)) {
                Log.i(TAG, "Removed camera package $pkg from HiddenStore")
            }
            if (wasHidden && restored) {
                camerasUnhidden++
                Log.i(TAG, "Unhid camera package $pkg")
            } else if (!wasHidden) {
                Log.i(TAG, "Kept camera package $pkg (already visible)")
            } else {
                Log.w(TAG, "Failed to unhide camera package $pkg (restored=$restored)")
            }
        }
        Log.i(TAG, "Camera unhide pass done: unhidden=$camerasUnhidden kept=${cameras.size}")

        val installed = installedPackageNames()
        val launchable = launchablePackageNames()

        // Safety: never leave keep-list packages hidden.
        for (pkg in installed) {
            if (keep.shouldKeep(pkg) && dpm.isApplicationHidden(admin, pkg)) {
                val restored = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
                if (restored) {
                    hidden.remove(pkg)
                    Log.i(TAG, "Unhid keep-list package $pkg")
                }
            }
        }

        var newlyHidden = 0
        var uninstallRequested = 0
        for (pkg in installed) {
            if (keep.shouldKeep(pkg)) continue
            // Hard guard: never touch this DPC.
            if (pkg == appContext.packageName) continue
            if (pkg in cameras) continue

            if (isSystemOrUpdatedSystemApp(pkg)) {
                // System bloat: hide only (never uninstall). Same launchable scope as prior releases.
                if (pkg !in launchable) continue
                val ok = runCatching {
                    dpm.setApplicationHidden(admin, pkg, true)
                }.onFailure {
                    Log.w(TAG, "Failed to hide system app $pkg", it)
                }.getOrDefault(false)
                if (ok) {
                    if (hidden.add(pkg)) newlyHidden++
                    Log.i(TAG, "Hidden system app $pkg")
                }
            } else {
                // Removable user app: silent uninstall as Device Owner (frees storage).
                if (requestSilentUninstall(pkg)) {
                    uninstallRequested++
                    if (hidden.remove(pkg)) {
                        Log.i(TAG, "Removed uninstalled package $pkg from HiddenStore")
                    }
                }
            }
        }

        // Drop HiddenStore entries for packages that are no longer installed.
        val after = installedPackageNames()
        val gone = hidden.filter { it !in after }.toList()
        for (pkg in gone) {
            hidden.remove(pkg)
            Log.i(TAG, "Pruned gone package $pkg from HiddenStore")
        }

        // Optional kiosk allowlist. Default false.
        if (BuildConfig.ENABLE_LOCK_TASK) {
            val lockTaskPkgs = linkedSetOf(
                "com.android.settings",
                "com.android.vending",
                "com.android.chrome",
                appContext.packageName
            )
            lockTaskPkgs.addAll(cameras)
            runCatching {
                dpm.setLockTaskPackages(admin, lockTaskPkgs.toTypedArray())
            }.onFailure { Log.w(TAG, "setLockTaskPackages failed", it) }
        }

        // Prefer dock HomeActivity as default HOME (after clearing our prior prefs).
        setDedicatedHomePreferred()

        // Display policies: dark mode + 30 min screen timeout (best-effort; never fail apply).
        val dark = applyDarkMode()
        val timeoutMs = applyScreenTimeout()

        store.replace(hidden)
        store.markApplied()
        Log.i(
            TAG,
            "Apply complete hidden=${hidden.size} newlyHidden=$newlyHidden " +
                "uninstallRequested=$uninstallRequested cameras=${cameras.size} " +
                "timeoutMs=$timeoutMs dark=${dark.result}"
        )
        return ApplyResult(
            success = true,
            hiddenCount = hidden.size,
            newlyHidden = newlyHidden,
            uninstallRequested = uninstallRequested,
            screenTimeoutMs = timeoutMs,
            cameraPackages = cameras.sorted(),
            darkMode = dark
        )
    }

    /**
     * Restore packages this DPC has **hidden** (typically system apps).
     * Cannot restore user apps that were uninstalled — those must be reinstalled from Play / APK.
     */
    fun unhideAll(): Int {
        if (!isDeviceOwner()) return 0
        val hidden = store.snapshot()
        var restored = 0
        for (pkg in hidden) {
            val ok = runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                .onFailure { Log.w(TAG, "Failed to unhide $pkg", it) }
                .getOrDefault(false)
            if (ok) restored++
        }
        store.replace(emptySet())
        Log.i(TAG, "Unhid $restored / ${hidden.size} packages")
        return restored
    }

    /**
     * Request silent uninstall via [android.content.pm.PackageInstaller.uninstall] as Device Owner.
     * Returns true if the uninstall request was submitted (not that it already finished).
     */
    private fun requestSilentUninstall(packageName: String): Boolean {
        runCatching { dpm.setUninstallBlocked(admin, packageName, false) }

        return runCatching {
            val statusIntent = Intent(UninstallStatusReceiver.ACTION).apply {
                setPackage(appContext.packageName)
                putExtra(UninstallStatusReceiver.EXTRA_PACKAGE, packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(
                appContext,
                packageName.hashCode(),
                statusIntent,
                flags
            )
            appContext.packageManager.packageInstaller.uninstall(
                packageName,
                pending.intentSender
            )
            Log.i(TAG, "Uninstall requested for user app $packageName")
            true
        }.onFailure {
            Log.w(TAG, "Failed to request uninstall for $packageName", it)
        }.getOrDefault(false)
    }

    private fun isSystemOrUpdatedSystemApp(packageName: String): Boolean {
        return runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(true) // treat unknown as system — safer than uninstalling
    }

    private fun launchablePackageNames(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = appContext.packageManager.queryIntentActivities(intent, MATCH_FLAGS)
        return infos.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    /** Currently installed packages (excludes residual uninstalled-with-data entries). */
    private fun installedPackageNames(): Set<String> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
        val apps = appContext.packageManager.getInstalledApplications(flags)
        return apps.map { it.packageName }.toSet()
    }

    /**
     * Stronger system-wide night mode for OEM devices (e.g. Sharp AQUOS sense3 on 9/10).
     * Failures are logged; apply() still succeeds. Result is persisted for Admin UI.
     */
    private fun applyDarkMode(): DarkModeStatus {
        val sdk = Build.VERSION.SDK_INT
        val uiMode = appContext.getSystemService(UiModeManager::class.java)
        val uiModeOk = uiMode != null
        val activatedApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                true
            }.getOrDefault(false)
        val likely = sdk >= 29 // Android 10+: system dark theme

        val notes = mutableListOf<String>()
        var anySuccess = false

        runCatching {
            uiMode?.setNightMode(UiModeManager.MODE_NIGHT_YES)
            anySuccess = true
            notes += "setNightMode=ok"
            Log.i(TAG, "UiModeManager.setNightMode(MODE_NIGHT_YES)")
        }.onFailure {
            notes += "setNightMode=fail"
            Log.w(TAG, "setNightMode failed", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiMode != null) {
            runCatching {
                val m = UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(uiMode, true)
                anySuccess = true
                notes += "setNightModeActivated=ok"
                Log.i(TAG, "UiModeManager.setNightModeActivated(true)")
            }.onFailure {
                notes += "setNightModeActivated=fail"
                Log.w(TAG, "setNightModeActivated failed", it)
            }
        }

        // Settings.Secure ui_night_mode = 2 (MODE_NIGHT_YES). Key is @hide.
        if (putSecureInt(SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES)) {
            anySuccess = true
            notes += "secure.ui_night_mode=2"
        } else {
            notes += "secure.ui_night_mode=fail"
        }

        // Additional OEM-safe Secure / System keys used by various skins (best-effort).
        // Values: dark_theme often 1=on; night_mode / ui_night_mode often 2=yes.
        val oemAttempts = listOf(
            SettingAttempt("secure", "dark_theme", 1),
            SettingAttempt("system", "dark_theme", 1),
            SettingAttempt("secure", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "ui_night_mode", MODE_NIGHT_YES),
            SettingAttempt("global", "ui_night_mode", MODE_NIGHT_YES),
        )
        for (attempt in oemAttempts) {
            val ok = when (attempt.table) {
                "secure" -> putSecureInt(attempt.key, attempt.value)
                "system" -> putSystemInt(attempt.key, attempt.value)
                "global" -> putGlobalInt(attempt.key, attempt.value)
                else -> false
            }
            if (ok) {
                anySuccess = true
                notes += "${attempt.table}.${attempt.key}=${attempt.value}"
            }
        }

        // Trigger a configuration refresh when possible (UiModeManager already does on many OEMs).
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && uiMode != null) {
                // Reading night mode forces some implementations to reconcile.
                val current = uiMode.nightMode
                Log.i(TAG, "UiModeManager.nightMode read-back=$current")
                notes += "nightModeRead=$current"
            }
        }.onFailure { Log.w(TAG, "nightMode read-back failed", it) }

        // Soft broadcast used by some OEM overlays (harmless if ignored).
        runCatching {
            val intent = Intent("android.intent.action.NIGHT_MODE_CHANGED")
                .putExtra("night_mode", MODE_NIGHT_YES)
                .setPackage(null)
            appContext.sendBroadcast(intent)
            notes += "broadcast.NIGHT_MODE_CHANGED"
        }.onFailure { Log.w(TAG, "NIGHT_MODE_CHANGED broadcast failed", it) }

        val result = when {
            !likely && !anySuccess -> "unsupported"
            !likely && anySuccess -> "success" // best-effort on API < 29
            anySuccess -> "success"
            else -> "fail"
        }
        if (!likely) {
            notes += "sdk<29 system_dark_may_be_unavailable"
        }

        val status = DarkModeStatus(
            sdkInt = sdk,
            uiModeManagerAvailable = uiModeOk,
            setNightModeActivatedAvailable = activatedApi,
            systemDarkThemeLikely = likely,
            result = result,
            detail = notes.joinToString("; ")
        )
        persistDarkModeStatus(status)
        Log.i(TAG, "Dark mode apply result=$result detail=${status.detail}")
        return status
    }

    private data class SettingAttempt(val table: String, val key: String, val value: Int)

    private fun putSecureInt(key: String, value: Int): Boolean {
        return runCatching {
            val ok = Settings.Secure.putInt(appContext.contentResolver, key, value)
            Log.i(TAG, "Settings.Secure.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.Secure.$key failed", it) }.getOrDefault(false)
    }

    private fun putSystemInt(key: String, value: Int): Boolean {
        return runCatching {
            val ok = Settings.System.putInt(appContext.contentResolver, key, value)
            Log.i(TAG, "Settings.System.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.System.$key failed", it) }.getOrDefault(false)
    }

    private fun putGlobalInt(key: String, value: Int): Boolean {
        return runCatching {
            val ok = Settings.Global.putInt(appContext.contentResolver, key, value)
            Log.i(TAG, "Settings.Global.$key=$value put=$ok")
            ok
        }.onFailure { Log.w(TAG, "Settings.Global.$key failed", it) }.getOrDefault(false)
    }

    private fun persistDarkModeStatus(status: DarkModeStatus) {
        darkPrefs.edit()
            .putInt(KEY_DARK_SDK, status.sdkInt)
            .putBoolean(KEY_DARK_UIM_OK, status.uiModeManagerAvailable)
            .putBoolean(KEY_DARK_ACTIVATED_OK, status.setNightModeActivatedAvailable)
            .putBoolean(KEY_DARK_LIKELY, status.systemDarkThemeLikely)
            .putString(KEY_DARK_RESULT, status.result)
            .putString(KEY_DARK_DETAIL, status.detail)
            .apply()
    }

    private fun probeDarkModeCapabilities(result: String, detail: String): DarkModeStatus {
        val sdk = Build.VERSION.SDK_INT
        val uiMode = appContext.getSystemService(UiModeManager::class.java)
        val activatedApi = sdk >= Build.VERSION_CODES.R &&
            runCatching {
                UiModeManager::class.java.getMethod(
                    "setNightModeActivated",
                    Boolean::class.javaPrimitiveType
                )
                true
            }.getOrDefault(false)
        return DarkModeStatus(
            sdkInt = sdk,
            uiModeManagerAvailable = uiMode != null,
            setNightModeActivatedAvailable = activatedApi,
            systemDarkThemeLikely = sdk >= 29,
            result = result,
            detail = detail
        )
    }

    /**
     * Screen-off timeout 30 minutes.
     *
     * Prefer [Settings.System.SCREEN_OFF_TIMEOUT] sticking (put + read-back). Also try
     * reflective [DevicePolicyManager.setSystemSetting] (DO SystemApi) when present.
     *
     * @return actual SCREEN_OFF_TIMEOUT after writes, or null if unreadable.
     */
    private fun applyScreenTimeout(): Int? {
        runCatching {
            val ok = Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                SCREEN_OFF_TIMEOUT_MS
            )
            Log.i(TAG, "SCREEN_OFF_TIMEOUT put=$ok target=${SCREEN_OFF_TIMEOUT_MS}ms")
        }.onFailure { Log.w(TAG, "SCREEN_OFF_TIMEOUT putInt failed", it) }

        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_OFF_TIMEOUT_MS.toString())
            Log.i(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT, $SCREEN_OFF_TIMEOUT_MS)")
        }.onFailure { Log.w(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT) unavailable/failed", it) }

        runCatching {
            dpm.setMaximumTimeToLock(admin, SCREEN_OFF_TIMEOUT_MS.toLong())
            Log.i(TAG, "setMaximumTimeToLock(${SCREEN_OFF_TIMEOUT_MS}ms)")
        }.onFailure { Log.w(TAG, "setMaximumTimeToLock failed", it) }

        val actual = currentScreenTimeoutMs()
        Log.i(TAG, "SCREEN_OFF_TIMEOUT read-back=${actual}ms (want ${SCREEN_OFF_TIMEOUT_MS})")
        return actual
    }

    /**
     * Make [HomeActivity] the default HOME via Device Owner persistent preferred activity.
     * Clears our package's prior prefs first, then sets Home again.
     */
    private fun setDedicatedHomePreferred() {
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${appContext.packageName}")
        }.onFailure {
            Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it)
        }

        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val homeComponent = ComponentName(appContext, HomeActivity::class.java)
        runCatching {
            dpm.addPersistentPreferredActivity(admin, homeFilter, homeComponent)
            Log.i(TAG, "Preferred HOME set to $homeComponent")
        }.onFailure {
            Log.w(TAG, "addPersistentPreferredActivity failed", it)
        }
    }

    companion object {
        private const val TAG = "IgniPolicy"
        private const val MODE_NIGHT_YES = UiModeManager.MODE_NIGHT_YES
        /** @hide Settings.Secure.UI_NIGHT_MODE */
        private const val SECURE_UI_NIGHT_MODE = "ui_night_mode"
        /** 30 minutes in milliseconds. */
        const val SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_ALL

        private const val DARK_PREFS = "igni_dark_mode"
        private const val KEY_DARK_SDK = "sdk"
        private const val KEY_DARK_UIM_OK = "uim_ok"
        private const val KEY_DARK_ACTIVATED_OK = "activated_ok"
        private const val KEY_DARK_LIKELY = "likely"
        private const val KEY_DARK_RESULT = "result"
        private const val KEY_DARK_DETAIL = "detail"
    }
}
