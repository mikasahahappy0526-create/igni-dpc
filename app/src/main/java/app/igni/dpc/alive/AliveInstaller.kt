package app.igni.dpc.alive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.igni.dpc.AliveInstallStatusReceiver
import app.igni.dpc.install.InstallSupport
import app.igni.dpc.policy.KeepPackages
import app.igni.dpc.update.SemVer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Admin「アライブ」button: install, upgrade to the pin, open, or ask for uninstall. */
enum class AliveButtonAction {
    INSTALL,
    UPDATE,
    OPEN,
    NEED_UNINSTALL
}

/**
 * Installs アライブ ([KeepPackages.ALIVE_PACKAGE]) from a pinned GitHub Releases APK URL.
 *
 * - Device Owner: silent PackageInstaller (auto from PolicyApplier + Admin button).
 * - Personal mode (Admin button): download then prompted PackageInstaller / ACTION_VIEW.
 *
 * v1.0.53: pins Alive **0.1.90** (versionCode 91) via GitHub latest/download,
 * verifies SHA-256 with a hard-fail on every source, and falls back to the version tag if latest fetch fails,
 * and surfaces clear Japanese status when an older / differently-signed install blocks update.
 * Does not silently uninstall.
 *
 * Standing rule whenever [TARGET_VERSION_CODE] / [TARGET_VERSION_NAME] are bumped:
 * an already-installed Alive older than the pin is upgraded (same hash check), not merely opened.
 * Open-only is reserved for a matching signature at or above the pin.
 */
object AliveInstaller {

    private const val TAG = "IgniAlive"
    private const val PREFS = "igni_alive_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    /** Pinned Alive 0.1.90 primary (fixed-overwrite latest asset). */
    const val APK_URL =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/latest/download/alive.apk"

    /** Version-tag fallback for the pinned Alive release. */
    const val APK_URL_FALLBACK =
        "https://github.com/mikasahahappy0526-create/puchicli/releases/download/0.1.90/alive.apk"

    /** SHA-256 of the pinned 0.1.90 APK (hard-fail for both sources). */
    const val APK_SHA256 =
        "601282c478c96de30b14596c10cb3277104d52fb45505566872a797252ef824a"

    const val TARGET_VERSION_NAME = "0.1.90"
    const val TARGET_VERSION_CODE = 91L

    /**
     * Signing-cert SHA-256 of the pinned Alive build (hex lowercase).
     * Used to detect signature mismatch that requires uninstall before reinstall.
     * Same publisher key as prior pins unless Alive rotates.
     */
    const val EXPECTED_CERT_SHA256 =
        "106691866d324942d8ad8bbe5722b59c2aceb35b0532692008a59248467f92c1"

    private const val USER_AGENT = "Igni-DPC-Alive/1.0.53 (Android)"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    /** Button-driven install: open Alive once PackageInstaller reports the pinned build. */
    private val openAfterInstall = AtomicBoolean(false)

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
     * If same signature but older ([needsAliveUpgrade]), downloads and attempts in-place update.
     *
     * @param openWhenReady when true (Admin button), open Alive after the pinned build is installed.
     * Policy auto-install leaves this false so it does not steal the foreground.
     */
    fun ensureAliveInstalled(context: Context, openWhenReady: Boolean = false) {
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
            if (!openWhenReady) openAfterInstall.set(false)
            if (isAliveInstalled(app)) {
                val code = installedVersionCode(app)
                val name = installedVersionName(app)
                if (isAliveCurrent(app)) {
                    Log.i(TAG, "Alive already current (vc=$code name=$name); skip")
                    persist(
                        app,
                        "already_installed",
                        "アライブ ${name ?: TARGET_VERSION_NAME} はインストール済み"
                    )
                    if (openWhenReady) openAlive(app)
                    return
                }
                if (!installedSignatureMatches(app)) {
                    Log.w(TAG, "Alive installed with different signature; uninstall required")
                    openAfterInstall.set(false)
                    persist(
                        app,
                        "need_uninstall",
                        "アライブの署名が違います。一度アンインストールしてから入れ直してください"
                    )
                    return
                }
                Log.i(TAG, "Alive installed but older (vc=$code name=$name < $TARGET_VERSION_NAME); updating")
            }

            val isDo = InstallSupport.isDeviceOwner(app)
            if (!isDo && !InstallSupport.ensureCanRequestInstall(app)) {
                openAfterInstall.set(false)
                persist(app, "need_permission", "個人用モード: 「提供元不明のアプリ」を許可してください")
                return
            }

            persist(app, "downloading", "アライブ $TARGET_VERSION_NAME をダウンロード中…")
            val dest = File(workDir, "alive.apk")
            val downloaded = downloadApk(dest)
            if (downloaded.isFailure) {
                openAfterInstall.set(false)
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
                openAfterInstall.set(false)
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
            // Commit returns before PackageInstaller finishes. An older Alive is still
            // "installed" here, so success is only claimed once the pin is actually present.
            if (isAliveCurrent(app)) {
                openAfterInstall.set(false)
                persist(app, "success", "インストール確認済み（$TARGET_VERSION_NAME）")
                if (openWhenReady) openAlive(app)
            } else if (openWhenReady && awaitAliveCurrent(app, if (isDo) 20_000L else 1_500L)) {
                openAfterInstall.set(false)
                persist(app, "success", "インストール確認済み（$TARGET_VERSION_NAME）")
                openAlive(app)
            } else {
                if (openWhenReady) openAfterInstall.set(true)
                persist(
                    app,
                    "installing",
                    if (isDo) "アライブ $TARGET_VERSION_NAME をインストール中…"
                    else "個人用モードでインストール確認が必要"
                )
                if (openWhenReady && isAliveCurrent(app) && openAfterInstall.compareAndSet(true, false)) {
                    persist(app, "success", "インストール確認済み（$TARGET_VERSION_NAME）")
                    openAlive(app)
                }
            }
            Log.i(TAG, "Alive PackageInstaller session committed (do=$isDo open=$openWhenReady)")
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

    /**
     * True only when Alive is installed, the signing cert matches, and versionCode
     * (or versionName when the code is missing) is at or above the pin.
     * An older install is not current — the Admin button must upgrade it.
     */
    fun isAliveCurrent(context: Context): Boolean {
        if (!isAliveInstalled(context)) return false
        val app = context.applicationContext
        if (!installedSignatureMatches(app)) return false
        return !needsAliveUpgrade(
            installedVersionCode(app),
            installedVersionName(app)
        )
    }

    /** What the Admin「アライブ」button should do for the package on this device. */
    fun primaryAction(context: Context): AliveButtonAction {
        if (!isAliveInstalled(context)) return AliveButtonAction.INSTALL
        if (!installedSignatureMatches(context.applicationContext)) {
            return AliveButtonAction.NEED_UNINSTALL
        }
        return if (isAliveCurrent(context)) AliveButtonAction.OPEN else AliveButtonAction.UPDATE
    }

    /** Short Japanese label: 入れる / 更新 / アライブ / 入れ直す. */
    fun primaryButtonLabel(context: Context): String = when (primaryAction(context)) {
        AliveButtonAction.INSTALL -> "入れる"
        AliveButtonAction.UPDATE -> "更新"
        AliveButtonAction.OPEN -> "アライブ"
        AliveButtonAction.NEED_UNINSTALL -> "入れ直す"
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

    /**
     * Short Japanese-only status for Admin UI (no English keys / long tails).
     *
     * Terminal prefs from an older Igni (`success` / `already_installed`) are ignored
     * when the live package is below the current pin, so a pin bump shows 「更新が必要」.
     */
    fun lastStatusText(context: Context): String {
        val status = prefs(context).getString(KEY_STATUS, null)
        val live = liveStatusLabel(context)
        return when (status) {
            null, "already_installed", "success", "browser_preferred", "missing" -> live
            "resolving", "downloading" -> "ダウンロード中（$TARGET_VERSION_NAME）"
            "installing" -> {
                if (isAliveInstalled(context) && !isAliveCurrent(context)) {
                    "更新中（$TARGET_VERSION_NAME）"
                } else if (InstallSupport.isDeviceOwner(context)) {
                    "インストール中"
                } else {
                    "確認待ち（個人用）"
                }
            }
            "need_permission" -> "許可が必要（個人用）"
            "need_uninstall" -> "要アンインストール（署名違い）"
            "sha_mismatch" -> "ハッシュ不一致（中止）"
            "failure", "silent_failed" -> "失敗"
            else -> live
        }
    }

    fun persistSuccess(context: Context) {
        val app = context.applicationContext
        persist(app, "success", "インストール成功")
        if (openAfterInstall.compareAndSet(true, false)) {
            openAlive(app)
        }
    }

    fun persistFailure(context: Context, message: String?) {
        openAfterInstall.set(false)
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

    /**
     * True when the installed Alive should be replaced by the pin.
     * versionCode is authoritative. versionName is used when the code is unknown,
     * and also when the code equals the pin but the name is still older.
     * Unknown version is treated as outdated so the button upgrades instead of opening.
     */
    internal fun needsAliveUpgrade(
        installedVersionCode: Long,
        installedVersionName: String?,
        targetVersionCode: Long = TARGET_VERSION_CODE,
        targetVersionName: String = TARGET_VERSION_NAME
    ): Boolean {
        if (installedVersionCode >= 0 && installedVersionCode < targetVersionCode) return true
        if (installedVersionCode > targetVersionCode) return false
        val installed = SemVer.parse(installedVersionName)
        val target = SemVer.parse(targetVersionName)
        if (installedVersionCode == targetVersionCode) {
            if (installed != null && target != null) return installed < target
            return false
        }
        if (installed != null && target != null) return installed < target
        return true
    }

    private fun liveStatusLabel(context: Context): String {
        if (!isAliveInstalled(context)) return "未インストール"
        if (!installedSignatureMatches(context)) return "要アンインストール（署名違い）"
        val name = installedVersionName(context) ?: "?"
        return if (needsAliveUpgrade(installedVersionCode(context), name)) {
            "更新が必要（$name→$TARGET_VERSION_NAME）"
        } else {
            "インストール済み（$name）"
        }
    }

    /** Poll until the pinned Alive is the installed package, or [timeoutMs] elapses. */
    private fun awaitAliveCurrent(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAliveCurrent(context)) return true
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return isAliveCurrent(context)
            }
        }
        return isAliveCurrent(context)
    }

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
     * Download latest first, then the version-tag fallback. Every successful download must match
     * the pinned SHA-256; a mismatch is a hard failure and is never installed.
     */
    private fun downloadApk(dest: File): Result<File> {
        var lastError: Throwable? = null
        for (url in listOf(APK_URL, APK_URL_FALLBACK)) {
            val downloaded = downloadOnce(url, dest)
            if (downloaded.isFailure) {
                lastError = downloaded.exceptionOrNull()
                Log.w(TAG, "Alive download failed ($url): ${lastError?.message}")
                continue
            }
            val hex = runCatching { fileSha256Hex(dest) }.getOrElse { err ->
                dest.delete()
                return Result.failure(IllegalStateException("SHA-256計算失敗: ${err.message}"))
            }
            if (!hex.equals(APK_SHA256, ignoreCase = true)) {
                Log.e(TAG, "Alive APK SHA-256 mismatch from $url: got=$hex expected=$APK_SHA256")
                dest.delete()
                return Result.failure(
                    IllegalStateException("ハッシュ不一致（期待 $APK_SHA256 / 実際 $hex）")
                )
            }
            Log.i(TAG, "Alive APK SHA-256 OK from $url (size=${dest.length()})")
            return downloaded
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
