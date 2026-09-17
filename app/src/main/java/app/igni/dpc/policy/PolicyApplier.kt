package app.igni.dpc.policy

import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.BuildConfig
import app.igni.dpc.HomeActivity

data class ApplyResult(
    val success: Boolean,
    val hiddenCount: Int,
    val newlyHidden: Int = 0,
    val message: String? = null
)

/**
 * Idempotent Device Owner policy: hide launchable apps except the product allowlist
 * and a safety keep-list. Hide is preferred over uninstall.
 *
 * Also applies display defaults: system dark mode ON and screen-off timeout 30 minutes.
 *
 * Lock-task is opt-in via [BuildConfig.ENABLE_LOCK_TASK] (default false).
 */
class PolicyApplier(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
    private val admin = AdminReceiver.componentName(appContext)
    private val store = HiddenStore(appContext)
    private val keep = KeepPackages(appContext)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    fun hiddenCount(): Int = store.snapshot().size

    fun hasApplied(): Boolean = store.hasApplied()

    fun allowlistForDisplay(): List<String> = keep.describeKeepReasons()

    fun apply(): ApplyResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner; skip apply")
            return ApplyResult(success = false, hiddenCount = store.snapshot().size, message = "not_device_owner")
        }

        runCatching { dpm.setUninstallBlocked(admin, appContext.packageName, true) }

        // Safety: never leave keep-list packages hidden.
        for (pkg in installedPackageNames()) {
            if (keep.shouldKeep(pkg) && dpm.isApplicationHidden(admin, pkg)) {
                val restored = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
                if (restored) {
                    Log.i(TAG, "Unhid keep-list package $pkg")
                }
            }
        }

        val hidden = store.mutableCopy()
        var newlyHidden = 0
        for (pkg in launchablePackageNames()) {
            if (keep.shouldKeep(pkg)) continue
            val ok = runCatching {
                dpm.setApplicationHidden(admin, pkg, true)
            }.onFailure {
                Log.w(TAG, "Failed to hide $pkg", it)
            }.getOrDefault(false)
            if (ok) {
                if (hidden.add(pkg)) newlyHidden++
                Log.i(TAG, "Hidden $pkg")
            }
        }

        // Optional kiosk allowlist. Default false — hide-only matches “ホームに設定とPlayだけ”.
        if (BuildConfig.ENABLE_LOCK_TASK) {
            runCatching {
                dpm.setLockTaskPackages(
                    admin,
                    arrayOf(
                        "com.android.settings",
                        "com.android.vending",
                        "com.android.chrome",
                        "com.android.camera2",
                        "com.android.camera",
                        "com.google.android.GoogleCamera",
                        appContext.packageName
                    )
                )
            }.onFailure { Log.w(TAG, "setLockTaskPackages failed", it) }
        }


        // Make HomeActivity the default HOME so Settings/Play/Camera/Chrome tiles show
        // without relying on the OEM launcher layout (which often omits Settings).
        setDedicatedHomePreferred()

        // Display policies: dark mode + 30 min screen timeout (best-effort; never fail apply).
        applyDarkMode()
        applyScreenTimeout()

        store.replace(hidden)
        store.markApplied()
        Log.i(TAG, "Apply complete hidden=${hidden.size} newlyHidden=$newlyHidden")
        return ApplyResult(success = true, hiddenCount = hidden.size, newlyHidden = newlyHidden)
    }

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

    private fun launchablePackageNames(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = appContext.packageManager.queryIntentActivities(intent, MATCH_FLAGS)
        return infos.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun installedPackageNames(): Set<String> {
        val apps = appContext.packageManager.getInstalledApplications(MATCH_FLAGS)
        return apps.map { it.packageName }.toSet()
    }

    /** System-wide night mode. Failures are logged; apply() still succeeds. */
    private fun applyDarkMode() {
        val uiMode = appContext.getSystemService(UiModeManager::class.java)

        runCatching {
            uiMode?.setNightMode(UiModeManager.MODE_NIGHT_YES)
            Log.i(TAG, "UiModeManager.setNightMode(MODE_NIGHT_YES)")
        }.onFailure { Log.w(TAG, "setNightMode failed", it) }

        // setNightModeActivated is public on device (API 30+) but missing from some SDK stubs;
        // invoke reflectively so we still prefer it when present.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiMode != null) {
            runCatching {
                val m = UiModeManager::class.java.getMethod("setNightModeActivated", Boolean::class.javaPrimitiveType)
                m.invoke(uiMode, true)
                Log.i(TAG, "UiModeManager.setNightModeActivated(true)")
            }.onFailure { Log.w(TAG, "setNightModeActivated failed", it) }
        }

        // Fallback / reinforce via Settings.Secure (key is @hide; use literal).
        runCatching {
            val ok = Settings.Secure.putInt(
                appContext.contentResolver,
                SECURE_UI_NIGHT_MODE,
                UiModeManager.MODE_NIGHT_YES
            )
            Log.i(TAG, "Settings.Secure.$SECURE_UI_NIGHT_MODE=$MODE_NIGHT_YES put=$ok")
        }.onFailure { Log.w(TAG, "Settings.Secure.$SECURE_UI_NIGHT_MODE failed", it) }
    }

    /** Screen-off timeout 30 minutes. Also set DPM max time-to-lock as a complementary ceiling. */
    private fun applyScreenTimeout() {
        runCatching {
            val ok = Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                SCREEN_OFF_TIMEOUT_MS
            )
            Log.i(TAG, "SCREEN_OFF_TIMEOUT=${SCREEN_OFF_TIMEOUT_MS}ms put=$ok")
        }.onFailure { Log.w(TAG, "SCREEN_OFF_TIMEOUT failed", it) }

        runCatching {
            dpm.setMaximumTimeToLock(admin, SCREEN_OFF_TIMEOUT_MS.toLong())
            Log.i(TAG, "setMaximumTimeToLock(${SCREEN_OFF_TIMEOUT_MS}ms)")
        }.onFailure { Log.w(TAG, "setMaximumTimeToLock failed", it) }
    }

    private fun setDedicatedHomePreferred() {
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val homeComponent = ComponentName(appContext, HomeActivity::class.java)

        // Best-effort: clear prior preferred HOME activities for known OEM launchers.
        val launcherPkgs = listOf(
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.android.systemui", // some devices bind HOME oddly
            appContext.packageName,
        )
        for (pkg in launcherPkgs) {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
                .onFailure { Log.w(TAG, "clearPackagePersistentPreferredActivities($pkg) failed", it) }
        }

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
        private const val SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_ALL
    }
}
