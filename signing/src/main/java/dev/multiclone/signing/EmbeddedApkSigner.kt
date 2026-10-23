package dev.multiclone.signing

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import dev.multiclone.core.SigningIdentity
import java.io.File
import java.security.MessageDigest

class EmbeddedApkSigner : dev.multiclone.core.ApkSigner {
    override fun verify(file: File): Set<String> {
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(28).build().verify()
        require(result.isVerified) { "APK signature verification failed: ${result.errors.joinToString()}" }
        return result.signerCertificates.map {
            MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString("") { b -> "%02x".format(b) }
        }.toSet().also { require(it.isNotEmpty()) { "APK has no verified signing certificate." } }
    }

    override fun sign(input: File, output: File, identity: SigningIdentity, minSdk: Int) {
        val signer = ApkSigner.SignerConfig.Builder("MultiClone", identity.privateKey, listOf(identity.certificate)).build()
        ApkSigner.Builder(listOf(signer)).setInputApk(input).setOutputApk(output)
            .setMinSdkVersion(maxOf(minSdk, 28))
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign()
        verify(output)
    }
}
