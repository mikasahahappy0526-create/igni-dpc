package app.igni.dpc.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.igni.dpc.InstallStatusReceiver
import java.io.File

/**
 * Installs a downloaded APK via [PackageInstaller] session.
 * Device Owner updating its own package (same signing key) is typically silent.
 */
object AppSelfUpdater {

    private const val TAG = "IgniUpdate"

    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            require(apkFile.exists() && apkFile.length() > 0L) { "APK ファイルがありません" }
            val appContext = context.applicationContext
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("igni-dpc.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(InstallStatusReceiver.ACTION).apply {
                    setPackage(appContext.packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pending = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    statusIntent,
                    flags
                )
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "PackageInstaller session $sessionId committed for self-update")
        }
    }
}
