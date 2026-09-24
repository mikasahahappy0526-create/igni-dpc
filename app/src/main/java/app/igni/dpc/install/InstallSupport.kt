package app.igni.dpc.install

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared helpers for Device Owner silent install vs personal-mode prompted install.
 */
object InstallSupport {

    private const val TAG = "IgniInstall"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm != null && dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * On API 26+ personal mode, unknown-sources / REQUEST_INSTALL_PACKAGES must be allowed.
     * Opens the per-app settings screen when missing. Device Owner skips this gate.
     * @return true if install may proceed now.
     */
    fun ensureCanRequestInstall(context: Context): Boolean {
        val app = context.applicationContext
        if (isDeviceOwner(app)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (app.packageManager.canRequestPackageInstalls()) return true
        Log.i(TAG, "Opening unknown-sources settings for personal install")
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${app.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "Failed to open unknown-sources settings", it) }
        return false
    }

    fun fileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context.applicationContext,
            "${context.applicationContext.packageName}.fileprovider",
            file
        )
    }

    /**
     * Commit a PackageInstaller session for one or more APK splits.
     * DO: prefer silent (USER_ACTION_NOT_REQUIRED). Personal: require user confirm.
     */
    fun commitApkSession(
        context: Context,
        apkFiles: List<File>,
        packageName: String,
        splitNamePrefix: String,
        statusAction: String,
        statusRequestCode: Int
    ): Result<Unit> = runCatching {
        require(apkFiles.isNotEmpty()) { "APK がありません" }
        for (f in apkFiles) {
            require(f.exists() && f.length() > 0L) { "空の APK: ${f.name}" }
        }
        val app = context.applicationContext
        val isDo = isDeviceOwner(app)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
            if (isDo) {
                // Policy install, not a raw sideload. Android 13+ marks
                // PACKAGE_SOURCE_LOCAL_FILE and PACKAGE_SOURCE_DOWNLOADED_FILE with
                // restricted settings (accessibility). OTHER is the public source for
                // a device-policy install and is not in that list. Do not claim STORE.
                runCatching { setInstallReason(PackageManager.INSTALL_REASON_POLICY) }
                    .onFailure { Log.w(TAG, "setInstallReason(POLICY) failed", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER) }
                        .onFailure { Log.w(TAG, "setPackageSource(OTHER) failed", it) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDo) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                } else {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFiles.forEachIndexed { index, apk ->
                val splitName = if (apkFiles.size == 1) {
                    "$splitNamePrefix.apk"
                } else {
                    "$splitNamePrefix-$index-${apk.name}"
                }
                apk.inputStream().use { input ->
                    session.openWrite(splitName, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val statusIntent = Intent(statusAction).apply {
                setPackage(app.packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(
                app,
                statusRequestCode xor sessionId,
                statusIntent,
                flags
            )
            session.commit(pending.intentSender)
        }
        Log.i(
            TAG,
            "PackageInstaller session $sessionId committed pkg=$packageName " +
                "apks=${apkFiles.size} do=$isDo"
        )
    }

    /**
     * Personal-mode fallback: open system installer UI for a single APK via FileProvider.
     * Not suitable for multi-split XAPK.
     */
    fun installViaViewIntent(context: Context, apk: File): Result<Unit> = runCatching {
        require(apk.exists() && apk.length() > 0L) { "空の APK: ${apk.name}" }
        val app = context.applicationContext
        val uri = fileProviderUri(app, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
        Log.i(TAG, "Started ACTION_VIEW install for ${apk.name}")
    }

    /**
     * Launch PackageInstaller confirmation UI from a status broadcast (personal mode).
     * @return true if an activity was started.
     */
    fun startPendingUserAction(context: Context, statusIntent: Intent): Boolean {
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            statusIntent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return false
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.applicationContext.startActivity(confirm)
            Log.i(TAG, "Started PackageInstaller user confirmation")
            true
        }.onFailure {
            Log.w(TAG, "Failed to start install confirmation", it)
        }.getOrDefault(false)
    }
}
