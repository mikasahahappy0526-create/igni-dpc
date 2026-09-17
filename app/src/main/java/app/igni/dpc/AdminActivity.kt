package app.igni.dpc

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import app.igni.dpc.databinding.ActivityAdminBinding
import app.igni.dpc.policy.PolicyApplier
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.app_name)
        binding.allowlist.text = PolicyApplier(this).allowlistForDisplay().joinToString("\n")

        binding.btnReapply.setOnClickListener { reapply() }
        binding.btnUnhide.setOnClickListener { confirmUnhide() }

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
        updateCameraLabel(applier.detectedCameraPackages())
        binding.allowlist.text = applier.allowlistForDisplay().joinToString("\n")
        binding.lockTaskNote.isVisible = BuildConfig.ENABLE_LOCK_TASK
        binding.btnReapply.isEnabled = isOwner
        binding.btnUnhide.isEnabled = isOwner
    }

    private fun updateTimeoutLabel(timeoutMs: Int?) {
        binding.screenTimeout.text = if (timeoutMs != null) {
            getString(R.string.screen_timeout_value, timeoutMs)
        } else {
            getString(R.string.screen_timeout_unknown)
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

    private fun reapply() {
        setBusy(true)
        executor.execute {
            val result = PolicyApplier(this).apply()
            runOnUiThread {
                setBusy(false)
                refresh()
                if (result.screenTimeoutMs != null) {
                    updateTimeoutLabel(result.screenTimeoutMs)
                }
                updateCameraLabel(result.cameraPackages)
                val message = if (result.success) {
                    getString(R.string.toast_reapplied, result.hiddenCount, result.newlyHidden)
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

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        val owner = PolicyApplier(this).isDeviceOwner()
        binding.btnReapply.isEnabled = owner && !busy
        binding.btnUnhide.isEnabled = owner && !busy
    }
}
