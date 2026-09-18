package app.igni.dpc.alive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.igni.dpc.AliveInstallStatusReceiver
import app.igni.dpc.policy.KeepPackages
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs アライブ ([KeepPackages.ALIVE_PACKAGE]) from a fixed GitHub Releases APK URL.
 *
 * Simpler than LINE/Chrome: no Uptodown — direct APK download + PackageInstaller.
 * **Admin button only** — never call from [app.igni.dpc.policy.PolicyApplier.apply].
 * On failure: status text only — never open Play.
 */
object AliveInstaller {

    private const val TAG = "IgniAlive"
    private const val PREFS = "igni_alive_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    /** Prefer latest/download so Alive can update without an Igni bump. */
    const val APK_URL =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/latest/download/puchicli.apk"

    /** Same asset via fixed tag path (fallback). */
    const val APK_URL_FALLBACK =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/download/latest-apk/puchicli.apk"

    private const val USER_AGENT = "Igni-DPC-Alive/1.0.20 (Android; DeviceOwner)"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    /** Kick off install on a background thread (returns immediately). */
    fun ensureAliveInstalledAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { ensureAliveInstalled(app) }
                .onFailure { Log.w(TAG, "ensureAliveInstalled crashed", it) }
        }
    }

    /**
     * Synchronous attempt (worker thread). Safe for Admin「アライブを入れる」.
     * Skips overlapping runs. Direct GitHub APK + PackageInstaller — never opens Play.
     * If already installed, updates status and returns (caller may open the app).
     */
    fun ensureAliveInstalled(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "Alive install already in progress; skip")
            return
        }
        val workDir = File(app.cacheDir, "alive-apk").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            if (isAliveInstalled(app)) {
                Log.i(TAG, "Alive already installed; skip")
                persist(app, "already_installed", "アライブ はインストール済み")
                return
            }

            persist(app, "downloading", "GitHub から APK をダウンロード中…")
            val dest = File(workDir, "puchicli.apk")
            val downloaded = downloadApk(dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "Alive download failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "DL失敗 ($err)")
                return
            }

            persist(app, "installing", "PackageInstaller でインストール中…")
            val installed = installApk(app, dest)
            if (installed.isFailure) {
                val err = installed.exceptionOrNull()?.message ?: "install failed"
                Log.w(TAG, "Alive silent install failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "サイレント失敗 ($err)")
                return
            }
            if (isAliveInstalled(app)) {
                persist(app, "success", "インストール確認済み")
            } else {
                persist(app, "installing", "インストール要求を送信済み（結果はログ）")
            }
            Log.i(TAG, "Alive PackageInstaller session committed (fire-and-forget)")
        } finally {
            busy.set(false)
        }
    }

    fun isAliveInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(KeepPackages.ALIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** Launch Alive if installed; returns true when a launcher Intent was started. */
    fun openAlive(context: Context): Boolean {
        val app = context.applicationContext
        if (!isAliveInstalled(app)) return false
        val launch = app.packageManager.getLaunchIntentForPackage(KeepPackages.ALIVE_PACKAGE)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching {
            app.startActivity(launch)
            Log.i(TAG, "Opened Alive")
            true
        }.onFailure {
            Log.w(TAG, "Failed to open Alive", it)
        }.getOrDefault(false)
    }


    /** Short Japanese-only status for Admin UI (no English keys / long tails). */
    fun lastStatusText(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString(KEY_STATUS, null)
        if (status == null) {
            return if (isAliveInstalled(context)) "インストール済み" else "未インストール"
        }
        return when (status) {
            "already_installed", "success", "browser_preferred" -> "インストール済み"
            "missing" -> "未インストール"
            "resolving", "downloading" -> "ダウンロード中"
            "installing" -> "インストール中"
            "failure", "silent_failed" -> "失敗"
            else -> if (isAliveInstalled(context)) "インストール済み" else "未インストール"
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

    private fun downloadApk(dest: File): Result<File> {
        var lastError: Throwable? = null
        for (url in listOf(APK_URL, APK_URL_FALLBACK)) {
            val result = downloadOnce(url, dest)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Download failed for $url: ${lastError?.message}")
        }
        return Result.failure(lastError ?: IllegalStateException("ダウンロード失敗"))
    }

    private fun downloadOnce(url: String, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val connection = openGetFollowingRedirects(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            if (dest.length() < 1024L) {
                dest.delete()
                error("Downloaded file too small (${dest.length()})")
            }
            Log.i(TAG, "Downloaded ${dest.length()} bytes from $url")
            dest
        } finally {
            connection.disconnect()
        }
    }

    private fun openGetFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) error("Redirect $code without Location")
                current = if (location.startsWith("http")) location else {
                    URL(URL(current), location).toString()
                }
                redirects++
                if (redirects > 8) error("Too many redirects")
                continue
            }
            return connection
        }
    }

    private fun installApk(context: Context, apk: File): Result<Unit> = runCatching {
        require(apk.exists() && apk.length() > 0L) { "空の APK: ${apk.name}" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(KeepPackages.ALIVE_PACKAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("alive.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(AliveInstallStatusReceiver.ACTION).apply {
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
        Log.i(TAG, "PackageInstaller session $sessionId committed for Alive")
    }
}
