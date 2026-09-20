package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.install.InstallSupport
import app.igni.dpc.line.LineInstaller
import app.igni.dpc.policy.PolicyApplier

/**
 * Receives [PackageInstaller] status for LINE installs.
 * Personal mode: launch confirmation UI on PENDING_USER_ACTION.
 * Device Owner silent path: ignore confirm UI (should not need it).
 * On real STATUS_SUCCESS while Device Owner: schedule auto「個人用に戻す」
 * (after Alive can finish) via [PolicyApplier.scheduleAutoReturnAfterLineSuccess].
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
                // Real PackageInstaller success only (not already-installed no-op).
                PolicyApplier.scheduleAutoReturnAfterLineSuccess(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val isDo = InstallSupport.isDeviceOwner(context)
                if (!isDo && InstallSupport.startPendingUserAction(context, intent)) {
                    Log.i(TAG, "LINE install: launched user confirmation (personal)")
                    LineInstaller.persistInstallingUserConfirm(context)
                } else {
                    Log.w(TAG, "LINE install needs user action (ignored do=$isDo): $message")
                    LineInstaller.persistFailure(context, "user_action_ignored: $message")
                }
            }
            else -> {
                Log.w(TAG, "LINE install failure status=$status msg=$message")
                LineInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniLineInstall"
        const val ACTION = "app.igni.dpc.action.LINE_INSTALL_STATUS"
    }
}
