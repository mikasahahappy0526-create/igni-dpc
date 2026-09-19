package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.alive.AliveInstaller
import app.igni.dpc.install.InstallSupport

/**
 * Receives [PackageInstaller] status for アライブ installs.
 * Personal mode: launch confirmation UI on PENDING_USER_ACTION.
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
                val isDo = InstallSupport.isDeviceOwner(context)
                if (!isDo && InstallSupport.startPendingUserAction(context, intent)) {
                    Log.i(TAG, "Alive install: launched user confirmation (personal)")
                    AliveInstaller.persistInstallingUserConfirm(context)
                } else {
                    Log.w(TAG, "Alive install needs user action (ignored do=$isDo): $message")
                    AliveInstaller.persistFailure(context, "user_action_ignored: $message")
                }
            }
            else -> {
                Log.w(TAG, "Alive install failure status=$status msg=$message")
                AliveInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniAliveInstall"
        const val ACTION = "app.igni.dpc.action.ALIVE_INSTALL_STATUS"
    }
}
