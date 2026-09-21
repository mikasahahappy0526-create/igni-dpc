package app.igni.dpc.alive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.igni.dpc.AliveInstallStatusReceiver
import app.igni.dpc.install.InstallSupport
import app.igni.dpc.policy.KeepPackages
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs アライブ ([KeepPackages.ALIVE_PACKAGE]) from a fixed GitHub Releases APK URL.
 *
 * - Device Owner: silent PackageInstaller (auto from PolicyApplier + Admin button).
 * - Personal mode (Admin button): download then prompted PackageInstaller / ACTION_VIEW.
 *
 * v1.0.44: pins public Alive **0.1.82** (versionCode 83) as primary download URL,
 * verifies SHA-256 (hard-fail on pinned), falls back to latest/download if pinned fetch fails,
 * and surfaces clear Japanese status when an older / differently-signed install blocks update.
 * Does not silently uninstall.
 */
object AliveInstaller {

    private const val TAG = "IgniAlive"
    private const val PREFS = "igni_alive_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    /** Pinned public Alive 0.1.82 (primary). */
    const val APK_URL =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/download/0.1.82/puchicli.apk"

    /** Fallback when the pinned tag asset cannot be fetched. */
    const val APK_URL_FALLBACK =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/latest/download/puchicli.apk"

    /** SHA-256 of the pinned 0.1.82 APK (hard-fail when primary URL downloads). */
    const val APK_SHA256 =
        "646bd751c434627d8c7d05981156bcf4f6fb6ddde62c37d3cbf0d9f798aa3af6"

    const val TARGET_VERSION_NAME = "0.1.82"
    const val TARGET_VERSION_CODE = 83L

    /**
     * Signing-cert SHA-256 of the pinned 0.1.82 build (hex lowercase).
     * Used to detect signature mismatch that requires uninstall before reinstall.
     */
    const val EXPECTED_CERT_SHA256 =
        "106691866d324942d8ad8bbe5722b59c2aceb35b0532692008a59248467f92c1"

    private const val USER_AGENT = "Igni-DPC-Alive/1.0.44 (Android)"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    /** Kick off install on a background thread (returns immediately). */
    fun ensureAliveInstalledAsync(context: Context) {
        val app = context.applicationContext
        if (!InstallSupport.isDeviceOwner(app)) {
            Log.i(TAG, "Skip auto Alive install — not Device Owner")
            return
        }
        executor.execute {
            runCatching { ensureAliveInstalled(app) }
                .onFailure { Log.w(TAG, "ensureAliveInstalled crashed", it) }
        }
    }

    /**
     * Synchronous attempt (worker thread). Safe for Admin「アライブを入れる」.
     * Skips overlapping runs. Works in DO (silent) and personal mode (user confirm).
     *
     * If Alive is already at/above the pinned version with the expected signature, no-ops.
     * If signature differs, surfaces [need_uninstall] — does **not** silently uninstall.
     * If same signature but older, downloads and attempts in-place update.
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
                val code = installedVersionCode(app)
                val sigOk = installedSignatureMatches(app)
                if (sigOk && code >= TARGET_VERSION_CODE) {
                    Log.i(TAG, "Alive already current (vc=$code); skip")
                    persist(
                        app,
                        "already_installed",
                        "アライブ ${installedVersionName(app) ?: TARGET_VERSION_NAME} はインストール済み"
                    )
                    return
                }
                if (!sigOk) {
                    Log.w(TAG, "Alive installed with different signature; uninstall required")
                    persist(
                        app,
                        "need_uninstall",
                        "アライブの署名が違います。一度アンインストールしてから入れ直してください"
                    )
                    return
                }
                Log.i(TAG, "Alive installed but older (vc=$code < $TARGET_VERSION_CODE); updating")
            }

            val isDo = InstallSupport.isDeviceOwner(app)
            if (!isDo && !InstallSupport.ensureCanRequestInstall(app)) {
                persist(app, "need_permission", "個人用モード: 「提供元不明のアプリ」を許可してください")
                return
            }

            persist(app, "downloading", "GitHub から アライブ $TARGET_VERSION_NAME をダウンロード中…")
            val dest = File(workDir, "puchicli.apk")
            val downloaded = downloadApk(dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "Alive download failed: $err")
                if (err.contains("ハッシュ不一致") || err.contains("SHA-256", ignoreCase = true)) {
                    persist(app, "sha_mismatch", "ハッシュ不一致のため中止しました（$err）")
                } else {
                    persist(app, "silent_failed", "DL失敗 ($err)")
                }
                return
            }

            if (isDo) {
                persist(app, "installing", "PackageInstaller でインストール中…")
            } else {
                persist(app, "installing", "個人用モードでインストール確認が必要")
            }

            val installed = installApk(app, dest)
            if (installed.isFailure) {
                val err = installed.exceptionOrNull()?.message ?: "install failed"
                Log.w(TAG, "Alive PackageInstaller failed: $err")
                if (isAliveInstalled(app) && !installedSignatureMatches(app)) {
                    persist(
                        app,
                        "need_uninstall",
                        "インストール失敗（署名不一致）。アライブをアンインストールしてから再試行してください"
                    )
                    return
                }
                if (!isDo) {
                    val view = InstallSupport.installViaViewIntent(app, dest)
                    if (view.isSuccess) {
                        persist(app, "installing", "個人用モードでインストール確認が必要")
                        return
                    }
                }
                val conflictHint =
                    if (isAliveInstalled(app)) {
                        "。既存アライブが古い／署名違いの場合はアンインストールが必要です"
                    } else {
                        ""
                    }
                persist(
                    app,
                    "silent_failed",
                    if (isDo) "サイレント失敗 ($err)$conflictHint"
                    else "インストール失敗 ($err)$conflictHint"
                )
                return
            }
            if (isAliveInstalled(app)) {
                persist(app, "success", "インストール確認済み（$TARGET_VERSION_NAME）")
            } else {
                persist(
                    app,
                    "installing",
                    if (isDo) "インストール要求を送信済み（結果はログ）"
                    else "個人用モードでインストール確認が必要"
                )
            }
            Log.i(TAG, "Alive PackageInstaller session committed (do=$isDo)")
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

    /** True when installed Alive is at/above pinned versionCode with expected signature. */
    fun isAliveCurrent(context: Context): Boolean {
        if (!isAliveInstalled(context)) return false
        if (!installedSignatureMatches(context.applicationContext)) return false
        return installedVersionCode(context.applicationContext) >= TARGET_VERSION_CODE
    }

    /** True while [ensureAliveInstalled] is running on the worker thread. */
    fun isBusy(): Boolean = busy.get()

    /** Raw prefs status key (downloading/installing/success/failure/…), or null. */
    fun lastRawStatus(context: Context): String? =
        prefs(context).getString(KEY_STATUS, null)

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
        val status = prefs(context).getString(KEY_STATUS, null)
        if (status == null) {
            return when {
                isAliveCurrent(context) -> "インストール済み"
                isAliveInstalled(context) && !installedSignatureMatches(context) ->
                    "要アンインストール（署名違い）"
                isAliveInstalled(context) -> "旧バージョン（更新可）"
                else -> "未インストール"
            }
        }
        return when (status) {
            "already_installed", "success", "browser_preferred" -> "インストール済み"
            "missing" -> "未インストール"
            "resolving", "downloading" -> "ダウンロード中"
            "installing" -> {
                if (InstallSupport.isDeviceOwner(context)) "インストール中"
                else "確認待ち（個人用）"
            }
            "need_permission" -> "許可が必要（個人用）"
            "need_uninstall" -> "要アンインストール（署名/旧版）"
            "sha_mismatch" -> "ハッシュ不一致（中止）"
            "failure", "silent_failed" -> "失敗"
            else -> when {
                isAliveCurrent(context) -> "インストール済み"
                isAliveInstalled(context) -> "旧バージョン（更新可）"
                else -> "未インストール"
            }
        }
    }

    fun persistSuccess(context: Context) {
        persist(context, "success", "インストール成功")
    }

    fun persistFailure(context: Context, message: String?) {
        persist(context, "failure", message ?: "install failure")
    }

    fun persistInstallingUserConfirm(context: Context) {
        persist(context, "installing", "個人用モードでインストール確認が必要")
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

    private fun installedVersionCode(context: Context): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(KeepPackages.ALIVE_PACKAGE, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(-1L)
    }

    private fun installedVersionName(context: Context): String? {
        return runCatching {
            context.packageManager.getPackageInfo(KeepPackages.ALIVE_PACKAGE, 0).versionName
        }.getOrNull()
    }

    /** True when installed Alive signing cert SHA-256 matches [EXPECTED_CERT_SHA256]. */
    private fun installedSignatureMatches(context: Context): Boolean {
        val digests = installedCertSha256Hexes(context)
        if (digests.isEmpty()) {
            // Unable to read signatures — do not block update; treat as unknown/match.
            Log.w(TAG, "Could not read Alive signing certs; assuming match")
            return true
        }
        return digests.any { it.equals(EXPECTED_CERT_SHA256, ignoreCase = true) }
    }

    private fun installedCertSha256Hexes(context: Context): List<String> {
        return runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(
                    KeepPackages.ALIVE_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return@runCatching emptyList()
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
                signers.map { certSha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(
                    KeepPackages.ALIVE_PACKAGE,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures?.map { certSha256Hex(it.toByteArray()) } ?: emptyList()
            }
        }.onFailure {
            Log.w(TAG, "installedCertSha256Hexes failed", it)
        }.getOrDefault(emptyList())
    }

    private fun certSha256Hex(certBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun fileSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Download pinned URL first (SHA-256 hard-fail on success).
     * If pinned download fails, try latest/download (accept even if SHA differs,
     * so a newer latest can still install; log a warning).
     */
    private fun downloadApk(dest: File): Result<File> {
        val pinned = downloadOnce(APK_URL, dest)
        if (pinned.isSuccess) {
            val hex = runCatching { fileSha256Hex(dest) }.getOrElse { err ->
                dest.delete()
                return Result.failure(IllegalStateException("SHA-256計算失敗: ${err.message}"))
            }
            if (!hex.equals(APK_SHA256, ignoreCase = true)) {
                Log.e(TAG, "Pinned APK SHA-256 mismatch: got=$hex expected=$APK_SHA256")
                dest.delete()
                return Result.failure(
                    IllegalStateException("ハッシュ不一致（期待 $APK_SHA256 / 実際 $hex）")
                )
            }
            Log.i(TAG, "Pinned Alive APK SHA-256 OK")
            return pinned
        }
        Log.w(TAG, "Pinned download failed: ${pinned.exceptionOrNull()?.message}; trying latest")

        val latest = downloadOnce(APK_URL_FALLBACK, dest)
        if (latest.isFailure) {
            return Result.failure(
                latest.exceptionOrNull()
                    ?: pinned.exceptionOrNull()
                    ?: IllegalStateException("ダウンロード失敗")
            )
        }
        val hex = runCatching { fileSha256Hex(dest) }.getOrNull()
        if (hex != null && hex.equals(APK_SHA256, ignoreCase = true)) {
            Log.i(TAG, "Fallback latest APK matches pinned SHA-256")
        } else {
            Log.w(
                TAG,
                "Fallback latest APK SHA-256 differs from pinned 0.1.82 (got=$hex); accepting latest"
            )
        }
        return latest
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

    private fun installApk(context: Context, apk: File): Result<Unit> {
        return InstallSupport.commitApkSession(
            context = context,
            apkFiles = listOf(apk),
            packageName = KeepPackages.ALIVE_PACKAGE,
            splitNamePrefix = "alive",
            statusAction = AliveInstallStatusReceiver.ACTION,
            statusRequestCode = 0x414C // 'AL'
        )
    }
}
