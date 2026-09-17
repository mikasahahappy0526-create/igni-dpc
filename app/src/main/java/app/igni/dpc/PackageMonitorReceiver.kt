package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.igni.dpc.policy.PolicyApplier

/** Re-apply policy when packages are added/changed (uninstall user / hide system). */
class PackageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
            intent.action != Intent.ACTION_PACKAGE_CHANGED
        ) {
            return
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val applier = PolicyApplier(context)
        if (!applier.isDeviceOwner()) return
        Log.i(TAG, "Package change ${intent.dataString}; re-applying policy")
        applier.apply()
    }

    companion object {
        private const val TAG = "IgniPkgMonitor"
    }
}
