package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.tiktok.TikTokLiteInstaller

/**
 * Receives [PackageInstaller] status for silent TikTok Lite installs.
 * Never opens Play Store or starts confirm activities.
 */
class TikTokLiteInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "TikTok Lite install success")
                TikTokLiteInstaller.persistSuccess(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "TikTok Lite install needs user action (ignored, no Play): $message")
                TikTokLiteInstaller.persistFailure(context, "user_action_ignored: $message")
            }
            else -> {
                Log.w(TAG, "TikTok Lite install failure status=$status msg=$message (no Play)")
                TikTokLiteInstaller.persistFailure(context, "status=$status msg=$message")
            }
        }
    }

    companion object {
        private const val TAG = "IgniTikTokLiteInstall"
        const val ACTION = "app.igni.dpc.action.TIKTOK_LITE_INSTALL_STATUS"
    }
}
