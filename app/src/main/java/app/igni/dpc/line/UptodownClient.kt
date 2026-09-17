package app.igni.dpc.line

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves the latest LINE (jp.naver.line.android) download URL from Uptodown's Android eAPI.
 *
 * Flow (reverse-engineered from Uptodown Android 7.38):
 * 1. HMAC-SHA256(auth seed, unixtime) → POST /eapi/auth/token → Bearer JWT
 * 2. GET /eapi/apps/byPackagename/{pkg} → appID
 * 3. Resolve latest fileID via public versions JSON or HTML data-file-id
 * 4. GET /eapi/apps/{appID}/file/{fileID}/downloadUrl → dw.uptodown.com CDN URL
 *
 * Does not hardcode a forever CDN path; resolves latest each call.
 */
object UptodownClient {

    private const val TAG = "IgniUptodown"
    private const val EAPI = "https://www.uptodown.app/eapi"
    private const val PAGE_JP = "https://line.jp.uptodown.com/android"
    private const val PAGE_EN = "https://line.en.uptodown.com/android"
    const val LINE_PACKAGE = "jp.naver.line.android"

    /** Native getAuthApikey() seed from Uptodown Android 7.38 (libuptodown-native.so). */
    private const val AUTH_SEED = "MDGMXUMdvHJBG/vjdFgmqX6LUdy7ecfwvYNd0gyfOCs="
    private const val IDENTIFICADOR = "Uptodown_Android"
    private const val IDENTIFICADOR_VERSION = "738"
    private const val DALVIK_UA =
        "Dalvik/2.1.0 (Linux; U; Android 14; SM-G955F Build/AP2A.240805.005)"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    data class ResolvedDownload(
        val appId: String,
        val fileId: String,
        val version: String?,
        val kindFile: String?,
        val downloadUrl: String,
        val sha256: String?
    )

    /** Resolve latest LINE CDN URL (APK or XAPK). */
    fun resolveLatestLine(): Result<ResolvedDownload> = runCatching {
        val token = fetchAuthToken()
        val appId = fetchAppId(token, LINE_PACKAGE)
        val (fileId, version, kind) = resolveLatestFile(appId)
        val (url, sha) = fetchDownloadUrl(token, appId, fileId)
        Log.i(TAG, "Resolved LINE appId=$appId fileId=$fileId ver=$version kind=$kind")
        ResolvedDownload(appId, fileId, version, kind, url, sha)
    }.onFailure { Log.w(TAG, "resolveLatestLine failed", it) }

    fun downloadTo(url: String, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val connection = open(url, method = "GET", accept = "*/*", userAgent = DALVIK_UA)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("CDN HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            if (dest.length() < 1024L) {
                dest.delete()
                error("Downloaded file too small (${dest.length()})")
            }
            Log.i(TAG, "Downloaded ${dest.length()} bytes -> ${dest.absolutePath}")
            dest
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchAuthToken(): String {
        val unixtime = (System.currentTimeMillis() / 1000L).toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(AUTH_SEED.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val hmac = mac.doFinal(unixtime.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
        val body = "hmac=${enc(hmac)}&unixtime=${enc(unixtime)}"
        val conn = open(
            "$EAPI/auth/token",
            method = "POST",
            accept = "application/json",
            userAgent = DALVIK_UA
        )
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("auth/token HTTP $code: ${text.take(200)}")
            val token = JSONObject(text).optString("token")
            if (token.isNullOrBlank()) error("auth/token missing token")
            return token
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchAppId(token: String, packageName: String): String {
        val conn = open(
            "$EAPI/apps/byPackagename/${encPath(packageName)}",
            method = "GET",
            accept = "application/json",
            userAgent = DALVIK_UA,
            bearer = token
        )
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("byPackagename HTTP $code: ${text.take(200)}")
            val data = JSONObject(text).optJSONObject("data") ?: JSONObject(text)
            val id = data.opt("appID")?.toString() ?: data.opt("id")?.toString()
            if (id.isNullOrBlank()) error("appID missing")
            return id
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Prefer public versions JSON (no captcha); fall back to HTML data-file-id on JP/EN pages.
     */
    private fun resolveLatestFile(appId: String): Triple<String, String?, String?> {
        runCatching {
            val url = "$PAGE_EN/apps/$appId/versions/1"
            val conn = open(url, method = "GET", accept = "application/json", userAgent = BROWSER_UA)
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val arr = JSONObject(text).optJSONArray("data")
                    if (arr != null && arr.length() > 0) {
                        val first = arr.getJSONObject(0)
                        val fileId = first.opt("fileID")?.toString()
                            ?: first.opt("fileId")?.toString()
                        if (!fileId.isNullOrBlank()) {
                            return Triple(
                                fileId,
                                first.optString("version").takeIf { it.isNotBlank() },
                                first.optString("kindFile").ifBlank {
                                    first.optString("titleKindFile")
                                }.takeIf { it.isNotBlank() }
                            )
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "versions JSON failed; trying HTML", it) }

        for (page in listOf(PAGE_JP, PAGE_EN, "$PAGE_EN/download", "$PAGE_JP/download")) {
            val id = scrapeFileId(page)
            if (id != null) return Triple(id, null, null)
        }
        error("Could not resolve latest Uptodown fileID for LINE")
    }

    private fun scrapeFileId(pageUrl: String): String? {
        return runCatching {
            val conn = open(pageUrl, method = "GET", accept = "text/html", userAgent = BROWSER_UA)
            try {
                if (conn.responseCode !in 200..299) return null
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("""data-file-id="(\d+)"""").find(html)?.groupValues?.get(1)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun fetchDownloadUrl(
        token: String,
        appId: String,
        fileId: String
    ): Pair<String, String?> {
        val path = "$EAPI/apps/$appId/file/$fileId/downloadUrl?update=0"
        val conn = open(
            path,
            method = "GET",
            accept = "application/json",
            userAgent = DALVIK_UA,
            bearer = token
        )
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("downloadUrl HTTP $code: ${text.take(200)}")
            val data = JSONObject(text).optJSONObject("data") ?: JSONObject(text)
            val url = data.optString("downloadURL").ifBlank { data.optString("downloadUrl") }
            if (url.isBlank()) error("downloadURL empty")
            if (!url.contains("uptodown.com", ignoreCase = true)) {
                error("Unexpected CDN host: ${url.take(80)}")
            }
            val sha = data.optString("sha256").takeIf { it.isNotBlank() }
                ?: data.optString("SHA256").takeIf { it.isNotBlank() }
            return url to sha
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        url: String,
        method: String,
        accept: String,
        userAgent: String,
        bearer: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = method
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
            setRequestProperty("Identificador", IDENTIFICADOR)
            setRequestProperty("Identificador-Version", IDENTIFICADOR_VERSION)
            if (bearer != null) {
                setRequestProperty("Authorization", "Bearer $bearer")
            }
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    private fun encPath(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
