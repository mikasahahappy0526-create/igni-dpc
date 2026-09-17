package app.igni.dpc

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Android 10+ (`GET_PROVISIONING_MODE`). Chooses fully managed device only.
 * Does not select managed profile.
 */
class GetProvisioningModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val allowed = intent.getIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
        )
        val fullyManaged = DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE

        if (allowed != null && allowed.isNotEmpty() && !allowed.contains(fullyManaged)) {
            Log.w(TAG, "Fully managed mode is not allowed: $allowed")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val result = Intent()
        result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, fullyManaged)
        copyAdminExtras(result)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun copyAdminExtras(result: Intent) {
        val extras = extraBundle() ?: return
        result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, extras)
    }

    private fun extraBundle(): PersistableBundle? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
    }

    companion object {
        private const val TAG = "IgniProvisionMode"
    }
}
