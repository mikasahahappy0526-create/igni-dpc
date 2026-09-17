package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.line.LineInstaller

/**
 * Receives [PackageInstaller] status for silent LINE installs.
 * Never opens Play Store or starts confirm activities (those interrupt setup / home).
 */
class LineInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "LINE install success")
                LineInstaller.persistSuccess(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Do not start confirm UI or Play — would hijack setup wizard / home.
                Log.w(TAG, "LINE install needs user action (ignored, no Play): $message")
                LineInstaller.persistFailure(context, "user_action_ignored: $message")
            }
            else -> {
                Log.w(TAG, "LINE install failure status=$status msg=$message (no Play)")
                LineInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniLineInstall"
        const val ACTION = "app.igni.dpc.action.LINE_INSTALL_STATUS"
    }
}
