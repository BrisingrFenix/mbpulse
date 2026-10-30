package wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** handoff.json is read by every study app's HandoffConformanceTest (template-app/src/test/…/Handoff.kt). */
class HandoffTest {

    // The shapes the wizard page posts to /api/brief (public/app.js, collect()).
    private val study = Json.parseToJsonElement("""
        {"studyName": "Pain study", "studyVersion": 1, "dataTypes": [
          {"id": "heart_rate", "name": "Heart rate", "isEssential": true, "retentionDays": 30},
          {"id": "pain", "name": "Pain", "isEssential": false, "retentionDays": 30},
          {"id": "daily_checkin", "name": "Check-in", "isEssential": false, "retentionDays": 30}]}
    """).jsonObject

    private val app = Json.parseToJsonElement("""
        {"appName": "Pain Study", "packageId": "org.uni.pain", "collectionMode": "PASSIVE_HEALTH",
         "collectionScreenInRelease": true,
         "dataTypeSchemas": {"heart_rate": ["omh:heart-rate", "mbpulse:stimulus-event"],
                             "pain": ["mystudy:pain-rating", "mystudy:symptom-log"], "daily_checkin": []},
         "customSchemas": [{"id": "mystudy:pain-rating", "version": "2.0", "role": "measurement", "dataType": "pain",
           "fields": [{"name": "score", "kind": "number"}, {"name": "site", "kind": "enum", "values": ["head", "back"]},
                      {"name": "temp", "kind": "quantity", "unit": "Cel"}]}]}
    """).jsonObject

    private fun build(url: String? = "https://x.supabase.co", key: String? = "anon") =
        Handoff.build(study, app, "RECEIPT", "SIGNING", "1.2.0", url, key)

    @Test fun `mapping and supported types are the brief's`() {
        val h = build()
        assertEquals(Handoff.FORMAT, h["format"]!!.jsonPrimitive.content)
        assertEquals(Brief.mapping(app), h["schemaToConsentType"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(setOf("heart_rate", "pain"), h.strings("supportedDataTypeIds").toSet())
        assertEquals(listOf("heart_rate", "pain", "daily_checkin"), h["dataTypes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(h["dataTypes"]!!.jsonArray[0].jsonObject["isEssential"]!!.jsonPrimitive.boolean)
    }

    @Test fun `events and template schemas are listed for the processor check`() {
        val h = build()
        assertEquals(listOf("mbpulse:stimulus-event"), h.strings("eventSchemas"))
        assertEquals(listOf("mystudy:symptom-log"), h.strings("templateSchemas"))
    }

    @Test fun `custom schemas carry kinds as FieldKind names, units and enum values`() {
        val c = build()["customSchemas"]!!.jsonArray.single().jsonObject
        assertEquals("mystudy:pain-rating", c["id"]!!.jsonPrimitive.content)
        assertEquals("2.0", c["version"]!!.jsonPrimitive.content)
        assertFalse(c["isEvent"]!!.jsonPrimitive.boolean)
        val f = c["fields"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("NUMBER", "ENUM", "QUANTITY"), f.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf("head", "back"), f[1]["values"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Cel", f[2]["unit"]!!.jsonPrimitive.content)
        assertNull(f[0]["unit"])
    }

    @Test fun `no secret is written, only hashes of the credentials`() {
        val text = build(url = "https://secret-ref.supabase.co", key = "secret-anon-key").toString()
        assertFalse("secret-ref" in text)
        assertFalse("secret-anon-key" in text)
        assertEquals(Handoff.sha256Hex("secret-anon-key"), build(key = "secret-anon-key")["supabaseKeySha256"]!!.jsonPrimitive.content)
        // Known vector: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Handoff.sha256Hex("abc"))
    }

    @Test fun `missing credentials and keys are null, not empty`() {
        val h = Handoff.build(study, app, null, null, "1.2.0", null, null)
        listOf("supabaseUrlSha256", "supabaseKeySha256", "studySigningPublicKeyB64", "deletionReceiptPublicKeyB64")
            .forEach { assertTrue(it, (h[it] as JsonPrimitive).content == "null" || h[it].toString() == "null") }
    }

    @Test fun `collection mode and release screen`() {
        val h = build()
        assertEquals("PASSIVE_HEALTH", h["collectionMode"]!!.jsonPrimitive.content)
        assertTrue(h["collectionScreenInRelease"]!!.jsonPrimitive.boolean)
        val session = Handoff.build(study, JsonObject(app + ("collectionMode" to JsonPrimitive("SESSION"))), null, null, "1", null, null)
        assertFalse(session["collectionScreenInRelease"]!!.jsonPrimitive.boolean)
    }

    private fun JsonObject.strings(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }
}
