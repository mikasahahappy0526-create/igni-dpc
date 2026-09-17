package app.igni.dpc

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import app.igni.dpc.databinding.ActivityHomeBinding
import app.igni.dpc.policy.KeepPackages

/**
 * Dedicated single-screen home: Settings, Play Store, Camera, Chrome.
 * No pager / empty pages. Admin is reachable via the discreet 「管理」 link.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileSettings.setOnClickListener { launchSettings() }
        binding.tilePlayStore.setOnClickListener { launchPlayStore() }
        binding.tileCamera.setOnClickListener { launchCamera() }
        binding.tileChrome.setOnClickListener { launchChrome() }
        binding.linkAdmin.setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        // Dedicated home: ignore back so we do not leave an empty stack.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // no-op
                }
            }
        )
    }

    private fun launchSettings() {
        val candidates = KeepPackages.SETTINGS_PACKAGES
        if (launchMainLauncher(candidates)) return
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(R.string.home_launch_failed)
        }
    }

    private fun launchPlayStore() {
        if (launchMainLauncher(listOf(KeepPackages.PLAY_STORE_PACKAGE))) return
        // Fallback: open Play Store details for itself, then market://, then package launch.
        val fallbacks = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.android.vending")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store")),
            packageManager.getLaunchIntentForPackage(KeepPackages.PLAY_STORE_PACKAGE),
        )
        for (intent in fallbacks) {
            if (intent == null) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(intent)) return
        }
        toast(R.string.home_launch_failed)
    }

    private fun launchCamera() {
        val preferred = resolveCameraPackage()
        if (preferred != null && launchMainLauncher(listOf(preferred))) return
        if (launchMainLauncher(KeepPackages.CAMERA_PACKAGES)) return
        // Last resort: try any MAIN/LAUNCHER activity whose package looks like a camera.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
        val cameraInfo = infos.firstOrNull { info ->
            val pkg = info.activityInfo?.packageName.orEmpty()
            val label = info.loadLabel(packageManager).toString()
            pkg in KeepPackages.CAMERA_PACKAGES ||
                pkg.contains("camera", ignoreCase = true) ||
                label.contains("カメラ") ||
                label.contains("Camera", ignoreCase = true)
        }
        if (cameraInfo != null) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(
                    ComponentName(
                        cameraInfo.activityInfo.packageName,
                        cameraInfo.activityInfo.name
                    )
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(intent)) return
        }
        toast(R.string.home_launch_failed)
    }

    private fun launchChrome() {
        val pkgs = buildList {
            add(KeepPackages.CHROME_PACKAGE)
            if (!isInstalled(KeepPackages.CHROME_PACKAGE)) {
                add(KeepPackages.CHROME_BETA_PACKAGE)
            }
        }
        if (launchMainLauncher(pkgs)) return
        // Fallback: VIEW google.com targeted at Chrome package.
        for (pkg in pkgs) {
            if (!isInstalled(pkg)) continue
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(view)) return
        }
        toast(R.string.home_launch_failed)
    }

    /**
     * Prefer a known camera package that is installed; pick system app when multiple match.
     */
    private fun resolveCameraPackage(): String? {
        val installed = KeepPackages.CAMERA_PACKAGES.filter { isInstalled(it) }
        if (installed.isEmpty()) return null
        val systemFirst = installed.sortedByDescending { pkg ->
            runCatching {
                val flags = packageManager.getApplicationInfo(pkg, 0).flags
                (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }
        return systemFirst.firstOrNull()
    }

    private fun launchMainLauncher(packages: Collection<String>): Boolean {
        for (pkg in packages) {
            if (!isInstalled(pkg)) continue
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (startSafely(launch)) return true
            }
            // Explicit MAIN/LAUNCHER resolve
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val infos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val info = infos.firstOrNull()?.activityInfo ?: continue
            val explicit = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(info.packageName, info.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(explicit)) return true
        }
        return false
    }

    private fun isInstalled(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun startSafely(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
