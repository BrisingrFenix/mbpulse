package com.example.mystudy

import android.content.Context
import pt.ua.mbpulse.CollectionMode
import pt.ua.mbpulse.MbPulseBranding
import pt.ua.mbpulse.MbPulseConfig

/**
 * This study's [MbPulseConfig] — everything the app wires into the library.
 *
 * Kept out of [TemplateApp] so `HandoffConformanceTest` (src/test) checks the
 * exact config the app starts with against the setup wizard's handoff.json:
 * build it here, never a second copy elsewhere. [context] is only captured by
 * the factories (the test never calls them for the real sensor).
 */
fun studyConfig(context: Context): MbPulseConfig =
    MbPulseConfig(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY,

        // TODO(agent): replace the CSV replay with the study's real
        // device driver — a SensorSource streaming SensorSample(
        // timestampMs, signals) from BLE/USB/etc. Keep the replay
        // around for development; see CsvReplaySensorSource docs.
        // Bluetooth LE wearable: start from BleSensorSource (fill in its
        // TODO(agent)s), turn on `deviceSelection` below and use
        //   sensorSource = { BleSensorSource(context,
        //       whenNoDevice = { if (BuildConfig.DEBUG) <the CSV replay below> else null }) },
        sensorSource = {
            CsvReplaySensorSource(
                context       = context,
                assetFileName = "sample_data.csv",
                // Real time: passive collection loops for as long as the
                // app runs, so a faster replay would stamp readings in
                // the future. (A quick demo in SESSION mode can use 30.0.)
                speedFactor   = 1.0,
                loop          = true
            )
        },

        // TODO(agent): extend the processor with the signals this study
        // actually derives (see TemplateSignalProcessor, and
        // E4SignalProcessor in the sample app for a full example).
        signalProcessor = { TemplateSignalProcessor() },

        // TODO(agent): map every OMH schema the processor emits to a
        // consent data-type id from the study definition (your
        // study.json "dataTypes"). A wrong mapping or null drops those
        // points before encryption (consent fails closed) — both are
        // logged once per schema under the EpochEncryptor tag.
        // "heart_rate" is the setup wizard's default data-type id.
        schemaToConsentType = { schema ->
            when (schema) {
                "omh:heart-rate"         -> "heart_rate"
                "mystudy:symptom-log"    -> "symptom_log"   // example host screen
                else                     -> null
            }
        },

        // TODO(agent): declare measures the library does not ship as
        // SchemaDefinitions (e.g. in CustomSchemas.kt — the setup
        // wizard's AGENT_BRIEF.md generates them) and list them here.
        // Never edit mbpulse for a study. SymptomLog is the
        // worked example of participant-entered data (host screen).
        customSchemas = listOf(SymptomLog.schema),

        // TODO(agent): if this build only supports specific studies,
        // list the consent data-type ids it can collect; studies whose
        // essential types are not covered appear greyed out. null = no
        // restriction.
        supportedDataTypeIds = null,

        // TODO(agent): register HealthConnectSource implementations to
        // let participants import history (heart rate, steps, sleep …).
        // Each source's permission must also be declared in the
        // manifest. Empty list = import UI hidden.
        healthConnectSources = emptyList(),

        // Once sources are registered, new Health Connect data (e.g. from a
        // watch that writes there) is imported in the background at this
        // interval — give each source its recordTypes for change tracking.
        // null = manual import only. Needs READ_HEALTH_DATA_IN_BACKGROUND
        // in the manifest.
        healthConnectBackgroundImport = java.time.Duration.ofHours(1),

        // TODO(agent): days of history the import screen reads (1–90).
        // Above 30, declare READ_HEALTH_DATA_HISTORY in the manifest —
        // Health Connect withholds older data without it.
        healthConnectImportDays = 30,

        // How often collected data is uploaded in the background
        // (min 15 min). The home screen also syncs whenever it opens.
        syncInterval = java.time.Duration.ofMinutes(30),

        // TODO(agent): the study's look. primaryColor drives every
        // library screen (light + dark palettes are derived from it);
        // logoRes appears on study selection and the consent intro.
        // Wording: override any mbpulse_* string in res/values/
        // (e.g. mbpulse_home_title) or add values-pt/ etc.
        branding = MbPulseBranding(
            primaryColor = 0xFF078966.toInt(),
            logoRes = null
        ),

        // TODO(agent): paste the deletionReceiptPublicKeyB64 value from
        // AGENT_BRIEF.md (the setup wizard's keys; or the SPKI base64
        // printed by backend/keygen.sh) to enable the "receipt
        // signature verified" badge on withdrawal.
        deletionReceiptPublicKeyB64 = null,

        // TODO(agent): paste the studySigningPublicKeyB64 value from
        // AGENT_BRIEF.md. The app then accepts only study definitions
        // the PI signed in the setup wizard (PI key, consent wording,
        // retention). Never remove it to make a failing study load —
        // that error means the server's copy is not the signed one.
        studySigningPublicKeyB64 = null,

        // TODO(agent): how this study collects (set by the setup wizard):
        //  - PASSIVE_CONNECTED_DEVICE (default): continuous background
        //    collection from a wearable; starts after onboarding and on
        //    boot. Needs a connected-device prerequisite in the manifest.
        //  - PASSIVE_HEALTH: same, for on-body/phone sensors.
        //  - SESSION: started/stopped on the collection screen (lab).
        collectionMode = CollectionMode.PASSIVE_CONNECTED_DEVICE,

        // The collection screen (Start/Stop, CSV-replay testing): by
        // default debug builds only in passive modes — it disappears from
        // the release APK with no change here. ALWAYS / NEVER to override.
        collectionScreen = null,

        // TODO(agent): Bluetooth LE wearable → the library's device step:
        // after onboarding the participant picks the device in Android's
        // chooser (only devices advertising these services are listed);
        // the home screen shows it with "Change". BleSensorSource reads
        // the choice. Needs BLUETOOTH_CONNECT in the manifest.
        //   deviceSelection = DeviceSelection(serviceUuids = listOf(BleSensorSource.SERVICE_UUID)),
        // null = no device step (CSV replay, or a vendor SDK that pairs itself).
        deviceSelection = null
    )
