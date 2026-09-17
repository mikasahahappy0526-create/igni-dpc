package app.igni.dpc.update

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class LatestRelease(
    val tagName: String,
    val versionName: String,
    val apkDownloadUrl: String,
    val releaseName: String?
)

sealed class CheckResult {
    data class UpToDate(val current: String, val latest: String) : CheckResult()
    data class UpdateAvailable(val release: LatestRelease) : CheckResult()
    data class Error(val message: String) : CheckResult()
}

/**
 * Queries GitHub Releases for mikasahahappy0526-create/igni-dpc and downloads igni-dpc.apk.
 */
object AppUpdateChecker {

    private const val TAG = "IgniUpdate"
    private const val LATEST_URL =
        "https://api.github.com/repos/mikasahahappy0526-create/igni-dpc/releases/latest"
    private const val APK_ASSET_NAME = "igni-dpc.apk"
    private const val USER_AGENT = "Igni-DPC-Updater"

    fun checkForUpdate(currentVersionName: String): CheckResult {
        return try {
            val release = fetchLatestRelease()
                ?: return CheckResult.Error("最新リリースに $APK_ASSET_NAME がありません")
            val current = SemVer.parse(currentVersionName)
                ?: return CheckResult.Error("現在のバージョンを解析できません: $currentVersionName")
            val latest = SemVer.parse(release.versionName)
                ?: return CheckResult.Error("タグを解析できません: ${release.tagName}")
            if (latest > current) {
                CheckResult.UpdateAvailable(release)
            } else {
                CheckResult.UpToDate(current.toString(), latest.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdate failed", e)
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun downloadApk(url: String, dest: File): Result<File> {
        return runCatching {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            val connection = openGet(url, accept = "*/*")
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("ダウンロード失敗 HTTP $code")
                }
                connection.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                if (dest.length() < 1024L) {
                    dest.delete()
                    error("ダウンロードした APK が小さすぎます")
                }
                Log.i(TAG, "Downloaded APK ${dest.length()} bytes -> ${dest.absolutePath}")
                dest
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun fetchLatestRelease(): LatestRelease? {
        val connection = openGet(LATEST_URL, accept = "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("GitHub API HTTP $code: ${err.take(200)}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name").orEmpty()
            if (tagName.isBlank()) error("tag_name が空です")
            val versionName = tagName.removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == APK_ASSET_NAME) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return null
            return LatestRelease(
                tagName = tagName,
                versionName = versionName,
                apkDownloadUrl = apkUrl,
                releaseName = json.optString("name").takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openGet(url: String, accept: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
        }
        return connection
    }
}
