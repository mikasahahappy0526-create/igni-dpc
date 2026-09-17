package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives [PackageInstaller] status for self-update installs.
 * STATUS_PENDING_USER_ACTION is unexpected as Device Owner updating self.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Self-update install success")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Self-update needs user action: $message")
                val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.w(TAG, "Could not start confirm activity", it) }
                }
            }
            else ->
                Log.w(TAG, "Self-update install failure status=$status msg=$message")
        }
    }

    companion object {
        private const val TAG = "IgniInstall"
        const val ACTION = "app.igni.dpc.action.INSTALL_STATUS"
    }
}
