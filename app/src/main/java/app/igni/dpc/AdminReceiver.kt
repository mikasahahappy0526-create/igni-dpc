package app.igni.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import app.igni.dpc.policy.PolicyApplier

/**
 * Device Owner admin component. QR extra:
 * `android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` =
 * `app.igni.dpc/.AdminReceiver`
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled; applying hide policy")
        PolicyApplier(context).apply()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "Profile/device provisioning complete; applying hide policy")
        PolicyApplier(context).apply()
    }

    companion object {
        private const val TAG = "IgniAdmin"

        fun componentName(context: Context): ComponentName {
            return ComponentName(context.packageName, AdminReceiver::class.java.name)
        }
    }
}
