package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.igni.dpc.policy.PolicyApplier

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val applier = PolicyApplier(context)
        if (!applier.isDeviceOwner()) return
        Log.i(TAG, "Re-applying policy (incl. dark mode) after $action")
        applier.apply()
    }

    companion object {
        private const val TAG = "IgniBoot"
    }
}
