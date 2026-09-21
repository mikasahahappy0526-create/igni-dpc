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
 * Force-remove (uninstall first, hide only if uninstall fails):
 *   - Google suite (non-Chrome): Drive/Docs/Maps/Photos/Gmail/YouTube/…
 *   - Google app / search / assistant (never Chrome substitute)
 *   - TikTok Lite
 *   - Y!mobile / Yahoo / SoftBank / UQ / nubia / PayPay carrier bloat (heuristics + constants)
 * Critical keep-list: System UI, dialer/phone, SMS (AOSP), provisioning, keyboards, launcher, GMS, DPC.
 *
 * Camera packages are detected dynamically (image-capture intent handlers, launcher apps
 * whose packageName contains "camera") in addition to a static OEM list, so hide policy
 * never removes the only camera app on an unfamiliar device.
 */
class KeepPackages(private val context: Context) {

    fun shouldKeep(packageName: String): Boolean {
        // Force-remove targets must never stay via HOME-handler / allowlist keep.
        if (isForceUninstall(packageName)) return false
        if (isForceHide(packageName)) return false
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
     * Aggressive force-remove (uninstall preferred, hide fallback): Google suite (non-Chrome),
     * Yahoo / Y!mobile / SoftBank / UQ / nubia / PayPay bloat, plus [FORCE_HIDE_GOOGLE] /
     * TikTok Lite. Never overrides Chrome / Play / Settings / LINE / Alive / Igni / CRITICAL /
     * cameras / IME / system HOME.
     *
     * Google Messages (`com.google.android.apps.messaging`): try uninstall only when another
     * SMS app (AOSP Messaging / MMS) is present; otherwise keep so the phone still has SMS.
     */
    fun isForceUninstall(packageName: String): Boolean {
        if (isProtectedKeepCore(packageName)) return false
        if (packageName in FORCE_HIDE_GOOGLE) return true
        if (FORCE_HIDE_PREFIXES.any { matchesPrefix(packageName, it) }) return true
        if (packageName in FORCE_REMOVE_TIKTOK_LITE) return true
        if (packageName in FORCE_UNINSTALL) {
            if (packageName == GOOGLE_MESSAGES_PACKAGE && !hasAlternateSmsApp()) {
                Log.i(TAG, "Keep Google Messages — no alternate SMS app installed")
                return false
            }
            return true
        }
        if (matchesForceUninstallHeuristic(packageName)) return true
        return false
    }

    /** True for allowlist / CRITICAL / Chrome / cameras / IME / system HOME / this DPC. */
    fun isProtectedKeepCore(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == IGN_PACKAGE) return true
        if (packageName in PRODUCT_ALLOWLIST) return true
        if (packageName in HARD_DENY_UNINSTALL) return true
        if (packageName in CHROME_PACKAGES) return true
        if (isTrichromePackage(packageName)) return true
        if (packageName in SETTINGS_PACKAGES) return true
        if (packageName == PLAY_STORE_PACKAGE) return true
        if (packageName in CRITICAL_PACKAGES) return true
        if (CRITICAL_PREFIXES.any { matchesPrefix(packageName, it) }) return true
        if (packageName in homeSystemPackages()) return true
        if (packageName in inputMethodPackages()) return true
        if (packageName in detectCameraPackages()) return true
        return false
    }

    /** Substring / prefix heuristics for JP carrier + Yahoo + nubia / ZTE bloat. */
    fun matchesForceUninstallHeuristic(packageName: String): Boolean {
        val lower = packageName.lowercase()
        // Never heuristic-match Chrome / Play / Settings / GMS / webview / trichrome.
        if (isProtectedKeepCore(packageName)) return false
        if (lower.startsWith("com.google.android.gms")) return false
        if (lower.startsWith("com.google.android.gsf")) return false
        if (lower.startsWith("com.google.android.webview")) return false
        if (lower.startsWith("com.google.android.trichrome")) return false
        if (lower.startsWith("com.android.chrome")) return false
        for (token in FORCE_UNINSTALL_TOKENS) {
            if (lower.contains(token)) return true
        }
        for (prefix in FORCE_UNINSTALL_PREFIXES) {
            if (matchesPrefix(packageName, prefix)) return true
        }
        return false
    }

    /** True when AOSP / OEM SMS (not Google Messages) is installed. */
    fun hasAlternateSmsApp(): Boolean {
        for (pkg in SMS_KEEP_PACKAGES) {
            if (pkg == GOOGLE_MESSAGES_PACKAGE) continue
            if (isInstalled(pkg)) return true
        }
        // Also accept any installed package that is the default SMS role and not Google Messages.
        return false
    }

    /**
     * Hard deny for PackageInstaller.uninstall — defense in depth beyond [shouldKeep]
     * (races / allowlist gaps must never remove Chrome, Play, Settings, LINE, or this DPC).
     */
    fun isHardDenyUninstall(packageName: String): Boolean {
        // Allow uninstall of force-remove / force-hide / TikTok targets.
        if (isForceUninstall(packageName)) return false
        if (isForceHide(packageName)) return false
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
         * Emergency-alert packages are handled by PolicyApplier.applyEmergencyAlertsOff().
         * Keep them out of the general uninstall pass so the dedicated hide/disable path
         * remains authoritative; these are deliberately not treated as visible keep packages.
         */
        val EMERGENCY_ALERT_PACKAGES: Set<String> = linkedSetOf(
            "com.kddi.android.cmail",
            "jp.co.sharp.android.safetyalert",
        )

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

        /** Google Messages — uninstall only when an alternate SMS app exists (see [hasAlternateSmsApp]). */
        const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

        /** AOSP / OEM SMS packages we prefer to keep over Google Messages. */
        val SMS_KEEP_PACKAGES: Set<String> = linkedSetOf(
            "com.android.mms",
            "com.android.messaging",
            "com.samsung.android.messaging",
            "com.android.mms.service",
        )

        /**
         * Aggressive force-uninstall set (Google suite non-Chrome + JP carrier / Yahoo / nubia).
         * Prefer [PolicyApplier.requestSilentUninstall]; hide only if uninstall fails / stub remains.
         * Never includes Chrome / Play / Settings / LINE / Alive / Igni / GMS / WebView.
         *
         * JP package refs (repo comments / common Y!mobile SoftBank debloat lists):
         * - jp.co.yahoo.android.yjtop / ybrowser / ymobile.mail / yshopping / ybox / YAuctionPad
         * - jp.ymobile.android.myymobile
         * - jp.softbank.mb.parentalcontrols (あんしんフィルター) / datamigration / dmb / …
         * - PayPay: jp.ne.paypay / com.paypay.*
         * - nubia/ZTE: cn.nubia.* pay/game shells, com.zte.wallet / zmall / …
         */
        val FORCE_UNINSTALL: Set<String> = linkedSetOf(
            // --- Google suite (non-Chrome; never Chrome / Play / GMS) ---
            "com.google.android.apps.docs", // Drive
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.google.android.apps.tachyon", // Duo / Meet legacy
            "com.google.android.apps.maps",
            "com.google.android.apps.photos",
            "com.google.android.apps.photosgo",
            "com.google.android.apps.nbu.files", // Files by Google
            "com.google.android.apps.messaging", // Google Messages (gated)
            "com.google.android.gm", // Gmail
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.google.android.videos", // Google TV / Play Movies
            "com.google.android.apps.magazines", // News / Play Newsstand
            "com.google.android.calendar",
            "com.google.android.keep",
            "com.google.android.apps.podcasts",
            "com.google.android.apps.walletnfcrel", // Wallet
            "com.google.android.apps.wallet",
            "com.google.android.apps.meetings", // Meet
            // --- Yahoo / Y!mobile ---
            "jp.co.yahoo.android.yjtop", // Yahoo! app
            "jp.co.yahoo.android.ybrowser", // Y!ブラウザ
            "jp.co.yahoo.android.ymobile.mail", // Y!メール
            "jp.co.yahoo.android.yshopping", // Y!ショッピング
            "jp.co.yahoo.android.ybox",
            "jp.co.yahoo.android.YAuctionPad", // ヤフオク
            "jp.co.yahoo.android.apps.navi", // Y!カーナビ
            "jp.co.yahoo.android.premiumwebclick", // Enjoyパック
            "jp.co.yahoo.android.ebookjapan.preinstall",
            "jp.co.yahoo.android.paypayfleamarket",
            "jp.ymobile.android.myymobile", // My Y!mobile
            "jp.co.yahoo.android.ymobile.wipass",
            "jp.co.yahoo.android.ymobile",
            // --- SoftBank / あんしん / data migration / guide ---
            "jp.softbank.mb.parentalcontrols", // あんしんフィルター
            "jp.softbank.mb.datamigration", // かんたんデータコピー
            "jp.softbank.mb.dmb",
            "jp.softbank.mb.bizlock",
            "jp.softbank.mb.ichinaviclt",
            "jp.softbank.mb.plusmessage",
            "jp.softbank.mb.linemusic",
            "jp.softbank.mb.ichioshiapp",
            "jp.softbank.mb.fivegservice",
            "jp.softbank.mb.cbrl",
            "jp.softbank.mb.xcap",
            "jp.softbank.mb.tdrl",
            "jp.softbank.mb.passwordmanager",
            "jp.softbank.mb.apud.manager",
            "jp.softbank.mb.apud.framework",
            "jp.softbank.anshin.databox",
            "jp.softbank.security",
            "jp.co.softbank.OfficialApp",
            "jp.co.softbank.wispr.nfp",
            "jp.co.softbank.wispr.froyo",
            "jp.softbank.tether.entitlement",
            "com.aura.oobe.softbank",
            // --- UQ / SoftBank market helpers ---
            "jp.uqmobile.app",
            "jp.uqcommunications.myuqmobile",
            // --- PayPay (bloat preinstall; not on keep list) ---
            "jp.ne.paypay.app",
            "jp.ne.paypay",
            "com.paypay.app",
            // --- oneseg / voice / SIM toolkit (launchable bloat; careful — only if not protected) ---
            "com.android.stk",
            "com.android.stk2",
            "com.mediatek.stk",
            "com.samsung.android.app.telephonyui.voiceaccess",
            "com.google.android.apps.accessibility.voiceaccess",
            "com.sonyericsson.android.oneseg",
            "jp.co.sharp.android.oneseg",
            "com.nttdocomo.android.store",
            // --- kisekae / sakusaku / help / guide / backup OEM ---
            "jp.co.yahoo.android.kisekae",
            "jp.softbank.mb.kisekae",
            "jp.softbank.mb.sakusaku",
            "com.zte.heartyservice",
            "com.zte.beautify",
            "com.zte.wallet",
            "com.zte.zmall",
            "com.zte.nps",
            "com.zte.cloud",
            "com.zte.analytics",
            "com.zte.aliveupdate",
            "com.zte.retrieve",
            "com.zte.remotecontroller",
            "com.zte.smartcast",
            "cn.nubia.paycomponent",
            "cn.nubia.gamelauncher",
            "cn.nubia.nbgame",
            "cn.nubia.videoeditor",
            "com.chaozh.iReaderNubia",
            "com.ume.browser",
            "com.android.mipop",
            // --- Help / guide / backup / migration common shells ---
            "com.customermobile.preload.vzw",
            "com.verizon.mips.services",
        )

        /**
         * Substring tokens (lowercase) — package name contains → force-uninstall candidate.
         * Narrow enough to avoid Chrome / GMS / Settings (those short-circuit in [isProtectedKeepCore]).
         */
        val FORCE_UNINSTALL_TOKENS: List<String> = listOf(
            "yahoo",
            "ymobile",
            "softbank",
            "uqmobile",
            "uqcommunications",
            "anshin",
            "kisekae",
            "sakusaku",
            "paypay",
            "oneseg",
            "one-seg",
            "datamigration",
            "parentalcontrols",
        )

        /** Prefix matches for carrier / nubia / ZTE bloat families. */
        val FORCE_UNINSTALL_PREFIXES: List<String> = listOf(
            "jp.co.yahoo.android",
            "jp.ymobile",
            "jp.softbank",
            "jp.co.softbank",
            "jp.uqmobile",
            "jp.uqcommunications",
            "jp.ne.paypay",
            "com.paypay",
            "cn.nubia.pay",
            "cn.nubia.game",
            "cn.nubia.nba",
            "com.zte.wallet",
            "com.zte.zmall",
            "com.zte.heartyservice",
            "com.zte.beautify",
            "com.zte.nps",
            "com.zte.cloud",
            "com.zte.analytics",
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
            "com.android.mms",
            "com.android.mms.service",
            "com.android.messaging",
            "com.samsung.android.messaging",
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
