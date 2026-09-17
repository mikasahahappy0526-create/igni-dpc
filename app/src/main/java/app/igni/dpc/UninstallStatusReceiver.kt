package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Logs PackageInstaller uninstall status for fire-and-forget Device Owner uninstalls.
 * Does not block [app.igni.dpc.policy.PolicyApplier.apply].
 */
class UninstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "unknown"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Uninstall success: $pkg")
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Log.w(TAG, "Uninstall needs user action (unexpected as DO): $pkg msg=$message")
            else ->
                Log.w(TAG, "Uninstall failure: $pkg status=$status msg=$message")
        }
    }

    companion object {
        private const val TAG = "IgniUninstall"
        const val ACTION = "app.igni.dpc.action.UNINSTALL_STATUS"
        const val EXTRA_PACKAGE = "package"
    }
}
