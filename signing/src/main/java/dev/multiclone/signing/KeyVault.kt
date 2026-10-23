package dev.multiclone.signing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.multiclone.core.SigningIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyVault(context: Context) {
    private val dir = File(context.noBackupFilesDir, "clone-keys").apply { mkdirs() }
    private val alias = "multiclone.key-wrapping.v1"
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    @Synchronized fun getOrCreate(id: String): SigningIdentity {
        val file = keyFile(id)
        if (file.exists()) return decode(unseal(id, file.readBytes()))
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=MultiClone $id")
        val bc = BouncyCastleProvider()
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger(159, SecureRandom()).add(BigInteger.ONE),
                Date(now - 86400000), Date(now + 40L * 365 * 86400000), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private)))
        return SigningIdentity(pair.private, certificate).also { store(id, encode(it)) }
    }
    fun exportRaw(id: String): ByteArray = unseal(id, keyFile(id).readBytes())
    @Synchronized fun delete(id: String) {
        val file = keyFile(id)
        AtomicFile(file).delete()
        check(!file.exists()) { "Could not delete the clone signing key. Retry deletion." }
    }
    @Synchronized fun importRaw(id: String, bytes: ByteArray) {
        val identity = decode(bytes)
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(identity.privateKey); signature.update(challenge)
        val proof = signature.sign()
        signature.initVerify(identity.certificate); signature.update(challenge)
        require(signature.verify(proof)) { "Backup certificate and key do not match." }
        if (keyFile(id).exists()) {
            require(getOrCreate(id).certificate.encoded.contentEquals(identity.certificate.encoded)) { "A different key already exists for this clone." }
        } else store(id, bytes)
    }
    private fun keyFile(id: String): File {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid clone identifier." }
        return File(dir, "$id.key")
    }
    private fun store(id: String, plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(id.toByteArray())
        val atomic = AtomicFile(keyFile(id))
        val stream = atomic.startWrite()
        try { stream.write(cipher.iv + cipher.doFinal(plain)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun unseal(id: String, data: ByteArray): ByteArray {
        require(data.size in 29..32768) { "Invalid key file." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(data, 12, data.size - 12)
    }
    private fun encode(identity: SigningIdentity): ByteArray {
        val privateKey = identity.privateKey.encoded
        return java.nio.ByteBuffer.allocate(4 + privateKey.size + identity.certificate.encoded.size)
            .putInt(privateKey.size).put(privateKey).put(identity.certificate.encoded).array()
    }
    private fun decode(data: ByteArray): SigningIdentity {
        require(data.size in 128..32768)
        val buffer = java.nio.ByteBuffer.wrap(data)
        val size = buffer.int
        require(size in 64..8192 && size < buffer.remaining())
        val privateBytes = ByteArray(size).also { buffer.get(it) }
        val certBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return try {
            SigningIdentity(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes)) as java.security.cert.X509Certificate)
        } finally { privateBytes.fill(0) }
    }
}
