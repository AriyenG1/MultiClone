package dev.multiclone.core

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID

enum class Severity { INFO, WARNING, BLOCKED }
data class Finding(val code: String, val severity: Severity, val message: String)
data class ApkPart(
    val file: File,
    val splitName: String?,
    val sha256: String,
    val packageName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
    val requiredSplits: Set<String> = emptySet(),
)
data class ApkSource(
    val id: String,
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val parts: List<ApkPart>,
    val findings: List<Finding>,
) {
    val base: ApkPart get() = parts.single { it.splitName == null }
    val buildable: Boolean get() = findings.none { it.severity == Severity.BLOCKED }
}
data class ClonePlan(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: File? = null,
    val identityMap: Map<String, String> = emptyMap(),
    val engineVersion: Int = 1,
) {
    companion object {
        fun create(label: String, icon: File? = null): ClonePlan {
            require(label.isNotBlank() && label.length <= 100) { "Use a name between 1 and 100 characters." }
            val id = UUID.randomUUID().toString().replace("-", "")
            return ClonePlan(id, "dev.multiclone.generated.c$id", label.trim(), icon)
        }
    }
}
data class SignedArtifact(val file: File, val splitName: String?, val sha256: String)
data class CloneArtifacts(val plan: ClonePlan, val files: List<SignedArtifact>, val versionCode: Long)
data class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)
interface CompatibilityAnalyzer { fun analyze(files: List<File>, label: String? = null): ApkSource }
interface CloneBuilder {
    fun build(source: ApkSource, plan: ClonePlan, outputDirectory: File, progress: (String) -> Unit): List<Pair<File, String?>>
}
interface ApkSigner {
    fun verify(file: File): Set<String>
    fun sign(input: File, output: File, identity: SigningIdentity, minSdk: Int)
}
fun File.sha256(): String = inputStream().use { input ->
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
