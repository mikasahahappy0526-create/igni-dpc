package app.igni.dpc.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/**
 * Packages that must remain visible or installed for a usable dedicated terminal.
 *
 * Product allowlist (shown in the launcher): Settings + Play Store.
 * Critical keep-list: System UI, provisioning, keyboards, default launcher, Play services, DPC, etc.
 */
class KeepPackages(private val context: Context) {

    fun shouldKeep(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName in PRODUCT_ALLOWLIST) return true
        if (packageName in CRITICAL_PACKAGES) return true
        if (CRITICAL_PREFIXES.any { matchesPrefix(packageName, it) }) return true
        if (packageName in homeSystemPackages()) return true
        if (packageName in inputMethodPackages()) return true
        return false
    }

    fun productAllowlist(): List<String> = PRODUCT_ALLOWLIST.toList()

    fun describeKeepReasons(): List<String> {
        return buildList {
            addAll(PRODUCT_ALLOWLIST)
            add(context.packageName)
        }
    }

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

    companion object {
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL

        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_BETA_PACKAGE = "com.chrome.beta"

        val SETTINGS_PACKAGES: List<String> = listOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.google.android.settings",
        )

        val CAMERA_PACKAGES: List<String> = listOf(
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
        )

        /** User-facing apps that must stay launchable from the dedicated home. */
        val PRODUCT_ALLOWLIST: Set<String> = linkedSetOf(
            // Settings (+ OEM variants)
            "com.android.settings",
            "com.samsung.android.settings",
            "com.google.android.settings",
            // Play Store
            PLAY_STORE_PACKAGE,
            // Camera (+ common OEM packages)
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
            // Chrome (stable; beta only if used as fallback launch target — keep installed)
            CHROME_PACKAGE,
            CHROME_BETA_PACKAGE,
        )

        /**
         * Never hide these even if they expose a launcher icon.
         * Prefer hiding over uninstalling; this list is the safety net against bricking.
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
        )

        private fun matchesPrefix(packageName: String, prefix: String): Boolean {
            return packageName == prefix || packageName.startsWith("$prefix.")
        }
    }
}
