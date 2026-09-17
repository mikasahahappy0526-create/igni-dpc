package app.igni.dpc.policy

import android.app.PendingIntent
import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.BuildConfig
import app.igni.dpc.UninstallStatusReceiver

data class ApplyResult(
    val success: Boolean,
    val hiddenCount: Int,
    val newlyHidden: Int = 0,
    val uninstallRequested: Int = 0,
    val message: String? = null,
    val screenTimeoutMs: Int? = null,
    val cameraPackages: List<String> = emptyList()
)

/**
 * Idempotent Device Owner policy:
 * - Uninstall **user** apps that are not on the keep / allowlist (frees storage).
 * - Hide **system** apps that are not kept (cannot safely uninstall).
 *
 * Does **not** replace the system launcher — Home stays with the OEM/stock launcher.
 * Non-allowlist user apps are removed; system bloat stays hidden only.
 *
 * Camera apps are detected dynamically (image-capture handlers + packageName contains
 * "camera" + static OEM list) and are always unhidden on every apply(), even if they
 * were previously stored in [HiddenStore].
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

    fun apply(): ApplyResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner; skip apply")
            return ApplyResult(
                success = false,
                hiddenCount = store.snapshot().size,
                message = "not_device_owner",
                cameraPackages = detectedCameraPackages()
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
                // Fire-and-forget; status logged by UninstallStatusReceiver.
                if (requestSilentUninstall(pkg)) {
                    uninstallRequested++
                    if (hidden.remove(pkg)) {
                        Log.i(TAG, "Removed uninstalled package $pkg from HiddenStore")
                    }
                }
            }
        }

        // Drop HiddenStore entries for packages that are no longer installed
        // (previous hide-only versions may have tracked user apps we now uninstall).
        val after = installedPackageNames()
        val gone = hidden.filter { it !in after }.toList()
        for (pkg in gone) {
            hidden.remove(pkg)
            Log.i(TAG, "Pruned gone package $pkg from HiddenStore")
        }

        // Optional kiosk allowlist. Default false — hide/uninstall + stock-launcher UX.
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

        // Clear any previous HOME takeover from older DPC versions; do not set a new one.
        clearHomeTakeover()

        // Display policies: dark mode + 30 min screen timeout (best-effort; never fail apply).
        applyDarkMode()
        val timeoutMs = applyScreenTimeout()

        // Shortcut pinning (ShortcutManager.requestPinShortcut) always prompts the user on
        // stock launchers — no reliable DO-silent pin API. Rely on hide + OEM home icons.

        store.replace(hidden)
        store.markApplied()
        Log.i(
            TAG,
            "Apply complete hidden=${hidden.size} newlyHidden=$newlyHidden " +
                "uninstallRequested=$uninstallRequested cameras=${cameras.size} timeoutMs=$timeoutMs"
        )
        return ApplyResult(
            success = true,
            hiddenCount = hidden.size,
            newlyHidden = newlyHidden,
            uninstallRequested = uninstallRequested,
            screenTimeoutMs = timeoutMs,
            cameraPackages = cameras.sorted()
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
        // Ensure we are not blocking uninstall of this target (DPC itself stays blocked).
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

    /**
     * Screen-off timeout 30 minutes.
     *
     * Prefer [Settings.System.SCREEN_OFF_TIMEOUT] sticking (put + read-back). Also try
     * reflective [DevicePolicyManager.setSystemSetting] (DO SystemApi) when present.
     *
     * [DevicePolicyManager.setMaximumTimeToLock] is kept as a complementary ceiling, but
     * on some OEMs it can interact oddly with interactive screen-off (keyguard vs blanking).
     * Prefer ensuring SCREEN_OFF_TIMEOUT sticks; max-time-to-lock is best-effort only.
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

        // DO SystemApi: DevicePolicyManager.setSystemSetting(admin, name, value)
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                android.content.ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_OFF_TIMEOUT_MS.toString())
            Log.i(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT, $SCREEN_OFF_TIMEOUT_MS)")
        }.onFailure { Log.w(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT) unavailable/failed", it) }

        runCatching {
            dpm.setMaximumTimeToLock(admin, SCREEN_OFF_TIMEOUT_MS.toLong())
            Log.i(TAG, "setMaximumTimeToLock(${SCREEN_OFF_TIMEOUT_MS}ms) — complementary; prefer SCREEN_OFF_TIMEOUT")
        }.onFailure { Log.w(TAG, "setMaximumTimeToLock failed", it) }

        val actual = currentScreenTimeoutMs()
        Log.i(TAG, "SCREEN_OFF_TIMEOUT read-back=${actual}ms (want ${SCREEN_OFF_TIMEOUT_MS})")
        return actual
    }

    /**
     * Undo HOME takeover from older releases that called addPersistentPreferredActivity
     * for HomeActivity. Leaves the stock/OEM launcher as the default HOME handler.
     */
    private fun clearHomeTakeover() {
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${appContext.packageName}")
        }.onFailure {
            Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it)
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
    }
}
