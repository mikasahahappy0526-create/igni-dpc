package app.igni.dpc.policy

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.util.Log
import java.util.Locale
import app.igni.dpc.AdminReceiver
import app.igni.dpc.BuildConfig
import app.igni.dpc.UninstallStatusReceiver
import app.igni.dpc.chrome.ChromeInstaller
import app.igni.dpc.line.LineInstaller

data class ApplyResult(
    val success: Boolean,
    val hiddenCount: Int,
    val newlyHidden: Int = 0,
    val uninstallRequested: Int = 0,
    val message: String? = null,
    val screenTimeoutMs: Int? = null,
    val cameraPackages: List<String> = emptyList(),
    val darkMode: DarkModeStatus? = null,
    val audio: AudioStatus? = null,
    val googleApp: GoogleAppStatus? = null,
    val chromeDefaultBrowser: String? = null
)

/**
 * Google app / search force-hide result for Admin UI.
 */
data class GoogleAppStatus(
    val hidden: Int,
    val uninstallRequested: Int,
    val packages: List<String>,
    val detail: String
)

/**
 * Result of returning the device to personal use (clear Device Owner).
 */
data class ClearOwnerResult(
    val success: Boolean,
    val restoredHidden: Int = 0,
    val message: String
)

/**
 * Result of audio / manner (silent) policy for Admin UI and logs.
 * [result]: success | fail | never
 */
data class AudioStatus(
    val ringerMode: String,
    val musicVolume: Int?,
    val ringVolume: Int?,
    val result: String,
    val detail: String
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
    /** Samsung One UI Settings.System display_night_theme read-back (1 = on), or null if unread. */
    val displayNightTheme: Int? = null,
    val result: String,
    val detail: String
)

/**
 * Idempotent Device Owner policy:
 * - Uninstall **user** apps that are not on the keep / allowlist (frees storage).
 * - Hide **system** apps that are not kept (cannot safely uninstall).
 * - Never claim HOME: clear this package's persistent preferred activities every apply
 *   so the stock Samsung / OEM launcher remains home (Igni HomeActivity is disabled).
 * - Keep Chrome / Settings / Play / Camera / LINE / Alive / Igni visible (explicit unhide+enable).
 * - Force-hide (+ uninstall if possible) Google app / search (not Chrome).
 * - Force-remove TikTok Lite (prefer uninstall; hide if system/uninstall fails).
 * - Prefer Chrome as http/https default browser via DPM persistent preferred activity.
 * - Best-effort stock-home pin shortcuts (no custom HOME / dock).
 * - Apply display defaults: Samsung Settings-reflecting dark theme + 30-minute timeout.
 * - Apply audio defaults: silent/manner ringer + all stream volumes to 0.
 * - Force system locale Japanese (ja_JP) + time zone Asia/Tokyo (best-effort; every apply).
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
        val nightThemeStored = if (darkPrefs.contains(KEY_DARK_NIGHT_THEME)) {
            darkPrefs.getInt(KEY_DARK_NIGHT_THEME, -1).takeIf { it >= 0 }
        } else {
            null
        }
        return DarkModeStatus(
            sdkInt = darkPrefs.getInt(KEY_DARK_SDK, Build.VERSION.SDK_INT),
            uiModeManagerAvailable = darkPrefs.getBoolean(KEY_DARK_UIM_OK, true),
            setNightModeActivatedAvailable = darkPrefs.getBoolean(KEY_DARK_ACTIVATED_OK, false),
            systemDarkThemeLikely = darkPrefs.getBoolean(KEY_DARK_LIKELY, Build.VERSION.SDK_INT >= 29),
            displayNightTheme = nightThemeStored,
            result = stored,
            detail = darkPrefs.getString(KEY_DARK_DETAIL, "").orEmpty()
        )
    }

    /** Current ringer mode + music/ring volumes (live), for Admin UI. */
    fun audioStatus(): AudioStatus {
        val am = appContext.getSystemService(AudioManager::class.java)
            ?: return AudioStatus("unknown", null, null, "never", "AudioManager unavailable")
        return AudioStatus(
            ringerMode = ringerModeLabel(am.ringerMode),
            musicVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
            ringVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_RING) }.getOrNull(),
            result = "live",
            detail = ""
        )
    }

    /** Last Google-app force-hide snapshot (live probe of known packages). */
    fun googleAppStatus(): GoogleAppStatus {
        val present = mutableListOf<String>()
        var hiddenCount = 0
        for (pkg in KeepPackages.FORCE_HIDE_GOOGLE) {
            if (!isPackageInstalled(pkg)) continue
            present += pkg
            val hidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            if (hidden) hiddenCount++
        }
        return GoogleAppStatus(
            hidden = hiddenCount,
            uninstallRequested = 0,
            packages = present,
            detail = if (present.isEmpty()) {
                "Googleアプリ系: 未インストール"
            } else {
                "検出 ${present.size} / 非表示 $hiddenCount — ${present.joinToString()}"
            }
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
                darkMode = darkModeStatus(),
                audio = audioStatus()
            )
        }

        runCatching { dpm.setUninstallBlocked(admin, appContext.packageName, true) }
        // Hard-block uninstall of Chrome / Play / Settings / LINE / Alive (defense in depth).
        for (pkg in KeepPackages.HARD_DENY_UNINSTALL) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
        }
        for (pkg in KeepPackages.CHROME_PACKAGES) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
        }

        // BEFORE any hide/uninstall loops: preserve preinstalled / present Chrome-family.
        val hiddenEarly = store.mutableCopy()
        preserveChromeFamilyEarly(hiddenEarly)
        store.replace(hiddenEarly)

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

        // Chrome must stay usable: never leave it hidden; drop from HiddenStore; enable.
        for (chromePkg in KeepPackages.CHROME_PACKAGES) {
            unhideKeepPackage(chromePkg, hidden, "Chrome")
            if (isPackageInstalled(chromePkg)) {
                runCatching { dpm.setUninstallBlocked(admin, chromePkg, true) }
                enablePackage(chromePkg)
            }
        }
        // Trichrome shared libs: never hide/uninstall when Chrome is kept.
        for (pkg in installedPackageNames()) {
            if (!keep.isTrichromePackage(pkg)) continue
            unhideKeepPackage(pkg, hidden, "Trichrome")
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
            // Do not force-enable trichrome libs (no launcher); just keep unhidden/blocked.
        }
        unhideKeepPackage(KeepPackages.LINE_PACKAGE, hidden, "LINE")
        // TikTok Lite is force-removed below — do NOT unhide/keep it.
        unhideKeepPackage(KeepPackages.ALIVE_PACKAGE, hidden, "Alive")
        for (settingsPkg in KeepPackages.SETTINGS_PACKAGES) {
            unhideKeepPackage(settingsPkg, hidden, "Settings")
        }
        unhideKeepPackage(KeepPackages.PLAY_STORE_PACKAGE, hidden, "Play Store")
        // Igni itself: unhide + enable so AdminActivity LAUNCHER icon stays visible.
        unhideKeepPackage(appContext.packageName, hidden, "Igni")
        enablePackage(appContext.packageName)
        enableComponent(
            ComponentName(appContext.packageName, "app.igni.dpc.AdminActivity")
        )
        // Keep HomeActivity disabled (no custom HOME / dock).
        disableComponent(
            ComponentName(appContext.packageName, "app.igni.dpc.HomeActivity")
        )

        // Force-hide Google app / search (not Chrome) before general hide loop.
        val googleStatus = forceHideGoogleApps(hidden)

        // Force-remove TikTok Lite (prefer silent uninstall; hide if system/uninstall fails).
        val tiktokRemoved = forceRemoveTikTokLite(hidden)

        val installed = installedPackageNames()
        val launchable = launchablePackageNames()

        // Safety: never leave keep-list packages hidden; also enable them.
        for (pkg in installed) {
            if (keep.isForceHide(pkg)) continue
            if (keep.isForceRemoveTikTokLite(pkg)) continue
            if (keep.shouldKeep(pkg)) {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    val restored = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
                    if (restored) {
                        hidden.remove(pkg)
                        Log.i(TAG, "Unhid keep-list package $pkg")
                    }
                }
                enablePackage(pkg)
            }
        }

        var newlyHidden = 0
        var uninstallRequested = 0
        for (pkg in installed) {
            // Force-hide Google / force-remove TikTok handled above; still skip hard-deny / keep.
            if (keep.isForceHide(pkg)) {
                // Ensure still hidden if forceHideGoogleApps raced.
                runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                if (hidden.add(pkg)) newlyHidden++
                continue
            }
            if (keep.isForceRemoveTikTokLite(pkg)) {
                // Prefer uninstall already requested; ensure hidden if still present (system stub).
                runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                if (hidden.add(pkg)) newlyHidden++
                continue
            }
            // Hard deny + shouldKeep: never uninstall or hide these.
            if (keep.isHardDenyUninstall(pkg)) continue
            if (keep.shouldKeep(pkg)) continue
            if (pkg == appContext.packageName) continue
            if (pkg in cameras) continue
            if (pkg in KeepPackages.CHROME_PACKAGES) continue
            if (keep.isTrichromePackage(pkg)) continue

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
                KeepPackages.LINE_PACKAGE,
                KeepPackages.ALIVE_PACKAGE,
                appContext.packageName
            )
            lockTaskPkgs.addAll(cameras)
            runCatching {
                dpm.setLockTaskPackages(admin, lockTaskPkgs.toTypedArray())
            }.onFailure { Log.w(TAG, "setLockTaskPackages failed", it) }
        }

        // Never be HOME: clear any prior persistent preferred activities for this package
        // so stock Samsung One UI / OEM launcher handles HOME (no addPersistentPreferredActivity).
        clearIgniHomePreferred()

        // Prefer Chrome as http/https VIEW handler (not Google app).
        val chromeBrowser = preferChromeAsDefaultBrowser()

        // Display policies: dark mode + 30 min screen timeout (best-effort; never fail apply).
        val dark = applyDarkMode()
        val timeoutMs = applyScreenTimeout()

        // Audio: silent/manner + all volumes 0 (best-effort; never fail apply).
        val audio = applyAudioPolicy()

        // System language Japanese + Asia/Tokyo (best-effort; re-applied every policy apply).
        val localeTz = applyJapaneseLocaleAndTimeZone()

        store.replace(hidden)
        store.markApplied()
        Log.i(
            TAG,
            "Apply complete hidden=${hidden.size} newlyHidden=$newlyHidden " +
                "uninstallRequested=$uninstallRequested cameras=${cameras.size} " +
                "timeoutMs=$timeoutMs dark=${dark.result} audio=${audio.result} " +
                "ringer=${audio.ringerMode} music=${audio.musicVolume} ring=${audio.ringVolume} " +
                "googleHidden=${googleStatus.hidden} googleUninst=${googleStatus.uninstallRequested} " +
                "tiktokUninst=$tiktokRemoved " +
                "chromeBrowser=$chromeBrowser localeTz=$localeTz"
        )
        // Post-setup: LINE/Chrome missing → silent Uptodown only (async). Never open Play.
        // Alive is Admin-button only — do NOT auto-install here.
        // TikTok Lite is force-removed (never install).
        LineInstaller.ensureLineInstalledAsync(appContext)
        ChromeInstaller.ensureChromeInstalledAsync(appContext)
        return ApplyResult(
            success = true,
            hiddenCount = hidden.size,
            newlyHidden = newlyHidden,
            uninstallRequested = uninstallRequested,
            screenTimeoutMs = timeoutMs,
            cameraPackages = cameras.sorted(),
            darkMode = dark,
            audio = audio,
            googleApp = googleStatus,
            chromeDefaultBrowser = chromeBrowser
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
     * Return to personal use: restore hidden system apps, clear DO-only policies,
     * then [DevicePolicyManager.clearDeviceOwnerApp] (deprecated self-clear API).
     * Must run while still Device Owner for unhide / clear calls to succeed.
     */
    @Suppress("DEPRECATION")
    fun returnToPersonalUse(): ClearOwnerResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "returnToPersonalUse: not device owner")
            return ClearOwnerResult(
                success = false,
                message = "not_device_owner"
            )
        }

        val restored = unhideAll()

        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities before DO clear")
        }.onFailure {
            Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it)
        }

        runCatching {
            dpm.setLockTaskPackages(admin, emptyArray())
            Log.i(TAG, "Cleared lock-task packages")
        }.onFailure {
            Log.w(TAG, "setLockTaskPackages(empty) failed", it)
        }

        runCatching {
            dpm.setUninstallBlocked(admin, appContext.packageName, false)
            Log.i(TAG, "Cleared uninstall-blocked on ${appContext.packageName}")
        }.onFailure {
            Log.w(TAG, "setUninstallBlocked(false) failed", it)
        }

        return try {
            dpm.clearDeviceOwnerApp(appContext.packageName)
            val stillOwner = isDeviceOwner()
            if (stillOwner) {
                Log.w(TAG, "clearDeviceOwnerApp returned but still device owner")
                ClearOwnerResult(
                    success = false,
                    restoredHidden = restored,
                    message = "still_device_owner"
                )
            } else {
                Log.i(TAG, "Device Owner cleared; restoredHidden=$restored")
                ClearOwnerResult(
                    success = true,
                    restoredHidden = restored,
                    message = "ok"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clearDeviceOwnerApp failed", t)
            ClearOwnerResult(
                success = false,
                restoredHidden = restored,
                message = t.message ?: t.javaClass.simpleName
            )
        }
    }

    /**
     * Request silent uninstall via [android.content.pm.PackageInstaller.uninstall] as Device Owner.
     * Returns true if the uninstall request was submitted (not that it already finished).
     */
    private fun requestSilentUninstall(packageName: String): Boolean {
        // Absolute hard deny — never call PackageInstaller.uninstall for these.
        if (keep.isHardDenyUninstall(packageName)) {
            Log.w(TAG, "Hard-deny uninstall skipped for $packageName")
            return false
        }
        if (packageName in KeepPackages.CHROME_PACKAGES) {
            Log.w(TAG, "Chrome-family uninstall skipped for $packageName")
            return false
        }
        if (keep.isTrichromePackage(packageName)) {
            Log.w(TAG, "Trichrome uninstall skipped for $packageName")
            return false
        }

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
     * Silent / manner mode + zero volumes across streams.
     * Prefer [AudioManager.RINGER_MODE_SILENT]; if blocked try VIBRATE, then still force volumes to 0.
     * Optional best-effort DND interruption filter. Failures are logged; apply() still succeeds.
     */
    private fun applyAudioPolicy(): AudioStatus {
        val am = appContext.getSystemService(AudioManager::class.java)
        if (am == null) {
            Log.w(TAG, "AudioManager unavailable")
            return AudioStatus("unknown", null, null, "fail", "AudioManager unavailable")
        }

        val notes = mutableListOf<String>()
        var anySuccess = false

        // Prefer SILENT (volumes all 0 / Japan マナー as mute). Fall back to VIBRATE if
        // set throws or read-back is not SILENT (some OEMs block silently).
        runCatching {
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            Log.i(TAG, "AudioManager.setRingerMode(RINGER_MODE_SILENT)")
        }.onFailure {
            notes += "ringer=SILENT throw"
            Log.w(TAG, "setRingerMode(SILENT) failed", it)
        }
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            anySuccess = true
            notes += "ringer=SILENT"
        } else {
            notes += "ringer=SILENT blocked(read=${ringerModeLabel(am.ringerMode)})"
            runCatching {
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                Log.i(TAG, "AudioManager.setRingerMode(RINGER_MODE_VIBRATE) fallback")
            }.onFailure {
                notes += "ringer=VIBRATE throw"
                Log.w(TAG, "setRingerMode(VIBRATE) failed", it)
            }
            if (am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                anySuccess = true
                notes += "ringer=VIBRATE"
            } else {
                notes += "ringer=VIBRATE fail(read=${ringerModeLabel(am.ringerMode)})"
            }
        }

        val streams = mutableListOf(
            AudioManager.STREAM_MUSIC to "MUSIC",
            AudioManager.STREAM_RING to "RING",
            AudioManager.STREAM_NOTIFICATION to "NOTIFICATION",
            AudioManager.STREAM_SYSTEM to "SYSTEM",
            AudioManager.STREAM_ALARM to "ALARM",
            AudioManager.STREAM_VOICE_CALL to "VOICE_CALL",
            AudioManager.STREAM_DTMF to "DTMF",
        )
        // STREAM_ACCESSIBILITY: API 26+ (minSdk 26). Guard with constant lookup for OEM quirks.
        runCatching {
            val accessibility = AudioManager::class.java.getField("STREAM_ACCESSIBILITY").getInt(null)
            streams += accessibility to "ACCESSIBILITY"
        }.onFailure {
            Log.w(TAG, "STREAM_ACCESSIBILITY unavailable", it)
            notes += "ACCESSIBILITY=skip"
        }

        for ((stream, name) in streams) {
            val ok = runCatching {
                am.setStreamVolume(stream, 0, /* flags */ 0)
                true
            }.onFailure {
                Log.w(TAG, "setStreamVolume($name) failed", it)
            }.getOrDefault(false)
            if (ok) {
                anySuccess = true
                notes += "$name=0"
            } else {
                notes += "$name=fail"
            }
        }

        // Optional DND: interruption filter NONE (or PRIORITY). Requires ACCESS_NOTIFICATION_POLICY
        // on many devices; Device Owner may still succeed — best-effort only.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nm = appContext.getSystemService(NotificationManager::class.java)
                if (nm != null) {
                    val granted = nm.isNotificationPolicyAccessGranted
                    if (granted) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                        notes += "interruptionFilter=NONE"
                        anySuccess = true
                        Log.i(TAG, "NotificationManager.setInterruptionFilter(NONE)")
                    } else {
                        // Try anyway as DO; may throw SecurityException.
                        runCatching {
                            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                            notes += "interruptionFilter=NONE"
                            anySuccess = true
                            Log.i(TAG, "NotificationManager.setInterruptionFilter(NONE) without grant")
                        }.onFailure { secondary ->
                            runCatching {
                                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                                notes += "interruptionFilter=PRIORITY"
                                anySuccess = true
                                Log.i(TAG, "NotificationManager.setInterruptionFilter(PRIORITY)")
                            }.onFailure {
                                notes += "interruptionFilter=skip"
                                Log.w(TAG, "setInterruptionFilter failed (policy not granted)", secondary)
                            }
                        }
                    }
                }
            }
        }.onFailure {
            notes += "interruptionFilter=fail"
            Log.w(TAG, "DND interruption filter failed", it)
        }

        val musicVol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val ringVol = runCatching { am.getStreamVolume(AudioManager.STREAM_RING) }.getOrNull()
        val mode = ringerModeLabel(am.ringerMode)
        val result = if (anySuccess) "success" else "fail"
        val status = AudioStatus(
            ringerMode = mode,
            musicVolume = musicVol,
            ringVolume = ringVol,
            result = result,
            detail = notes.joinToString("; ")
        )
        Log.i(TAG, "Audio apply result=$result ringer=$mode music=$musicVol ring=$ringVol detail=${status.detail}")
        return status
    }

    private fun ringerModeLabel(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
        else -> "unknown($mode)"
    }

    /**
     * System-wide night / dark mode, with Samsung One UI Settings-reflecting dark ON.
     * Primary: Settings.System display_night_theme=1 with read-back verification.
     * Also: UiModeManager MODE_NIGHT_YES, setNightModeActivated(true), Secure ui_night_mode=2,
     * reflective DPM setSystemSetting / setSecureSetting. Failures logged; apply() still succeeds.
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

        // 1) PRIMARY (Samsung One UI Settings → Dark mode): display_night_theme = 1
        if (putSystemInt(SYSTEM_DISPLAY_NIGHT_THEME, 1)) {
            anySuccess = true
            notes += "system.display_night_theme=1"
        } else {
            notes += "system.display_night_theme=fail"
        }
        // Immediate verify; retry once via put + DPM if Settings UI would still show off.
        var nightThemeProbe = readSystemInt(SYSTEM_DISPLAY_NIGHT_THEME)
        if (nightThemeProbe != 1) {
            notes += "display_night_theme_probe=${nightThemeProbe ?: "null"}→retry"
            putSystemInt(SYSTEM_DISPLAY_NIGHT_THEME, 1)
            dpmSetSystemSetting(SYSTEM_DISPLAY_NIGHT_THEME, "1")
            dpmSetSecureSetting(SYSTEM_DISPLAY_NIGHT_THEME, "1")
            // Some One UI builds also mirror dark ON via these Settings-facing keys.
            putSystemInt("dark_mode", 1)
            putSecureInt("dark_mode", 1)
            putSystemInt("theme_mode", 1)
            putSecureInt("theme_mode", 1)
            nightThemeProbe = readSystemInt(SYSTEM_DISPLAY_NIGHT_THEME)
        }

        // 2) Standard UiModeManager + Secure ui_night_mode=2
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

        // 3) Additional OEM keys: dark_theme / theme_mode / night_mode (1 or 2 = on)
        val oemAttempts = listOf(
            SettingAttempt("secure", "dark_theme", 1),
            SettingAttempt("system", "dark_theme", 1),
            SettingAttempt("secure", "theme_mode", 1),
            SettingAttempt("system", "theme_mode", 1),
            SettingAttempt("secure", "theme_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "theme_mode", MODE_NIGHT_YES),
            SettingAttempt("secure", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "night_mode", MODE_NIGHT_YES),
            SettingAttempt("system", "ui_night_mode", MODE_NIGHT_YES),
            SettingAttempt("global", "ui_night_mode", MODE_NIGHT_YES),
            SettingAttempt("secure", SYSTEM_DISPLAY_NIGHT_THEME, 1),
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
            // Failures already logged inside put*; skip adding every miss to Admin detail.
        }

        // 4) Reflective DPM setSystemSetting / setSecureSetting (display_night_theme + ui_night_mode)
        if (dpmSetSystemSetting(SYSTEM_DISPLAY_NIGHT_THEME, "1")) {
            anySuccess = true
            notes += "dpm.setSystemSetting.display_night_theme=1"
        } else {
            notes += "dpm.setSystemSetting.display_night_theme=fail"
        }
        if (dpmSetSystemSetting(SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES.toString())) {
            anySuccess = true
            notes += "dpm.setSystemSetting.ui_night_mode=2"
        } else {
            notes += "dpm.setSystemSetting.ui_night_mode=fail"
        }
        if (dpmSetSecureSetting(SECURE_UI_NIGHT_MODE, MODE_NIGHT_YES.toString())) {
            anySuccess = true
            notes += "dpm.setSecureSetting.ui_night_mode=2"
        } else {
            notes += "dpm.setSecureSetting.ui_night_mode=fail"
        }
        if (dpmSetSecureSetting(SYSTEM_DISPLAY_NIGHT_THEME, "1")) {
            anySuccess = true
            notes += "dpm.setSecureSetting.display_night_theme=1"
        } else {
            notes += "dpm.setSecureSetting.display_night_theme=fail"
        }

        // 5) setApplicationNightMode if present; Samsung / night broadcasts (Settings listeners)
        runCatching {
            if (uiMode != null) {
                val m = UiModeManager::class.java.getMethod(
                    "setApplicationNightMode",
                    Int::class.javaPrimitiveType
                )
                m.invoke(uiMode, MODE_NIGHT_YES)
                anySuccess = true
                notes += "setApplicationNightMode=ok"
                Log.i(TAG, "UiModeManager.setApplicationNightMode(MODE_NIGHT_YES)")
            }
        }.onFailure {
            notes += "setApplicationNightMode=skip"
            Log.w(TAG, "setApplicationNightMode unavailable/failed", it)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && uiMode != null) {
                val current = uiMode.nightMode
                Log.i(TAG, "UiModeManager.nightMode read-back=$current")
                notes += "nightModeRead=$current"
            }
        }.onFailure { Log.w(TAG, "nightMode read-back failed", it) }

        runCatching {
            val intent = Intent("android.intent.action.NIGHT_MODE_CHANGED")
                .putExtra("night_mode", MODE_NIGHT_YES)
                .setPackage(null)
            appContext.sendBroadcast(intent)
            notes += "broadcast.NIGHT_MODE_CHANGED"
            Log.i(TAG, "Sent android.intent.action.NIGHT_MODE_CHANGED")
        }.onFailure {
            notes += "broadcast.NIGHT_MODE_CHANGED=fail"
            Log.w(TAG, "NIGHT_MODE_CHANGED broadcast failed", it)
        }

        // Samsung One UI theme listeners (best-effort; ignore if missing / blocked)
        val samsungActions = listOf(
            "com.samsung.android.theme.THEMEDARK_CHANGED",
            "com.android.server.action.DISPLAY_NIGHT_THEME_CHANGED",
            "com.samsung.intent.action.THEME_CHANGED",
        )
        for (action in samsungActions) {
            runCatching {
                val intent = Intent(action)
                    .putExtra("dark_mode", true)
                    .putExtra("display_night_theme", 1)
                    .putExtra("night_mode", MODE_NIGHT_YES)
                appContext.sendBroadcast(intent)
                notes += "broadcast.$action"
                Log.i(TAG, "Sent Samsung/theme broadcast $action")
            }.onFailure {
                notes += "broadcast.$action=fail"
                Log.w(TAG, "Samsung broadcast $action failed", it)
            }
        }

        // 6) Admin status: display_night_theme read-back (Settings → Dark mode ON when == 1)
        val nightTheme = readSystemInt(SYSTEM_DISPLAY_NIGHT_THEME)
        notes += if (nightTheme != null) {
            "display_night_theme_read=$nightTheme"
        } else {
            "display_night_theme_read=null"
        }
        if (nightTheme == 1) {
            anySuccess = true
            notes += "settings_dark_theme=ON (display_night_theme=1)"
        } else {
            notes += "settings_dark_theme=OFF_OR_UNKNOWN (want display_night_theme=1 for One UI Settings)"
        }
        Log.i(TAG, "Samsung display_night_theme read-back=$nightTheme (want 1)")

        val result = when {
            !likely && !anySuccess -> "unsupported"
            !likely && anySuccess -> "success" // best-effort on API < 29
            nightTheme == 1 -> "success"
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
            displayNightTheme = nightTheme,
            result = result,
            detail = notes.joinToString("; ")
        )
        persistDarkModeStatus(status)
        Log.i(TAG, "Dark mode apply result=$result display_night_theme=$nightTheme detail=${status.detail}")
        return status
    }

    private data class SettingAttempt(val table: String, val key: String, val value: Int)

    private fun readSystemInt(key: String): Int? {
        return runCatching {
            Settings.System.getInt(appContext.contentResolver, key)
        }.onFailure {
            Log.w(TAG, "Settings.System.getInt($key) failed", it)
        }.getOrNull()
    }

    private fun dpmSetSystemSetting(key: String, value: String): Boolean {
        return runCatching {
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
    }

    private fun dpmSetSecureSetting(key: String, value: String): Boolean {
        return runCatching {
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
        val editor = darkPrefs.edit()
            .putInt(KEY_DARK_SDK, status.sdkInt)
            .putBoolean(KEY_DARK_UIM_OK, status.uiModeManagerAvailable)
            .putBoolean(KEY_DARK_ACTIVATED_OK, status.setNightModeActivatedAvailable)
            .putBoolean(KEY_DARK_LIKELY, status.systemDarkThemeLikely)
            .putString(KEY_DARK_RESULT, status.result)
            .putString(KEY_DARK_DETAIL, status.detail)
        if (status.displayNightTheme != null) {
            editor.putInt(KEY_DARK_NIGHT_THEME, status.displayNightTheme)
        } else {
            editor.remove(KEY_DARK_NIGHT_THEME)
        }
        editor.apply()
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
            displayNightTheme = readSystemInt(SYSTEM_DISPLAY_NIGHT_THEME),
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
     * Force-hide Google app / search packages; uninstall when removable.
     * Never touches Chrome / Play / Settings.
     */
    private fun forceHideGoogleApps(hidden: MutableSet<String>): GoogleAppStatus {
        val touched = mutableListOf<String>()
        var hiddenN = 0
        var uninstN = 0
        val notes = mutableListOf<String>()

        val candidates = linkedSetOf<String>()
        candidates.addAll(KeepPackages.FORCE_HIDE_GOOGLE)
        // Also scan installed packages matching force-hide prefixes.
        for (pkg in installedPackageNames()) {
            if (keep.isForceHide(pkg)) candidates.add(pkg)
        }

        for (pkg in candidates) {
            if (!isPackageInstalled(pkg)) continue
            if (!keep.isForceHide(pkg)) continue
            touched += pkg

            // Clear any http/https preferred activity so Google is not the browser.
            runCatching {
                dpm.clearPackagePersistentPreferredActivities(admin, pkg)
            }

            val hideOk = runCatching {
                dpm.setApplicationHidden(admin, pkg, true)
            }.onFailure {
                Log.w(TAG, "Failed to hide Google package $pkg", it)
            }.getOrDefault(false)
            if (hideOk) {
                hiddenN++
                hidden.add(pkg)
                notes += "$pkg=hidden"
                Log.i(TAG, "Force-hidden Google package $pkg")
            } else {
                notes += "$pkg=hide_fail"
            }

            // Disable as defense in depth (system apps often cannot uninstall).
            runCatching {
                appContext.packageManager.setApplicationEnabledSetting(
                    pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    0
                )
                notes += "$pkg=disabled"
            }.onFailure {
                Log.w(TAG, "Failed to disable Google package $pkg", it)
            }

            if (!isSystemOrUpdatedSystemApp(pkg)) {
                if (requestSilentUninstall(pkg)) {
                    uninstN++
                    hidden.remove(pkg)
                    notes += "$pkg=uninstall_req"
                }
            } else {
                notes += "$pkg=system_keep_hidden"
            }
        }

        val detail = if (touched.isEmpty()) {
            "Googleアプリ系なし"
        } else {
            "非表示 $hiddenN / アンインストール要求 $uninstN — ${notes.joinToString("; ")}"
        }
        Log.i(TAG, "Google force-hide: $detail")
        return GoogleAppStatus(
            hidden = hiddenN,
            uninstallRequested = uninstN,
            packages = touched,
            detail = detail
        )
    }

    /**
     * Prefer Chrome for http/https VIEW via DPM persistent preferred activity.
     * Clears Google app preferred handlers first. Best-effort.
     */

    /**
     * Force-remove TikTok Lite packages. Prefer silent PackageInstaller uninstall;
     * if system/updated-system and uninstall request fails → [DevicePolicyManager.setApplicationHidden].
     * Never unhides / keeps these packages.
     *
     * @return number of uninstall requests submitted
     */
    private fun forceRemoveTikTokLite(hidden: MutableSet<String>): Int {
        var uninstallRequested = 0
        val candidates = linkedSetOf<String>()
        candidates.addAll(KeepPackages.FORCE_REMOVE_TIKTOK_LITE)
        for (pkg in installedPackageNames()) {
            if (keep.isForceRemoveTikTokLite(pkg)) candidates.add(pkg)
        }

        for (pkg in candidates) {
            if (!isPackageInstalled(pkg)) continue
            if (!keep.isForceRemoveTikTokLite(pkg)) continue

            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }

            val requested = requestSilentUninstall(pkg)
            if (requested) {
                uninstallRequested++
                hidden.remove(pkg)
                Log.i(TAG, "TikTok Lite uninstall requested: $pkg")
            }

            val system = isSystemOrUpdatedSystemApp(pkg)
            if (system || !requested) {
                val hideOk = runCatching {
                    dpm.setApplicationHidden(admin, pkg, true)
                }.onFailure {
                    Log.w(TAG, "Failed to hide TikTok Lite package $pkg", it)
                }.getOrDefault(false)
                if (hideOk) {
                    hidden.add(pkg)
                    Log.i(
                        TAG,
                        "TikTok Lite hidden (fallback): $pkg " +
                            "(system=$system uninstallRequested=$requested)"
                    )
                } else {
                    Log.w(TAG, "TikTok Lite hide failed: $pkg (system=$system)")
                }
            }
        }
        Log.i(TAG, "TikTok Lite force-remove: uninstallRequested=$uninstallRequested")
        return uninstallRequested
    }

    private fun preferChromeAsDefaultBrowser(): String {
        if (!ChromeInstaller.isChromeInstalled(appContext)) {
            Log.i(TAG, "Chrome not installed; skip default-browser prefer")
            return "chrome_missing"
        }
        // Drop Google app as browser handler.
        for (pkg in KeepPackages.FORCE_HIDE_GOOGLE) {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
        }

        val chromeLaunch = resolveChromeBrowserComponent()
            ?: return "chrome_activity_missing"

        return runCatching {
            val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            }
            dpm.addPersistentPreferredActivity(admin, filter, chromeLaunch)
            Log.i(TAG, "Set Chrome as persistent preferred for http/https: $chromeLaunch")
            "ok:$chromeLaunch"
        }.onFailure {
            Log.w(TAG, "preferChromeAsDefaultBrowser failed", it)
        }.getOrElse { "fail:${it.javaClass.simpleName}" }
    }

    private fun resolveChromeBrowserComponent(): ComponentName? {
        val pm = appContext.packageManager
        // Prefer MAIN/LAUNCHER of Chrome (opens browser UI).
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(KeepPackages.CHROME_PACKAGE)
        val launchInfo = runCatching {
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList()).firstOrNull()?.activityInfo
        if (launchInfo != null) {
            return ComponentName(launchInfo.packageName, launchInfo.name)
        }
        // Fallback: VIEW http handler inside Chrome.
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
            .setPackage(KeepPackages.CHROME_PACKAGE)
        val viewInfo = runCatching {
            pm.queryIntentActivities(view, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList()).firstOrNull()?.activityInfo
        return viewInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private fun enablePackage(packageName: String) {
        runCatching {
            appContext.packageManager.setApplicationEnabledSetting(
                packageName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                0
            )
            Log.i(TAG, "Enabled package $packageName")
        }.onFailure {
            Log.w(TAG, "enablePackage($packageName) failed", it)
        }
    }

    private fun enableComponent(component: ComponentName) {
        runCatching {
            appContext.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "Enabled component $component")
        }.onFailure {
            Log.w(TAG, "enableComponent($component) failed", it)
        }
    }

    private fun disableComponent(component: ComponentName) {
        runCatching {
            appContext.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "Disabled component $component")
        }.onFailure {
            Log.w(TAG, "disableComponent($component) failed", it)
        }
    }

    /**
     * Ensure this DPC is **not** the default HOME.
     * Clears package persistent preferred activities every apply.
     * Does **not** call [DevicePolicyManager.addPersistentPreferredActivity] for HOME.
     * (Browser http/https preferred for Chrome is set separately.)
     */
    private fun clearIgniHomePreferred() {
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${appContext.packageName} (stock launcher HOME)")
        }.onFailure {
            Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it)
        }
    }

    /**
     * Early Chrome / Trichrome preserve — runs before hide/uninstall loops.
     * For every installed Chrome-family package: uninstall-blocked, unhide, enable,
     * drop from HiddenStore. Logs clearly when a preinstalled Chrome is preserved.
     */
    private fun preserveChromeFamilyEarly(hidden: MutableSet<String>) {
        val installed = installedPackageNames()
        var preserved = 0
        for (pkg in KeepPackages.CHROME_PACKAGES) {
            if (pkg !in installed) continue
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
            val wasHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
            enablePackage(pkg)
            if (hidden.remove(pkg)) {
                Log.i(TAG, "Chrome preserve: removed $pkg from HiddenStore")
            }
            preserved++
            val flags = runCatching {
                appContext.packageManager.getApplicationInfo(pkg, 0).flags
            }.getOrDefault(0)
            val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system) {
                Log.i(
                    TAG,
                    "Preinstalled Chrome preserved: $pkg " +
                        "(system/updated-system; wasHidden=$wasHidden; uninstallBlocked+unhide+enable)"
                )
            } else {
                Log.i(
                    TAG,
                    "Chrome package preserved (already installed): $pkg " +
                        "(wasHidden=$wasHidden; uninstallBlocked+unhide+enable) — skip Uptodown replace"
                )
            }
        }
        for (pkg in installed) {
            if (!keep.isTrichromePackage(pkg)) continue
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
            hidden.remove(pkg)
            Log.i(TAG, "Trichrome kept (Chrome dependency): $pkg")
        }
        if (preserved == 0) {
            Log.i(TAG, "No Chrome-family package installed yet; ChromeInstaller may download if still absent")
        } else {
            Log.i(TAG, "Chrome early preserve done: preserved=$preserved (will not replace preinstall)")
        }
    }

    /** Explicitly unhide a keep-list package and remove it from HiddenStore. */
    private fun unhideKeepPackage(pkg: String, hidden: MutableSet<String>, label: String) {
        if (!isPackageInstalled(pkg)) return
        val wasHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
        val restored = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
        if (hidden.remove(pkg)) {
            Log.i(TAG, "Removed $label package $pkg from HiddenStore")
        }
        if (wasHidden && restored) {
            Log.i(TAG, "Unhid $label package $pkg")
        } else if (!wasHidden) {
            Log.i(TAG, "Kept $label package $pkg (already visible)")
        } else {
            Log.w(TAG, "Failed to unhide $label package $pkg (restored=$restored)")
        }
    }


    /**
     * Force system locale to Japanese (ja_JP) and time zone to Asia/Tokyo.
     * Best-effort for Device Owner; logged; never fails [apply].
     * Re-run on every policy reapply so already-enrolled English devices get fixed.
     *
     * Order:
     * 1) Reflective DPM setConfiguredLocales / related (compileSdk may expose SystemApi)
     * 2) LocaleList + ActivityManager updatePersistentConfiguration / updateConfiguration
     *    (AOSP LocalePicker pattern)
     * 3) Reflective LocalePicker.updateLocale
     * Skip persist.sys.locale (needs root; unreliable without it).
     * Time zone: [DevicePolicyManager.setTimeZone], then [AlarmManager.setTimeZone].
     */
    private fun applyJapaneseLocaleAndTimeZone(): String {
        val locale = Locale.forLanguageTag(TARGET_LOCALE_TAG)
        val localeList = LocaleList(locale)
        val notes = mutableListOf<String>()
        var localeOk = false
        var tzOk = false

        // 1) DPM setConfiguredLocales(ComponentName, LocaleList) if present
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setConfiguredLocales",
                ComponentName::class.java,
                LocaleList::class.java
            )
            method.invoke(dpm, admin, localeList)
            localeOk = true
            notes += "dpm.setConfiguredLocales=ok"
            Log.i(TAG, "DPM.setConfiguredLocales(ja_JP)")
        }.onFailure {
            notes += "dpm.setConfiguredLocales=skip"
            Log.w(TAG, "DPM.setConfiguredLocales unavailable/failed", it)
        }

        // Some builds expose setSystemLocales / setOverrideLocales without admin arg
        if (!localeOk) {
            for (name in listOf("setSystemLocales", "setOverrideLocales")) {
                val ok = runCatching {
                    val method = DevicePolicyManager::class.java.getMethod(name, LocaleList::class.java)
                    method.invoke(dpm, localeList)
                    true
                }.onFailure {
                    Log.w(TAG, "DPM.$name unavailable/failed", it)
                }.getOrDefault(false)
                if (ok) {
                    localeOk = true
                    notes += "dpm.$name=ok"
                    Log.i(TAG, "DPM.$name(ja_JP)")
                    break
                } else {
                    notes += "dpm.$name=skip"
                }
            }
        }

        // LocaleManager (API 33+): application locales + hidden system setters if present
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                val lmClass = Class.forName("android.app.LocaleManager")
                val lm = appContext.getSystemService(lmClass)
                if (lm != null) {
                    runCatching {
                        val m = lmClass.getMethod("setApplicationLocales", LocaleList::class.java)
                        m.invoke(lm, localeList)
                        notes += "localeManager.setApplicationLocales=ok"
                        Log.i(TAG, "LocaleManager.setApplicationLocales(ja_JP)")
                    }.onFailure {
                        notes += "localeManager.setApplicationLocales=fail"
                        Log.w(TAG, "LocaleManager.setApplicationLocales failed", it)
                    }
                    for (name in listOf("setSystemLocales", "setSystemLocalesForUser")) {
                        runCatching {
                            val methods = lmClass.methods.filter { it.name == name }
                            for (m in methods) {
                                when (m.parameterTypes.size) {
                                    1 -> m.invoke(lm, localeList)
                                    2 -> m.invoke(lm, localeList, 0)
                                    else -> continue
                                }
                                localeOk = true
                                notes += "localeManager.$name=ok"
                                Log.i(TAG, "LocaleManager.$name(ja_JP)")
                                return@runCatching
                            }
                        }.onFailure {
                            notes += "localeManager.$name=skip"
                            Log.w(TAG, "LocaleManager.$name failed", it)
                        }
                    }
                }
            }
        }.onFailure {
            notes += "localeManager=skip"
            Log.w(TAG, "LocaleManager path failed", it)
        }

        // 2) ActivityManager updatePersistentConfiguration / updateConfiguration (LocalePicker)
        runCatching {
            val config = Configuration(appContext.resources.configuration)
            config.setLocales(localeList)
            runCatching {
                val field = Configuration::class.java.getField("userSetLocale")
                field.setBoolean(config, true)
            }

            val am = ActivityManager::class.java
            val getService = runCatching { am.getDeclaredMethod("getService") }.getOrNull()
            val iAm = getService?.let { m ->
                m.isAccessible = true
                m.invoke(null)
            }
            if (iAm != null) {
                val iAmClass = iAm.javaClass
                val updated = runCatching {
                    val m = iAmClass.methods.firstOrNull {
                        it.name == "updatePersistentConfiguration" && it.parameterTypes.size == 1
                    } ?: throw NoSuchMethodException("updatePersistentConfiguration")
                    m.invoke(iAm, config)
                    true
                }.onFailure {
                    Log.w(TAG, "IActivityManager.updatePersistentConfiguration failed", it)
                }.getOrDefault(false)
                if (updated) {
                    localeOk = true
                    notes += "am.updatePersistentConfiguration=ok"
                    Log.i(TAG, "IActivityManager.updatePersistentConfiguration(ja_JP)")
                } else {
                    val updated2 = runCatching {
                        val m = iAmClass.methods.firstOrNull {
                            it.name == "updateConfiguration" && it.parameterTypes.size == 1
                        } ?: throw NoSuchMethodException("updateConfiguration")
                        m.invoke(iAm, config)
                        true
                    }.onFailure {
                        Log.w(TAG, "IActivityManager.updateConfiguration failed", it)
                    }.getOrDefault(false)
                    if (updated2) {
                        localeOk = true
                        notes += "am.updateConfiguration=ok"
                        Log.i(TAG, "IActivityManager.updateConfiguration(ja_JP)")
                    } else {
                        notes += "am.updateConfiguration=fail"
                    }
                }
            } else {
                notes += "am.getService=null"
                Log.w(TAG, "ActivityManager.getService() unavailable")
            }
        }.onFailure {
            notes += "am.config=fail"
            Log.w(TAG, "ActivityManager locale update failed", it)
        }

        // 3) com.android.internal.app.LocalePicker.updateLocale(Locale)
        runCatching {
            val picker = Class.forName("com.android.internal.app.LocalePicker")
            val m = picker.getMethod("updateLocale", Locale::class.java)
            m.invoke(null, locale)
            localeOk = true
            notes += "LocalePicker.updateLocale=ok"
            Log.i(TAG, "LocalePicker.updateLocale(ja_JP)")
        }.onFailure {
            notes += "LocalePicker.updateLocale=skip"
            Log.w(TAG, "LocalePicker.updateLocale unavailable/failed", it)
        }

        // Do NOT write persist.sys.locale (needs root; unreliable for DO).

        // Time zone Asia/Tokyo
        runCatching {
            val ok = dpm.setTimeZone(admin, TARGET_TIME_ZONE)
            if (ok) {
                tzOk = true
                notes += "dpm.setTimeZone=ok"
                Log.i(TAG, "DPM.setTimeZone($TARGET_TIME_ZONE)")
            } else {
                notes += "dpm.setTimeZone=false"
                Log.w(TAG, "DPM.setTimeZone returned false")
            }
        }.onFailure {
            notes += "dpm.setTimeZone=fail"
            Log.w(TAG, "DPM.setTimeZone failed", it)
        }

        if (!tzOk) {
            runCatching {
                val alarm = appContext.getSystemService(AlarmManager::class.java)
                alarm?.setTimeZone(TARGET_TIME_ZONE)
                tzOk = true
                notes += "alarm.setTimeZone=ok"
                Log.i(TAG, "AlarmManager.setTimeZone($TARGET_TIME_ZONE)")
            }.onFailure {
                notes += "alarm.setTimeZone=fail"
                Log.w(TAG, "AlarmManager.setTimeZone failed", it)
            }
        }

        // Optional: disable auto time zone so Tokyo sticks
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setAutoTimeZoneEnabled(admin, false)
                notes += "dpm.setAutoTimeZoneEnabled=false"
            }
        }.onFailure {
            notes += "dpm.setAutoTimeZoneEnabled=skip"
            Log.w(TAG, "setAutoTimeZoneEnabled failed", it)
        }

        val currentLocales = runCatching {
            LocaleList.getDefault().toLanguageTags()
        }.getOrDefault("?")
        val currentTz = java.util.TimeZone.getDefault().id
        val summary =
            "locale=${if (localeOk) "ok" else "fail"} tz=${if (tzOk) "ok" else "fail"} " +
                "readLocales=$currentLocales readTz=$currentTz detail=${notes.joinToString("; ")}"
        Log.i(TAG, "Locale/TZ apply: $summary")
        return summary
    }


    private fun isPackageInstalled(packageName: String): Boolean {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
        return runCatching {
            appContext.packageManager.getApplicationInfo(packageName, flags)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "IgniPolicy"
        private const val MODE_NIGHT_YES = UiModeManager.MODE_NIGHT_YES
        /** @hide Settings.Secure.UI_NIGHT_MODE */
        private const val SECURE_UI_NIGHT_MODE = "ui_night_mode"
        /** Samsung One UI: Settings.System display_night_theme (1 = dark). */
        private const val SYSTEM_DISPLAY_NIGHT_THEME = "display_night_theme"
        /** 30 minutes in milliseconds. */
        const val SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000
        /** System language for Japan provisioning / policy reapply. */
        const val TARGET_LOCALE_TAG = "ja-JP"
        /** IANA time zone for Japan. */
        const val TARGET_TIME_ZONE = "Asia/Tokyo"
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
        private const val KEY_DARK_NIGHT_THEME = "display_night_theme"
    }
}
