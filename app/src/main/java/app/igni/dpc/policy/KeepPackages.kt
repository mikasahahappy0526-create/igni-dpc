package app.igni.dpc.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Packages that must remain installed (and visible when launchable) for a usable dedicated terminal.
 *
 * Product allowlist (shown in the launcher): Settings + Play Store + Camera + Chrome + LINE + Alive + Igni.
 * Force-remove: Google app / search / assistant (uninstall then hide; never Chrome substitute).
 * Force-remove: TikTok Lite (uninstall preferred; hide if system/uninstall fails).
 * Critical keep-list: System UI, provisioning, keyboards, default launcher, Play services, DPC, etc.
 *
 * Camera packages are detected dynamically (image-capture intent handlers, launcher apps
 * whose packageName contains "camera") in addition to a static OEM list, so hide policy
 * never removes the only camera app on an unfamiliar device.
 */
class KeepPackages(private val context: Context) {

    fun shouldKeep(packageName: String): Boolean {
        // Google app / search must never stay via HOME-handler keep.
        if (isForceHide(packageName)) return false
        // TikTok Lite must never stay via allowlist / HOME keep.
        if (isForceRemoveTikTokLite(packageName)) return false
        if (packageName == context.packageName) return true
        if (packageName in PRODUCT_ALLOWLIST) return true
        if (packageName in CHROME_PACKAGES) return true
        if (isTrichromePackage(packageName)) return true
        if (packageName in CRITICAL_PACKAGES) return true
        if (CRITICAL_PREFIXES.any { matchesPrefix(packageName, it) }) return true
        if (packageName in homeSystemPackages()) return true
        if (packageName in inputMethodPackages()) return true
        if (packageName in detectCameraPackages()) return true
        return false
    }

    /**
     * Google app / Assistant / search lite — prefer uninstall then hide (TikTok-style).
     * Never treat these as a Chrome substitute (Sense3 showed Google instead of Chrome).
     */
    fun isForceHide(packageName: String): Boolean {
        if (packageName in CHROME_PACKAGES) return false
        if (packageName == PLAY_STORE_PACKAGE) return false
        if (packageName in SETTINGS_PACKAGES) return false
        if (packageName == context.packageName) return false
        if (packageName == IGN_PACKAGE) return false
        if (packageName in FORCE_HIDE_GOOGLE) return true
        return FORCE_HIDE_PREFIXES.any { matchesPrefix(packageName, it) }
    }

    /**
     * TikTok Lite (preinstall or user) — prefer silent uninstall; hide if system/uninstall fails.
     * Never treat as keep / allowlist / hard-deny.
     */
    fun isForceRemoveTikTokLite(packageName: String): Boolean {
        return packageName in FORCE_REMOVE_TIKTOK_LITE
    }

    /**
     * Hard deny for PackageInstaller.uninstall — defense in depth beyond [shouldKeep]
     * (races / allowlist gaps must never remove Chrome, Play, Settings, LINE, or this DPC).
     */
    fun isHardDenyUninstall(packageName: String): Boolean {
        // Allow uninstall of force-hide Google search/app packages.
        if (isForceHide(packageName)) return false
        // Allow uninstall of TikTok Lite force-remove targets.
        if (isForceRemoveTikTokLite(packageName)) return false
        if (packageName == context.packageName) return true
        if (packageName in HARD_DENY_UNINSTALL) return true
        if (packageName in CHROME_PACKAGES) return true
        if (isTrichromePackage(packageName)) return true
        if (shouldKeep(packageName)) return true
        return false
    }

    /** Trichrome / Chrome shared library packages — never uninstall; keep when Chrome is kept. */
    fun isTrichromePackage(packageName: String): Boolean {
        return TRICHROME_PREFIXES.any { matchesPrefix(packageName, it) } ||
            packageName in TRICHROME_PACKAGES
    }

    fun productAllowlist(): List<String> = PRODUCT_ALLOWLIST.toList()

    fun describeKeepReasons(): List<String> {
        return buildList {
            addAll(PRODUCT_ALLOWLIST)
            if (IGN_PACKAGE !in this) add(IGN_PACKAGE)
            if (context.packageName !in this) add(context.packageName)
            // Show dynamically detected cameras that are not already in the static list.
            for (pkg in detectCameraPackages().sorted()) {
                if (pkg !in this) add(pkg)
            }
        }
    }

    /**
     * Camera packages that must stay unhidden: static OEM list + dynamic detection.
     * Cached per KeepPackages instance (one PolicyApplier.apply() cycle).
     */
    fun detectCameraPackages(): Set<String> {
        cachedCameraPackages?.let { return it }
        val found = linkedSetOf<String>()

        // 1) Static known OEM camera packages that are installed.
        for (pkg in CAMERA_PACKAGES) {
            if (isInstalled(pkg)) found.add(pkg)
        }

        // 2) Packages that resolve image / still / video camera intents.
        for (action in CAMERA_INTENT_ACTIONS) {
            val intent = Intent(action)
            val resolved = runCatching {
                context.packageManager.queryIntentActivities(intent, MATCH_FLAGS)
            }.getOrDefault(emptyList())
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                found.add(pkg)
            }
        }

        // 3) MAIN/LAUNCHER activities whose packageName contains "camera".
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = runCatching {
            context.packageManager.queryIntentActivities(launcher, MATCH_FLAGS)
        }.getOrDefault(emptyList())
        for (info in launchable) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg.contains("camera", ignoreCase = true)) {
                found.add(pkg)
            }
        }

        cachedCameraPackages = found
        Log.i(TAG, "Detected camera packages (${found.size}): ${found.sorted().joinToString()}")
        return found
    }

    private var cachedCameraPackages: Set<String>? = null

    private fun homeSystemPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.queryIntentActivities(intent, MATCH_FLAGS)
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (isSystemApp(pkg)) pkg else null
        }.toSet()
    }

    private fun inputMethodPackages(): Set<String> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return imm.inputMethodList.map { it.packageName }.toSet()
    }

    private fun isSystemApp(packageName: String): Boolean {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    private fun isInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "IgniKeepPackages"
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_ALL

        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_BETA_PACKAGE = "com.chrome.beta"
        const val CHROME_DEV_PACKAGE = "com.chrome.dev"
        const val CHROME_CANARY_PACKAGE = "com.chrome.canary"
        const val LINE_PACKAGE = "jp.naver.line.android"
        /** TikTok Lite (primary package id on many devices). Force-remove on policy apply. */
        const val TIKTOK_LITE_PACKAGE = "com.zhiliaoapp.musically.go"
        /** Alternate TikTok Lite package id seen on some OEM / region builds. */
        const val TIKTOK_LITE_ALT_PACKAGE = "com.tiktok.lite.go"
        /** アライブ (puchicli) — Admin-button install; stay visible after policy apply. */
        const val ALIVE_PACKAGE = "jp.puchicli.app"

        /** All Chrome package ids we must never uninstall/hide. */
        val CHROME_PACKAGES: List<String> = listOf(
            CHROME_PACKAGE,
            CHROME_BETA_PACKAGE,
            CHROME_DEV_PACKAGE,
            CHROME_CANARY_PACKAGE,
        )

        /** Trichrome library packages Chrome depends on (exact ids). */
        val TRICHROME_PACKAGES: Set<String> = linkedSetOf(
            "com.google.android.trichromelibrary",
        )

        /** Prefixes for versioned trichrome shared libraries. */
        val TRICHROME_PREFIXES: List<String> = listOf(
            "com.google.android.trichromelibrary",
        )

        val SETTINGS_PACKAGES: List<String> = listOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.google.android.settings",
        )

        /** Intent actions that identify camera / capture apps. */
        val CAMERA_INTENT_ACTIONS: List<String> = listOf(
            MediaStore.ACTION_IMAGE_CAPTURE, // android.media.action.IMAGE_CAPTURE
            "android.media.action.IMAGE_CAPTURE",
            "android.media.action.STILL_IMAGE_CAMERA",
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, // same string on most APIs
            "android.media.action.VIDEO_CAMERA",
            MediaStore.INTENT_ACTION_VIDEO_CAMERA,
            MediaStore.ACTION_VIDEO_CAPTURE,
        ).distinct()

        val CAMERA_PACKAGES: List<String> = listOf(
            // AOSP / Google
            "com.android.camera2",
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "com.google.android.apps.cameralite",
            // Samsung
            "com.sec.android.app.camera",
            // MediaTek reference
            "com.mediatek.camera",
            // Huawei / Honor
            "com.huawei.camera",
            "com.hihonor.camera",
            // Oppo / OnePlus / Realme (ColorOS / OxygenOS)
            "com.oplus.camera",
            "com.oneplus.camera",
            "com.oppo.camera",
            "com.realme.camera",
            // Motorola
            "com.motorola.camera2",
            "com.motorola.camera3",
            // Qualcomm reference
            "org.codeaurora.snapcam",
            // Sony
            "com.sonyericsson.android.camera",
            "com.sonymobile.android.camera",
            // Xiaomi / Redmi / POCO
            "com.android.camera",
            "com.mlab.cam",
            "com.xiaomi.scanner",
            // Vivo / iQOO
            "com.vivo.camera",
            "com.android.bbkcamera",
            // Transsion (Tecno / Infinix / itel)
            "com.transsion.camera",
            "com.tecno.camera",
            "com.infinix.camera",
            "com.itel.camera",
            // Sharp / SoftBank Aquos
            "jp.co.sharp.android.camera",
            "com.sharp.android.camera",
            "jp.co.sharp.camera",
            // FCNT (Fujitsu / arrows)
            "com.fujitsu.mobile_phone.camera",
            "com.fcnt.camera",
            "jp.co.fujitsufilm.camera",
            // Kyocera
            "com.kyocera.camera",
            "jp.kyocera.camera",
            "com.kyocera.android.camera",
            // LG (legacy)
            "com.lge.camera",
            // ASUS
            "com.asus.camera",
            // Nokia / HMD
            "com.evenwell.camera",
            "com.hmdglobal.camera2",
            // Fairphone / others
        )

        /**
         * Absolute never-uninstall set (in addition to [shouldKeep]).
         * Checked in [PolicyApplier] before PackageInstaller.uninstall.
         */
        val HARD_DENY_UNINSTALL: Set<String> = linkedSetOf(
            CHROME_PACKAGE,
            CHROME_BETA_PACKAGE,
            CHROME_DEV_PACKAGE,
            CHROME_CANARY_PACKAGE,
            PLAY_STORE_PACKAGE,
            "com.android.settings",
            LINE_PACKAGE,
            ALIVE_PACKAGE,
        )

        const val IGN_PACKAGE = "app.igni.dpc"

        /**
         * Google app / search / assistant — force-remove (uninstall preferred, then hide).
         * Do **not** treat as Chrome. Never includes Chrome / Play / Settings.
         */
        val FORCE_HIDE_GOOGLE: Set<String> = linkedSetOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant",
            "com.google.android.apps.assistant",
            "com.google.android.apps.searchlite",
            "com.google.android.apps.bard",
            "com.google.android.apps.gemini",
        )

        /** Prefix matches for Google search shells (narrow — not all com.google.android.apps.*). */
        val FORCE_HIDE_PREFIXES: List<String> = listOf(
            "com.google.android.googlequicksearchbox",
        )

        /**
         * TikTok Lite packages — force-remove on every [PolicyApplier.apply].
         * Prefer PackageInstaller silent uninstall; hide when system/updated-system and uninstall fails.
         */
        val FORCE_REMOVE_TIKTOK_LITE: Set<String> = linkedSetOf(
            TIKTOK_LITE_PACKAGE,
            TIKTOK_LITE_ALT_PACKAGE,
        )

        /** User-facing apps that must stay launchable from the stock OEM home. */
        val PRODUCT_ALLOWLIST: Set<String> = linkedSetOf(
            // Settings (+ OEM variants)
            "com.android.settings",
            "com.samsung.android.settings",
            "com.google.android.settings",
            // Play Store
            PLAY_STORE_PACKAGE,
            // Camera (+ common OEM packages) — also expanded dynamically at runtime
            "com.android.camera2",
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.mediatek.camera",
            "com.huawei.camera",
            "com.oplus.camera",
            "com.oneplus.camera",
            "com.motorola.camera2",
            "org.codeaurora.snapcam",
            "com.sonyericsson.android.camera",
            "com.sonymobile.android.camera",
            "com.transsion.camera",
            "jp.co.sharp.android.camera",
            "com.sharp.android.camera",
            "com.fujitsu.mobile_phone.camera",
            "com.fcnt.camera",
            "com.kyocera.camera",
            "jp.kyocera.camera",
            // Chrome (stable + channels — never uninstall/hide)
            CHROME_PACKAGE,
            CHROME_BETA_PACKAGE,
            CHROME_DEV_PACKAGE,
            CHROME_CANARY_PACKAGE,
            // LINE
            LINE_PACKAGE,
            // アライブ (puchicli) — button install; allowlist so it stays visible
            ALIVE_PACKAGE,
            // Igni DPC itself (AdminActivity LAUNCHER icon on page 1 when OEM places it)
            IGN_PACKAGE,
        )

        /**
         * Never hide these even if they expose a launcher icon.
         * Never hide or uninstall these; this list is the safety net against bricking.
         */
        val CRITICAL_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.shell",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.android.settings.overlay",
            "com.google.android.settings.intelligence",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.google.android.partnersetup",
            "com.android.managedprovisioning",
            "com.google.android.setupwizard",
            "com.android.setupwizard",
            "com.google.android.apps.restore",
            "com.google.android.apps.setupwizard.searchselector",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.android.webview",
            "com.google.android.webview",
            "com.google.android.captiveportallogin",
            "com.android.captiveportallogin",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.intentresolver",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.vpndialogs",
            "com.android.proxyhandler",
            "com.android.storagemanager",
            "com.android.externalstorage",
            "com.android.mtp",
            "com.android.companiondevicemanager",
            "com.android.credentialmanager",
            "com.android.role",
            "com.android.dynsystem",
            "com.android.localtransport",
            "com.android.location.fused",
            "com.google.android.location",
            "com.google.android.ext.services",
            "com.android.ext.services",
            "com.android.wifi",
            "com.android.networkstack",
            "com.android.networkstack.tethering",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.gmsconfig.common",
            "com.google.android.overlay.gmsconfig.gsa",
            "com.google.android.overlay.gmsconfig.asi",
        )

        val CRITICAL_PREFIXES: List<String> = listOf(
            "com.android.systemui",
            "com.android.providers",
            "com.android.permission",
            "com.google.android.permission",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.launcher",
            "com.google.android.setupwizard",
            "com.android.setupwizard",
            "com.android.networkstack",
            "com.google.android.overlay",
            "com.android.wifi",
            "com.google.android.ext",
            "com.android.ext.services",
            "com.android.server",
            "com.google.android.trichromelibrary",
            "com.google.android.webview",
        )

        private fun matchesPrefix(packageName: String, prefix: String): Boolean {
            return packageName == prefix || packageName.startsWith("$prefix.")
        }
    }
}
