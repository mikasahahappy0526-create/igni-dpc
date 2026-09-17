package app.igni.dpc

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import app.igni.dpc.databinding.ActivityAdminBinding
import app.igni.dpc.policy.AudioStatus
import app.igni.dpc.policy.DarkModeStatus
import app.igni.dpc.chrome.ChromeInstaller
import app.igni.dpc.line.LineInstaller
import app.igni.dpc.policy.PolicyApplier
import app.igni.dpc.update.AppSelfUpdater
import app.igni.dpc.update.AppUpdateChecker
import app.igni.dpc.update.CheckResult
import app.igni.dpc.update.LatestRelease
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val updateBusy = AtomicBoolean(false)
    private var pendingRelease: LatestRelease? = null

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
        binding.btnInstallUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.update_status_idle)

        binding.btnReapply.setOnClickListener { reapply() }
        binding.btnUnhide.setOnClickListener { confirmUnhide() }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnInstallUpdate.setOnClickListener { installUpdate() }
        binding.btnInstallLine.setOnClickListener { installLine() }
        binding.btnInstallChrome.setOnClickListener { installChrome() }

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
        binding.btnCheckUpdate.isEnabled = !updateBusy.get()
        binding.btnInstallUpdate.isEnabled = !updateBusy.get() && pendingRelease != null
        binding.lineInstallStatus.text = LineInstaller.lastStatusText(this)
        binding.btnInstallLine.isEnabled = !busy && !updateBusy.get()
        binding.chromeInstallStatus.text = ChromeInstaller.lastStatusText(this)
        binding.btnInstallChrome.isEnabled = !busy && !updateBusy.get()
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

    private fun checkUpdate() {
        if (!updateBusy.compareAndSet(false, true)) return
        pendingRelease = null
        binding.btnInstallUpdate.isEnabled = false
        binding.btnCheckUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.update_status_checking)
        executor.execute {
            val result = AppUpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            runOnUiThread {
                updateBusy.set(false)
                binding.btnCheckUpdate.isEnabled = true
                when (result) {
                    is CheckResult.UpToDate -> {
                        pendingRelease = null
                        binding.btnInstallUpdate.isEnabled = false
                        binding.updateStatus.text = getString(
                            R.string.update_status_uptodate,
                            result.latest
                        )
                    }
                    is CheckResult.UpdateAvailable -> {
                        pendingRelease = result.release
                        binding.btnInstallUpdate.isEnabled = true
                        if (result.release.fromMirror) {
                            val label = result.release.releaseName
                                ?: result.release.tagName
                            binding.updateStatus.text = getString(
                                R.string.update_status_mirror,
                                label
                            )
                        } else {
                            binding.updateStatus.text = getString(
                                R.string.update_status_available,
                                result.release.tagName
                            )
                        }
                        Toast.makeText(
                            this,
                            getString(R.string.toast_update_available, result.release.tagName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is CheckResult.Error -> {
                        pendingRelease = null
                        binding.btnInstallUpdate.isEnabled = false
                        binding.updateStatus.text = getString(
                            R.string.update_status_error,
                            result.message
                        )
                    }
                }
            }
        }
    }

    private fun installUpdate() {
        val release = pendingRelease ?: return
        if (!updateBusy.compareAndSet(false, true)) return
        binding.btnCheckUpdate.isEnabled = false
        binding.btnInstallUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.update_status_downloading)
        executor.execute {
            val dest = File(cacheDir, "igni-dpc-update.apk")
            val downloaded = AppUpdateChecker.downloadApk(release.apkDownloadUrl, dest)
            if (downloaded.isFailure) {
                runOnUiThread {
                    updateBusy.set(false)
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnInstallUpdate.isEnabled = pendingRelease != null
                    binding.updateStatus.text = getString(
                        R.string.update_status_error,
                        downloaded.exceptionOrNull()?.message ?: "download"
                    )
                }
                return@execute
            }
            runOnUiThread {
                binding.updateStatus.text = getString(R.string.update_status_installing)
            }
            val installed = AppSelfUpdater.installApk(this, dest)
            runOnUiThread {
                if (installed.isSuccess) {
                    binding.updateStatus.text = getString(R.string.update_status_installing)
                    Toast.makeText(this, R.string.toast_update_installing, Toast.LENGTH_LONG).show()
                    // Keep busy until process is replaced; allow re-check if install fails silently.
                    binding.btnCheckUpdate.isEnabled = true
                    updateBusy.set(false)
                } else {
                    updateBusy.set(false)
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnInstallUpdate.isEnabled = pendingRelease != null
                    binding.updateStatus.text = getString(
                        R.string.update_status_error,
                        installed.exceptionOrNull()?.message ?: "install"
                    )
                }
            }
        }
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
        binding.chromeInstallStatus.text = "Chrome インストール: 開始…"
        Toast.makeText(this, R.string.toast_chrome_install_started, Toast.LENGTH_SHORT).show()
        executor.execute {
            ChromeInstaller.ensureChromeInstalled(this)
            runOnUiThread {
                binding.chromeInstallStatus.text = ChromeInstaller.lastStatusText(this)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        val owner = PolicyApplier(this).isDeviceOwner()
        binding.btnReapply.isEnabled = owner && !busy
        binding.btnUnhide.isEnabled = owner && !busy
        binding.btnCheckUpdate.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallUpdate.isEnabled =
            !busy && !updateBusy.get() && pendingRelease != null
        binding.btnInstallLine.isEnabled = !busy && !updateBusy.get()
        binding.btnInstallChrome.isEnabled = !busy && !updateBusy.get()
    }

    companion object {
        private const val PREFS_UPDATE = "igni_update"
        private const val KEY_LAST_APPLIED_VERSION = "lastAppliedVersionCode"
    }
}
