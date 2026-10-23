package dev.multiclone.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class ArchiveGuardTest {
    @Test(expected = IllegalArgumentException::class) fun rejectsFalseExpandedSizeMetadata() {
        val file = File.createTempFile("false-size", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(ByteArray(100) { it.toByte() }); zip.closeEntry()
            }
            val bytes = file.readBytes()
            val central = bytes.indices.first { i -> i + 3 < bytes.size && bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 1)
            file.writeBytes(bytes)
            ArchiveGuard.check(file)
        } finally { file.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathTraversal() {
        val file = File.createTempFile("unsafe", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in listOf("AndroidManifest.xml", "../escape")) { zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry() }
            }
            ArchiveGuard.check(file)
        } finally { file.delete() }
    }
}
