package pt.ua.mbpulse

import pt.ua.mbpulse.collection.HealthConnectSource
import pt.ua.mbpulse.collection.SensorSource
import pt.ua.mbpulse.collection.SignalProcessor
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.SchemaDefinition
import java.time.Duration

/**
 * Configuration provided by the host application when initialising the library.
 *
 * @param supabaseUrl      Your Supabase project URL, e.g. `https://xxxx.supabase.co`.
 * @param supabaseKey      Your Supabase anon/public key (starts with `eyJ`).
 * @param sensorSource     Factory function that creates a [SensorSource] for each
 *                         collection session. Called on [Dispatchers.IO].
 * @param signalProcessor  Factory function that creates a fresh [SignalProcessor]
 *                         for each collection session. The processor is stateful
 *                         so a new instance is needed per session.
 */
public data class MbPulseConfig(
    val supabaseUrl: String,
    val supabaseKey: String,
    /** Called once per collection session to produce the sensor data stream. */
    val sensorSource: () -> SensorSource,
    /** Called once per collection session to produce a fresh processor. */
    val signalProcessor: () -> SignalProcessor,
    /**
     * The study data-type id (consent type) of each OMH schema
     * (`"namespace:name"`). Fails closed: only data points whose schema maps to
     * a type the participant accepted are stored and uploaded — a schema mapped
     * to null (the default for every schema) belongs to no type and is dropped.
     */
    val schemaToConsentType: (schemaName: String) -> String? = { null },
    /**
     * Study-specific schemas this app emits besides the built-in [OmhSchemas]
     * ones — declared here instead of modifying the library. Each is displayed,
     * charted, recovered and mapped to FHIR from its [SchemaDefinition]; build
     * its data points with [SchemaDefinition.dataPoint]. Map each one in
     * [schemaToConsentType] like any other schema.
     */
    val customSchemas: List<SchemaDefinition> = emptyList(),
    /**
     * Consent/data-type IDs (matching `StudyConfig` data type IDs) that this app
     * build can actually collect, given its [sensorSource] and [signalProcessor].
     *
     * A study is offered for selection only if this app supports **all** of the
     * study's *essential* data types — i.e. the app was built for that study.
     * Studies it cannot fulfil are shown disabled.
     *
     * `null` (default) means "no restriction": every study is selectable. Leave
     * it null for a general-purpose build; set it for an app tailored to a
     * specific study or signal set.
     */
    val supportedDataTypeIds: Set<String>? = null,
    /**
     * Health Connect import sources this build supports. The library's import
     * screen reads, maps, encrypts and uploads each registered source through
     * the normal pipeline; the host **expands** what can be imported by adding
     * [HealthConnectSource] implementations here (heart rate, steps, sleep, …).
     *
     * Empty (default) disables Health Connect import for this build. Each source
     * declares its own Health Connect read permissions, and the host app's
     * manifest must declare the matching `android.permission.health.*` entries.
     */
    val healthConnectSources: List<HealthConnectSource> = emptyList(),
    /**
     * How often new Health Connect data is imported in the background, without
     * the participant opening the import screen — so data from watches that
     * write to Health Connect flows in continuously. `null` disables it (import
     * stays manual). Only active when [healthConnectSources] is non-empty.
     *
     * Requires the host manifest to declare
     * `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`; the import
     * screen requests it together with the read permissions. On devices whose
     * Health Connect lacks background reads the job is a no-op. Minimum 15
     * minutes (WorkManager's periodic floor).
     */
    val healthConnectBackgroundImport: Duration? = Duration.ofHours(1),
    /**
     * How many days of history the import screen's "Import last N days" button
     * reads from Health Connect. 1–[MAX_HEALTH_CONNECT_IMPORT_DAYS] (90).
     *
     * Health Connect only serves data from up to 30 days before the app's first
     * permission grant unless `android.permission.health.READ_HEALTH_DATA_HISTORY`
     * is granted — above 30 the host manifest should declare it; the import
     * screen then requests it where the device supports it.
     */
    val healthConnectImportDays: Int = 30,
    /**
     * How often collected data is uploaded while the app is in the background.
     * During collection the foreground service uploads on this cadence itself
     * (not subject to WorkManager deferral); a periodic WorkManager job at the
     * same interval covers the rest (Health Connect imports, a backlog after
     * collection stopped, no network earlier). Minimum 15 minutes. Opening the
     * home screen always syncs immediately, with a progress bar.
     */
    val syncInterval: Duration = Duration.ofMinutes(30),
    /**
     * Base64 (standard, no-wrap) of the DER **X.509 SubjectPublicKeyInfo** for the
     * backend's deletion-receipt signing key (RSA). When set, the app verifies the
     * signature on the withdrawal deletion receipt and shows a "verified" badge —
     * making the receipt a tamper-evident, independently checkable attestation.
     *
     * The matching private key lives only as a Supabase Edge Function secret
     * (`DELETION_SIGNING_KEY_PKCS8_B64`); it is distinct from the PI key.
     *
     * `null` (default) skips receipt verification — the receipt still shows, just
     * without the cryptographic "verified" badge.
     */
    val deletionReceiptPublicKeyB64: String? = null,
    /**
     * Base64 DER X.509 (SPKI) public key of the PI's **study-signing** key —
     * printed by the setup wizard into AGENT_BRIEF.md. When set, the app
     * accepts a study only if its definition is signed by that key, and takes
     * the PI key, consent wording, retention and sampling ONLY from the signed
     * text; older signed versions are refused (no rollback). So whoever
     * controls the backend cannot redirect data to their own key or change
     * what participants consent to.
     *
     * Must be pinned here (in the build), never fetched from the server.
     * `null` (default) disables verification — logged as a warning; only for
     * development against an unsigned study.
     */
    val studySigningPublicKeyB64: String? = null,
    /**
     * How collection runs — and which Android foreground-service type it uses.
     * Default [CollectionMode.PASSIVE_CONNECTED_DEVICE]. See [CollectionMode].
     */
    val collectionMode: CollectionMode = CollectionMode.PASSIVE_CONNECTED_DEVICE,
    /**
     * Whether the collection screen (manual start/stop, CSV-replay testing) is
     * reachable from the home screen. `null` (default) = per mode:
     * [CollectionScreenAccess.DEBUG_BUILDS_ONLY] for passive modes (so testing
     * works while developing and the button disappears in the release build
     * automatically), [CollectionScreenAccess.ALWAYS] for [CollectionMode.SESSION]
     * (participants or researchers start each session there).
     */
    val collectionScreen: CollectionScreenAccess? = null,
    /**
     * The library's device-selection step for a Bluetooth LE wearable — see
     * [DeviceSelection]. `null` (default) = no step (no device, or the host
     * pairs its device itself, e.g. through a vendor SDK on a host screen).
     */
    val deviceSelection: DeviceSelection? = null,
    /** Colours, logo and title of the library's screens — see [MbPulseBranding]. */
    val branding: MbPulseBranding = MbPulseBranding()
) {
    /** [collectionScreen] with the per-mode default applied. */
    val collectionScreenAccess: CollectionScreenAccess
        get() = collectionScreen ?: if (collectionMode.isPassive)
            CollectionScreenAccess.DEBUG_BUILDS_ONLY else CollectionScreenAccess.ALWAYS

    public companion object {
        /** Upper bound for [healthConnectImportDays]. */
        public const val MAX_HEALTH_CONNECT_IMPORT_DAYS: Int = 90
    }
}

/**
 * How a study collects sensor data. Each mode maps to the Android
 * foreground-service type the collection service runs as — the type decides
 * what Android allows (background start, run time) and what the app must
 * declare.
 */
public enum class CollectionMode(
    /** Collection starts itself and runs continuously — no collection screen needed. */
    public val isPassive: Boolean
) {
    /**
     * Continuous background collection from a connected device (Bluetooth /
     * Wi-Fi / USB wearable). Starts after onboarding and after reboot, and
     * reconnects when the sensor stream drops. Service type `connectedDevice`.
     *
     * Android 14+ requires a runtime prerequisite: the host app must declare
     * and hold one of e.g. `BLUETOOTH_CONNECT` (BLE wearable — request it at
     * runtime), `CHANGE_NETWORK_STATE` (network sensor), or USB permission.
     */
    PASSIVE_CONNECTED_DEVICE(isPassive = true),

    /**
     * Continuous background collection from on-phone/body sensors. Like
     * [PASSIVE_CONNECTED_DEVICE] but service type `health`; Android 14+
     * requires `BODY_SENSORS` or `ACTIVITY_RECOGNITION` granted, or
     * `HIGH_SAMPLING_RATE_SENSORS` declared (Android 16+: a Health Connect
     * read permission also qualifies).
     */
    PASSIVE_HEALTH(isPassive = true),

    /**
     * Time-boxed sessions started and stopped on the collection screen — e.g.
     * a lab session while stimuli are shown. Service type `dataSync`: no
     * runtime prerequisite, but Android 15+ caps it at ~6 h per 24 h and it
     * cannot start from the background, so it is not for passive collection.
     */
    SESSION(isPassive = false);
}

/** Who can reach the collection screen. */
public enum class CollectionScreenAccess {
    /** Always reachable (needed for [CollectionMode.SESSION]). */
    ALWAYS,
    /**
     * Reachable only in debuggable builds (`debug` build type) — for testing a
     * passive app with CSV replay; hidden in the release APK without code changes.
     */
    DEBUG_BUILDS_ONLY,
    /** Never reachable. Not allowed with [CollectionMode.SESSION]. */
    NEVER
}