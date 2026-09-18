package app.igni.dpc.line

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import app.igni.dpc.LineInstallStatusReceiver
import app.igni.dpc.policy.KeepPackages
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Ensures LINE ([KeepPackages.LINE_PACKAGE]) is installed after Device Owner policy apply.
 *
 * 1. If already installed → skip.
 * 2. Resolve latest file from Uptodown eAPI (APK or XAPK) and download to cache.
 * 3. .apk → single PackageInstaller session (silent DO).
 * 4. .xapk → unzip, install all .apk splits in one session; OBB best-effort copy.
 * 5. On hard failure → log + status prefs only (never open Play from auto path).
 *    Admin UI may call [openPlayStore] explicitly.
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
        val workDir = File(app.cacheDir, "line-uptodown").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            if (isLineInstalled(app)) {
                Log.i(TAG, "LINE already installed; skip")
                persist(app, "already_installed", "LINE はインストール済み")
                return
            }

            persist(app, "resolving", "Uptodown から最新 URL を解決中…")
            val resolved = UptodownClient.resolveLatestLine()
            if (resolved.isFailure) {
                val err = resolved.exceptionOrNull()?.message ?: "resolve failed"
                Log.w(TAG, "Uptodown resolve failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "解決失敗 ($err)")
                return
            }
            val info = resolved.getOrThrow()
            val ext = guessExtension(info.kindFile, info.downloadUrl)
            val dest = File(workDir, "line-latest.$ext")

            persist(
                app,
                "downloading",
                "Uptodown からダウンロード中… (${info.version ?: "latest"} / $ext)"
            )
            val downloaded = UptodownClient.downloadTo(info.downloadUrl, dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "LINE download failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "DL失敗 ($err)")
                return
            }

            persist(app, "installing", "PackageInstaller でインストール中…")
            val installed = when {
                ext.equals("xapk", ignoreCase = true) || looksLikeZip(dest) ->
                    installFromXapk(app, dest, workDir)
                else ->
                    installApks(app, listOf(dest))
            }
            if (installed.isFailure) {
                val err = installed.exceptionOrNull()?.message ?: "install failed"
                Log.w(TAG, "LINE silent install failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "サイレント失敗 ($err)")
                return
            }
            // Session committed; final success/failure arrives via LineInstallStatusReceiver.
            // Best-effort immediate package probe (usually still absent until commit finishes).
            if (isLineInstalled(app)) {
                persist(app, "success", "インストール確認済み (${info.version ?: ext})")
            } else {
                persist(app, "installing", "インストール要求を送信済み（結果はログ）")
            }
            Log.i(TAG, "LINE PackageInstaller session committed (fire-and-forget)")
        } finally {
            busy.set(false)
            // Keep cache briefly for debugging; wipe empty leftovers on next run.
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
            "installing" -> "インストール中"
            "failure", "silent_failed" -> "失敗"
            else -> if (isLineInstalled(context)) "インストール済み" else "未インストール"
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

    private fun guessExtension(kindFile: String?, url: String): String {
        val kind = kindFile?.lowercase().orEmpty()
        when {
            kind.contains("xapk") -> return "xapk"
            kind.contains("apk") -> return "apk"
        }
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".xapk") -> "xapk"
            path.endsWith(".apks") -> "xapk"
            path.endsWith(".apkm") -> "xapk"
            path.endsWith(".apk") -> "apk"
            else -> "xapk" // LINE on Uptodown is typically XAPK
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

    /** Best-effort OBB copy to Android/obb/<pkg>/; on failure leave Play fallback to caller. */
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
        return runCatching {
            require(apkFiles.isNotEmpty()) { "APK がありません" }
            for (f in apkFiles) {
                require(f.exists() && f.length() > 0L) { "空の APK: ${f.name}" }
            }
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
                apkFiles.forEachIndexed { index, apk ->
                    val splitName = if (apkFiles.size == 1) "line.apk" else "line-$index-${apk.name}"
                    apk.inputStream().use { input ->
                        session.openWrite(splitName, 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
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
            Log.i(TAG, "PackageInstaller session $sessionId committed (${apkFiles.size} APKs)")
        }
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
