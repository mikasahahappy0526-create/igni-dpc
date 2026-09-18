package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.alive.AliveInstaller

/**
 * Receives [PackageInstaller] status for silent アライブ installs.
 * Never opens Play Store or starts confirm activities.
 */
class AliveInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Alive install success")
                AliveInstaller.persistSuccess(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Alive install needs user action (ignored, no Play): $message")
                AliveInstaller.persistFailure(context, "user_action_ignored: $message")
            }
            else -> {
                Log.w(TAG, "Alive install failure status=$status msg=$message (no Play)")
                AliveInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniAliveInstall"
        const val ACTION = "app.igni.dpc.action.ALIVE_INSTALL_STATUS"
    }
}
