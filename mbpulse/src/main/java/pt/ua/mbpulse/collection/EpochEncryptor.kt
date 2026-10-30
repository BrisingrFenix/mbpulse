package pt.ua.mbpulse.collection

import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.EncryptedRecordEntity
import kotlinx.serialization.json.Json
import kotlin.collections.map

/**
 * Converts a [SleepEpoch] to openMHealth-structured \[OmhDataPoint] documents,
 * encrypts each one individually, and returns a list of [EncryptedRecordEntity]
 * ready for Room insertion.
 *
 * Encrypts with AES-256-GCM using the in-memory DEK (fresh IV per record).
 * Encodes the ciphertext and wrapped DEK as hex strings for Room storage.
 *
 * The DEK ([dekBytes]) must be unwrapped from the Keystore at session start
 * and zeroed at session end — it is never written to disk.
 */
internal class EpochEncryptor internal constructor(
    private val keystoreManager: KeystoreManager,
    private val participantPseudoId: String,
    private val studyId: String,
    val dekBytes: ByteArray,
    private val piPublicKeyBytes: ByteArray?,
    /** Accepted data-type ids. Empty = nothing consented (mapped schemas are all dropped). */
    private val consentedTypeIds: Set<String>,
    private val schemaToConsentType: (schemaName: String) -> String? = { null }
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }

    // Schemas already warned about — consent-filter decisions are logged once
    // per schema per session so silent drops/passes are visible in logcat
    // without flooding it (an epoch arrives every ~30s).
    private val warnedSchemas = mutableSetOf<String>()

    fun encrypt(epoch: ProcessedEpoch, wrappedDekBytes: ByteArray): List<EncryptedRecordEntity> {
        // Wrap DEK under PI key once per epoch — same for all records in this epoch
        val piWrappedDek = if (piPublicKeyBytes != null) {
            keystoreManager.wrapWithExternalKey(dekBytes, piPublicKeyBytes)
        } else {
            Log.w("EpochEncryptor", "PI public key not available — pi_wrapped_dek_hex will be empty")
            ByteArray(0)
        }

        // Fail closed ([ConsentFilter]): only points of a data type the
        // participant accepted are kept — unmapped schemas and an empty consent
        // set keep nothing.
        val allowedPoints = epoch.dataPoints.filter { point ->
                val schemaName = "${point.header.schemaId.namespace}:${point.header.schemaId.name}"
                when (ConsentFilter.decide(schemaName, consentedTypeIds, schemaToConsentType)) {
                    ConsentFilter.Decision.CONSENTED -> true
                    ConsentFilter.Decision.UNMAPPED -> {
                        if (warnedSchemas.add(schemaName)) {
                            Log.w("EpochEncryptor", "Dropping '$schemaName' — it has no consent " +
                                "mapping (schemaToConsentType returned null), so no participant " +
                                "consented to it. Map it in MbPulseConfig.schemaToConsentType.")
                        }
                        false
                    }
                    ConsentFilter.Decision.NOT_CONSENTED -> {
                        if (warnedSchemas.add(schemaName)) {
                            Log.w("EpochEncryptor", "Dropping '$schemaName' — it maps to consent " +
                                "type '${schemaToConsentType(schemaName)}', which this participant " +
                                "did not accept. If it should be collected, check " +
                                "schemaToConsentType and the study's data-type ids (seed_study).")
                        }
                        false
                    }
                }
        }

        return allowedPoints.map { point ->
            val plaintext = json.encodeToString(point).toByteArray(Charsets.UTF_8)
            val ciphertext = keystoreManager.encryptRecord(plaintext, dekBytes)

            EncryptedRecordEntity(
                participantPseudoId         = participantPseudoId,
                studyId                     = studyId,
                epochStartMs                = epoch.epochStartMs,
                schemaName                  = "${point.header.schemaId.namespace}:${point.header.schemaId.name}",
                ciphertextHex               = ciphertext.toHex(),
                participantWrappedDekHex    = wrappedDekBytes.toHex(),
                piWrappedDekHex             = piWrappedDek.toHex()
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * The consent decision for one schema — shared by [EpochEncryptor] and
 * [RecordPolicy], pure so it is unit-tested. **Fails closed:** a point is kept
 * only when its schema maps to a data type in the participant's accepted set.
 * A schema the host leaves unmapped (`schemaToConsentType` = null) belongs to
 * no data type, so nobody consented to it and it is dropped; an empty set
 * (declined everything) keeps nothing.
 */
internal object ConsentFilter {

    enum class Decision { CONSENTED, NOT_CONSENTED, UNMAPPED }

    fun decide(
        schemaName: String,
        consentedTypeIds: Set<String>,
        schemaToConsentType: (schemaName: String) -> String?
    ): Decision {
        val typeId = schemaToConsentType(schemaName) ?: return Decision.UNMAPPED
        return if (typeId in consentedTypeIds) Decision.CONSENTED else Decision.NOT_CONSENTED
    }

    fun allows(
        schemaName: String,
        consentedTypeIds: Set<String>,
        schemaToConsentType: (schemaName: String) -> String?
    ): Boolean = decide(schemaName, consentedTypeIds, schemaToConsentType) == Decision.CONSENTED
}