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
    val releaseName: String?,
    /** True when GitHub API failed and we fell back to the short mirror. */
    val fromMirror: Boolean = false
)

sealed class CheckResult {
    data class UpToDate(val current: String, val latest: String) : CheckResult()
    data class UpdateAvailable(val release: LatestRelease) : CheckResult()
    data class Error(val message: String) : CheckResult()
}

/**
 * Queries GitHub Releases for mikasahahappy0526-create/igni-dpc and downloads igni-dpc.apk.
 *
 * Prefer the stable short mirror (`…/i/releases/download/1/d.apk`) for the actual APK bytes —
 * GitHub `browser_download_url` / API often returns 403/500/rate-limit on devices.
 */
object AppUpdateChecker {

    private const val TAG = "IgniUpdate"
    private const val LATEST_URL =
        "https://api.github.com/repos/mikasahahappy0526-create/igni-dpc/releases/latest"
    private const val APK_ASSET_NAME = "igni-dpc.apk"
    /** Always-latest mirror published alongside each igni-dpc release. */
    const val MIRROR_APK_URL =
        "https://github.com/mikasahahappy0526-create/i/releases/download/1/d.apk"
    private const val USER_AGENT = "Igni-DPC-Updater/1.0.20 (Android; DeviceOwner)"

    fun checkForUpdate(currentVersionName: String): CheckResult {
        return try {
            val release = fetchLatestRelease()
            if (release == null) {
                // API ok but no asset — still offer mirror install path.
                Log.w(TAG, "Latest release missing $APK_ASSET_NAME; offering mirror")
                return CheckResult.UpdateAvailable(
                    LatestRelease(
                        tagName = "mirror",
                        versionName = "mirror",
                        apkDownloadUrl = MIRROR_APK_URL,
                        releaseName = "ミラーから取得",
                        fromMirror = true
                    )
                )
            }
            val current = SemVer.parse(currentVersionName)
                ?: return CheckResult.Error("現在のバージョンを解析できません: $currentVersionName")
            val latest = SemVer.parse(release.versionName)
            if (latest == null) {
                // Unparseable tag — still allow mirror download.
                Log.w(TAG, "Unparseable tag ${release.tagName}; offering mirror")
                return CheckResult.UpdateAvailable(release.copy(fromMirror = true, apkDownloadUrl = MIRROR_APK_URL))
            }
            if (latest > current) {
                // Prefer mirror URL for the actual download (stable CDN path we control).
                CheckResult.UpdateAvailable(
                    release.copy(apkDownloadUrl = MIRROR_APK_URL, fromMirror = true)
                )
            } else {
                CheckResult.UpToDate(current.toString(), latest.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdate failed — falling back to mirror", e)
            val detail = humanizeHttpError(e)
            // API 500/403/rate-limit: assume update may be available; install from mirror.
            CheckResult.UpdateAvailable(
                LatestRelease(
                    tagName = "mirror",
                    versionName = "mirror",
                    apkDownloadUrl = MIRROR_APK_URL,
                    releaseName = "ミラーから取得 ($detail)",
                    fromMirror = true
                )
            )
        }
    }

    /**
     * Download APK. Tries [url] first; on failure tries [MIRROR_APK_URL].
     * Follows 302/301 manually so release-assets.githubusercontent.com redirects succeed.
     */
    fun downloadApk(url: String, dest: File): Result<File> {
        val candidates = linkedSetOf(url, MIRROR_APK_URL).filter { it.isNotBlank() }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = downloadOnce(candidate, dest)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Download failed for $candidate: ${lastError?.message}")
        }
        return Result.failure(
            lastError ?: IllegalStateException("ダウンロード失敗（ミラー含む）")
        )
    }

    private fun downloadOnce(url: String, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val connection = openGetFollowingRedirects(url, accept = "*/*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = runCatching {
                    (connection.errorStream ?: connection.inputStream)
                        ?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                error(humanizeHttpCode(code, errBody, url))
            }
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            if (dest.length() < 1024L) {
                dest.delete()
                error("ダウンロードした APK が小さすぎます (${dest.length()} bytes)")
            }
            Log.i(TAG, "Downloaded APK ${dest.length()} bytes from $url -> ${dest.absolutePath}")
            dest
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchLatestRelease(): LatestRelease? {
        val connection = openGetFollowingRedirects(
            LATEST_URL,
            accept = "application/vnd.github+json"
        )
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                error(humanizeHttpCode(code, err, "GitHub API"))
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
                releaseName = json.optString("name").takeIf { it.isNotBlank() },
                fromMirror = false
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GET with User-Agent / Accept, following up to 8 redirects manually.
     * HttpURLConnection often mishandles cross-host 302 to release-assets.githubusercontent.com
     * when combined with custom headers; we re-open each Location ourselves.
     */
    private fun openGetFollowingRedirects(url: String, accept: String): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false // we follow manually
                connectTimeout = 20_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                // GitHub API recommends this; harmless on asset CDN.
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    error("リダイレクト先が空です (HTTP $code) url=$current")
                }
                current = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
                redirects++
                if (redirects > 8) error("リダイレクトが多すぎます ($redirects)")
                Log.i(TAG, "Follow redirect $code -> $current")
                continue
            }
            return connection
        }
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private fun humanizeHttpError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            "HTTP 500" in msg -> "GitHub一時障害(HTTP 500)"
            "HTTP 403" in msg -> "GitHub拒否/レート制限(HTTP 403)"
            "HTTP 429" in msg -> "GitHubレート制限(HTTP 429)"
            "HTTP 404" in msg -> "見つかりません(HTTP 404)"
            msg.isNotBlank() -> msg.take(80)
            else -> e.javaClass.simpleName
        }
    }

    private fun humanizeHttpCode(code: Int, body: String, where: String): String {
        val snippet = body.replace('\n', ' ').trim().take(120)
        val reason = when (code) {
            403 -> "アクセス拒否またはレート制限"
            429 -> "レート制限"
            500, 502, 503, 504 -> "サーバー一時障害"
            404 -> "見つかりません"
            else -> "エラー"
        }
        return "$where 失敗: HTTP $code ($reason)" +
            if (snippet.isNotBlank()) " — $snippet" else ""
    }
}
