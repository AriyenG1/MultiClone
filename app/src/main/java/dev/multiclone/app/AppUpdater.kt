package dev.multiclone.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dev.multiclone.core.sha256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class AppRelease(val version: String, val url: String, val size: Long, val sha256: String)

/** User-initiated, public GitHub updates. No tokens or clone metadata are sent. */
internal class AppUpdater(private val app: MultiCloneApplication) {
    companion object {
        const val LATEST = "https://api.github.com/repos/AriyenG1/MultiClone/releases/latest"
        private const val MAX_APK = 256L * 1024 * 1024
        fun version(value: String): List<Int> = value.removePrefix("v").also {
            require(it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) { "Unsupported release version." }
        }.split('.').map { it.toInt() }
        fun newer(candidate: String, installed: String): Boolean {
            val a = version(candidate); val b = version(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val comparison = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (comparison != 0) return comparison > 0
            }
            return false
        }
        fun parse(json: String): AppRelease {
            val root = JSONObject(json)
            require(!root.getBoolean("draft") && !root.getBoolean("prerelease")) { "This is not a stable release." }
            val tag = root.getString("tag_name")
            version(tag)
            val name = "MultiClone-$tag.apk"
            val assets = root.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.getString("name") == name }
                ?: error("This release has no uniquely named MultiClone APK.")
            val url = asset.getString("browser_download_url")
            require(url == "https://github.com/AriyenG1/MultiClone/releases/download/$tag/$name") { "Unexpected update download URL." }
            val hash = asset.getString("digest").removePrefix("sha256:")
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "The release has no SHA-256 verification digest." }
            val size = asset.getLong("size")
            require(size in 1..MAX_APK) { "Update size is unsupported." }
            return AppRelease(tag.removePrefix("v"), url, size, hash)
        }
    }

    private fun connect(address: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "The update download redirected outside GitHub."
            }
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MultiClone/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            }
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                require(next != null) { "GitHub returned an invalid redirect." }
                url = URL(url, next)
            } else {
                if (status != 200) { connection.disconnect(); error(when (status) {
                    403, 429 -> "GitHub's request limit was reached. Try again later."
                    404 -> "No published release is available yet."
                    else -> "GitHub returned HTTP $status. Try again later."
                }) }
                return connection
            }
        }
        error("Too many update redirects.")
    }

    fun latest(): AppRelease {
        val connection = connect(LATEST)
        try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024) { "Release metadata is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 2 * 1024 * 1024) { "Release metadata is too large." }
            return parse(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    suspend fun download(release: AppRelease, progress: suspend (Int) -> Unit): File {
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        val partial = File(folder, "update.part")
        val target = File(folder, "MultiClone.apk")
        partial.delete(); target.delete()
        require(folder.usableSpace > release.size * 2 + 16 * 1024 * 1024) { "Free some storage before downloading the update." }
        val connection = connect(release.url)
        try {
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L; var last = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    require(total <= release.size) { "Downloaded update exceeds its expected size." }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.size).toInt()
                    if (percent != last) { progress(percent); last = percent }
                }
                require(total == release.size) { "Download was interrupted. Please retry." }
            } }
            require(partial.sha256() == release.sha256) { "Update checksum failed. Please retry the download." }
            validate(partial)
            require(partial.renameTo(target)) { "Could not save the verified update." }
            return target
        } finally { connection.disconnect(); partial.delete() }
    }

    fun validate(file: File) {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("The update APK could not be read.")
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        require(info.packageName == app.packageName) { "This APK belongs to a different app." }
        require(info.longVersionCode > installed.longVersionCode) { "This APK is not newer than the installed app." }
        require(app.signer.verify(file) == app.signer.verify(File(app.applicationInfo.sourceDir))) {
            "The update signing certificate does not match this installation. No data was changed."
        }
    }

    fun installIntent(file: File): Intent {
        validate(file) // Recheck the cached APK immediately before handing it to Android.
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
