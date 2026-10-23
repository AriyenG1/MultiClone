package dev.multiclone.engine

import android.os.Build
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.reandroid.apk.ApkModule
import dev.multiclone.core.*
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class ApkAnalyzer(private val signer: ApkSigner) : CompatibilityAnalyzer {
    override fun analyze(files: List<File>, label: String?): ApkSource {
        require(files.isNotEmpty()) { "Select a base APK, together with all its splits if applicable." }
        val findings = mutableListOf<Finding>()
        fun finding(code: String, severity: Severity, text: String) {
            if (findings.none { it.code == code }) findings += Finding(code, severity, text)
        }
        val parts = files.map { file ->
            ArchiveGuard.check(file)
            val digests = signer.verify(file)
            ApkModule.loadApkFile(file).use { apk ->
                val manifest = apk.androidManifest
                val root = manifest.manifestElement
                val pkg = manifest.packageName ?: error("Missing package identity.")
                require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package identity." }
                if (root.android("sharedUserId") != null) finding("shared-uid", Severity.BLOCKED, "Shared-UID applications are unsupported.")
                if (manifest.split.isNullOrEmpty() && (manifest.minSdkVersion ?: 1) > Build.VERSION.SDK_INT)
                    finding("min-sdk", Severity.BLOCKED, "This source requires a newer Android version than this device.")
                if (manifest.split.isNullOrEmpty() && (manifest.targetSdkVersion ?: 1) < 24)
                    finding("old-sdk", Severity.BLOCKED, "This app targets an obsolete Android version. MultiClone will not change its target SDK to bypass installation restrictions.")
                val nodes = root.elements() + root
                if (nodes.any { it.name in setOf("overlay", "instrumentation", "static-library", "sdk-library") })
                    finding("special-package", Severity.BLOCKED, "Overlays, instrumentation and library packages are unsupported.")
                if (nodes.any { it.name == "uses-library" && it.text("required") != "false" })
                    finding("libraries", Severity.WARNING, "The app declares platform library dependencies; availability must be checked by Android.")
                if (nodes.any { it.name == "data" && (it.text("scheme") in setOf("http", "https") || it.text("scheme")?.startsWith("msauth") == true) })
                    finding("auth-links", Severity.WARNING, "Web links or login callbacks may depend on the original identity and certificate.")
                if (manifest.usesPermissions.any { it.startsWith("android.permission.") && it in PRIVILEGED })
                    finding("privileged", Severity.WARNING, "Some requested permissions require privileged or system approval and may not work in a clone.")
                if (manifest.applicationElement?.android("isSplitRequired")?.valueAsBoolean == true && files.size == 1)
                    finding("missing-splits", Severity.BLOCKED, "This base APK requires split APKs. Import the complete installation.")
                val required = nodes.filter { it.name == "uses-split" }.mapNotNull { it.text("name") }.toMutableSet()
                root.searchAttributeByName("configForSplit")?.valueAsString?.takeIf { it.isNotBlank() && it != "base" }?.let { required += it }
                scanCode(file, pkg, ::finding)
                val low = (manifest.versionCode ?: 0).toLong() and 0xffffffffL
                val high = (root.android("versionCodeMajor")?.data ?: 0).toLong() shl 32
                ApkPart(file, manifest.split?.takeIf { it.isNotEmpty() }, file.sha256(), pkg, high or low, digests, required)
            }
        }
        require(parts.count { it.splitName == null } == 1) { "Select exactly one base APK. Separate apps must be imported separately." }
        val base = parts.single { it.splitName == null }
        require(parts.all { it.packageName == base.packageName && it.versionCode == base.versionCode && it.signerDigests == base.signerDigests }) {
            "All APKs must have the same package, version and verified signing certificates."
        }
        require(parts.map { it.splitName }.distinct().size == parts.size) { "Duplicate split names." }
        val names = parts.mapNotNull { it.splitName }.toSet()
        val missing = parts.flatMap { it.requiredSplits }.filter { it !in names }.toSet()
        if (missing.isNotEmpty()) finding("missing-dependencies", Severity.BLOCKED, "Missing required splits: ${missing.joinToString()}.")
        if (parts.size > 1) finding("split-experimental", Severity.WARNING, "Split transformations are experimental until tested for this app and device. Only supplied features are available; Play asset delivery is not reproduced.")
        finding("runtime-unknown", Severity.INFO, "Static analysis cannot certify login, native behavior or server compatibility. Installation and runtime testing are separate steps.")
        return ApkSource(UUID.randomUUID().toString(), base.packageName, label ?: base.packageName, base.versionCode,
            parts.sortedBy { it.splitName ?: "" }, findings)
    }

    private fun scanCode(file: File, pkg: String, finding: (String, Severity, String) -> Unit) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name.startsWith("lib/") && e.name.endsWith(".so"))
                    finding("native", Severity.WARNING, "Native code is present. Native identity references and page-size compatibility are not automatically repaired.")
                if (!e.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                require(e.size <= 256L * 1024 * 1024) { "A DEX file exceeds the safe processing size." }
                val dex = zip.getInputStream(e).use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(it)) }
                for (ref in dex.stringReferences) {
                    val value = ref.string
                    when {
                        value.contains("play/core/integrity") || value.contains("safetynet", ignoreCase = true) ->
                            finding("integrity", Severity.WARNING, "Integrity-related APIs are present. If the app requires its original identity, the clone will not work; protections will not be bypassed.")
                        value.contains("firebase") || value.contains("google/android/gms/auth") ->
                            finding("identity-service", Severity.WARNING, "Google/Firebase integrations may require the original package and signing certificate.")
                        value in setOf("checkSignatures", "hasSigningCertificate", "getSigningCertificateHistory", "getApkContentsSigners") ->
                            finding("signature-api", Severity.WARNING, "Code references signing-certificate APIs. Original-signature dependencies cannot be satisfied by a re-signed clone.")
                        value == pkg || value.startsWith("content://$pkg") ->
                            finding("hardcoded-identity", Severity.WARNING, "Code contains original-identity strings. Only explicitly recognized self-provider URI calls can be rewritten; other identity-dependent behavior may fail.")
                    }
                }
            }
        }
    }
    private companion object {
        val PRIVILEGED = setOf("android.permission.INSTALL_PACKAGES", "android.permission.DELETE_PACKAGES", "android.permission.READ_PRIVILEGED_PHONE_STATE", "android.permission.WRITE_SECURE_SETTINGS")
    }
}
