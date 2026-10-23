package dev.multiclone.engine

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32

/** Bounds are parser safety limits, not limits on the number of clones. */
object ArchiveGuard {
    const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    fun check(file: File) {
        require(file.length() in 1..MAX_EXPANDED_BYTES) { "Empty or oversized APK." }
        ZipFile(file).use { zip ->
            val seen = HashSet<String>()
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                require(seen.add(e.name)) { "Duplicate archive entry: ${e.name}" }
                require(seen.size <= MAX_ENTRIES) { "Archive has too many entries." }
                require(!e.name.startsWith('/') && '\\' !in e.name && e.name.split('/').none { it == ".." }) {
                    "Unsafe archive path."
                }
                require(e.size >= 0 && e.size <= MAX_EXPANDED_BYTES) { "Invalid archive entry size." }
                val parserLimit = when {
                    e.name == "AndroidManifest.xml" -> 8L * 1024 * 1024
                    e.name == "resources.arsc" || e.name.matches(Regex("classes[0-9]*\\.dex")) -> 64L * 1024 * 1024
                    else -> MAX_EXPANDED_BYTES
                }
                require(e.size <= parserLimit) { "Binary metadata exceeds the safe on-device processing size: ${e.name}" }
                if (parserLimit != MAX_EXPANDED_BYTES) {
                    // A hostile ZIP can lie in its central directory. Bound actual inflation
                    // before giving binary metadata to any in-memory parser.
                    var actual = 0L
                    val crc = CRC32()
                    zip.getInputStream(e).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Archive inspection cancelled." }
                            val n = input.read(buffer); if (n < 0) break
                            actual += n
                            require(actual <= parserLimit && actual <= e.size) { "Archive metadata has an invalid expanded size." }
                            crc.update(buffer, 0, n)
                        }
                    }
                    require(actual == e.size && crc.value == e.crc) { "Archive metadata size or checksum does not match its directory." }
                }
                total = Math.addExact(total, e.size)
                require(total <= MAX_EXPANDED_BYTES) { "Expanded archive is too large." }
                require(e.size < 1_048_576 || e.compressedSize > 0 && e.size / e.compressedSize < 500) { "Suspicious compression ratio." }
            }
            require("AndroidManifest.xml" in seen) { "This file is not an APK." }
        }
    }
}
