package app.igni.dpc.line

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import app.igni.dpc.BuildConfig
import app.igni.dpc.LineInstallStatusReceiver
import app.igni.dpc.policy.KeepPackages
import app.igni.dpc.update.AppUpdateChecker
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures LINE ([KeepPackages.LINE_PACKAGE]) is installed after Device Owner policy apply.
 *
 * 1. If already installed → log and return.
 * 2. Try silent install: HTTPS download from [BuildConfig.LINE_APK_URL] then
 *    [PackageInstaller] MODE_FULL_INSTALL (same pattern as self-update).
 * 3. On download/install failure → open Play Store details for LINE (market:// with HTTPS fallback).
 *
 * Fire-and-forget; never blocks [app.igni.dpc.policy.PolicyApplier.apply].
 */
object LineInstaller {

    private const val TAG = "IgniLine"
    private const val PREFS = "igni_line_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    const val PLAY_MARKET_URI = "market://details?id=jp.naver.line.android"
    const val PLAY_HTTPS_URI =
        "https://play.google.com/store/apps/details?id=jp.naver.line.android"

    /** Kick off install attempt on a background thread (returns immediately). */
    fun ensureLineInstalledAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { ensureLineInstalled(app) }
                .onFailure { Log.w(TAG, "ensureLineInstalled crashed", it) }
        }
    }

    /**
     * Synchronous attempt (call from a worker thread). Safe to call from Admin UI button.
     * Skips overlapping runs.
     */
    fun ensureLineInstalled(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "LINE install already in progress; skip")
            return
        }
        try {
            if (isLineInstalled(app)) {
                Log.i(TAG, "LINE already installed; skip")
                persist(app, "already_installed", "LINE はインストール済み")
                return
            }

            persist(app, "downloading", "APK をダウンロード中…")
            val dest = File(app.cacheDir, "line-install.apk")
            val downloaded = AppUpdateChecker.downloadApk(BuildConfig.LINE_APK_URL, dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "LINE APK download failed: $err — opening Play Store")
                persist(app, "play_fallback", "サイレント失敗 ($err) → Play を開く")
                openPlayStore(app)
                return
            }

            persist(app, "installing", "PackageInstaller でインストール中…")
            val installed = installLineApk(app, dest)
            if (installed.isFailure) {
                val err = installed.exceptionOrNull()?.message ?: "install failed"
                Log.w(TAG, "LINE silent install failed: $err — opening Play Store")
                persist(app, "play_fallback", "サイレント失敗 ($err) → Play を開く")
                openPlayStore(app)
                return
            }
            // Session committed; final success/failure arrives via LineInstallStatusReceiver.
            persist(app, "installing", "インストール要求を送信済み（結果はログ）")
            Log.i(TAG, "LINE PackageInstaller session committed (fire-and-forget)")
        } finally {
            busy.set(false)
        }
    }

    fun isLineInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(KeepPackages.LINE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** Short status line for Admin UI. */
    fun lastStatusText(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString(KEY_STATUS, null) ?: return "LINE インストール: 未試行"
        val detail = prefs.getString(KEY_DETAIL, "").orEmpty()
        val at = prefs.getLong(KEY_AT, 0L)
        val whenLabel = if (at > 0L) {
            val agoMin = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
            if (agoMin < 1) "たった今" else "${agoMin}分前"
        } else {
            ""
        }
        val prefix = "LINE インストール: $status"
        return buildString {
            append(prefix)
            if (detail.isNotBlank()) append(" — ").append(detail)
            if (whenLabel.isNotBlank()) append(" ($whenLabel)")
        }
    }

    fun persistSuccess(context: Context) {
        persist(context, "success", "サイレントインストール成功")
    }

    fun persistFailure(context: Context, message: String?) {
        persist(context, "failure", message ?: "install failure")
    }

    private fun persist(context: Context, status: String, detail: String) {
        prefs(context).edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installLineApk(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            require(apkFile.exists() && apkFile.length() > 0L) { "LINE APK がありません" }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(KeepPackages.LINE_PACKAGE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("line.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(LineInstallStatusReceiver.ACTION).apply {
                    setPackage(context.packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    flags
                )
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "PackageInstaller session $sessionId committed for LINE")
        }
    }

    /** Opens Play Store LINE page; falls back to HTTPS if market:// fails. */
    fun openPlayStore(context: Context) {
        val app = context.applicationContext
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_MARKET_URI)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val ok = runCatching {
            app.startActivity(market)
            true
        }.onFailure {
            Log.w(TAG, "market:// failed; trying HTTPS Play URL", it)
        }.getOrDefault(false)
        if (!ok) {
            val https = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_HTTPS_URI)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { app.startActivity(https) }
                .onFailure { Log.w(TAG, "HTTPS Play Store open failed", it) }
                .onSuccess { Log.i(TAG, "Opened Play Store HTTPS for LINE") }
        } else {
            Log.i(TAG, "Opened Play Store market:// for LINE")
        }
    }
}
