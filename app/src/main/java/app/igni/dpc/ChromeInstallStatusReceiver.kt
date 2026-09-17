package app.igni.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.igni.dpc.chrome.ChromeInstaller

/**
 * Receives [PackageInstaller] status for silent Chrome installs.
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
                Log.w(TAG, "Chrome install needs user action: $message — opening Play Store")
                ChromeInstaller.persistFailure(context, "user_action: $message")
                val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure {
                            Log.w(TAG, "Could not start confirm activity; Play fallback", it)
                            ChromeInstaller.openPlayStore(context)
                        }
                } else {
                    ChromeInstaller.openPlayStore(context)
                }
            }
            else -> {
                Log.w(TAG, "Chrome install failure status=$status msg=$message")
                ChromeInstaller.persistFailure(context, "status=$status msg=$message")
                ChromeInstaller.openPlayStore(context)
            }
        }
    }

    companion object {
        private const val TAG = "IgniChromeInstall"
        const val ACTION = "app.igni.dpc.action.CHROME_INSTALL_STATUS"
    }
}
