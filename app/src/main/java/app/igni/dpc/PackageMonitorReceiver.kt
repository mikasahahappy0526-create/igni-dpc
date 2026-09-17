package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.igni.dpc.policy.PolicyApplier

/** Hide newly installed launchable apps that are not on the keep list. */
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
        Log.i(TAG, "Package change ${intent.dataString}; re-applying hide policy")
        applier.apply()
    }

    companion object {
        private const val TAG = "IgniPkgMonitor"
    }
}
