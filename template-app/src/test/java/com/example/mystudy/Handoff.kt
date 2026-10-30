package com.example.mystudy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * `handoff.json`, written by the setup wizard into this app's folder (step 4,
 * next to local.properties) — what the study expects of this app. Read-only:
 * never edit it to make a check pass; regenerate it in the wizard.
 */
data class Handoff(
    val format: String,
    val studyName: String?,
    val libraryVersion: String,
    val packageId: String,
    val supabaseUrlSha256: String?,
    val supabaseKeySha256: String?,
    val studySigningPublicKeyB64: String?,
    val deletionReceiptPublicKeyB64: String?,
    val collectionMode: String,
    val collectionScreenInRelease: Boolean,
    val dataTypeIds: List<String>,
    val schemaToConsentType: Map<String, String>,
    val supportedDataTypeIds: Set<String>,
    val eventSchemas: Set<String>,
    val templateSchemas: Set<String>,
    val customSchemas: List<CustomSchema>
) {
    data class CustomSchema(val id: String, val version: String, val isEvent: Boolean, val fields: List<Field>)
    data class Field(val name: String, val kind: String, val unit: String?, val values: List<String>)

    companion object {
        const val FORMAT = "mbpulse-handoff-v1"

        fun read(file: File): Handoff = parse(Json.parseToJsonElement(file.readText()).jsonObject)

        fun parse(o: JsonObject): Handoff {
            fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
            fun strings(key: String) = o[key]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            return Handoff(
                format = str(o, "format") ?: "",
                studyName = str(o, "studyName"),
                libraryVersion = str(o, "libraryVersion") ?: "",
                packageId = str(o, "packageId") ?: "",
                supabaseUrlSha256 = str(o, "supabaseUrlSha256"),
                supabaseKeySha256 = str(o, "supabaseKeySha256"),
                studySigningPublicKeyB64 = str(o, "studySigningPublicKeyB64"),
                deletionReceiptPublicKeyB64 = str(o, "deletionReceiptPublicKeyB64"),
                collectionMode = str(o, "collectionMode") ?: "",
                collectionScreenInRelease = (o["collectionScreenInRelease"] as? JsonPrimitive)?.booleanOrNull ?: false,
                dataTypeIds = o["dataTypes"]?.jsonArray.orEmpty().mapNotNull { str(it.jsonObject, "id") },
                schemaToConsentType = o["schemaToConsentType"]?.jsonObject.orEmpty()
                    .mapValues { it.value.jsonPrimitive.content },
                supportedDataTypeIds = strings("supportedDataTypeIds").toSet(),
                eventSchemas = strings("eventSchemas").toSet(),
                templateSchemas = strings("templateSchemas").toSet(),
                customSchemas = o["customSchemas"]?.jsonArray.orEmpty().map { el ->
                    val c = el.jsonObject
                    CustomSchema(
                        id = str(c, "id") ?: "",
                        version = str(c, "version") ?: "1.0",
                        isEvent = (c["isEvent"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        fields = c["fields"]?.jsonArray.orEmpty().map { fe ->
                            val f = fe.jsonObject
                            Field(
                                name = str(f, "name") ?: "",
                                kind = str(f, "kind") ?: "",
                                unit = str(f, "unit"),
                                values = f["values"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                            )
                        }
                    )
                }
            )
        }
    }
}
