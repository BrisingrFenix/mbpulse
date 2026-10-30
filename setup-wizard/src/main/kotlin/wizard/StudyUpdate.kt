package wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Updating an existing study: what the wizard checks before re-signing and
 * re-seeding it under the same `studyId`.
 *
 * - **Study-signing key** — the stored definition must verify against this
 *   wizard's study-signing key. Apps pin that key, so an update signed with a
 *   different one would be refused by every installed app; the original key
 *   must be copied over instead (backend/keys/study_signing_*).
 * - **PI key** — the update keeps the study's PI key when this wizard holds it.
 *   A different local PI key is a **handover** to a new investigator, allowed
 *   only with proof of the current PI's private key (a fresh signature that
 *   verifies against the stored public key). New data is then encrypted for
 *   the new PI; data collected before stays readable only with the old key.
 * - **Version** — must increase: participants' consent records name the
 *   version they agreed to, and the app asks for consent again when the
 *   terms change.
 */
object StudyUpdate {

    /** The studies for the picker, annotated with what this wizard can do with each. */
    fun list(dbUrl: String): JsonArray {
        val rows = Json.parseToJsonElement(Db.listStudies(dbUrl)).jsonArray
        val localPi = Keys.piPublicPem(keysDir)?.let(::publicKeyFromPem)
        return buildJsonArray {
            rows.forEach { el ->
                val row = el.jsonObject
                add(JsonObject(row + mapOf(
                    "signature" to JsonPrimitive(signatureStatus(row)),
                    "piKeyIsLocal" to JsonPrimitive(
                        localPi != null && storedPiKey(row)?.encoded?.contentEquals(localPi.encoded) == true
                    )
                )))
            }
        }
    }

    /**
     * Checks an update of [studyId] to [study] (the form contents) and returns
     * a human summary of what the update does; throws with every problem.
     */
    fun check(dbUrl: String, studyId: String, study: JsonObject, oldPiPrivatePem: String?): Plan {
        val row = Json.parseToJsonElement(Db.listStudies(dbUrl)).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["studyId"]?.jsonPrimitive?.content.equals(studyId, ignoreCase = true) }
            ?: error("Study $studyId not found on this backend.")
        val problems = mutableListOf<String>()

        row["endedAt"]?.jsonPrimitive?.contentOrNull?.let {
            error("This study ended on $it — an ended study cannot be changed.")
        }

        if (signatureStatus(row) == "other-key") {
            problems += "The study's stored definition does not verify with this wizard's study-signing key " +
                "(backend/keys/study_signing_*). Either it was signed with another key — its apps pin that key " +
                "and would refuse an update signed with yours, so copy the original key files into backend/keys " +
                "first — or it was altered on the server, which you should investigate."
        }

        val oldVersion = row["studyVersion"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val newVersion = study["studyVersion"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (newVersion == null || newVersion <= oldVersion) {
            problems += "Version must be higher than the current ${fmt(oldVersion)} (e.g. ${fmt(oldVersion + 1)}) — " +
                "consent records name the version participants agreed to."
        }

        val stored = storedPiKey(row) ?: error("The study has no readable PI key.")
        val localPem = Keys.piPublicPem(keysDir) ?: error("PI key not found — generate the keys in step 2 first")
        val handover = !publicKeyFromPem(localPem).encoded.contentEquals(stored.encoded)
        if (handover) {
            if (oldPiPrivatePem.isNullOrBlank()) {
                problems += "This wizard's PI key is not the study's PI key. To hand the study over to a new " +
                    "investigator, provide the CURRENT PI's private key (pi_private.pem) as proof."
            } else if (!provesKey(oldPiPrivatePem, stored)) {
                problems += "The private key provided is not the study's current PI key."
            }
        }
        require(problems.isEmpty()) { problems.joinToString("\n• ", prefix = "Cannot update this study:\n• ") }

        return Plan(row, handover, diffSummary(row, study))
    }

    data class Plan(val current: JsonObject, val handover: Boolean, val changes: List<String>)

    /** "valid" (verifies with this wizard's key), "other-key" (does not — other key or altered), or "unsigned". */
    internal fun signatureStatus(row: JsonObject): String {
        val text = row["signedDefinition"]?.jsonPrimitive?.contentOrNull ?: return "unsigned"
        val sig = row["definitionSignature"]?.jsonPrimitive?.contentOrNull ?: return "unsigned"
        val ok = verifiesWithLocalKey(text, sig)
        // A signature that fails under our key: either another key made it, or the
        // text was altered. Indistinguishable without the other key — report the
        // more likely (and more actionable) one.
        return if (ok) "valid" else "other-key"
    }

    /** True when [sigB64] over [text] verifies with this wizard's study-signing key (what apps pin). */
    internal fun verifiesWithLocalKey(text: String, sigB64: String): Boolean {
        val pinned = Keys.studySigningPublicB64(keysDir) ?: return false
        return runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pinned))))
                update(text.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(sigB64))
            }
        }.getOrDefault(false)
    }

    private fun storedPiKey(row: JsonObject): PublicKey? =
        row["piPublicKey"]?.jsonPrimitive?.contentOrNull?.let { runCatching { publicKeyFromPem(it) }.getOrNull() }

    /** True when [privatePem] signs a fresh challenge that [publicKey] verifies — real possession, not just matching numbers. */
    internal fun provesKey(privatePem: String, publicKey: PublicKey): Boolean = runCatching {
        val key = privateKeyFromPem(privatePem)
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(challenge); sign() }
        Signature.getInstance("SHA256withRSA").run { initVerify(publicKey); update(challenge); verify(sig) }
    }.getOrDefault(false)

    internal fun publicKeyFromPem(pem: String): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pemBody(pem, "PUBLIC KEY")))

    private fun privateKeyFromPem(pem: String): PrivateKey {
        require(!pem.contains("BEGIN RSA PRIVATE KEY")) {
            "PKCS#1 key — convert it: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key_pkcs8.pem"
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pemBody(pem, "PRIVATE KEY")))
    }

    private fun pemBody(pem: String, label: String): ByteArray = Base64.getMimeDecoder().decode(
        pem.replace("-----BEGIN $label-----", "").replace("-----END $label-----", "").trim()
    )

    /** What the update changes, for the researcher (and a warning on what participants will be asked). */
    private fun diffSummary(row: JsonObject, study: JsonObject): List<String> {
        fun types(el: Any?) = (el as? JsonArray).orEmpty()
            .map { it.jsonObject }.associateBy { it["id"]?.jsonPrimitive?.content.orEmpty() }
        val before = types(row["dataTypes"])
        val after = types(study["dataTypes"])
        return buildList {
            (after.keys - before.keys).forEach { add("New data type '$it' — participants are asked to consent to it") }
            (before.keys - after.keys).forEach { add("Removed data type '$it' — phones stop collecting it") }
            (after.keys intersect before.keys).forEach { id ->
                val b = before.getValue(id); val a = after.getValue(id)
                val fields = listOf("name", "description", "purpose", "retentionDays", "isEssential", "samplingSeconds")
                    .filter { (b[it]?.toString() ?: "null") != (a[it]?.toString() ?: "null") }
                if (fields.isNotEmpty()) add("Changed '$id' (${fields.joinToString()}) — participants are asked to consent again")
                if ("retentionDays" in fields) add("Retention of '$id' changes for data ALREADY collected too (applied by the nightly purge)")
            }
            if (row["studyName"]?.jsonPrimitive?.contentOrNull != study["studyName"]?.jsonPrimitive?.contentOrNull)
                add("Study renamed")
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() + ".0" else v.toString()
}
