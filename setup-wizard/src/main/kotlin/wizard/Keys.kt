package wizard

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Generates the RSA-2048 keypairs the platform needs — PI (data decryption),
 * deletion-receipt signing, and study-definition signing — with the JDK's own
 * crypto, no openssl required. Refuses to overwrite existing keys.
 */
object Keys {

    data class Result(
        val created: Boolean,
        val receiptPublicKeyB64: String,
        val receiptPrivatePkcs8B64: String,
        val studySigningPublicKeyB64: String
    )

    fun generateOrLoad(dir: Path): Result =
        generateOrLoadPiAndReceipt(dir).copy(studySigningPublicKeyB64 = ensureStudySigningKey(dir))

    private fun generateOrLoadPiAndReceipt(dir: Path): Result {
        if (Files.exists(dir.resolve("pi_private.pem"))) {
            // Existing keys: never regenerate silently. Report what the app and
            // backend need, read back from disk.
            val receiptPub  = dir.resolve("receipt_public_spki.der")
            val receiptPriv = dir.resolve("receipt_private_pkcs8.der")
            require(Files.exists(receiptPub) && Files.exists(receiptPriv)) {
                "Keys in $dir are incomplete (PI key present, receipt key missing). " +
                    "Move the folder away and generate a fresh set."
            }
            return Result(
                created = false,
                receiptPublicKeyB64 = b64(Files.readAllBytes(receiptPub)),
                receiptPrivatePkcs8B64 = b64(Files.readAllBytes(receiptPriv)),
                studySigningPublicKeyB64 = ""
            )
        }

        Files.createDirectories(dir)
        val pi = rsa2048()
        val receipt = rsa2048()

        // PI keypair — private key stays on the PI machine (PKCS#8 PEM is what
        // the export frontend loads); public key goes into the study definition.
        Files.writeString(dir.resolve("pi_private.pem"), pem("PRIVATE KEY", pi.private.encoded))
        Files.writeString(dir.resolve("pi_private_pkcs8.pem"), pem("PRIVATE KEY", pi.private.encoded))
        Files.writeString(dir.resolve("pi_public.pem"), pem("PUBLIC KEY", pi.public.encoded))

        // Receipt-signing keypair — private half becomes an Edge Function
        // secret; SPKI public half goes into MbPulseConfig.
        Files.writeString(dir.resolve("receipt_private.pem"), pem("PRIVATE KEY", receipt.private.encoded))
        Files.write(dir.resolve("receipt_private_pkcs8.der"), receipt.private.encoded)
        Files.write(dir.resolve("receipt_public_spki.der"), receipt.public.encoded)

        return Result(
            created = true,
            receiptPublicKeyB64 = b64(receipt.public.encoded),
            receiptPrivatePkcs8B64 = b64(receipt.private.encoded),
            studySigningPublicKeyB64 = ""
        )
    }

    /** The PI public key PEM (for seeding), or null if keys were never generated. */
    fun piPublicPem(dir: Path): String? =
        dir.resolve("pi_public.pem").takeIf { Files.exists(it) }?.let { Files.readString(it) }

    /** The receipt-verification public key (SPKI b64), or null if not generated. */
    fun receiptPublicB64(dir: Path): String? =
        dir.resolve("receipt_public_spki.der").takeIf { Files.exists(it) }
            ?.let { b64(Files.readAllBytes(it)) }

    // ---- study-definition signing key ---------------------------------------
    // A third RSA-2048 pair, held ONLY by the PI (never uploaded): the wizard
    // signs every seeded study definition with it, and the app pins the public
    // half (MbPulseConfig.studySigningPublicKeyB64) — so whoever controls the
    // backend cannot swap the PI key or the consent wording. Separate from the
    // PI key (one key, one purpose) and from the receipt key (which lives on the
    // server — the party this protects against).

    private const val STUDY_SIGNING_PRIVATE = "study_signing_private_pkcs8.der"
    private const val STUDY_SIGNING_PUBLIC  = "study_signing_public_spki.der"

    /** Creates the study-signing pair if missing (existing key sets gain it; nothing is overwritten). */
    fun ensureStudySigningKey(dir: Path): String {
        val pub = dir.resolve(STUDY_SIGNING_PUBLIC)
        if (!Files.exists(pub)) {
            require(!Files.exists(dir.resolve(STUDY_SIGNING_PRIVATE))) {
                "$dir has a study-signing private key without its public key — restore it from your backup."
            }
            Files.createDirectories(dir)
            val pair = rsa2048()
            Files.write(dir.resolve(STUDY_SIGNING_PRIVATE), pair.private.encoded)
            Files.writeString(dir.resolve("study_signing_private.pem"), pem("PRIVATE KEY", pair.private.encoded))
            Files.write(pub, pair.public.encoded)
        }
        return b64(Files.readAllBytes(pub))
    }

    /** The pinned study-signing public key (SPKI b64), or null if not generated. */
    fun studySigningPublicB64(dir: Path): String? =
        dir.resolve(STUDY_SIGNING_PUBLIC).takeIf { Files.exists(it) }?.let { b64(Files.readAllBytes(it)) }

    /** Base64 SHA256withRSA signature of [text] (UTF-8) with the study-signing private key. */
    fun signStudyDefinition(dir: Path, text: String): String {
        val der = dir.resolve(STUDY_SIGNING_PRIVATE).takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }
            ?: error("Study-signing key not found — generate the keys in step 2 first")
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        val signature = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        }
        return b64(signature)
    }

    private fun rsa2048(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun pem(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der) +
            "\n-----END $label-----\n"
}
