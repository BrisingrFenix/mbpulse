package pt.ua.mbpulse.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Instrumented tests for the hardware-backed RSA paths of [KeystoreManager].
 *
 * These MUST run on a device or emulator (`connectedDebugAndroidTest`): they
 * use the real `AndroidKeyStore` provider, which the JVM unit-test sandbox
 * cannot emulate. They complement the plain-JVM `KeystoreManagerRecordTest`
 * (AES-GCM envelope) and `KeyRecoveryManagerTest` (passphrase recovery).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreManagerInstrumentedTest {

    private lateinit var manager: KeystoreManager

    @Before
    fun setUp() {
        manager = KeystoreManager()
        manager.deleteKeyPair()            // start from a clean slate
        manager.generateKeyPairIfAbsent()
    }

    @After
    fun tearDown() {
        manager.deleteKeyPair()
    }

    @Test
    fun keyPairIsGeneratedAndReported() {
        assertTrue(manager.keyPairExists())
        // A 2048-bit RSA SubjectPublicKeyInfo DER is ~294 bytes.
        assertTrue("public key DER should be non-trivial", manager.getPublicKeyBytes().size > 256)
    }

    @Test
    fun generateKeyPairIfAbsentIsIdempotent() {
        val first = manager.getPublicKeyBytes()
        manager.generateKeyPairIfAbsent()      // must NOT replace the existing key
        val second = manager.getPublicKeyBytes()
        assertArrayEquals(first, second)
    }

    @Test
    fun wrappedDekUnwrapsToTheSameBytes() {
        val wrapped = manager.generateAndWrapDek()
        val unwrapped = manager.unwrapDek(wrapped.encryptedDekBytes)

        assertEquals("DEK must be 256-bit", 32, unwrapped.size)
        assertArrayEquals(wrapped.rawDekBytes, unwrapped)
    }

    @Test
    fun rewrappingARecoveredDekRoundTrips() {
        // Recovery re-wraps a recovered raw DEK under the new device key.
        val raw = manager.generateAndWrapDek().rawDekBytes
        val rewrapped = manager.wrapDekWithPublicKey(raw)
        assertArrayEquals(raw, manager.unwrapDek(rewrapped))
    }

    @Test
    fun endToEndRecordEncryptionThroughTheKeystore() {
        val wrapped = manager.generateAndWrapDek()
        val plaintext = """{"heart_rate":61.0}""".toByteArray()

        val ciphertext = manager.encryptRecord(plaintext, wrapped.rawDekBytes)

        // Simulate a later session: unwrap the stored DEK, then decrypt.
        val dek = manager.unwrapDek(wrapped.encryptedDekBytes)
        assertArrayEquals(plaintext, manager.decryptRecord(ciphertext, dek))
    }

    @Test
    fun externalWrapMatchesWebCryptoOaepContract() {
        // Software RSA key pair standing in for the PI's key.
        val piPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val dek = manager.generateAndWrapDek().rawDekBytes

        val wrapped = manager.wrapWithExternalKey(dek, piPair.public.encoded)

        // Decrypt exactly as the PI / Web Crypto would: RSA-OAEP with SHA-256
        // for BOTH the hash and the MGF1 hash. This pins the cross-platform
        // contract that wrapWithExternalKey relies on.
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            piPair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        assertArrayEquals(dek, cipher.doFinal(wrapped))
    }

    @Test
    fun deletingTheKeyPairMakesUnwrapImpossible() {
        val wrapped = manager.generateAndWrapDek()

        manager.deleteKeyPair()

        assertFalse(manager.keyPairExists())
        // Private key is gone — the wrapped DEK (and thus all ciphertext) is
        // now permanently unrecoverable on this device.
        assertThrows(Exception::class.java) {
            manager.unwrapDek(wrapped.encryptedDekBytes)
        }
    }

    @Test
    fun unwrapRejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) {
            manager.unwrapDek(ByteArray(0))
        }
    }
}
