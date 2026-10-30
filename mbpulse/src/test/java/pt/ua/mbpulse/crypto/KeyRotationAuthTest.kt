package pt.ua.mbpulse.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyRotationAuthTest {

    private val dek = ByteArray(32) { it.toByte() }
    private val pseudoId = "6F1C2A3B-0000-4000-8000-000000000001"

    @Test
    fun `rotation key is a fixed one-way derivation of the DEK`() {
        val key = KeyRotationAuth.rotationKey(dek)
        assertEquals(32, key.size)
        assertEquals(key.toList(), KeyRotationAuth.rotationKey(dek.copyOf()).toList())
        assertNotEquals(key.toList(), KeyRotationAuth.rotationKey(ByteArray(32)).toList())
        assertNotEquals(dek.toList(), key.toList())
    }

    @Test
    fun `proof message is the documented format with a lowercase pseudo id`() {
        assertEquals(
            "rotate|6f1c2a3b-0000-4000-8000-000000000001|n0nce|PUB|WRAPPED",
            KeyRotationAuth.proofMessage(pseudoId, "n0nce", "PUB", "WRAPPED")
        )
    }

    @Test
    fun `proof binds the nonce and the new key material`() {
        val key = KeyRotationAuth.rotationKey(dek)
        val proof = KeyRotationAuth.proof(key, pseudoId, "n1", "PUB", "WRAPPED")
        assertEquals(proof, KeyRotationAuth.proof(key, pseudoId.lowercase(), "n1", "PUB", "WRAPPED"))
        assertNotEquals(proof, KeyRotationAuth.proof(key, pseudoId, "n2", "PUB", "WRAPPED"))
        assertNotEquals(proof, KeyRotationAuth.proof(key, pseudoId, "n1", "OTHER", "WRAPPED"))
        assertNotEquals(proof, KeyRotationAuth.proof(key, pseudoId, "n1", "PUB", "OTHER"))
        assertNotEquals(proof, KeyRotationAuth.proof(KeyRotationAuth.rotationKey(ByteArray(32)), pseudoId, "n1", "PUB", "WRAPPED"))
    }

    // ---- passphrase proof (backend API 4) ---------------------------------------

    @Test
    fun `passphrase proof message is the documented format and covers the device key`() {
        assertEquals(
            "rotate-passphrase|6f1c2a3b-0000-4000-8000-000000000001|n0nce|PUB|WRAPPED|DEVKEY",
            KeyRotationAuth.passphraseProofMessage(pseudoId, "n0nce", "PUB", "WRAPPED", "DEVKEY")
        )
    }

    @Test
    fun `passphrase proof binds key, nonce, key material and device key`() {
        val key = ByteArray(32) { 7 }
        val proof = KeyRotationAuth.passphraseProof(key, pseudoId, "n1", "PUB", "WRAPPED", "DEV")
        assertEquals(proof, KeyRotationAuth.passphraseProof(key, pseudoId.lowercase(), "n1", "PUB", "WRAPPED", "DEV"))
        assertNotEquals(proof, KeyRotationAuth.passphraseProof(key, pseudoId, "n1", "PUB", "WRAPPED", "OTHER"))
        assertNotEquals(proof, KeyRotationAuth.passphraseProof(key, pseudoId, "n2", "PUB", "WRAPPED", "DEV"))
        assertNotEquals(proof, KeyRotationAuth.passphraseProof(ByteArray(32), pseudoId, "n1", "PUB", "WRAPPED", "DEV"))
        // Not the DEK proof under another name.
        assertNotEquals(proof, KeyRotationAuth.proof(key, pseudoId, "n1", "PUB", "WRAPPED"))
    }

    @Test
    fun `hmac is standard HMAC-SHA256 like the backend's hmac_sha256 (RFC 4231 case 2)`() {
        val mac = KeyRotationAuth.hmac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.joinToString("") { "%02x".format(it) }
        )
    }
}
