package dev.multiclone.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

data class InstalledApp(val packageName: String, val label: String, val splitCount: Int)
class SourceImporter(private val context: Context) {
    private val root = File(context.filesDir, "sources").apply { mkdirs() }
    fun installedApps(): List<InstalledApp> = context.packageManager.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
        .map { InstalledApp(it.packageName, context.packageManager.getApplicationLabel(it).toString(), it.splitSourceDirs?.size ?: 0) }
        .sortedBy { it.label.lowercase() }

    fun importInstalled(packageName: String): List<File> {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return try {
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).mapIndexed { index, path ->
                File(directory, "$index.apk").also { output -> File(path).inputStream().use { copyBounded(it, output) } }
            }
        } catch (e: Exception) { directory.deleteRecursively(); throw IllegalArgumentException("The installed APK files could not be read. Import APK files instead. ${e.message}", e) }
    }

    fun importFiles(uris: List<Uri>): List<File> {
        require(uris.isNotEmpty())
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return try {
            val files = uris.mapIndexed { index, uri ->
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0).orEmpty().lowercase()
                        require(listOf(".aab", ".apkm", ".xapk").none { name.endsWith(it) }) { "Import ordinary APKs or a single-set .apks archive. AAB, APKM and XAPK are unsupported." }
                    }
                }
                File(directory, "import-$index.apk").also { output ->
                    context.contentResolver.openInputStream(uri)?.use { copyBounded(it, output) } ?: error("The selected file cannot be opened.")
                }
            }
            if (files.size == 1 && ZipFile(files.single()).use { it.getEntry("AndroidManifest.xml") == null }) {
                extractSet(files.single(), directory).also { files.single().delete() }
            } else files
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    private fun extractSet(archive: File, directory: File): List<File> = ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= 100_000 && entries.map { it.name }.distinct().size == entries.size) { "Invalid APK archive." }
        require(entries.none { it.name.startsWith('/') || '\\' in it.name || ".." in it.name.split('/') }) { "Unsafe archive path." }
        val apks = entries.filter { !it.isDirectory && it.name.endsWith(".apk", true) }
        require(apks.isNotEmpty()) { "No APKs found. AAB, APKM and XAPK containers are not supported." }
        var total = 0L
        apks.mapIndexed { index, entry ->
            require(entry.size in 1..MAX_BYTES) { "Invalid APK entry size." }
            total = Math.addExact(total, entry.size)
            require(total <= MAX_BYTES) { "APK set exceeds safe import size." }
            File(directory, "part-$index.apk").also { out -> zip.getInputStream(entry).use { copyBounded(it, out) } }
        }
    }
    fun importIcon(uri: Uri): File {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 16 * 1024 * 1024) { "Icon image is too large." }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: error("Image cannot be read.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "Invalid or oversized image." }
        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Unsupported image.")
        val resized = Bitmap.createScaledBitmap(bitmap, 432, 432, true)
        val file = File(context.filesDir, "icons/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { resized.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return file
    }
    private fun copyBounded(input: InputStream, output: File) {
        output.outputStream().use { sink ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Import cancelled." }
                val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= MAX_BYTES) { "File exceeds safe import size." }
                require(output.parentFile!!.usableSpace > n + 16 * 1024 * 1024) { "Not enough free storage." }
                sink.write(buffer, 0, n)
            }
        }
    }
    companion object { const val MAX_BYTES = 4L * 1024 * 1024 * 1024 }
}
