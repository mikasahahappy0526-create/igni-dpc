package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.chrome.ChromeInstaller

/**
 * Receives [PackageInstaller] status for silent Chrome installs.
 * Never opens Play Store or starts confirm activities (those interrupt setup / home).
 */
class ChromeInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Chrome install success")
                ChromeInstaller.persistSuccess(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Do not start confirm UI or Play — would hijack setup wizard / home.
                Log.w(TAG, "Chrome install needs user action (ignored, no Play): $message")
                ChromeInstaller.persistFailure(context, "user_action_ignored: $message")
            }
            else -> {
                Log.w(TAG, "Chrome install failure status=$status msg=$message (no Play)")
                ChromeInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniChromeInstall"
        const val ACTION = "app.igni.dpc.action.CHROME_INSTALL_STATUS"
    }
}
