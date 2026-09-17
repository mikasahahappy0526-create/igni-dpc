package app.igni.dpc

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import app.igni.dpc.databinding.ActivityHomeBinding
import app.igni.dpc.policy.KeepPackages

/**
 * Dock-style home: wallpaper background + bottom dock
 * (設定 | Playストア | Chrome | カメラ). Admin via discreet 「管理」 / long-press.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Status bar stays visible (not immersive).
        WindowCompat.setDecorFitsSystemWindows(window, true)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dockSettings.setOnClickListener { launchSettings() }
        binding.dockPlayStore.setOnClickListener { launchPlayStore() }
        binding.dockChrome.setOnClickListener { launchChrome() }
        binding.dockCamera.setOnClickListener { launchCamera() }

        val openAdmin = View.OnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }
        binding.linkAdmin.setOnClickListener(openAdmin)
        binding.wallpaperArea.setOnLongClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
            true
        }

        bindDockIcons()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Dedicated home: ignore back.
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        bindDockIcons()
    }

    private fun bindDockIcons() {
        setPackageIcon(binding.iconSettings, resolveSettingsPackage())
        setPackageIcon(binding.iconPlayStore, KeepPackages.PLAY_STORE_PACKAGE)
        val chromePkg = when {
            isInstalled(KeepPackages.CHROME_PACKAGE) -> KeepPackages.CHROME_PACKAGE
            isInstalled(KeepPackages.CHROME_BETA_PACKAGE) -> KeepPackages.CHROME_BETA_PACKAGE
            else -> KeepPackages.CHROME_PACKAGE
        }
        setPackageIcon(binding.iconChrome, chromePkg)
        setPackageIcon(binding.iconCamera, resolveCameraPackage())
    }

    private fun setPackageIcon(view: ImageView, packageName: String?) {
        val icon: Drawable? = packageName?.let { loadAppIcon(it) }
        if (icon != null) {
            view.setImageDrawable(icon)
        } else {
            view.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    private fun loadAppIcon(packageName: String): Drawable? {
        return runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    private fun resolveSettingsPackage(): String? {
        return KeepPackages.SETTINGS_PACKAGES.firstOrNull { isInstalled(it) }
            ?: "com.android.settings"
    }

    private fun launchSettings() {
        val candidates = KeepPackages.SETTINGS_PACKAGES
        if (launchMainLauncher(candidates)) return
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast(R.string.home_launch_failed)
        }
    }

    private fun launchPlayStore() {
        if (launchMainLauncher(listOf(KeepPackages.PLAY_STORE_PACKAGE))) return
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
        for (pkg in pkgs) {
            if (!isInstalled(pkg)) continue
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(view)) return
        }
        toast(R.string.home_launch_failed)
    }

    /** Prefer a known camera package that is installed; pick system app when multiple match. */
    private fun resolveCameraPackage(): String? {
        val detected = KeepPackages(this).detectCameraPackages()
        val candidates = (detected + KeepPackages.CAMERA_PACKAGES).distinct().filter { isInstalled(it) }
        if (candidates.isEmpty()) return null
        return candidates.sortedByDescending { pkg ->
            runCatching {
                val flags = packageManager.getApplicationInfo(pkg, 0).flags
                (flags and ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }.firstOrNull()
    }

    private fun launchMainLauncher(packages: Collection<String>): Boolean {
        for (pkg in packages) {
            if (!isInstalled(pkg)) continue
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (startSafely(launch)) return true
            }
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
