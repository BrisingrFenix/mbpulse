package pt.ua.mbpulse.crypto

import java.security.MessageDigest

/**
 * Identifies a study definition: lowercase hex SHA-256 of the UTF-8 bytes of
 * its exact text — the PI-signed definition (`StudyConfig."signedDefinition"`).
 * The backend computes the same over the same text when it records the
 * definition in its append-only history (`record_study_definition()` in
 * backend/seed_study.sql — keep in sync), and consent records name the terms
 * they were given for by this hash ([ConsentSignature], format v2).
 * Pure JVM, unit-testable.
 */
internal object StudyDefinitionHash {

    fun of(definitionText: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(definitionText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A well-formed hash (64 lowercase hex characters). */
    fun isValid(hash: String?): Boolean = hash != null && HEX64.matches(hash)

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}
