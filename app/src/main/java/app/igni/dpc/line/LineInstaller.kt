package app.igni.dpc.line

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import app.igni.dpc.LineInstallStatusReceiver
import app.igni.dpc.install.InstallSupport
import app.igni.dpc.policy.KeepPackages
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Ensures LINE ([KeepPackages.LINE_PACKAGE]) is installed.
 *
 * - Device Owner: silent PackageInstaller (auto from PolicyApplier + Admin button).
 * - Personal mode (Admin button): download then PackageInstaller with user confirm
 *   (or ACTION_VIEW / FileProvider for a single APK).
 *
 * Fire-and-forget async helper never blocks [app.igni.dpc.policy.PolicyApplier.apply].
 */
object LineInstaller {

    private const val TAG = "IgniLine"
    private const val PREFS = "igni_line_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    /** Stable short mirror URL (preferred). */
    const val XAPK_URL =
        "https://github.com/mikasahahappy0526-create/i/releases/download/line/line.xapk"

    private const val USER_AGENT = "Igni-DPC-Line/1.0.37 (Android)"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    const val PLAY_MARKET_URI = "market://details?id=jp.naver.line.android"
    const val PLAY_HTTPS_URI =
        "https://play.google.com/store/apps/details?id=jp.naver.line.android"

    /** Kick off install attempt on a background thread (returns immediately). */
    fun ensureLineInstalledAsync(context: Context) {
        val app = context.applicationContext
        // Auto path is DO-only (PolicyApplier already gates; belt-and-suspenders).
        if (!InstallSupport.isDeviceOwner(app)) {
            Log.i(TAG, "Skip auto LINE install — not Device Owner")
            return
        }
        executor.execute {
            runCatching { ensureLineInstalled(app) }
                .onFailure { Log.w(TAG, "ensureLineInstalled crashed", it) }
        }
    }

    /**
     * Synchronous attempt (call from a worker thread). Safe to call from Admin UI button.
     * Skips overlapping runs. Works in DO (silent) and personal mode (user confirm).
     */
    fun ensureLineInstalled(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "LINE install already in progress; skip")
            return
        }
        val workDir = File(app.cacheDir, "line-apk").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            if (isLineInstalled(app)) {
                Log.i(TAG, "LINE already installed; skip")
                persist(app, "already_installed", "LINE はインストール済み")
                return
            }

            val isDo = InstallSupport.isDeviceOwner(app)
            if (!isDo && !InstallSupport.ensureCanRequestInstall(app)) {
                persist(app, "need_permission", "個人用モード: 「提供元不明のアプリ」を許可してください")
                return
            }

            persist(app, "downloading", "GitHub から XAPK をダウンロード中…")
            val dest = File(workDir, "line.xapk")
            val downloaded = downloadXapk(dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "LINE download failed: $err")
                persist(app, "silent_failed", "DL失敗 ($err)")
                return
            }

            if (isDo) {
                persist(app, "installing", "PackageInstaller でインストール中…")
            } else {
                persist(app, "installing", "個人用モードでインストール確認が必要")
            }

            val installed = when {
                looksLikeZip(dest) -> installFromXapk(app, dest, workDir)
                else -> installApks(app, listOf(dest))
            }
            if (installed.isFailure) {
                val err = installed.exceptionOrNull()?.message ?: "install failed"
                Log.w(TAG, "LINE install failed: $err")
                // Personal single-APK fallback via FileProvider if session failed.
                if (!isDo && !looksLikeZip(dest)) {
                    val view = InstallSupport.installViaViewIntent(app, dest)
                    if (view.isSuccess) {
                        persist(app, "installing", "個人用モードでインストール確認が必要")
                        return
                    }
                }
                persist(app, "silent_failed", if (isDo) "サイレント失敗 ($err)" else "インストール失敗 ($err)")
                return
            }
            if (isLineInstalled(app)) {
                persist(app, "success", "インストール確認済み")
            } else {
                persist(
                    app,
                    "installing",
                    if (isDo) "インストール要求を送信済み（結果はログ）"
                    else "個人用モードでインストール確認が必要"
                )
            }
            Log.i(TAG, "LINE PackageInstaller session committed (do=$isDo)")
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

    /** Short Japanese-only status for Admin UI (no English keys / long tails). */
    fun lastStatusText(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString(KEY_STATUS, null)
        if (status == null) {
            return if (isLineInstalled(context)) "インストール済み" else "未インストール"
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
            "failure", "silent_failed" -> "失敗"
            else -> if (isLineInstalled(context)) "インストール済み" else "未インストール"
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

    private fun downloadXapk(dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val connection = openGetFollowingRedirects(XAPK_URL)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            // LINE XAPK is ~160MB; reject tiny / error HTML pages.
            if (dest.length() < 1_000_000L) {
                dest.delete()
                error("Downloaded file too small (${dest.length()})")
            }
            Log.i(TAG, "Downloaded ${dest.length()} bytes from $XAPK_URL")
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
                readTimeout = 300_000
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

    private fun looksLikeZip(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val b = ByteArray(2)
                if (input.read(b) != 2) return false
                b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * XAPK = zip of base + split APKs (+ optional OBB).
     * Install all .apk entries in one PackageInstaller session.
     */
    private fun installFromXapk(context: Context, xapk: File, workDir: File): Result<Unit> {
        return runCatching {
            val extractDir = File(workDir, "xapk-unpacked").also {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            val apkFiles = mutableListOf<File>()
            val obbFiles = mutableListOf<File>()
            ZipFile(xapk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isBlank() || name.startsWith(".")) continue
                    val lower = name.lowercase()
                    val out = File(extractDir, name)
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    when {
                        lower.endsWith(".apk") -> apkFiles += out
                        lower.endsWith(".obb") -> obbFiles += out
                    }
                }
            }
            require(apkFiles.isNotEmpty()) { "XAPK 内に APK がありません" }
            Log.i(TAG, "XAPK unpacked apks=${apkFiles.size} obbs=${obbFiles.size}")
            if (obbFiles.isNotEmpty()) {
                copyObbsBestEffort(context, obbFiles)
            }
            installApks(context, apkFiles).getOrThrow()
        }
    }

    /** Best-effort OBB copy to Android/obb/<pkg>/; on failure continue APK install. */
    private fun copyObbsBestEffort(context: Context, obbs: List<File>) {
        val obbRoot = runCatching {
            File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/${KeepPackages.LINE_PACKAGE}"
            )
        }.getOrElse {
            File(context.getExternalFilesDir(null)?.parentFile?.parentFile, "obb/${KeepPackages.LINE_PACKAGE}")
        }
        runCatching {
            if (!obbRoot.exists()) obbRoot.mkdirs()
            for (obb in obbs) {
                val target = File(obbRoot, obb.name)
                obb.copyTo(target, overwrite = true)
                Log.i(TAG, "Copied OBB -> ${target.absolutePath}")
            }
        }.onFailure {
            Log.w(TAG, "OBB copy failed (best-effort); continuing APK install", it)
        }
    }

    private fun installApks(context: Context, apkFiles: List<File>): Result<Unit> {
        return InstallSupport.commitApkSession(
            context = context,
            apkFiles = apkFiles,
            packageName = KeepPackages.LINE_PACKAGE,
            splitNamePrefix = "line",
            statusAction = LineInstallStatusReceiver.ACTION,
            statusRequestCode = 0x4C49 // 'LI'
        )
    }

    /**
     * Opens Play Store LINE page; falls back to HTTPS if market:// fails.
     * **Admin / explicit user action only** — never call from PolicyApplier / Boot /
     * PackageMonitor / compliance / async ensure* auto paths.
     */
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
