package dev.multiclone.signing

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated envelope. Untrusted headers never control KDF cost or allocation. */
object BackupCipher {
    private val magic = "MCLONE01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val MAX_BACKUP = 16 * 1024 * 1024
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Use a backup passphrase with at least 12 characters." }
        require(plain.size <= MAX_BACKUP) { "Backup is too large." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = magic + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        require(envelope.size in 52..(MAX_BACKUP + 52)) { "Invalid backup size." }
        require(envelope.copyOfRange(0, 8).contentEquals(magic)) { "Unsupported backup format." }
        val salt = envelope.copyOfRange(8, 24)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, envelope.copyOfRange(24, 36)))
        cipher.updateAAD(envelope.copyOfRange(0, 36))
        return cipher.doFinal(envelope, 36, envelope.size - 36)
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
        } finally { spec.clearPassword() }
    }
}
