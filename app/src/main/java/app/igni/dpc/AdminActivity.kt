package app.igni.dpc

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import app.igni.dpc.databinding.ActivityAdminBinding
import app.igni.dpc.policy.AudioStatus
import app.igni.dpc.policy.DarkModeStatus
import app.igni.dpc.policy.GoogleAppStatus
import app.igni.dpc.policy.HomeLayoutHelper
import app.igni.dpc.alive.AliveInstaller
import app.igni.dpc.chrome.ChromeInstaller
import app.igni.dpc.line.LineInstaller
import app.igni.dpc.tiktok.TikTokLiteInstaller
import app.igni.dpc.policy.PolicyApplier
import app.igni.dpc.update.AppSelfUpdater
import app.igni.dpc.update.AppUpdateChecker
import app.igni.dpc.update.CheckResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val updateBusy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.app_name)
        binding.allowlist.text = PolicyApplier(this).allowlistForDisplay().joinToString("\n")
        binding.currentVersion.text = getString(
            R.string.current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        binding.updateStatus.text = getString(R.string.update_status_idle)

        binding.btnReapply.setOnClickListener { reapply() }
        binding.btnUnhide.setOnClickListener { confirmUnhide() }
        binding.btnReturnPersonal.setOnClickListener { confirmReturnPersonal() }
        binding.btnUpdate.setOnClickListener { checkUpdate() }
        binding.btnInstallLine.setOnClickListener { installLine() }
        binding.btnInstallLinePlay.setOnClickListener { installLineViaPlay() }
        binding.btnInstallChrome.setOnClickListener { installChrome() }
        binding.btnInstallChromePlay.setOnClickListener { installChromeViaPlay() }
        binding.btnInstallTikTokLite.setOnClickListener { installTikTokLite() }
        binding.btnInstallTikTokLitePlay.setOnClickListener { installTikTokLiteViaPlay() }
        binding.btnInstallAlive.setOnClickListener { installAlive() }
        binding.btnOpenAlive.setOnClickListener { openAlive() }

        maybeReapplyAfterVersionChange()

        val applier = PolicyApplier(this)
        if (applier.isDeviceOwner() && !applier.hasApplied()) {
            reapply()
        } else {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    /**
     * After self-update, optionally re-apply policy once when versionCode changes
     * (BootReceiver also re-applies on MY_PACKAGE_REPLACED).
     */
    private fun maybeReapplyAfterVersionChange() {
        val prefs = getSharedPreferences(PREFS_UPDATE, MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_APPLIED_VERSION, -1)
        val current = BuildConfig.VERSION_CODE
        if (last == current) return
        prefs.edit().putInt(KEY_LAST_APPLIED_VERSION, current).apply()
        if (last < 0) return // first install / fresh prefs — PolicyApplier.hasApplied handles apply
        val applier = PolicyApplier(this)
        if (!applier.isDeviceOwner()) return
        executor.execute {
            LogI("Version changed $last -> $current; re-applying policy")
            applier.apply()
            runOnUiThread { refresh() }
        }
    }

    private fun LogI(msg: String) {
        android.util.Log.i("IgniAdmin", msg)
    }

    private fun refresh() {
        val applier = PolicyApplier(this)
        val isOwner = applier.isDeviceOwner()
        binding.statusValue.text = if (isOwner) {
            getString(R.string.status_device_owner)
        } else {
            getString(R.string.status_not_device_owner)
        }
        binding.statusValue.setTextColor(
            getColor(if (isOwner) R.color.igni_ok else R.color.igni_bad)
        )
        binding.hint.text = if (isOwner) {
            getString(R.string.hint_owner)
        } else {
            getString(R.string.hint_not_owner)
        }
        binding.hiddenCount.text = getString(R.string.hidden_count, applier.hiddenCount())
        updateTimeoutLabel(applier.currentScreenTimeoutMs())
        updateAudioLabel(applier.audioStatus())
        updateCameraLabel(applier.detectedCameraPackages())
        updateDarkModeLabel(applier.darkModeStatus())
        binding.allowlist.text = applier.allowlistForDisplay().joinToString("\n")
        binding.lockTaskNote.isVisible = BuildConfig.ENABLE_LOCK_TASK
        val busy = binding.progress.isVisible
        binding.btnReapply.isEnabled = isOwner && !busy
        binding.btnUnhide.isEnabled = isOwner && !busy
        binding.btnReturnPersonal.isEnabled = isOwner && !busy
        binding.btnUpdate.isEnabled = !updateBusy.get()
        binding.lineInstallStatus.text = LineInstaller.lastStatusText(this)
        binding.btnInstallLine.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallLinePlay.isEnabled = !busy && !updateBusy.get()
        binding.chromeInstallStatus.text = ChromeInstaller.lastStatusText(this)
        binding.btnInstallChrome.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallChromePlay.isEnabled = !busy && !updateBusy.get()
        binding.tiktokLiteInstallStatus.text = TikTokLiteInstaller.lastStatusText(this)
        binding.btnInstallTikTokLite.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallTikTokLitePlay.isEnabled = !busy && !updateBusy.get()
        binding.aliveInstallStatus.text = AliveInstaller.lastStatusText(this)
        binding.btnInstallAlive.isEnabled = !busy && !updateBusy.get()
        binding.btnOpenAlive.isEnabled = !busy && !updateBusy.get()
        updateGoogleLabel(applier.googleAppStatus())
        binding.homeLayoutStatus.text = applier.homeLayoutStatusText()
        val chromeOk = ChromeInstaller.isChromeInstalled(this)
        binding.chromeBrowserStatus.text = getString(
            R.string.chrome_browser_status_value,
            if (chromeOk) "利用可（適用時に http/https 優先設定を試行）" else "未インストール"
        )
    }

    private fun updateGoogleLabel(status: GoogleAppStatus) {
        binding.googleAppStatus.text = getString(
            R.string.google_app_status_value,
            status.detail
        )
    }

    private fun updateTimeoutLabel(timeoutMs: Int?) {
        binding.screenTimeout.text = if (timeoutMs != null) {
            getString(R.string.screen_timeout_value, timeoutMs)
        } else {
            getString(R.string.screen_timeout_unknown)
        }
    }

    private fun updateAudioLabel(status: AudioStatus) {
        val music = status.musicVolume?.toString() ?: "—"
        val ring = status.ringVolume?.toString() ?: "—"
        if (status.ringerMode == "unknown" && status.musicVolume == null && status.ringVolume == null) {
            binding.audioStatus.text = getString(R.string.audio_status_unknown)
        } else {
            binding.audioStatus.text = getString(
                R.string.audio_status_value,
                status.ringerMode,
                music,
                ring
            )
        }
    }

    private fun updateCameraLabel(cameras: List<String>) {
        binding.cameraPackages.text = if (cameras.isEmpty()) {
            getString(R.string.camera_packages_none)
        } else {
            getString(
                R.string.camera_packages_value,
                cameras.size,
                cameras.joinToString("\n")
            )
        }
    }

    private fun updateDarkModeLabel(status: DarkModeStatus) {
        val release = Build.VERSION.RELEASE ?: "?"
        binding.darkModeSdk.text = getString(R.string.dark_mode_sdk, status.sdkInt, "Android $release")
        binding.darkModeApis.text = getString(
            R.string.dark_mode_apis,
            yesNo(status.uiModeManagerAvailable),
            yesNo(status.setNightModeActivatedAvailable),
            yesNo(status.systemDarkThemeLikely)
        )
        binding.darkModeResult.text = getString(
            R.string.dark_mode_result,
            status.result,
            status.detail
        )
        val nightThemeLabel = status.displayNightTheme?.toString() ?: "—"
        binding.darkModeSamsung.text = getString(R.string.dark_mode_samsung, nightThemeLabel)
        val showNote = status.sdkInt < 29
        binding.darkModeNote.isVisible = showNote
        if (showNote) {
            binding.darkModeNote.text = getString(R.string.dark_mode_note_pre_q)
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "あり" else "なし"

    private fun reapply() {
        setBusy(true)
        executor.execute {
            val result = PolicyApplier(this).apply()
            runOnUiThread {
                setBusy(false)
                getSharedPreferences(PREFS_UPDATE, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LAST_APPLIED_VERSION, BuildConfig.VERSION_CODE)
                    .apply()
                refresh()
                if (result.screenTimeoutMs != null) {
                    updateTimeoutLabel(result.screenTimeoutMs)
                }
                result.audio?.let { updateAudioLabel(it) }
                updateCameraLabel(result.cameraPackages)
                result.darkMode?.let { updateDarkModeLabel(it) }
                result.googleApp?.let { updateGoogleLabel(it) }
                result.homeLayout?.let {
                    binding.homeLayoutStatus.text = HomeLayoutHelper.lastStatusText(this)
                }
                result.chromeDefaultBrowser?.let { browser ->
                    binding.chromeBrowserStatus.text = getString(
                        R.string.chrome_browser_status_value,
                        browser
                    )
                }
                val message = if (result.success) {
                    getString(
                        R.string.toast_reapplied,
                        result.hiddenCount,
                        result.newlyHidden,
                        result.uninstallRequested
                    )
                } else {
                    getString(R.string.toast_not_owner)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmUnhide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unhide_confirm_title)
            .setMessage(R.string.unhide_confirm_message)
            .setPositiveButton(R.string.unhide_confirm_ok) { _, _ -> unhide() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun unhide() {
        setBusy(true)
        executor.execute {
            val restored = PolicyApplier(this).unhideAll()
            runOnUiThread {
                setBusy(false)
                refresh()
                Toast.makeText(
                    this,
                    getString(R.string.toast_unhidden, restored),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Check GitHub (with the mirror fallback) and immediately install when an
     * update is available. A single background task keeps the UI responsive
     * through the check, download, and PackageInstaller hand-off.
     */
    private fun checkUpdate() {
        if (!updateBusy.compareAndSet(false, true)) return
        binding.btnUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.update_status_checking)
        executor.execute {
            when (val result = AppUpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is CheckResult.UpToDate -> runOnUiThread {
                    updateBusy.set(false)
                    binding.btnUpdate.isEnabled = true
                    binding.updateStatus.text = getString(
                        R.string.update_status_uptodate,
                        result.latest
                    )
                }

                is CheckResult.Error -> runOnUiThread {
                    showUpdateError(result.message)
                }

                is CheckResult.UpdateAvailable -> {
                    runOnUiThread {
                        binding.updateStatus.text = getString(R.string.update_status_downloading)
                    }
                    val dest = File(cacheDir, "igni-dpc-update.apk")
                    val downloaded = AppUpdateChecker.downloadApk(
                        result.release.apkDownloadUrl,
                        dest
                    )
                    if (downloaded.isFailure) {
                        runOnUiThread {
                            showUpdateError(
                                downloaded.exceptionOrNull()?.message ?: "ダウンロードに失敗しました"
                            )
                        }
                        return@execute
                    }

                    runOnUiThread {
                        binding.updateStatus.text = getString(R.string.update_status_installing)
                    }
                    val installed = AppSelfUpdater.installApk(this, dest)
                    runOnUiThread {
                        updateBusy.set(false)
                        binding.btnUpdate.isEnabled = true
                        if (installed.isSuccess) {
                            binding.updateStatus.text = getString(R.string.update_status_installing)
                            Toast.makeText(
                                this,
                                R.string.toast_update_installing,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            showUpdateError(
                                installed.exceptionOrNull()?.message ?: "インストールに失敗しました"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateError(message: String) {
        updateBusy.set(false)
        binding.btnUpdate.isEnabled = true
        binding.updateStatus.text = getString(
            R.string.update_status_error,
            message.ifBlank { "更新に失敗しました" }
        )
    }

    private fun installLine() {
        binding.lineInstallStatus.text = "LINE インストール: 開始…"
        Toast.makeText(this, R.string.toast_line_install_started, Toast.LENGTH_SHORT).show()
        executor.execute {
            LineInstaller.ensureLineInstalled(this)
            runOnUiThread {
                binding.lineInstallStatus.text = LineInstaller.lastStatusText(this)
            }
        }
    }

    private fun installChrome() {
        // Default: silent Uptodown only (no Play).
        binding.chromeInstallStatus.text = "Chrome インストール: サイレント開始…"
        Toast.makeText(this, R.string.toast_chrome_install_started, Toast.LENGTH_SHORT).show()
        executor.execute {
            ChromeInstaller.ensureChromeInstalled(this)
            runOnUiThread {
                binding.chromeInstallStatus.text = ChromeInstaller.lastStatusText(this)
            }
        }
    }

    /** Explicit user action: open Play Store for LINE. */
    private fun installLineViaPlay() {
        binding.lineInstallStatus.text = "LINE インストール: Play を開く…"
        Toast.makeText(this, R.string.toast_line_play_opened, Toast.LENGTH_SHORT).show()
        LineInstaller.openPlayStore(this)
        binding.lineInstallStatus.text = LineInstaller.lastStatusText(this)
    }

    /** Explicit user action: open Play Store for Chrome. */
    private fun installChromeViaPlay() {
        binding.chromeInstallStatus.text = "Chrome インストール: Play を開く…"
        Toast.makeText(this, R.string.toast_chrome_play_opened, Toast.LENGTH_SHORT).show()
        ChromeInstaller.openPlayStore(this)
        binding.chromeInstallStatus.text = ChromeInstaller.lastStatusText(this)
    }



    private fun installTikTokLite() {
        binding.tiktokLiteInstallStatus.text = "TikTokライト インストール: 開始…"
        Toast.makeText(this, R.string.toast_tiktok_lite_install_started, Toast.LENGTH_SHORT).show()
        executor.execute {
            TikTokLiteInstaller.ensureTikTokLiteInstalled(this)
            runOnUiThread {
                binding.tiktokLiteInstallStatus.text = TikTokLiteInstaller.lastStatusText(this)
            }
        }
    }

    /** Explicit user action: open Play Store for TikTok Lite. */
    private fun installTikTokLiteViaPlay() {
        binding.tiktokLiteInstallStatus.text = "TikTokライト インストール: Play を開く…"
        Toast.makeText(this, R.string.toast_tiktok_lite_play_opened, Toast.LENGTH_SHORT).show()
        TikTokLiteInstaller.openPlayStore(this)
        binding.tiktokLiteInstallStatus.text = TikTokLiteInstaller.lastStatusText(this)
    }

    private fun installAlive() {
        // Button-triggered only: GitHub APK silent install (no Play).
        if (AliveInstaller.isAliveInstalled(this)) {
            binding.aliveInstallStatus.text = AliveInstaller.lastStatusText(this)
            Toast.makeText(this, R.string.toast_alive_opened, Toast.LENGTH_SHORT).show()
            AliveInstaller.openAlive(this)
            return
        }
        binding.aliveInstallStatus.text = "アライブ インストール: 開始…"
        Toast.makeText(this, R.string.toast_alive_install_started, Toast.LENGTH_SHORT).show()
        executor.execute {
            AliveInstaller.ensureAliveInstalled(this)
            runOnUiThread {
                binding.aliveInstallStatus.text = AliveInstaller.lastStatusText(this)
            }
        }
    }

    /** Optional: open Alive when already installed. */
    private fun openAlive() {
        if (AliveInstaller.openAlive(this)) {
            Toast.makeText(this, R.string.toast_alive_opened, Toast.LENGTH_SHORT).show()
            binding.aliveInstallStatus.text = AliveInstaller.lastStatusText(this)
        } else {
            Toast.makeText(this, R.string.toast_alive_not_installed, Toast.LENGTH_SHORT).show()
            binding.aliveInstallStatus.text = AliveInstaller.lastStatusText(this)
        }
    }

    private fun confirmReturnPersonal() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.return_personal_confirm_title)
            .setMessage(R.string.return_personal_confirm_message)
            .setPositiveButton(R.string.return_personal_confirm_ok) { _, _ -> returnToPersonal() }
            .setNegativeButton(R.string.return_personal_confirm_cancel, null)
            .show()
    }

    private fun returnToPersonal() {
        setBusy(true)
        binding.returnPersonalStatus.isVisible = true
        binding.returnPersonalStatus.text = "個人用に戻す: 処理中…"
        executor.execute {
            val result = PolicyApplier(this).returnToPersonalUse()
            runOnUiThread {
                setBusy(false)
                refresh()
                when {
                    result.message == "not_device_owner" -> {
                        binding.returnPersonalStatus.text =
                            getString(R.string.return_personal_status_not_owner)
                        Toast.makeText(
                            this,
                            R.string.toast_return_personal_not_owner,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    result.success -> {
                        binding.returnPersonalStatus.text =
                            getString(R.string.return_personal_status_success)
                        Toast.makeText(
                            this,
                            getString(R.string.toast_return_personal_success, result.restoredHidden),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        binding.returnPersonalStatus.text = getString(
                            R.string.return_personal_status_failed,
                            result.message
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.toast_return_personal_failed, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        val owner = PolicyApplier(this).isDeviceOwner()
        binding.btnReapply.isEnabled = owner && !busy
        binding.btnUnhide.isEnabled = owner && !busy
        binding.btnReturnPersonal.isEnabled = owner && !busy
        binding.btnUpdate.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallLine.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallLinePlay.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallChrome.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallChromePlay.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallTikTokLite.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallTikTokLitePlay.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallAlive.isEnabled = !busy && !updateBusy.get()
        binding.btnOpenAlive.isEnabled = !busy && !updateBusy.get()
    }

    companion object {
        private const val PREFS_UPDATE = "igni_update"
        private const val KEY_LAST_APPLIED_VERSION = "lastAppliedVersionCode"
    }
}
