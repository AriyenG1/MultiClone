package dev.multiclone.signing

import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    @Test fun backupsAreRandomizedAndAuthenticated() {
        val password = "correct long passphrase".toCharArray()
        val plain = "private signing key material".toByteArray()
        val first = BackupCipher.encrypt(plain, password)
        val second = BackupCipher.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, BackupCipher.decrypt(first, password))
        try { BackupCipher.decrypt(first, "wrong long passphrase".toCharArray()); fail("Wrong password accepted") } catch (_: javax.crypto.AEADBadTagException) { }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        try { BackupCipher.decrypt(first, password); fail("Tampered backup accepted") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test(expected = IllegalArgumentException::class) fun shortPasswordsAreRejected() { BackupCipher.encrypt(byteArrayOf(1), "short".toCharArray()) }
}
