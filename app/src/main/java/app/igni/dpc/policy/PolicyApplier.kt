package app.igni.dpc.policy

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import app.igni.dpc.AdminReceiver
import app.igni.dpc.BuildConfig
import app.igni.dpc.UninstallStatusReceiver
import app.igni.dpc.chrome.ChromeInstaller
import app.igni.dpc.alive.AliveInstaller
import app.igni.dpc.line.LineInstaller

data class ApplyResult(
    val success: Boolean,
    val hiddenCount: Int,
    val newlyHidden: Int = 0,
    val uninstallRequested: Int = 0,
    val message: String? = null,
    val screenTimeoutMs: Int? = null,
    val cameraPackages: List<String> = emptyList(),
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
 * Idempotent Device Owner policy:
 * - Prefer **silent uninstall** for every non-keep package (user + system/updated-system).
 * - Hide (`setApplicationHidden`) only as **fallback** when uninstall fails / stub remains
 *   and the package is launchable / visible bloat — so「個人用に戻す」does not bring them back.
 * - Never claim HOME: clear this package's persistent preferred activities every apply
 *   so the stock Samsung / OEM launcher remains home (Igni HomeActivity is disabled).
 * - Keep Chrome / Settings / Play / Camera / LINE / Alive / Igni visible (explicit unhide+enable).
 * - Force-remove Google suite + Yahoo/Y!mobile/SoftBank/UQ/nubia bloat (FORCE_UNINSTALL;
 *   prefer uninstall then hide; never Chrome / keep-list).
 * - Force-remove Google app / search (prefer uninstall then hide; not Chrome).
 * - Force-remove TikTok Lite (prefer uninstall; hide if system/uninstall fails).
 * - On「個人用に戻す」: final uninstall pass, then unhide **keep-list only** (never restore bloat).
 * - Prefer Chrome as http/https default browser via DPM persistent preferred activity.
 * - After policies: async silent install LINE / Chrome / Alive if missing (never Play).
 * - Best-effort stock-home pin shortcuts (no custom HOME / dock).
 * - Apply display defaults: screen-off timeout prefer Never (消灯しない),
 *   else 30 min, else 10 min; force 3-button navigation (not gesture);
 *   turn ON status-bar battery percentage.
 * - Apply audio defaults: silent/manner ringer + all stream volumes to 0.
 * - Force system locale Japanese (ja_JP) + time zone Asia/Tokyo (best-effort; every apply).
 * - Best-effort OFF for OEM「充電情報を表示」(Show charging information) on lock screen.
 *   Generic keys plus confirmed OEM keys (Samsung `charging_info_always`,
 *   Nubia/ZTE `charging_indicator`, OPPO `oplus_keyguard_charge_anim_show`,
 *   Sharp `settings_ex` / `display_charging_when_screen_off`). Never touches
 *   status-bar battery % or Samsung `aod_charging_mode`.
 * - Best-effort OFF for「緊急速報メール」/ cell-broadcast emergency alerts (settings keys +
 *   hide known CB packages; never SMS/phone).
 * - Best-effort portrait lock: auto-rotation OFF (ACCELEROMETER_ROTATION=0) and
 *   USER_ROTATION=0, re-applied on every [apply].
 * - NFC off while Device Owner: user restrictions, `nfc_on=0`, and hide/suspend
 *   TagViewer. Restrictions drop when Device Owner is cleared.
 * - Pocket / anti-misoperation off on every [apply]: Samsung `screen_off_pocket`,
 *   OPPO mistouch keys, Sony pocket-mode package + settings, plus best-effort
 *   Sharp / Xiaomi / nubia keys. Never writes `proximity_sensor` or `surface_palm_*`.
 *   Failures are logged and do not fail [apply].
 * - After a **real** LINE PackageInstaller success while Device Owner: auto「個人用に戻す」
 *   ([returnToPersonalUse]), waiting briefly for Alive when needed (one-shot).
 * - While still Device Owner (apply + just before that auto-release): USB debugging on,
 *   USB file transfer allowed (clear DISALLOW_USB_FILE_TRANSFER and the physical-media
 *   mount block), USB data signaling on (API 31+), stay awake while plugged in
 *   (AC/USB/wireless = 7), and Samsung Auto Blocker (rampart) off. Global settings
 *   persist after DO clear; user restrictions do not.
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
                audio = audioStatus()
            )
        }

        // Before LINE auto-release: USB data, ADB, stay-awake. Re-applied again in returnToPersonalUse.
        val controllerPrep = applyPcControllerPrep()

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
        if (isPackageInstalled(KeepPackages.ALIVE_PACKAGE)) {
            runCatching { dpm.setUninstallBlocked(admin, KeepPackages.ALIVE_PACKAGE, true) }
            enablePackage(KeepPackages.ALIVE_PACKAGE)
        }
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

        // Aggressive FORCE_UNINSTALL (Google suite + Yahoo/carrier/nubia) — uninstall first.
        val forceUninst = forceUninstallAggressive(hidden)

        val installed = installedPackageNames()
        val launchable = launchablePackageNames()

        // Safety: never leave keep-list packages hidden; also enable them.
        for (pkg in installed) {
            if (keep.isForceHide(pkg)) continue
            if (keep.isForceRemoveTikTokLite(pkg)) continue
            if (keep.isForceUninstall(pkg)) continue
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
        var hideFallback = 0
        for (pkg in installed) {
            // Force-hide Google / TikTok / FORCE_UNINSTALL handled above; still skip hard-deny / keep.
            if (keep.isForceHide(pkg) || keep.isForceRemoveTikTokLite(pkg) || keep.isForceUninstall(pkg)) {
                // Ensure still hidden if force-remove raced / stub remains.
                runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                if (hidden.add(pkg)) newlyHidden++
                continue
            }
            // Emergency-alert packages are suppressed by applyEmergencyAlertsOff() below;
            // do not uninstall them here or let the generic pass override that path.
            if (pkg in KeepPackages.EMERGENCY_ALERT_PACKAGES) continue

            // Hard deny + shouldKeep: never uninstall or hide these.
            // CRITICAL / launcher / systemui stay protected via shouldKeep — do not widen.
            if (keep.isHardDenyUninstall(pkg)) continue
            if (keep.shouldKeep(pkg)) continue
            if (pkg == appContext.packageName) continue
            if (pkg in cameras) continue
            if (pkg in KeepPackages.CHROME_PACKAGES) continue
            if (keep.isTrichromePackage(pkg)) continue

            val system = isSystemOrUpdatedSystemApp(pkg)
            // Prefer silent uninstall for ALL non-keep (user + system/updated-system).
            // Updated-system: uninstall often removes the update / may remove the app.
            val requested = requestSilentUninstall(pkg)
            if (requested) {
                uninstallRequested++
                if (hidden.remove(pkg)) {
                    Log.i(TAG, "Removed uninstall-target $pkg from HiddenStore")
                }
                Log.i(TAG, "Uninstall attempted for $pkg (system=$system)")
            } else {
                Log.i(TAG, "Uninstall not submitted for $pkg (system=$system)")
            }

            // Hide fallback only: uninstall failed to submit, or system stub may remain,
            // and package is still present + launchable / visible bloat.
            val stillPresent = isPackageInstalled(pkg)
            val needHideFallback =
                stillPresent &&
                    pkg in launchable &&
                    (!requested || system)
            if (needHideFallback) {
                val ok = runCatching {
                    dpm.setApplicationHidden(admin, pkg, true)
                }.onFailure {
                    Log.w(TAG, "Failed to hide fallback $pkg", it)
                }.getOrDefault(false)
                if (ok) {
                    if (hidden.add(pkg)) newlyHidden++
                    hideFallback++
                    Log.i(
                        TAG,
                        "Hide fallback for $pkg " +
                            "(system=$system uninstallRequested=$requested)"
                    )
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

        // Display policies: screen timeout Never→30m→10m (best-effort; never fail apply).
        val timeoutMs = applyScreenTimeout()

        // Navigation: force 3-button (not gesture); best-effort every apply.
        val navMode = applyNavigationMode3Button()

        // Status-bar battery % ON (best-effort every apply).
        val batteryPct = applyBatteryPercentOn()

        // Audio: silent/manner + all volumes 0 (best-effort; never fail apply).
        val audio = applyAudioPolicy()

        // System language Japanese + Asia/Tokyo (best-effort; re-applied every policy apply).
        val localeTz = applyJapaneseLocaleAndTimeZone()

        // OEM lock-screen「充電情報を表示」OFF (Samsung / Sense; best-effort every apply).
        val chargingInfo = applyChargingInfoOff()

        // OEM / carrier「緊急速報メール」OFF (cell broadcast; best-effort every apply).
        val emergencyAlerts = applyEmergencyAlertsOff(hidden)

        // Portrait lock: auto-rotation OFF and USER_ROTATION=0 (best-effort every apply).
        val autoRotate = applyAutoRotateOff()

        // NFC radio off + TagViewer hidden (best-effort every apply; restrictions last while DO).
        val nfc = applyNfcOff(hidden)

        // Pocket / anti-misoperation off (best-effort every apply; never fails apply).
        val pocket = runCatching { applyPocketModeOff() }
            .onFailure { Log.w(TAG, "applyPocketModeOff failed", it) }
            .getOrDefault("fail")

        store.replace(hidden)
        store.markApplied()
        Log.i(
            TAG,
            "Apply complete hidden=${hidden.size} newlyHidden=$newlyHidden " +
                "uninstallRequested=$uninstallRequested hideFallback=$hideFallback " +
                "cameras=${cameras.size} " +
                "timeoutMs=$timeoutMs audio=${audio.result} " +
                "ringer=${audio.ringerMode} music=${audio.musicVolume} ring=${audio.ringVolume} " +
                "googleHidden=${googleStatus.hidden} googleUninst=${googleStatus.uninstallRequested} " +
                "tiktokUninst=$tiktokRemoved forceUninst=$forceUninst " +
                "chromeBrowser=$chromeBrowser localeTz=$localeTz " +
                "chargingInfo=$chargingInfo emergencyAlerts=$emergencyAlerts " +
                "autoRotate=$autoRotate nfc=$nfc pocket=$pocket navMode=$navMode " +
                "batteryPct=$batteryPct controller=$controllerPrep"
        )
        // Post-setup / stock home: LINE/Chrome/Alive missing → silent install (async). Never open Play.
        // TikTok Lite is force-removed (never install).
        LineInstaller.ensureLineInstalledAsync(appContext)
        ChromeInstaller.ensureChromeInstalledAsync(appContext)
        // Alive: auto-download + PackageInstaller when reaching home / after policy apply.
        AliveInstaller.ensureAliveInstalledAsync(appContext)
        return ApplyResult(
            success = true,
            hiddenCount = hidden.size,
            newlyHidden = newlyHidden,
            uninstallRequested = uninstallRequested,
            screenTimeoutMs = timeoutMs,
            cameraPackages = cameras.sorted(),
            audio = audio,
            googleApp = googleStatus,
            chromeDefaultBrowser = chromeBrowser
        )
    }

    /**
     * Restore packages this DPC has **hidden** (hide-fallback stubs).
     * Admin「アプリ一覧を表示に戻す」uses this. Prefer [unhideOnlyKeepPackages] for
     *「個人用に戻す」so force-removed bloat does not flood the launcher.
     * Cannot restore packages that were uninstalled — those stay gone after DO clear
     * and must be reinstalled from Play / APK.
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
     * Unhide **keep-list / critical only**. Force-removed / non-keep packages that are
     * still installed (hide fallback) stay hidden so「個人用に戻す」does not restore
     * Google suite / Yahoo / carrier bloat visibility.
     */
    fun unhideOnlyKeepPackages(): Int {
        if (!isDeviceOwner()) return 0
        val hidden = store.snapshot()
        var restored = 0
        var skipped = 0
        for (pkg in hidden) {
            if (!keep.shouldKeep(pkg) || keep.isForceUninstall(pkg) ||
                keep.isForceHide(pkg) || keep.isForceRemoveTikTokLite(pkg)
            ) {
                skipped++
                Log.i(TAG, "Skip unhide non-keep/force-removed: $pkg")
                continue
            }
            val ok = runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                .onFailure { Log.w(TAG, "Failed to unhide keep package $pkg", it) }
                .getOrDefault(false)
            if (ok) {
                restored++
                Log.i(TAG, "Unhid keep package $pkg")
            }
        }
        // Drop tracking; non-keep stay hidden at PackageManager level.
        store.replace(emptySet())
        Log.i(TAG, "unhideOnlyKeepPackages restored=$restored skippedNonKeep=$skipped")
        return restored
    }

    /**
     * Last-chance silent uninstall of every non-keep package while still Device Owner.
     * Called from [returnToPersonalUse] before clearDeviceOwner so bloat stays gone after unlock.
     */
    fun finalUninstallPassBeforeClearOwner(): Int {
        if (!isDeviceOwner()) return 0
        var requested = 0
        for (pkg in installedPackageNames()) {
            if (keep.isHardDenyUninstall(pkg)) continue
            if (keep.shouldKeep(pkg)) continue
            if (pkg == appContext.packageName) continue
            if (keep.isTrichromePackage(pkg)) continue
            if (pkg in KeepPackages.CHROME_PACKAGES) continue
            val ok = requestSilentUninstall(pkg)
            if (ok) {
                requested++
                Log.i(TAG, "Final uninstall before DO clear: $pkg")
            }
        }
        Log.i(TAG, "finalUninstallPassBeforeClearOwner requested=$requested")
        return requested
    }

    /**
     * Device Owner window only: allow USB data to a PC and keep debugging unblocked.
     *
     * Clears [UserManager.DISALLOW_DEBUGGING_FEATURES] and
     * [UserManager.DISALLOW_USB_FILE_TRANSFER]. The file-transfer restriction forces
     * charge-only and blocks USB storage. Also clears
     * [UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA] (public since API 18; soft-fail).
     * These restrictions are only cleared, never added.
     *
     * On API 31+, [DevicePolicyManager.setUsbDataSignalingEnabled] is set true every
     * apply and [DevicePolicyManager.isUsbDataSignalingEnabled] is logged.
     *
     * [DevicePolicyManager.setGlobalSetting] allowlist includes `adb_enabled` and
     * `stay_on_while_plugged_in` (AC | USB | wireless = 7). Soft-fail; read-back is logged.
     * Does not set a default USB function (MTP), enable wireless debugging, or grant
     * accessibility or overlay. USB debugging fully turning on is still limited by Android.
     *
     * Samsung One UI 8.5+ Auto Blocker (rampart) can turn USB debugging back off
     * after about 30 minutes. Both switches are written to 0 on every apply:
     * `rampart_main_switch_enabled` and `rampart_auto_enabled_switch_enabled`.
     * Devices without those keys are skipped.
     */
    private fun applyPcControllerPrep(): String {
        val cr = appContext.contentResolver
        val notes = mutableListOf<String>()

        runCatching {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            notes += "debugRestriction=cleared"
        }.onFailure { Log.w(TAG, "clear DISALLOW_DEBUGGING_FEATURES failed", it) }

        // If set, the OS forces charge-only and blocks file transfer.
        clearUserRestrictionSoft(
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            "usbFileTransfer",
            notes
        )
        clearUserRestrictionSoft(
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            "mountPhysicalMedia",
            notes
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                dpm.setUsbDataSignalingEnabled(true)
            }.onFailure { Log.w(TAG, "setUsbDataSignalingEnabled(true) failed", it) }
            val signaling = runCatching { dpm.isUsbDataSignalingEnabled() }.getOrNull()
            notes += "usbData=$signaling"
        }

        val adbWrote = writeDeviceOwnerGlobal(Settings.Global.ADB_ENABLED, "1")
        val adbRead = runCatching {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED)
        }.getOrNull()
        notes += "adb=$adbRead write=$adbWrote"

        // 1 | 2 | 4 = 7: stay awake on AC, USB, and wireless. Do not narrow this mask.
        val stayMask = (
            BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            ).toString()
        val stayWrote = writeDeviceOwnerGlobal(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stayMask)
        val stayRead = runCatching {
            Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN)
        }.getOrNull()
        notes += "stayOn=$stayRead want=$stayMask write=$stayWrote"

        for (key in listOf(
            "rampart_main_switch_enabled",
            "rampart_auto_enabled_switch_enabled",
        )) {
            val wrote = writeDeviceOwnerSecure(key, "0")
            val read = runCatching { Settings.Secure.getInt(cr, key) }.getOrNull()
            notes += "$key=$read write=$wrote"
        }

        val summary = notes.joinToString(" ")
        Log.i(TAG, "PC controller prep: $summary")
        return summary
    }

    /**
     * Keep NFC off for the whole Device Owner session.
     *
     * [UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO] (`no_near_field_communication_radio`)
     * and [UserManager.DISALLOW_OUTGOING_BEAM] are re-applied every [apply]. Android drops
     * user restrictions when Device Owner is cleared. `nfc_on=0` is a best-effort global
     * write. TagViewer (`com.android.apps.tag`) and a few tag-UI package ids are hidden
     * and suspended when installed so an empty TECH_DISCOVERED tag cannot open a white screen.
     * The NFC stack (`com.android.nfc`) is left in place so the radio restriction can be honored.
     *
     * Quick Share / Nearby Share has no separate public radio restriction. This also sets
     * [UserManager.DISALLOW_BLUETOOTH_SHARING], writes `nearby_sharing_enabled=0`, and
     * hides Samsung Quick Share packages when present. GMS itself is not hidden.
     */
    private fun applyNfcOff(hidden: MutableSet<String>): String {
        val notes = mutableListOf<String>()
        addUserRestrictionSoft(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO, notes)
        addUserRestrictionSoft(UserManager.DISALLOW_OUTGOING_BEAM, notes)

        val nfcWrote = writeDeviceOwnerGlobal("nfc_on", "0")
        val nfcRead = runCatching {
            Settings.Global.getInt(appContext.contentResolver, "nfc_on")
        }.getOrNull()
        notes += "nfc_on=$nfcRead write=$nfcWrote"

        for (pkg in NFC_TAG_UI_PACKAGES) {
            suppressPackageSoft(pkg, hidden, notes)
        }

        addUserRestrictionSoft(UserManager.DISALLOW_BLUETOOTH_SHARING, notes)
        val nearbyWrote = writeDeviceOwnerSecure("nearby_sharing_enabled", "0")
        notes += "nearby_sharing write=$nearbyWrote"
        for (pkg in QUICK_SHARE_PACKAGES) {
            suppressPackageSoft(pkg, hidden, notes)
        }

        val summary = notes.joinToString(" ")
        Log.i(TAG, "NFC off apply: $summary")
        return summary
    }

    /**
     * Turn off pocket detection and anti-misoperation while Device Owner.
     *
     * Confirmed keys are written on every [apply] (carrier brands often do not match
     * [Build.MANUFACTURER]). Missing keys and denied writes are logged and skipped.
     * Does not write `proximity_sensor` (in-call screen off) or `surface_palm_*`.
     * Does not inject `input keycombination`.
     */
    private fun applyPocketModeOff(): String {
        val cr = appContext.contentResolver
        val notes = mutableListOf<String>()

        fun noteSystem(key: String) {
            val wrote = writeDeviceOwnerSystem(key, "0")
            val read = runCatching { Settings.System.getInt(cr, key) }.getOrNull()
            notes += "sys.$key=$read write=$wrote"
        }

        fun noteSecure(key: String) {
            val wrote = writeDeviceOwnerSecure(key, "0")
            val read = runCatching { Settings.Secure.getInt(cr, key) }.getOrNull()
            notes += "sec.$key=$read write=$wrote"
        }

        fun noteGlobal(key: String) {
            val wrote = writeDeviceOwnerGlobal(key, "0")
            val read = runCatching { Settings.Global.getInt(cr, key) }.getOrNull()
            notes += "g.$key=$read write=$wrote"
        }

        // Samsung. System only — do not write proximity_sensor or surface_palm_*.
        noteSystem("screen_off_pocket")

        // OPPO.
        noteSecure("gesture_mistouch_prevention_enable")
        noteSecure("gesture_mistouch_prevention_side_enable")
        noteSystem("oplus_customize_prevent_misoperation_enabled")

        // Sony settings, plus disable the pocket-mode package for user 0.
        noteSystem("pocket_mode")
        noteSecure("pocket_mode")
        noteSecure("pocketmode2")
        disableUserPackageSoft("com.sonymobile.pocketmode2", notes)

        // Sharp / FCNT best-effort. System pocket_mode and screen_off_pocket already written.
        noteGlobal("ambient_touch_to_wake")
        noteSystem("misoperation_prevention")
        noteSecure("misoperation_prevention")
        noteSystem("anti_misoperation")
        noteSecure("anti_misoperation")
        noteSecure("screen_off_pocket")
        sendSharpProximityBroadcast(notes)

        // Xiaomi. Global writes are often denied.
        noteGlobal("enable_screen_on_proximity_sensor")

        // nubia. pocket_mode / screen_off_pocket system writes are above.
        noteSystem("cover_interface")
        noteSystem("nubia_screen_off_tp")

        val summary = notes.joinToString(" ")
        Log.i(TAG, "Pocket mode off: $summary")
        return summary
    }

    /** `pm disable-user` equivalent. Missing packages are skipped. */
    private fun disableUserPackageSoft(packageName: String, notes: MutableList<String>) {
        if (!isPackageInstalled(packageName)) {
            notes += "$packageName=absent"
            return
        }
        runCatching {
            appContext.packageManager.setApplicationEnabledSetting(
                packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0
            )
            val state = appContext.packageManager.getApplicationEnabledSetting(packageName)
            val disabled = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            notes += if (disabled) "$packageName=disabled" else "$packageName=state=$state"
            Log.i(TAG, "disable-user $packageName state=$state")
        }.onFailure {
            notes += "$packageName=fail"
            Log.w(TAG, "disable-user $packageName failed", it)
        }
    }

    /** Sharp/FCNT only. Other OEMs skip the broadcast. */
    private fun sendSharpProximityBroadcast(notes: MutableList<String>) {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val sharp = manufacturer.contains("sharp") || brand.contains("sharp") ||
            manufacturer.contains("fcnt") || brand.contains("fcnt") ||
            brand.contains("aquos")
        if (!sharp) {
            notes += "sharpBroadcast=skip"
            return
        }
        runCatching {
            appContext.sendBroadcast(
                Intent("jp.co.sharp.android.intent.action.PROXIMITY_SCREEN_ON")
            )
            notes += "sharpBroadcast=sent"
            Log.i(TAG, "Sent Sharp PROXIMITY_SCREEN_ON")
        }.onFailure {
            notes += "sharpBroadcast=fail"
            Log.w(TAG, "Sharp PROXIMITY_SCREEN_ON failed", it)
        }
    }

    /** Clear one user restriction. Never adds it. Missing or rejected keys are logged. */
    private fun clearUserRestrictionSoft(
        restriction: String,
        noteKey: String,
        notes: MutableList<String>
    ) {
        runCatching {
            dpm.clearUserRestriction(admin, restriction)
            val still = appContext.getSystemService(UserManager::class.java)
                .hasUserRestriction(restriction)
            notes += if (still) "$noteKey=still-set" else "$noteKey=cleared"
            Log.i(TAG, "clearUserRestriction $restriction still=$still")
        }.onFailure {
            notes += "$noteKey=fail"
            Log.w(TAG, "clearUserRestriction $restriction failed", it)
        }
    }

    private fun addUserRestrictionSoft(restriction: String, notes: MutableList<String>) {
        runCatching {
            dpm.addUserRestriction(admin, restriction)
            notes += "$restriction=set"
            Log.i(TAG, "addUserRestriction $restriction")
        }.onFailure {
            notes += "$restriction=fail"
            Log.w(TAG, "addUserRestriction $restriction failed", it)
        }
    }

    /** Hide and suspend one package when it is installed. Missing packages are skipped. */
    private fun suppressPackageSoft(
        packageName: String,
        hidden: MutableSet<String>,
        notes: MutableList<String>
    ) {
        if (!isPackageInstalled(packageName)) {
            notes += "$packageName=absent"
            return
        }
        val hid = runCatching {
            dpm.setApplicationHidden(admin, packageName, true)
        }.onFailure {
            Log.w(TAG, "setApplicationHidden($packageName) failed", it)
        }.getOrDefault(false)
        if (hid) hidden.add(packageName)
        val suspendFailed = runCatching {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
        }.onFailure {
            Log.w(TAG, "setPackagesSuspended($packageName) failed", it)
        }.getOrNull()
        val suspended = suspendFailed != null && packageName !in suspendFailed
        notes += "$packageName hidden=$hid suspended=$suspended"
        Log.i(TAG, "Suppressed $packageName hidden=$hid suspended=$suspended")
    }

    /** Reflective DO [DevicePolicyManager.setSystemSetting], then Settings.System.putInt. */
    private fun writeDeviceOwnerSystem(key: String, value: String): Boolean {
        var wrote = false
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            wrote = true
            Log.i(TAG, "DPM.setSystemSetting($key, $value)")
        }.onFailure { Log.w(TAG, "DPM.setSystemSetting($key) failed", it) }
        val asInt = value.toIntOrNull()
        if (asInt != null) {
            runCatching {
                if (Settings.System.putInt(appContext.contentResolver, key, asInt)) {
                    wrote = true
                    Log.i(TAG, "Settings.System.putInt($key, $asInt)")
                }
            }.onFailure { Log.w(TAG, "Settings.System.putInt($key) failed", it) }
        }
        return wrote
    }

    /** Reflective DO [DevicePolicyManager.setSecureSetting], then Settings.Secure.putInt. */
    private fun writeDeviceOwnerSecure(key: String, value: String): Boolean {
        var wrote = false
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSecureSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            wrote = true
            Log.i(TAG, "DPM.setSecureSetting($key, $value)")
        }.onFailure { Log.w(TAG, "DPM.setSecureSetting($key) failed", it) }
        val asInt = value.toIntOrNull()
        if (asInt != null) {
            runCatching {
                if (Settings.Secure.putInt(appContext.contentResolver, key, asInt)) {
                    wrote = true
                    Log.i(TAG, "Settings.Secure.putInt($key, $asInt)")
                }
            }.onFailure { Log.w(TAG, "Settings.Secure.putInt($key) failed", it) }
        }
        return wrote
    }

    /** Reflective DO [DevicePolicyManager.setGlobalSetting], then Settings.Global.putInt. */
    private fun writeDeviceOwnerGlobal(key: String, value: String): Boolean {
        var wrote = false
        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setGlobalSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, value)
            wrote = true
            Log.i(TAG, "DPM.setGlobalSetting($key, $value)")
        }.onFailure { Log.w(TAG, "DPM.setGlobalSetting($key) failed", it) }
        val asInt = value.toIntOrNull()
        if (asInt != null) {
            runCatching {
                if (Settings.Global.putInt(appContext.contentResolver, key, asInt)) {
                    wrote = true
                    Log.i(TAG, "Settings.Global.putInt($key, $asInt)")
                }
            }.onFailure { Log.w(TAG, "Settings.Global.putInt($key) failed", it) }
        }
        return wrote
    }

    /**
     * Return to personal use: final uninstall pass on non-keep, unhide **keep-list only**
     * (never restore force-removed / Yahoo / Google suite bloat), clear DO-only policies,
     * then [DevicePolicyManager.clearDeviceOwnerApp] (deprecated self-clear API).
     * Must run while still Device Owner for uninstall / unhide / clear calls to succeed.
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

        // Last moment we are still Device Owner: re-assert USB data, ADB, and stay-awake.
        // Global settings survive clearDeviceOwnerApp; user restrictions do not.
        applyPcControllerPrep()

        // Before losing DO: one more uninstall pass so hidden-only bloat is removed if possible.
        val finalUninst = finalUninstallPassBeforeClearOwner()
        Log.i(TAG, "returnToPersonalUse: finalUninstallPass=$finalUninst")

        // Do NOT unhide force-removed / non-keep — only restore keep-list / critical.
        val restored = unhideOnlyKeepPackages()

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
        // Also clear uninstall-blocked on keep-list so user can manage them after unlock.
        for (pkg in KeepPackages.HARD_DENY_UNINSTALL) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
        }
        for (pkg in KeepPackages.CHROME_PACKAGES) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
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
                Log.i(
                    TAG,
                    "Device Owner cleared; restoredKeepHidden=$restored finalUninst=$finalUninst"
                )
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
            Log.i(TAG, "Uninstall requested for $packageName")
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
     * Screen-off / sleep timeout policy (Molly):
     * 1) Prefer Never / 「消灯しない」/「スリープしない」if the device accepts it
     * 2) Else 30 minutes
     * 3) Else 10 minutes
     *
     * Writes [Settings.System.SCREEN_OFF_TIMEOUT] via putInt + reflective
     * [DevicePolicyManager.setSystemSetting], then **read-back** to see if it stuck.
     * Soft-fail per write. Does **not** call [DevicePolicyManager.setMaximumTimeToLock]
     * for Never (would fight a long timeout); only sets max lock for finite choices.
     *
     * @return actual SCREEN_OFF_TIMEOUT after writes, or null if unreadable.
     */
    private fun applyScreenTimeout(): Int? {
        // Prefer Integer.MAX_VALUE (common OEM 「消灯しない」), then other never-ish ms.
        for (candidate in SCREEN_OFF_NEVER_CANDIDATES) {
            writeScreenOffTimeout(candidate)
            val actual = currentScreenTimeoutMs()
            if (isNeverLikeTimeout(actual)) {
                // Omit setMaximumTimeToLock so it cannot force a shorter lock than Never.
                clearMaximumTimeToLockSoft()
                Log.i(TAG, "SCREEN_OFF_TIMEOUT Never stuck readBack=${actual}ms (wrote $candidate)")
                return actual
            }
            Log.i(TAG, "SCREEN_OFF_TIMEOUT Never candidate $candidate did not stick (readBack=$actual)")
        }

        // 30 minutes
        writeScreenOffTimeout(SCREEN_OFF_TIMEOUT_30_MS)
        var actual = currentScreenTimeoutMs()
        if (isAtLeastTimeout(actual, SCREEN_OFF_TIMEOUT_30_MS)) {
            setMaximumTimeToLockSoft(SCREEN_OFF_TIMEOUT_30_MS.toLong())
            Log.i(TAG, "SCREEN_OFF_TIMEOUT 30min stuck readBack=${actual}ms")
            return actual
        }
        Log.i(TAG, "SCREEN_OFF_TIMEOUT 30min did not stick / too short (readBack=$actual); try 10min")

        // 10 minutes fallback
        writeScreenOffTimeout(SCREEN_OFF_TIMEOUT_10_MS)
        actual = currentScreenTimeoutMs()
        if (actual != null) {
            setMaximumTimeToLockSoft(SCREEN_OFF_TIMEOUT_10_MS.toLong())
        }
        Log.i(TAG, "SCREEN_OFF_TIMEOUT 10min apply readBack=${actual}ms")
        return actual
    }

    private fun writeScreenOffTimeout(ms: Int) {
        runCatching {
            val ok = Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                ms
            )
            Log.i(TAG, "SCREEN_OFF_TIMEOUT put=$ok target=${ms}ms")
        }.onFailure { Log.w(TAG, "SCREEN_OFF_TIMEOUT putInt($ms) failed", it) }

        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, Settings.System.SCREEN_OFF_TIMEOUT, ms.toString())
            Log.i(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT, $ms)")
        }.onFailure {
            Log.w(TAG, "DPM.setSystemSetting(SCREEN_OFF_TIMEOUT, $ms) unavailable/failed", it)
        }
    }

    private fun setMaximumTimeToLockSoft(ms: Long) {
        runCatching {
            dpm.setMaximumTimeToLock(admin, ms)
            Log.i(TAG, "setMaximumTimeToLock(${ms}ms)")
        }.onFailure { Log.w(TAG, "setMaximumTimeToLock($ms) failed", it) }
    }

    /**
     * Clear / raise max lock so it cannot cap a Never / long SCREEN_OFF_TIMEOUT.
     * Soft-fail: 0 often means "no admin max lock limit".
     */
    private fun clearMaximumTimeToLockSoft() {
        runCatching {
            dpm.setMaximumTimeToLock(admin, 0L)
            Log.i(TAG, "setMaximumTimeToLock(0) — no max lock vs Never timeout")
        }.onFailure { Log.w(TAG, "setMaximumTimeToLock(0) failed", it) }
    }

    /** True if read-back looks like OEM 「消灯しない」/ Never. */
    private fun isNeverLikeTimeout(ms: Int?): Boolean {
        if (ms == null) return false
        if (ms < 0) return true // some OEMs use -1
        if (ms == Int.MAX_VALUE) return true
        // Very large (e.g. >= 24h) treated as Never-ish.
        return ms >= SCREEN_OFF_NEVER_THRESHOLD_MS
    }

    /** True if read-back is at least [wantMs] (allow tiny OEM rounding). */
    private fun isAtLeastTimeout(ms: Int?, wantMs: Int): Boolean {
        if (ms == null) return false
        return ms >= wantMs - 1_000
    }

    /**
     * Force 3-button navigation (not gesture / not 2-button).
     *
     * AOSP: [Settings.Secure] `navigation_mode` = 0 (3-button), 1 (2-button), 2 (gesture).
     * Prefer standard key via Secure.putInt + reflective DPM.setSecureSetting.
     * Also soft-try known Samsung / OEM companion keys. Never crashes [apply].
     *
     * @return short log summary (read-back or note).
     */
    private fun applyNavigationMode3Button(): String {
        val cr = appContext.contentResolver
        val written = mutableListOf<String>()
        val navKey = "navigation_mode" // Settings.Secure.NAVIGATION_MODE

        runCatching {
            val ok = Settings.Secure.putInt(cr, navKey, NAVIGATION_MODE_3_BUTTON)
            if (ok) written += "Secure.putInt:$navKey=0"
            Log.i(TAG, "NAVIGATION_MODE put=$ok target=0 (3-button)")
        }.onFailure { Log.w(TAG, "NAVIGATION_MODE Secure.putInt failed", it) }

        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSecureSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, navKey, NAVIGATION_MODE_3_BUTTON.toString())
            written += "DPM.setSecureSetting:$navKey=0"
            Log.i(TAG, "DPM.setSecureSetting(navigation_mode, 0)")
        }.onFailure {
            Log.w(TAG, "DPM.setSecureSetting(navigation_mode) unavailable/failed", it)
        }

        // Samsung / OEM companions — soft-fail; prefer standard NAVIGATION_MODE=0 above.
        val oemKeys = listOf(
            // Disable gesture-while-hidden / force buttons where OEMs split the toggle.
            "navigation_bar_gesture_while_hidden" to 0,
            "navigation_bar_gesture_detail_type" to 0,
            "navigationbar_gesture_hint" to 0,
            "navigation_gestures_enabled" to 0,
            "secure_gesture_navigation" to 0,
            "systemui_navigation_bar_mode" to 0,
            "sem_navbar_gesture" to 0,
            "navigation_bar_mode" to 0,
        )
        for ((key, value) in oemKeys) {
            runCatching {
                if (Settings.Secure.putInt(cr, key, value)) {
                    written += "Secure.putInt:$key=$value"
                    Log.i(TAG, "Nav 3-button OEM wrote Settings.Secure.$key=$value")
                }
            }.onFailure { Log.w(TAG, "Settings.Secure.putInt($key) failed", it) }
            runCatching {
                if (Settings.Global.putInt(cr, key, value)) {
                    written += "Global.putInt:$key=$value"
                }
            }.onFailure { /* expected */ }
            runCatching {
                val method = DevicePolicyManager::class.java.getMethod(
                    "setSecureSetting",
                    ComponentName::class.java,
                    String::class.java,
                    String::class.java
                )
                method.invoke(dpm, admin, key, value.toString())
                written += "DPM.setSecureSetting:$key=$value"
            }.onFailure { /* expected on many builds */ }
            runCatching {
                val method = DevicePolicyManager::class.java.getMethod(
                    "setGlobalSetting",
                    ComponentName::class.java,
                    String::class.java,
                    String::class.java
                )
                method.invoke(dpm, admin, key, value.toString())
                written += "DPM.setGlobalSetting:$key=$value"
            }.onFailure { /* expected */ }
        }

        val actual = runCatching { Settings.Secure.getInt(cr, navKey) }.getOrNull()
        val summary = if (written.isEmpty()) {
            "none (readBack=$actual)"
        } else {
            "ok readBack=$actual ${written.distinct().take(8).joinToString("; ")}"
        }
        Log.i(TAG, "Navigation 3-button apply: $summary")
        return summary
    }

    /**
     * Turn ON status-bar battery percentage / remaining battery display.
     *
     * Prefer AOSP/Samsung [Settings.System] `show_battery_percent` = 1, then
     * Secure/Global + known OEM keys. Reflective DPM setSystemSetting /
     * setSecureSetting / setGlobalSetting. Soft-fail; never crashes [apply].
     *
     * Distinct from lock-screen「充電情報を表示」(charging info overlay).
     *
     * @return short log summary.
     */
    private fun applyBatteryPercentOn(): String {
        val cr = appContext.contentResolver
        val written = mutableListOf<String>()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val isSamsung = manufacturer.contains("samsung") || brand.contains("samsung")

        // Common AOSP / Samsung / OEM keys for status-bar battery %.
        val keys = linkedSetOf(
            "show_battery_percent", // Settings.System.SHOW_BATTERY_PERCENT
            "status_bar_show_battery_percent",
            "display_battery_percentage",
            "battery_percentage",
            "show_battery_percentage",
            "status_bar_battery_style", // some OEMs: percent style (best-effort 1)
            "battery_percent",
            "sec_status_bar_battery_percent",
            "display_battery_percent",
            "lock_screen_show_battery_percent", // harmless if absent
        )
        if (isSamsung) {
            keys.addAll(
                listOf(
                    "display_battery_percentage",
                    "status_bar_show_battery_percent",
                    "show_battery_percent",
                )
            )
        }

        fun tryPutSystem(key: String): Boolean = runCatching {
            val ok = Settings.System.putInt(cr, key, 1)
            if (ok) {
                written += "System.putInt:$key=1"
                Log.i(TAG, "Battery% ON wrote Settings.System.$key=1")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.System.putInt($key)=1 failed", it)
        }.getOrDefault(false)

        fun tryPutSecure(key: String): Boolean = runCatching {
            val ok = Settings.Secure.putInt(cr, key, 1)
            if (ok) {
                written += "Secure.putInt:$key=1"
                Log.i(TAG, "Battery% ON wrote Settings.Secure.$key=1")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.Secure.putInt($key)=1 failed", it)
        }.getOrDefault(false)

        fun tryPutGlobal(key: String): Boolean = runCatching {
            val ok = Settings.Global.putInt(cr, key, 1)
            if (ok) {
                written += "Global.putInt:$key=1"
                Log.i(TAG, "Battery% ON wrote Settings.Global.$key=1")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.Global.putInt($key)=1 failed", it)
        }.getOrDefault(false)

        fun tryDpmSetting(methodName: String, key: String): Boolean = runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                methodName,
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, "1")
            written += "DPM.$methodName:$key=1"
            Log.i(TAG, "Battery% ON DPM.$methodName($key, 1)")
            true
        }.onFailure {
            Log.w(TAG, "DPM.$methodName($key)=1 unavailable/failed", it)
        }.getOrDefault(false)

        fun trySemSettings(key: String): Boolean = runCatching {
            val sem = Class.forName("android.provider.SemSettings\$System")
            val putInt = sem.getMethod(
                "putInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val ok = putInt.invoke(null, cr, key, 1) as? Boolean ?: true
            if (ok) {
                written += "SemSettings.System.putInt:$key=1"
                Log.i(TAG, "Battery% ON SemSettings.System.putInt($key, 1)")
            }
            ok
        }.onFailure {
            Log.w(TAG, "SemSettings.System.putInt($key)=1 unavailable/failed", it)
        }.getOrDefault(false)

        for (key in keys) {
            tryPutSystem(key)
            tryPutSecure(key)
            tryPutGlobal(key)
            tryDpmSetting("setSystemSetting", key)
            tryDpmSetting("setSecureSetting", key)
            tryDpmSetting("setGlobalSetting", key)
            if (isSamsung) trySemSettings(key)
        }
        // Extra SemSettings for preferred keys even if brand string odd.
        if (!isSamsung) {
            for (key in listOf("show_battery_percent", "display_battery_percentage")) {
                trySemSettings(key)
            }
        }

        val primary = runCatching {
            Settings.System.getInt(cr, "show_battery_percent")
        }.getOrNull()
        val distinct = written.distinct()
        val summary = if (distinct.isEmpty()) {
            "none (readBack show_battery_percent=$primary; tried=${keys.size} keys)"
        } else {
            val head = distinct.take(10).joinToString("; ")
            val more = if (distinct.size > 10) " …(+${distinct.size - 10})" else ""
            "ok readBack=$primary $head$more"
        }
        Log.i(TAG, "Battery% ON apply: $summary")
        return summary
    }

    /**
     * Lock the screen to portrait for new Device Owner setups.
     *
     * Writes [Settings.System.ACCELEROMETER_ROTATION] = 0 (auto-rotate off) and
     * [Settings.System.USER_ROTATION] = 0 (portrait) on every [apply]. Each key uses
     * [Settings.System.putInt] plus reflective [DevicePolicyManager.setSystemSetting]
     * when that SystemApi is present, then both values are read back into the log.
     *
     * Soft-fail: OEM blocks are logged only; never crashes [apply]. Does not hide
     * the system auto-rotate toggle.
     *
     * @return short log summary (read-back values or note).
     */
    private fun applyAutoRotateOff(): String {
        val cr = appContext.contentResolver
        val written = mutableListOf<String>()

        runCatching {
            val ok = Settings.System.putInt(
                cr,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )
            if (ok) written += "System.putInt:ACCELEROMETER_ROTATION=0"
            Log.i(TAG, "ACCELEROMETER_ROTATION put=$ok target=0")
        }.onFailure { Log.w(TAG, "ACCELEROMETER_ROTATION putInt failed", it) }

        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, Settings.System.ACCELEROMETER_ROTATION, "0")
            written += "DPM.setSystemSetting:ACCELEROMETER_ROTATION=0"
            Log.i(TAG, "DPM.setSystemSetting(ACCELEROMETER_ROTATION, 0)")
        }.onFailure {
            Log.w(TAG, "DPM.setSystemSetting(ACCELEROMETER_ROTATION) unavailable/failed", it)
        }

        // Portrait. 0 is Surface.ROTATION_0. Re-applied every pass so a later
        // manual rotation is put back on the next policy apply.
        runCatching {
            val ok = Settings.System.putInt(
                cr,
                Settings.System.USER_ROTATION,
                0
            )
            if (ok) written += "System.putInt:USER_ROTATION=0"
            Log.i(TAG, "USER_ROTATION put=$ok target=0")
        }.onFailure { Log.w(TAG, "USER_ROTATION putInt failed", it) }

        runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                "setSystemSetting",
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, Settings.System.USER_ROTATION, "0")
            written += "DPM.setSystemSetting:USER_ROTATION=0"
            Log.i(TAG, "DPM.setSystemSetting(USER_ROTATION, 0)")
        }.onFailure {
            Log.w(TAG, "DPM.setSystemSetting(USER_ROTATION) unavailable/failed", it)
        }

        // Some OEMs mirror the toggle under alternate keys; harmless if absent.
        for (key in listOf("accelerometer_rotation", "auto_rotate", "screen_auto_rotation")) {
            if (key == Settings.System.ACCELEROMETER_ROTATION) continue
            runCatching {
                if (Settings.System.putInt(cr, key, 0)) {
                    written += "System.putInt:$key=0"
                    Log.i(TAG, "Auto-rotate OFF wrote Settings.System.$key=0")
                }
            }.onFailure { Log.w(TAG, "Settings.System.putInt($key) failed", it) }
            runCatching {
                val method = DevicePolicyManager::class.java.getMethod(
                    "setSystemSetting",
                    ComponentName::class.java,
                    String::class.java,
                    String::class.java
                )
                method.invoke(dpm, admin, key, "0")
                written += "DPM.setSystemSetting:$key=0"
            }.onFailure { /* expected on many builds */ }
        }

        val actual = runCatching {
            Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION)
        }.getOrNull()
        val userRotation = runCatching {
            Settings.System.getInt(cr, Settings.System.USER_ROTATION)
        }.getOrNull()
        val summary = if (written.isEmpty()) {
            "none (readBack=$actual userRotation=$userRotation)"
        } else {
            "ok readBack=$actual userRotation=$userRotation ${written.distinct().joinToString("; ")}"
        }
        Log.i(TAG, "Portrait lock apply: $summary")
        return summary
    }

    /**
     * Force-remove Google app / search packages (TikTok-style): prefer silent uninstall,
     * then hide (+ disable) when system/uninstall fails or stub remains.
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

            val requested = requestSilentUninstall(pkg)
            if (requested) {
                uninstN++
                hidden.remove(pkg)
                notes += "$pkg=uninstall_req"
                Log.i(TAG, "Google uninstall requested: $pkg")
            }

            val system = isSystemOrUpdatedSystemApp(pkg)
            if (system || !requested) {
                val hideOk = runCatching {
                    dpm.setApplicationHidden(admin, pkg, true)
                }.onFailure {
                    Log.w(TAG, "Failed to hide Google package $pkg", it)
                }.getOrDefault(false)
                if (hideOk) {
                    hiddenN++
                    hidden.add(pkg)
                    notes += "$pkg=hidden"
                    Log.i(
                        TAG,
                        "Google hidden (fallback): $pkg " +
                            "(system=$system uninstallRequested=$requested)"
                    )
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
            }
        }

        val detail = if (touched.isEmpty()) {
            "Googleアプリ系なし"
        } else {
            "非表示 $hiddenN / アンインストール要求 $uninstN — ${notes.joinToString("; ")}"
        }
        Log.i(TAG, "Google force-remove: $detail")
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

    /**
     * Aggressive force-remove for [KeepPackages.FORCE_UNINSTALL] + heuristics
     * (Google suite non-Chrome, Yahoo / Y!mobile / SoftBank / UQ / nubia / PayPay).
     * Prefer silent uninstall; hide (+ disable) when system/uninstall fails or stub remains.
     * Never touches Chrome / Play / Settings / LINE / Alive / CRITICAL.
     *
     * @return number of uninstall requests submitted
     */
    private fun forceUninstallAggressive(hidden: MutableSet<String>): Int {
        var uninstallRequested = 0
        val candidates = linkedSetOf<String>()
        candidates.addAll(KeepPackages.FORCE_UNINSTALL)
        for (pkg in installedPackageNames()) {
            if (keep.isForceUninstall(pkg)) candidates.add(pkg)
        }

        for (pkg in candidates) {
            if (!isPackageInstalled(pkg)) continue
            if (!keep.isForceUninstall(pkg)) continue
            // Skip if somehow protected (defense in depth).
            if (keep.isProtectedKeepCore(pkg)) continue
            if (keep.isHardDenyUninstall(pkg)) continue

            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }

            val requested = requestSilentUninstall(pkg)
            if (requested) {
                uninstallRequested++
                hidden.remove(pkg)
                Log.i(TAG, "FORCE_UNINSTALL requested: $pkg")
            }

            val system = isSystemOrUpdatedSystemApp(pkg)
            // Always hide fallback for force-uninstall targets that remain (incl. after request),
            // so「個人用に戻す」cannot restore them via unhide.
            if (isPackageInstalled(pkg)) {
                val hideOk = runCatching {
                    dpm.setApplicationHidden(admin, pkg, true)
                }.onFailure {
                    Log.w(TAG, "Failed to hide FORCE_UNINSTALL package $pkg", it)
                }.getOrDefault(false)
                if (hideOk) {
                    hidden.add(pkg)
                    Log.i(
                        TAG,
                        "FORCE_UNINSTALL hidden (fallback): $pkg " +
                            "(system=$system uninstallRequested=$requested)"
                    )
                }
                runCatching {
                    appContext.packageManager.setApplicationEnabledSetting(
                        pkg,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                        0
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to disable FORCE_UNINSTALL package $pkg", it)
                }
            }
        }
        Log.i(TAG, "FORCE_UNINSTALL aggressive: uninstallRequested=$uninstallRequested")
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
     * Turn OFF OEM「充電情報を表示」(Show charging information) on the lock screen
     * while charging (battery % / time-to-full overlay). Samsung Galaxy A23 / One UI
     * path: Settings → Display → 充電情報を表示. Also probes Sharp/AQUOS Sense /
     * Kyocera Sense-style keys.
     *
     * Best-effort: write 0 / "0" / false via Settings.System / Secure / Global,
     * reflective DPM setSystemSetting / setSecureSetting / setGlobalSetting,
     * and Samsung SemSettings.putInt when present. Never crashes apply().
     *
     * Confirmed keys are attempted first, in their real namespace:
     * Samsung System `charging_info_always` (not `aod_charging_mode`),
     * Nubia/ZTE System `charging_indicator`, OPPO Secure
     * `oplus_keyguard_charge_anim_show`, and Sharp `settings_ex`
     * `display_charging_when_screen_off` via [trySharpSettingsExOff].
     * FCG01 and A202SO have no known key and are left alone.
     *
     * Does **not** touch `show_battery_percent` (status-bar battery %).
     *
     * @return short log summary of successful writes (or none).
     */
    private fun applyChargingInfoOff(): String {
        val cr = appContext.contentResolver
        val written = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val isSamsung = manufacturer.contains("samsung") || brand.contains("samsung")
        val isSenseOem =
            manufacturer.contains("sharp") || brand.contains("sharp") ||
                manufacturer.contains("kyocera") || brand.contains("kyocera") ||
                manufacturer.contains("fcnt") || brand.contains("fcnt") ||
                brand.contains("aquos")

        // Confirmed real-device keys first, then older generic probes.
        // Sharp `display_charging_when_screen_off` is settings_ex only — not listed here.
        // `aod_charging_mode` is intentionally absent.
        val keys = linkedSetOf(
            "charging_info_always",
            "charging_indicator",
            "oplus_keyguard_charge_anim_show",
            "show_charging_info",
            "sec_show_charging_info",
            "lock_screen_show_charging_info",
            "charging_info",
            "display_charging_info",
            "charging_information",
            "lockscreen_show_charging_info",
            "lock_screen_charging_info",
            "show_charging_information",
            "sec_lock_screen_show_charging_info",
            // Custom-ROM / AOSP-adjacent cousins (harmless if absent)
            "lockscreen_battery_info",
            "lockscreen_charging_info",
        )
        if (isSenseOem) {
            keys.addAll(
                listOf(
                    "show_charging_info",
                    "charging_info_display",
                    "display_charging_information",
                    "jp_show_charging_info",
                    "sense_show_charging_info",
                    "aquos_show_charging_info",
                )
            )
            notes += "senseOem=true"
            Log.i(TAG, "Charging-info OFF: Sense/Sharp/Kyocera OEM probe (keys=${keys.size})")
        }
        if (isSamsung) {
            notes += "samsung=true"
            Log.i(TAG, "Charging-info OFF: Samsung One UI probe")
        }

        fun tryPutSystem(key: String): Boolean {
            return runCatching {
                val ok = Settings.System.putInt(cr, key, 0)
                if (ok) {
                    written += "System.putInt:$key=0"
                    Log.i(TAG, "Charging-info OFF wrote Settings.System.$key=0")
                }
                ok
            }.onFailure {
                Log.w(TAG, "Settings.System.putInt($key) failed", it)
            }.getOrDefault(false)
        }

        fun tryPutSecure(key: String): Boolean {
            return runCatching {
                val ok = Settings.Secure.putInt(cr, key, 0)
                if (ok) {
                    written += "Secure.putInt:$key=0"
                    Log.i(TAG, "Charging-info OFF wrote Settings.Secure.$key=0")
                }
                ok
            }.onFailure {
                Log.w(TAG, "Settings.Secure.putInt($key) failed", it)
            }.getOrDefault(false)
        }

        fun tryPutGlobal(key: String): Boolean {
            return runCatching {
                val ok = Settings.Global.putInt(cr, key, 0)
                if (ok) {
                    written += "Global.putInt:$key=0"
                    Log.i(TAG, "Charging-info OFF wrote Settings.Global.$key=0")
                }
                ok
            }.onFailure {
                Log.w(TAG, "Settings.Global.putInt($key) failed", it)
            }.getOrDefault(false)
        }

        fun tryDpmSetting(methodName: String, key: String): Boolean {
            return runCatching {
                val method = DevicePolicyManager::class.java.getMethod(
                    methodName,
                    ComponentName::class.java,
                    String::class.java,
                    String::class.java
                )
                method.invoke(dpm, admin, key, "0")
                written += "DPM.$methodName:$key=0"
                Log.i(TAG, "Charging-info OFF DPM.$methodName($key, 0)")
                true
            }.onFailure {
                // Method missing or OEM reject — expected on many builds.
                Log.w(TAG, "DPM.$methodName($key) unavailable/failed", it)
            }.getOrDefault(false)
        }

        fun trySemSettings(key: String): Boolean {
            return runCatching {
                val sem = Class.forName("android.provider.SemSettings\$System")
                val putInt = sem.getMethod(
                    "putInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                val ok = putInt.invoke(null, cr, key, 0) as? Boolean ?: true
                if (ok) {
                    written += "SemSettings.System.putInt:$key=0"
                    Log.i(TAG, "Charging-info OFF SemSettings.System.putInt($key, 0)")
                }
                ok
            }.onFailure {
                Log.w(TAG, "SemSettings.System.putInt($key) unavailable/failed", it)
            }.getOrDefault(false)
        }

        // Confirmed namespace writes, before the generic spray.
        // Samsung One UI (SC-56C / SCG18). Do not write aod_charging_mode.
        tryPutSystem("charging_info_always")
        tryDpmSetting("setSystemSetting", "charging_info_always")
        trySemSettings("charging_info_always")
        // SoftBank Nubia/ZTE (Z6305R / A403ZT).
        tryPutSystem("charging_indicator")
        tryDpmSetting("setSystemSetting", "charging_indicator")
        // OPPO (OPG06) keyguard charge animation.
        tryPutSecure("oplus_keyguard_charge_anim_show")
        tryDpmSetting("setSecureSetting", "oplus_keyguard_charge_anim_show")
        val sharpEx = trySharpSettingsExOff()
        if (sharpEx.isNotEmpty()) {
            written += sharpEx
        }

        for (key in keys) {
            if (key == "aod_charging_mode") continue
            tryPutSystem(key)
            tryPutSecure(key)
            tryPutGlobal(key)
            tryDpmSetting("setSystemSetting", key)
            tryDpmSetting("setSecureSetting", key)
            tryDpmSetting("setGlobalSetting", key)
            if (isSamsung) {
                trySemSettings(key)
            }
        }

        // Extra SemSettings pass for preferred Samsung keys even if brand string odd.
        if (!isSamsung) {
            for (key in listOf(
                "charging_info_always",
                "show_charging_info",
                "sec_show_charging_info",
            )) {
                trySemSettings(key)
            }
        }

        val summary = if (written.isEmpty()) {
            "none (${notes.joinToString(",")}; tried=${keys.size} keys)"
        } else {
            "ok ${written.distinct().joinToString("; ")}"
        }
        Log.i(TAG, "Charging-info OFF apply: $summary")
        return summary
    }

    /**
     * Sharp Aquos (SHG10 / SH-M24 / SH-53C / SH-54D) stores「充電情報を表示」in the
     * OEM namespace `settings_ex`, key `display_charging_when_screen_off`, not in
     * Settings.System / Secure / Global. There is no public DevicePolicyManager
     * setter for that namespace.
     *
     * Attempted, all soft-fail, on every apply:
     * 1. [ContentProviderClient.call] `PUT_ex` / `PUT_settings_ex` on authorities
     *    `settings`, `jp.co.sharp.android.providers.settings`, and
     *    `jp.co.sharp.android.providers.settings.ex` (AOSP NameValueCache shape:
     *    arg = key, extras `value` = "0"), then `GET_*` read-back.
     * 2. insert/update of a name/value row at `content://<authority>/{ex,settings_ex}`.
     * 3. Reflective `putInt` / `putString` on `android.provider.SettingsEx`,
     *    `android.provider.Settings$Ex`, and `jp.co.sharp.android.provider(s).settings.SettingsEx`
     *    (`$System` included).
     *
     * A call that does not throw is logged, and counted only when read-back is "0"
     * or insert/update/reflection reports success. Missing provider/class is skipped.
     *
     * @return short note for the apply log (empty when nothing was confirmed).
     */
    private fun trySharpSettingsExOff(): String {
        val key = "display_charging_when_screen_off"
        val value = "0"
        val cr = appContext.contentResolver
        val confirmed = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        val authorities = listOf(
            "settings",
            "jp.co.sharp.android.providers.settings",
            "jp.co.sharp.android.providers.settings.ex",
        )
        val callPairs = listOf(
            "PUT_ex" to "GET_ex",
            "PUT_settings_ex" to "GET_settings_ex",
        )

        fun client(authority: String): ContentProviderClient? {
            return runCatching { cr.acquireUnstableContentProviderClient(authority) }
                .getOrNull()
        }

        fun readCall(authority: String, method: String): String? {
            val c = client(authority) ?: return null
            return try {
                c.call(method, key, null)?.getString(Settings.NameValueTable.VALUE)
            } catch (_: Throwable) {
                null
            } finally {
                c.close()
            }
        }

        for (authority in authorities) {
            for ((putMethod, getMethod) in callPairs) {
                val c = client(authority)
                if (c == null) {
                    attempts += "$putMethod@$authority:no-provider"
                    continue
                }
                val putOk = try {
                    val extras = Bundle()
                    extras.putString(Settings.NameValueTable.VALUE, value)
                    c.call(putMethod, key, extras)
                    true
                } catch (t: Throwable) {
                    attempts += "$putMethod@$authority:${t.javaClass.simpleName}"
                    false
                } finally {
                    c.close()
                }
                if (!putOk) continue
                val readBack = readCall(authority, getMethod)
                if (readBack == value) {
                    confirmed += "$putMethod@$authority readBack=0"
                } else {
                    attempts += "$putMethod@$authority:accepted readBack=$readBack"
                }
            }
            for (path in listOf("ex", "settings_ex")) {
                val uri = Uri.parse("content://$authority/$path")
                val row = ContentValues().apply {
                    put(Settings.NameValueTable.NAME, key)
                    put(Settings.NameValueTable.VALUE, value)
                }
                val inserted = runCatching { cr.insert(uri, row) }.getOrNull()
                if (inserted != null) {
                    confirmed += "insert:$uri"
                }
                val updated = runCatching {
                    cr.update(uri, row, "name=?", arrayOf(key))
                }.getOrDefault(-1)
                if (updated > 0) {
                    confirmed += "update:$uri"
                }
                if (inserted == null && updated <= 0) {
                    attempts += "row@$uri:miss"
                }
            }
        }

        val classes = listOf(
            "android.provider.SettingsEx",
            "android.provider.Settings\$Ex",
            "jp.co.sharp.android.provider.SettingsEx",
            "jp.co.sharp.android.provider.SettingsEx\$System",
            "jp.co.sharp.android.providers.settings.SettingsEx",
            "jp.co.sharp.android.providers.settings.SettingsEx\$System",
            "jp.co.sharp.android.os.SettingsEx",
        )
        for (className in classes) {
            val reflected = runCatching { reflectSharpSettingsExPut(className, key, 0) }
                .getOrElse { t ->
                    attempts += "$className:${t.javaClass.simpleName}"
                    false
                }
            if (reflected) {
                confirmed += "reflect:$className"
            } else if (attempts.none { it.startsWith(className) }) {
                attempts += "$className:miss"
            }
        }

        val readBack = authorities.firstNotNullOfOrNull { authority ->
            callPairs.firstNotNullOfOrNull { (_, getMethod) -> readCall(authority, getMethod) }
        }
        val summary = buildString {
            append("settings_ex:$key")
            append(" readBack=")
            append(readBack ?: "unread")
            if (confirmed.isNotEmpty()) {
                append(" ok=")
                append(confirmed.distinct().joinToString("|"))
            }
            if (attempts.isNotEmpty()) {
                append(" fail=")
                append(attempts.distinct().take(8).joinToString("|"))
            }
        }
        Log.i(TAG, "Charging-info Sharp settings_ex: $summary")
        return if (confirmed.isEmpty()) "" else summary
    }

    /** Reflective putInt/putString on a Sharp settings_ex helper. False if absent. */
    private fun reflectSharpSettingsExPut(className: String, key: String, value: Int): Boolean {
        val clazz = try {
            Class.forName(className)
        } catch (_: ClassNotFoundException) {
            return false
        }
        val cr = appContext.contentResolver
        val putInt = clazz.methods.firstOrNull { method ->
            method.name == "putInt" &&
                method.parameterTypes.size == 3 &&
                android.content.ContentResolver::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                java.lang.reflect.Modifier.isStatic(method.modifiers)
        }
        if (putInt != null) {
            val result = putInt.invoke(null, cr, key, value)
            return result as? Boolean ?: true
        }
        val putString = clazz.methods.firstOrNull { method ->
            method.name == "putString" &&
                method.parameterTypes.size == 3 &&
                java.lang.reflect.Modifier.isStatic(method.modifiers)
        }
        if (putString != null) {
            val result = putString.invoke(null, cr, key, value.toString())
            return result as? Boolean ?: true
        }
        return false
    }

    /**
     * Best-effort OFF for Japanese「緊急速報メール」/ Wireless Emergency Alerts /
     * cell-broadcast (ETWS/CMAS) on Galaxy A23, Sense, SoftBank/Y!mobile/UQ etc.
     *
     * 1) Write 0 to known Settings.System / Secure / Global keys + reflective DPM /
     *    SemSettings puts (same pattern as [applyChargingInfoOff]).
     * 2) Hide / disable known cell-broadcast / carrier emergency-mail packages when present.
     *    Never touches SMS / phone / dialer / messaging keep-list packages.
     * 3) Does not open Settings UI.
     *
     * @return short log summary.
     */
    private fun applyEmergencyAlertsOff(hidden: MutableSet<String>): String {
        val cr = appContext.contentResolver
        val written = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val isSamsung = manufacturer.contains("samsung") || brand.contains("samsung")
        val isSenseOem =
            manufacturer.contains("sharp") || brand.contains("sharp") ||
                manufacturer.contains("kyocera") || brand.contains("kyocera") ||
                manufacturer.contains("fcnt") || brand.contains("fcnt") ||
                brand.contains("aquos")

        val keys = linkedSetOf(
            "enable_alerts_master_toggle",
            "enable_emergency_alerts",
            "enable_cmas_extreme_threat_alerts",
            "enable_cmas_severe_threat_alerts",
            "enable_cmas_amber_alerts",
            "enable_cmas_presidential_alerts",
            "enable_alert_vibrate",
            "enable_alert_speech",
            "enable_public_safety_messages",
            "enable_area_update_info_alerts",
            "enable_test_alerts",
            "enable_exercise_alerts",
            "enable_operator_defined_alerts",
            "enable_state_local_test_alerts",
            "cell_broadcast_enabled",
            "cell_broadcast_sms",
            "cdma_cell_broadcast_sms",
            "emergency_tone",
            "emergency_alert",
            "emergency_alerts",
            "emergency_alert_reminder_interval",
            "wireless_emergency_alerts",
            "show_emergency_alerts",
            "receive_emergency_alerts",
            "etws_alert_enabled",
            "cmas_alert_enabled",
            "jp_emergency_alert",
            "jp_emergency_mail",
            "area_mail_enabled",
            "emergency_mail_enabled",
            "sec_emergency_alert",
            "sec_emergency_alerts",
            "samsung_emergency_alert",
        )
        if (isSenseOem) {
            keys.addAll(
                listOf(
                    "sense_emergency_alert",
                    "aquos_emergency_alert",
                    "jp_emergency_sokuhou",
                )
            )
            notes += "senseOem=true"
        }
        if (isSamsung) notes += "samsung=true"

        fun tryPutSystem(key: String): Boolean = runCatching {
            val ok = Settings.System.putInt(cr, key, 0)
            if (ok) {
                written += "System.putInt:$key=0"
                Log.i(TAG, "Emergency-alert OFF wrote Settings.System.$key=0")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.System.putInt($key) failed", it)
        }.getOrDefault(false)

        fun tryPutSecure(key: String): Boolean = runCatching {
            val ok = Settings.Secure.putInt(cr, key, 0)
            if (ok) {
                written += "Secure.putInt:$key=0"
                Log.i(TAG, "Emergency-alert OFF wrote Settings.Secure.$key=0")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.Secure.putInt($key) failed", it)
        }.getOrDefault(false)

        fun tryPutGlobal(key: String): Boolean = runCatching {
            val ok = Settings.Global.putInt(cr, key, 0)
            if (ok) {
                written += "Global.putInt:$key=0"
                Log.i(TAG, "Emergency-alert OFF wrote Settings.Global.$key=0")
            }
            ok
        }.onFailure {
            Log.w(TAG, "Settings.Global.putInt($key) failed", it)
        }.getOrDefault(false)

        fun tryDpmSetting(methodName: String, key: String): Boolean = runCatching {
            val method = DevicePolicyManager::class.java.getMethod(
                methodName,
                ComponentName::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(dpm, admin, key, "0")
            written += "DPM.$methodName:$key=0"
            Log.i(TAG, "Emergency-alert OFF DPM.$methodName($key, 0)")
            true
        }.onFailure {
            Log.w(TAG, "DPM.$methodName($key) unavailable/failed", it)
        }.getOrDefault(false)

        fun trySemSettings(key: String): Boolean = runCatching {
            val sem = Class.forName("android.provider.SemSettings\$System")
            val putInt = sem.getMethod(
                "putInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val ok = putInt.invoke(null, cr, key, 0) as? Boolean ?: true
            if (ok) {
                written += "SemSettings.System.putInt:$key=0"
                Log.i(TAG, "Emergency-alert OFF SemSettings.System.putInt($key, 0)")
            }
            ok
        }.onFailure {
            Log.w(TAG, "SemSettings.System.putInt($key) unavailable/failed", it)
        }.getOrDefault(false)

        for (key in keys) {
            tryPutSystem(key)
            tryPutSecure(key)
            tryPutGlobal(key)
            tryDpmSetting("setSystemSetting", key)
            tryDpmSetting("setSecureSetting", key)
            tryDpmSetting("setGlobalSetting", key)
            if (isSamsung) trySemSettings(key)
        }

        val hidePkgs = linkedSetOf(
            "com.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver.module",
            "com.google.android.cellbroadcastreceiver",
            "com.samsung.android.cellbroadcastreceiver",
            "com.samsung.android.app.telephonyui.cellbroadcast",
            "jp.co.softbank.emergencymail",
            "jp.softbank.mb.emergencymail",
            "com.softbank.emergencymail",
            "com.kddi.android.emg",
            "com.kddi.disasterapp",
            "com.kddi.android.cmail",
            "jp.au.emergencymail",
            "com.nttdocomo.android.areamail",
            "jp.co.nttdocomo.areamail",
            "jp.co.sharp.android.safetyalert",
            "jp.co.rakuten.mobile.emergencymail",
        )
        val neverHide = setOf(
            "com.android.mms",
            "com.android.mms.service",
            "com.android.messaging",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.systemui",
        )
        var hideOk = 0
        var hideSkip = 0
        for (pkg in hidePkgs) {
            if (pkg in neverHide) {
                hideSkip++
                continue
            }
            if (keep.isProtectedKeepCore(pkg) || keep.shouldKeep(pkg)) {
                hideSkip++
                Log.i(TAG, "Emergency-alert OFF skip keep package $pkg")
                continue
            }
            if (!isPackageInstalled(pkg)) continue
            val ok = runCatching { dpm.setApplicationHidden(admin, pkg, true) }.getOrDefault(false)
            if (ok) {
                hidden += pkg
                hideOk++
                written += "hide:$pkg"
                Log.i(TAG, "Emergency-alert OFF hid $pkg")
            } else {
                Log.w(TAG, "Emergency-alert OFF failed to hide $pkg")
            }
        }
        notes += "hideOk=$hideOk hideSkip=$hideSkip"

        for (pkg in hidePkgs) {
            if (pkg in neverHide || !isPackageInstalled(pkg)) continue
            if (keep.isProtectedKeepCore(pkg) || keep.shouldKeep(pkg)) continue
            runCatching {
                appContext.packageManager.setApplicationEnabledSetting(
                    pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    0
                )
                written += "disable:$pkg"
                Log.i(TAG, "Emergency-alert OFF disabled $pkg")
            }.onFailure {
                Log.w(TAG, "Emergency-alert OFF disable $pkg failed", it)
            }
        }

        val distinct = written.distinct()
        val summary = if (distinct.isEmpty()) {
            "none (${notes.joinToString(",")}; tried=${keys.size} keys)"
        } else {
            val head = distinct.take(12).joinToString("; ")
            if (distinct.size > 12) "ok $head …(+${distinct.size - 12})" else "ok $head"
        }
        Log.i(TAG, "Emergency-alert OFF apply: $summary notes=$notes")
        return summary
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

        /** Tag UI that opens a white screen on empty TECH_DISCOVERED tags. Not the NFC stack. */
        private val NFC_TAG_UI_PACKAGES = listOf(
            "com.android.apps.tag",
            "com.google.android.tag",
            "com.samsung.android.tag",
        )

        /** Separate Quick Share APKs. Nearby Share inside GMS is not in this list. */
        private val QUICK_SHARE_PACKAGES = listOf(
            "com.samsung.android.app.sharelive",
            "com.samsung.android.sharelive",
        )
        /** Preferred finite screen-off timeout: 30 minutes (ms). */
        const val SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000
        /** Fallback finite screen-off timeout: 10 minutes (ms). */
        const val SCREEN_OFF_TIMEOUT_30_MS = 30 * 60 * 1000
        const val SCREEN_OFF_TIMEOUT_10_MS = 10 * 60 * 1000
        /**
         * OEM 「消灯しない」candidates for [Settings.System.SCREEN_OFF_TIMEOUT].
         * [Int.MAX_VALUE] is the common AOSP/OEM Never value; extras cover clamps.
         */
        val SCREEN_OFF_NEVER_CANDIDATES: IntArray = intArrayOf(
            Int.MAX_VALUE,
            Int.MAX_VALUE - 1,
            24 * 60 * 60 * 1000, // 24h — some OEMs max spinner
            12 * 60 * 60 * 1000,
        )
        /** Read-back ≥ this treated as Never-like (24h). */
        const val SCREEN_OFF_NEVER_THRESHOLD_MS = 24 * 60 * 60 * 1000
        /** Settings.Secure.NAVIGATION_MODE: 0 = 3-button. */
        const val NAVIGATION_MODE_3_BUTTON = 0
        /** System language for Japan provisioning / policy reapply. */
        const val TARGET_LOCALE_TAG = "ja-JP"
        /** IANA time zone for Japan. */
        const val TARGET_TIME_ZONE = "Asia/Tokyo"
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_ALL

        private const val AUTO_RETURN_PREFS = "igni_auto_return"
        private const val KEY_AUTO_RETURN_AFTER_LINE = "after_line_started"
        /** Max wait for Alive silent install before clearing Device Owner. */
        private const val ALIVE_WAIT_MS = 90_000L

        private val autoReturnExecutor = Executors.newSingleThreadExecutor()
        private val autoReturnInFlight = AtomicBoolean(false)

        /**
         * After a **real** LINE PackageInstaller [android.content.pm.PackageInstaller.STATUS_SUCCESS]
         * (via [app.igni.dpc.LineInstallStatusReceiver] → [LineInstaller.persistSuccess]),
         * schedule the same「個人用に戻す」flow as the Admin button ([returnToPersonalUse]).
         *
         * Guards:
         * - Only while currently Device Owner (personal mode: no-op).
         * - One-shot per enrollment (prefs + in-flight flag); not on already-installed no-op.
         * - Prefer Alive finish first when Alive is still installing; if Alive already
         *   installed / not busy, clear immediately (short grace).
         */
        fun scheduleAutoReturnAfterLineSuccess(context: Context) {
            val app = context.applicationContext
            val applier = PolicyApplier(app)
            if (!applier.isDeviceOwner()) {
                Log.i(TAG, "auto-return after LINE: not Device Owner; skip")
                return
            }
            val prefs = app.getSharedPreferences(AUTO_RETURN_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_AUTO_RETURN_AFTER_LINE, false)) {
                Log.i(TAG, "auto-return after LINE: already started once; skip")
                return
            }
            if (!autoReturnInFlight.compareAndSet(false, true)) {
                Log.i(TAG, "auto-return after LINE: already in flight; skip")
                return
            }
            prefs.edit().putBoolean(KEY_AUTO_RETURN_AFTER_LINE, true).apply()
            Log.i(TAG, "auto-return after LINE: scheduling (wait Alive if needed)")
            autoReturnExecutor.execute {
                try {
                    waitForAliveBeforeClear(app)
                    val current = PolicyApplier(app)
                    if (!current.isDeviceOwner()) {
                        Log.i(TAG, "auto-return after LINE: no longer DO; skip clear")
                        LineInstaller.persistAutoReturnDetail(app, "インストール成功（既に個人用）")
                        return@execute
                    }
                    LineInstaller.persistAutoReturnDetail(app, "インストール成功。個人用に戻しています…")
                    val result = current.returnToPersonalUse()
                    Log.i(
                        TAG,
                        "auto-return after LINE: done success=${result.success} msg=${result.message} " +
                            "restored=${result.restoredHidden}"
                    )
                    val detail = when {
                        result.success -> "インストール成功。個人用に戻しました"
                        result.message == "not_device_owner" -> "インストール成功（既に個人用）"
                        else -> "インストール成功。個人用復帰失敗 (${result.message})"
                    }
                    LineInstaller.persistAutoReturnDetail(app, detail)
                } catch (t: Throwable) {
                    Log.e(TAG, "auto-return after LINE crashed", t)
                    runCatching {
                        LineInstaller.persistAutoReturnDetail(
                            app,
                            "インストール成功。個人用復帰エラー"
                        )
                    }
                } finally {
                    autoReturnInFlight.set(false)
                }
            }
        }

        /**
         * If Alive is already installed → return immediately.
         * If Alive install is busy → wait up to [ALIVE_WAIT_MS].
         * If not busy and not installed → short grace then proceed (Alive failed / not needed).
         */
        private fun waitForAliveBeforeClear(app: Context) {
            if (AliveInstaller.isAliveInstalled(app)) {
                Log.i(TAG, "auto-return: Alive already installed; clear immediately")
                return
            }
            val deadline = System.currentTimeMillis() + ALIVE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (AliveInstaller.isAliveInstalled(app)) {
                    Log.i(TAG, "auto-return: Alive became installed; proceeding")
                    return
                }
                if (AliveInstaller.isBusy()) {
                    Log.i(TAG, "auto-return: Alive still busy; waiting…")
                    Thread.sleep(2_000L)
                    continue
                }
                // Not busy: give status receiver a moment, then proceed.
                val status = AliveInstaller.lastRawStatus(app)
                Log.i(TAG, "auto-return: Alive not busy (status=$status); short grace then clear")
                Thread.sleep(3_000L)
                if (AliveInstaller.isAliveInstalled(app)) {
                    Log.i(TAG, "auto-return: Alive appeared during grace")
                }
                return
            }
            Log.w(TAG, "auto-return: Alive wait timed out; clearing DO anyway")
        }
    }
}
