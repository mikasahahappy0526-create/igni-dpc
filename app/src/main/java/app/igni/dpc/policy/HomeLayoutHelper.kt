package app.igni.dpc.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import app.igni.dpc.AdminActivity
import app.igni.dpc.AdminReceiver

/**
 * Best-effort stock-OEM home layout helpers (NO custom HomeActivity / dock UI).
 *
 * Android does not give Device Owner a reliable silent "pin to home page 1" API
 * that works across Samsung One UI / AOSP launchers without user confirmation.
 * We try [ShortcutManager.requestPinShortcut] when supported; otherwise skip and
 * report honesty in Admin status.
 */
object HomeLayoutHelper {

    private const val TAG = "IgniHomeLayout"
    private const val PREFS = "igni_home_layout"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    data class Status(val result: String, val detail: String)

    fun lastStatus(context: Context): Status {
        val prefs = prefs(context)
        return Status(
            result = prefs.getString(KEY_STATUS, "never") ?: "never",
            detail = prefs.getString(KEY_DETAIL, "未試行").orEmpty()
        )
    }

    fun lastStatusText(context: Context): String {
        val s = lastStatus(context)
        val at = prefs(context).getLong(KEY_AT, 0L)
        val whenLabel = if (at > 0L) {
            val agoMin = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
            if (agoMin < 1) "たった今" else "${agoMin}分前"
        } else {
            ""
        }
        return buildString {
            append("ホーム配置: ${s.result}")
            if (s.detail.isNotBlank()) append(" — ").append(s.detail)
            if (whenLabel.isNotBlank()) append(" ($whenLabel)")
        }
    }

    /**
     * Clear Igni HOME preferred (stock OEM launcher stays home) and best-effort
     * request pin shortcuts for Settings / Play / Chrome / Camera / Igni.
     */
    fun applyBestEffortHomeLayout(context: Context, cameras: Set<String>): Status {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.componentName(app)

        // Always clear Igni as HOME preferred activity.
        if (dpm != null && dpm.isDeviceOwnerApp(app.packageName)) {
            runCatching {
                dpm.clearPackagePersistentPreferredActivities(admin, app.packageName)
                Log.i(TAG, "Cleared Igni persistent preferred (stock HOME)")
            }.onFailure { Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it) }
        }

        val targets = linkedMapOf<String, String>()
        // Settings
        for (pkg in KeepPackages.SETTINGS_PACKAGES) {
            if (isInstalled(app, pkg) && resolveLauncher(app, pkg) != null) {
                targets[pkg] = "設定"
                break
            }
        }
        if (isInstalled(app, KeepPackages.PLAY_STORE_PACKAGE)) {
            targets[KeepPackages.PLAY_STORE_PACKAGE] = "Play"
        }
        if (isInstalled(app, KeepPackages.CHROME_PACKAGE)) {
            targets[KeepPackages.CHROME_PACKAGE] = "Chrome"
        }
        for (pkg in cameras) {
            if (isInstalled(app, pkg) && resolveLauncher(app, pkg) != null) {
                targets[pkg] = "カメラ"
                break
            }
        }
        targets[app.packageName] = "イグニ"

        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.getSystemService(ShortcutManager::class.java)
        } else {
            null
        }

        if (sm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val status = Status(
                "skipped",
                "ShortcutManager 不可（API < 26）。標準ホームに手動で配置してください"
            )
            persist(app, status)
            return status
        }

        if (!sm.isRequestPinShortcutSupported) {
            val status = Status(
                "unsupported",
                "ランチャーがピンショートカット非対応。標準ホームに手動配置が必要"
            )
            persist(app, status)
            return status
        }

        var requested = 0
        val notes = mutableListOf<String>()
        for ((pkg, label) in targets) {
            val launch = if (pkg == app.packageName) {
                igniLauncherComponent(app)
            } else {
                resolveLauncher(app, pkg)
            }
            if (launch == null) {
                notes += "$label=no_launcher"
                continue
            }
            val ok = runCatching {
                val shortcut = ShortcutInfo.Builder(app, "igni-pin-$pkg")
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource(app, android.R.drawable.sym_def_app_icon))
                    .setIntent(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(launch)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    .build()
                // Most OEMs still show a confirm UI; DO has no silent pin API.
                // requestPinShortcut returns whether the request was accepted by the launcher.
                sm.requestPinShortcut(shortcut, null)
            }.onFailure {
                Log.w(TAG, "requestPinShortcut($pkg) failed", it)
            }.getOrDefault(false)
            if (ok) {
                requested++
                notes += "$label=requested"
            } else {
                notes += "$label=fail"
            }
        }

        val status = when {
            requested == 0 -> Status(
                "skipped",
                "サイレント固定不可（OEMは確認UI必須のことが多い）。" +
                    "許可アプリは表示済み — ホーム1ページ目は手動配置を推奨。 " +
                    notes.joinToString("; ")
            )
            else -> Status(
                "best_effort",
                "ピン要求 $requested/${targets.size}（確認ダイアログが出る端末あり）。" +
                    "カスタムHOMEは使いません。 " + notes.joinToString("; ")
            )
        }
        persist(app, status)
        Log.i(TAG, "Home layout ${status.result}: ${status.detail}")
        return status
    }

    private fun resolveLauncher(context: Context, packageName: String): ComponentName? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        val info = resolved.firstOrNull()?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /** Prefer AdminActivity for Igni pin when resolving our own package. */
    fun igniLauncherComponent(context: Context): ComponentName {
        return ComponentName(context.packageName, AdminActivity::class.java.name)
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun persist(context: Context, status: Status) {
        prefs(context).edit()
            .putString(KEY_STATUS, status.result)
            .putString(KEY_DETAIL, status.detail)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
