package com.clinicalTests

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import pt.ua.mbpulse.CollectionMode
import pt.ua.mbpulse.MbPulseConfig
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.collection.HealthConnectSource
import pt.ua.mbpulse.collection.ProcessedEpoch
import pt.ua.mbpulse.collection.readAll
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame
import pt.ua.mbpulse.data.model.OmhHeader
import pt.ua.mbpulse.data.model.OmhProvenance
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhTimeInterval
import pt.ua.mbpulse.data.model.OmhUnitValue
import dagger.hilt.android.HiltAndroidApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@HiltAndroidApp
class MeasuresApp : Application() , Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // Credentials come from local.properties (mbpulse.supabaseUrl / .supabaseKey)
        // via BuildConfig — never hardcode them here.
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()) {
            "Missing backend credentials: add mbpulse.supabaseUrl and mbpulse.supabaseKey " +
                "to local.properties, then rebuild."
        }

        MbPulseLibrary.init(
            MbPulseConfig(
                supabaseUrl     = BuildConfig.SUPABASE_URL,
                supabaseKey     = BuildConfig.SUPABASE_KEY,
                sensorSource    = { DreamtSensorSource(applicationContext) },
                signalProcessor = { E4SignalProcessor() },
                schemaToConsentType = { schema ->
                    when (schema) {
                        "omh:heart-rate",
                        "mbpulse:hrv"                    -> "bvp_hr_ibi"
                        "omh:sleep-episode"                    -> "accelerometry"
                        "omh:body-temperature"                 -> "skin_temperature"
                        "mbpulse:electrodermal-activity" -> "eda"
                        else                                   -> null
                    }
                },
                supportedDataTypeIds = setOf("bvp_hr_ibi","accelerometry"),
                healthConnectSources = listOf(HeartRateSource),
                // DREAMT replay is started/stopped on the collection screen —
                // a time-boxed session (dataSync), not passive collection.
                collectionMode = CollectionMode.SESSION
            )
        )
    }

    override val workManagerConfiguration get() =
        Configuration.Builder().setWorkerFactory(workerFactory).build()
}

/**
 * Imports heart-rate history from Health Connect, binned into 1-minute mean-HR
 * epochs on the omh:heart-rate schema (the same one [E4SignalProcessor] emits,
 * so imports show up alongside collected data and map to the "bvp_hr_ibi"
 * consent type). Provenance is tagged as self-reported / Health Connect so the
 * imported data stays distinguishable from data the E4 measured live.
 *
 * This is the host's contribution to the library's generic Health Connect
 * import: registering more [HealthConnectSource]s (steps, sleep, …) is all it
 * takes to widen what the import screen can pull in.
 */
private object HeartRateSource : HealthConnectSource {
    override val id          = "heart-rate"
    override val displayName = "Heart rate"
    override val permissions = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))
    // Change tracking for the background import; read() bins into 1-minute epochs.
    override val recordTypes = setOf(HeartRateRecord::class)
    override val readAlignment: Duration = Duration.ofMinutes(1)

    override suspend fun read(
        client: HealthConnectClient,
        since: Instant,
        until: Instant
    ): List<ProcessedEpoch> {
        val binMs = 60_000L
        val iso   = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        return client.readAll(HeartRateRecord::class, since, until)
            .flatMap { record -> record.samples }
            .filter { it.beatsPerMinute > 0 }
            .groupBy { it.time.toEpochMilli() / binMs }
            .toSortedMap()
            .map { (bin, group) ->
                val startMs = bin * binMs
                val endMs   = startMs + binMs
                val meanBpm = group.map { it.beatsPerMinute }.average().toFloat()
                ProcessedEpoch(
                    epochStartMs = startMs,
                    dataPoints   = listOf(
                        OmhDataPoint(
                            header = OmhHeader(
                                id                    = UUID.randomUUID().toString(),
                                creationDateTime      = Instant.now().atOffset(ZoneOffset.UTC).format(iso),
                                schemaId              = OmhSchemas.HEART_RATE,
                                acquisitionProvenance = OmhProvenance(
                                    sourceName = "Health Connect",
                                    modality   = "self-reported"
                                )
                            ),
                            body = OmhBody(
                                heartRate          = OmhUnitValue(meanBpm, "beats/min"),
                                effectiveTimeFrame = OmhEffectiveTimeFrame(
                                    OmhTimeInterval(
                                        startDateTime = Instant.ofEpochMilli(startMs).atOffset(ZoneOffset.UTC).format(iso),
                                        endDateTime   = Instant.ofEpochMilli(endMs).atOffset(ZoneOffset.UTC).format(iso)
                                    )
                                )
                            )
                        )
                    )
                )
            }
    }
}