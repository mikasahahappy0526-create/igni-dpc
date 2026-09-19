package app.igni.dpc.line

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.install.InstallSupport
import app.igni.dpc.policy.KeepPackages
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clears LINE app user data / local session on this device for handoff to the next phone.
 * Does NOT delete the LINE account on LINE servers.
 */
object LineAccountClearer {

    private const val TAG = "IgniLineClear"

    data class Result(
        val success: Boolean,
        /** cleared | opened_settings | not_installed | failed */
        val mode: String,
        val messageJa: String
    )

    /**
     * Synchronous (call from a background thread).
     * DO: [DevicePolicyManager.clearApplicationUserData].
     * Personal: open LINE app-info and guide the user to erase storage/cache.
     */
    fun clearLineLocalAccount(context: Context): Result {
        val app = context.applicationContext
        if (!LineInstaller.isLineInstalled(app)) {
            return Result(
                success = false,
                mode = "not_installed",
                messageJa = "LINE がインストールされていません"
            )
        }
        val pkg = KeepPackages.LINE_PACKAGE
        return if (InstallSupport.isDeviceOwner(app)) {
            clearAsDeviceOwner(app, pkg)
        } else {
            openAppInfo(app, pkg)
        }
    }

    private fun clearAsDeviceOwner(app: Context, pkg: String): Result {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
            ?: return Result(false, "failed", "DevicePolicyManager がありません")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return openAppInfo(app, pkg)
        }
        val admin = AdminReceiver.componentName(app)
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            dpm.clearApplicationUserData(admin, pkg, executor) { _, succeeded ->
                ok.set(succeeded)
                latch.countDown()
            }
            val finished = latch.await(45, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "clearApplicationUserData timed out for $pkg")
                return Result(false, "failed", "データ消去がタイムアウトしました")
            }
            if (ok.get()) {
                Log.i(TAG, "DPM clearApplicationUserData success for $pkg")
                return Result(true, "cleared", "端末上の LINE ログイン情報を消しました")
            }
            Log.w(TAG, "DPM clearApplicationUserData reported failure for $pkg")
            val fallback = openAppInfo(app, pkg)
            return fallback.copy(
                messageJa = "自動消去に失敗したためアプリ情報を開きました。「ストレージとキャッシュを消去」をタップしてください"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "DPM clearApplicationUserData threw", t)
            val fallback = openAppInfo(app, pkg)
            return fallback.copy(
                messageJa = "自動消去できないためアプリ情報を開きました。「ストレージとキャッシュを消去」をタップしてください"
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openAppInfo(app: Context, pkg: String): Result {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            app.startActivity(intent)
            true
        }.onFailure {
            Log.w(TAG, "Failed to open LINE app-info", it)
        }.getOrDefault(false)
        return if (opened) {
            Result(
                success = true,
                mode = "opened_settings",
                messageJa = "LINE のアプリ情報を開きました。「ストレージとキャッシュを消去」をタップしてください"
            )
        } else {
            Result(false, "failed", "LINE のアプリ情報を開けませんでした")
        }
    }
}
