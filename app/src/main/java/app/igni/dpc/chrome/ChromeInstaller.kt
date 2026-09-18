package app.igni.dpc.chrome

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.igni.dpc.AdminReceiver
import app.igni.dpc.ChromeInstallStatusReceiver
import app.igni.dpc.line.UptodownClient
import app.igni.dpc.policy.KeepPackages
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Ensures Chrome ([KeepPackages.CHROME_PACKAGE]) is installed after Device Owner policy apply.
 *
 * Strategy (v1.0.19 silent-only auto):
 * 1. If package present but DPM-hidden / disabled → unhide / enable; treat as installed only if usable.
 * 2. If missing → Uptodown download + PackageInstaller only (no Play).
 * 3. When usable → best-effort DPM persistent preferred for http/https VIEW (Chrome, not Google app).
 * 4. Hard failures → log + status prefs only; never open Play from auto paths.
 * 5. Admin UI may call [openPlayStore] as an explicit user action.
 *
 * Fire-and-forget; never blocks [app.igni.dpc.policy.PolicyApplier.apply].
 */
object ChromeInstaller {

    private const val TAG = "IgniChrome"
    private const val PREFS = "igni_chrome_install"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at_ms"

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    const val PLAY_MARKET_URI = "market://details?id=com.android.chrome"
    const val PLAY_HTTPS_URI =
        "https://play.google.com/store/apps/details?id=com.android.chrome"

    /** Kick off install attempt on a background thread (returns immediately). */
    fun ensureChromeInstalledAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { ensureChromeInstalled(app) }
                .onFailure { Log.w(TAG, "ensureChromeInstalled crashed", it) }
        }
    }

    /**
     * Policy-apply helper (v1.0.18): unhide/enable via [isChromeInstalled]; if still missing,
     * persist status only and kick silent Uptodown async. **Never opens Play.**
     * Prefer calling [ensureChromeInstalledAsync] from [app.igni.dpc.policy.PolicyApplier] directly.
     */
    fun ensureChromeInstalledPrompt(context: Context) {
        val app = context.applicationContext
        if (isChromeInstalled(app)) {
            Log.i(TAG, "ensureChromeInstalledPrompt: Chrome usable; skip")
            persist(app, "already_installed", "Chrome はインストール済み")
            return
        }
        Log.i(TAG, "ensureChromeInstalledPrompt: Chrome missing — status only + silent async (no Play)")
        persist(app, "missing", "未インストール — サイレント試行のみ（Play は開かない）")
        ensureChromeInstalledAsync(app)
    }

    /**
     * Synchronous silent attempt (worker thread). Safe for Admin「Chromeを入れる」default.
     * Skips overlapping runs. Uptodown + PackageInstaller only — never opens Play.
     */
    fun ensureChromeInstalled(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "Chrome install already in progress; skip")
            return
        }
        val workDir = File(app.cacheDir, "chrome-uptodown").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            if (isChromeInstalled(app)) {
                Log.i(TAG, "Chrome already installed; skip")
                persist(app, "already_installed", "Chrome はインストール済み")
                preferChromeAsBrowser(app)
                return
            }

            persist(app, "resolving", "Uptodown から最新 URL を解決中…")
            val resolved = UptodownClient.resolveLatestChrome()
            if (resolved.isFailure) {
                val err = resolved.exceptionOrNull()?.message ?: "resolve failed"
                Log.w(TAG, "Uptodown resolve failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "解決失敗 ($err)")
                return
            }
            val info = resolved.getOrThrow()
            val ext = guessExtension(info.kindFile, info.downloadUrl)
            val dest = File(workDir, "chrome-latest.$ext")

            persist(
                app,
                "downloading",
                "Uptodown からダウンロード中… (${info.version ?: "latest"} / $ext)"
            )
            val downloaded = UptodownClient.downloadTo(info.downloadUrl, dest)
            if (downloaded.isFailure) {
                val err = downloaded.exceptionOrNull()?.message ?: "download failed"
                Log.w(TAG, "Chrome download failed: $err — silent path stops (no Play)")
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
                Log.w(TAG, "Chrome silent install failed: $err — silent path stops (no Play)")
                persist(app, "silent_failed", "サイレント失敗 ($err)")
                return
            }
            if (isChromeInstalled(app)) {
                persist(app, "success", "インストール確認済み (${info.version ?: ext})")
                preferChromeAsBrowser(app)
            } else {
                persist(app, "installing", "インストール要求を送信済み（結果はログ）")
            }
            Log.i(TAG, "Chrome PackageInstaller session committed (fire-and-forget)")
        } finally {
            busy.set(false)
        }
    }

    /**
     * Package present AND usable (not DPM-hidden; not disabled).
     * If DO and hidden → unhide; return true only after unhide succeeds.
     * If disabled → try enable; return true only if usable afterward.
     */
    fun isChromeInstalled(context: Context): Boolean {
        val app = context.applicationContext
        val present = runCatching {
            app.packageManager.getPackageInfo(KeepPackages.CHROME_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!present) return false

        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val isDo = dpm != null && dpm.isDeviceOwnerApp(app.packageName)
        if (isDo && dpm != null) {
            val admin = AdminReceiver.componentName(app)
            val hidden = runCatching {
                dpm.isApplicationHidden(admin, KeepPackages.CHROME_PACKAGE)
            }.getOrDefault(false)
            if (hidden) {
                val restored = runCatching {
                    dpm.setApplicationHidden(admin, KeepPackages.CHROME_PACKAGE, false)
                }.getOrDefault(false)
                val stillHidden = runCatching {
                    dpm.isApplicationHidden(admin, KeepPackages.CHROME_PACKAGE)
                }.getOrDefault(true)
                if (!restored || stillHidden) {
                    Log.w(TAG, "Chrome present but still hidden after unhide (restored=$restored)")
                    return false
                }
                Log.i(TAG, "Chrome was DPM-hidden; unhid successfully")
            }
        }

        val pm = app.packageManager
        val enabledSetting = runCatching {
            pm.getApplicationEnabledSetting(KeepPackages.CHROME_PACKAGE)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val disabled = enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        val appInfoEnabled = runCatching {
            pm.getApplicationInfo(KeepPackages.CHROME_PACKAGE, 0).enabled
        }.getOrDefault(true)

        if (disabled || !appInfoEnabled) {
            runCatching {
                pm.setApplicationEnabledSetting(
                    KeepPackages.CHROME_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    0
                )
            }.onFailure { Log.w(TAG, "Failed to enable Chrome package", it) }

            // DO: also clear hidden again in case enable interacted with hide.
            if (isDo && dpm != null) {
                runCatching {
                    dpm.setApplicationHidden(
                        AdminReceiver.componentName(app),
                        KeepPackages.CHROME_PACKAGE,
                        false
                    )
                }
            }

            val newSetting = runCatching {
                pm.getApplicationEnabledSetting(KeepPackages.CHROME_PACKAGE)
            }.getOrDefault(enabledSetting)
            val stillDisabled =
                newSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    newSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    newSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            val stillAppDisabled = runCatching {
                !pm.getApplicationInfo(KeepPackages.CHROME_PACKAGE, 0).enabled
            }.getOrDefault(true)
            if (stillDisabled || stillAppDisabled) {
                Log.w(TAG, "Chrome present but still disabled after enable attempt")
                return false
            }
            Log.i(TAG, "Chrome was disabled; enabled successfully")
        }

        return true
    }


    /** Short Japanese-only status for Admin UI (no English keys / long tails). */
    fun lastStatusText(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString(KEY_STATUS, null)
        if (status == null) {
            return if (isChromeInstalled(context)) "インストール済み" else "未インストール"
        }
        return when (status) {
            "already_installed", "success", "browser_preferred" -> "インストール済み"
            "missing" -> "未インストール"
            "resolving", "downloading" -> "ダウンロード中"
            "installing" -> "インストール中"
            "failure", "silent_failed" -> "失敗"
            else -> if (isChromeInstalled(context)) "インストール済み" else "未インストール"
        }
    }

    fun persistSuccess(context: Context) {
        persist(context, "success", "サイレントインストール成功")
        preferChromeAsBrowser(context.applicationContext)
    }

    /**
     * Device Owner: set Chrome as persistent preferred activity for http/https VIEW.
     * Clears Google app preferred handlers first. Best-effort; never throws.
     */
    fun preferChromeAsBrowser(context: Context) {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(app.packageName)) return
        if (!isChromeInstalled(app)) return
        val admin = AdminReceiver.componentName(app)

        for (pkg in KeepPackages.FORCE_HIDE_GOOGLE) {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
        }

        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(KeepPackages.CHROME_PACKAGE)
        val launchInfo = runCatching {
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList()).firstOrNull()?.activityInfo
        val component = if (launchInfo != null) {
            android.content.ComponentName(launchInfo.packageName, launchInfo.name)
        } else {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .setPackage(KeepPackages.CHROME_PACKAGE)
            val viewInfo = runCatching {
                pm.queryIntentActivities(view, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList()).firstOrNull()?.activityInfo
            viewInfo?.let { android.content.ComponentName(it.packageName, it.name) }
        } ?: run {
            Log.w(TAG, "preferChromeAsBrowser: no Chrome activity")
            return
        }

        runCatching {
            val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            }
            dpm.addPersistentPreferredActivity(admin, filter, component)
            Log.i(TAG, "Chrome set as default browser preferred: $component")
            // Do not overwrite install status; only enrich detail when idle/missing.
            val cur = prefs(app).getString(KEY_STATUS, null)
            if (cur.isNullOrBlank() || cur == "missing" || cur == "silent_failed") {
                persist(app, "browser_preferred", "既定ブラウザ候補に設定")
            }
        }.onFailure {
            Log.w(TAG, "preferChromeAsBrowser failed", it)
        }
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
            else -> "apk" // Chrome on Uptodown is typically APK
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

    private fun copyObbsBestEffort(context: Context, obbs: List<File>) {
        val obbRoot = runCatching {
            File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/${KeepPackages.CHROME_PACKAGE}"
            )
        }.getOrElse {
            File(context.getExternalFilesDir(null)?.parentFile?.parentFile, "obb/${KeepPackages.CHROME_PACKAGE}")
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
                setAppPackageName(KeepPackages.CHROME_PACKAGE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFiles.forEachIndexed { index, apk ->
                    val splitName = if (apkFiles.size == 1) "chrome.apk" else "chrome-$index-${apk.name}"
                    apk.inputStream().use { input ->
                        session.openWrite(splitName, 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
                val statusIntent = Intent(ChromeInstallStatusReceiver.ACTION).apply {
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
     * Opens Play Store Chrome page on the main looper (Samsung-friendly).
     * **Admin / explicit user action only** — never call from PolicyApplier / Boot /
     * PackageMonitor / compliance / async ensure* auto paths.
     * Uses NEW_TASK | CLEAR_TOP | RESET_TASK_IF_NEEDED + CATEGORY_BROWSABLE.
     * Prefer com.android.vending; fall back to generic market:// then HTTPS.
     */
    fun openPlayStore(context: Context) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { openPlayStoreNow(app) }
            return
        }
        openPlayStoreNow(app)
    }

    private fun openPlayStoreNow(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        fun tryStart(intent: Intent, label: String): Boolean {
            return runCatching {
                context.startActivity(intent)
                Log.i(TAG, "Opened Play Store via $label")
                true
            }.onFailure {
                Log.w(TAG, "Play open failed ($label)", it)
            }.getOrDefault(false)
        }

        // 1) Explicit Play Store package (most reliable on Samsung One UI).
        val vendingMarket = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_MARKET_URI)).apply {
            addFlags(flags)
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.vending")
        }
        if (tryStart(vendingMarket, "vending+market://")) return

        // 2) Generic market:// (any handler).
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_MARKET_URI)).apply {
            addFlags(flags)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (tryStart(market, "market://")) return

        // 3) HTTPS Play URL via vending, then any browser.
        val vendingHttps = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_HTTPS_URI)).apply {
            addFlags(flags)
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.vending")
        }
        if (tryStart(vendingHttps, "vending+https")) return

        val https = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_HTTPS_URI)).apply {
            addFlags(flags)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        tryStart(https, "https")
    }
}
