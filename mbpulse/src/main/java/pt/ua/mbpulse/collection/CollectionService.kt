package pt.ua.mbpulse.collection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import pt.ua.mbpulse.logging.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CollectionService"

/** Lets [CollectionService.start] (no injection in a companion) publish errors. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CollectionStateEntryPoint {
    fun collectionState(): CollectionStateHolder
}

/**
 * Foreground service that owns a collection session.
 *
 * Pipeline separate from the ViewModel and instead a foreground service so the
 * collection keeps running when the collection screen leaves the back stack or
 * the app is backgrounded — required for overnight/long recordings, which the
 * OS would otherwise kill. Progress is published to [CollectionStateHolder] so
 * any UI can observe it without owning the work.
 *
 * Start with [start] and stop with [stop].
 *
 * ## Collection mode ([MbPulseConfig.collectionMode])
 * - **Passive** (`connectedDevice` / `health` service type): started by
 *   [PassiveCollection] after onboarding and on boot; when the sensor stream
 *   ends or fails it reconnects with exponential backoff instead of stopping.
 * - **Session** (`dataSync`): started/stopped on the collection screen; the
 *   service stops itself when the sensor stream completes or errors.
 *
 * [start] checks the type's Android 14+ prerequisites first
 * ([CollectionRequirements]) and reports what is missing instead of crashing.
 */
@AndroidEntryPoint
internal class CollectionService : Service() {

    @Inject lateinit var encryptedRecordDao: EncryptedRecordDao
    @Inject lateinit var displayEpochDao: DisplayEpochDao
    @Inject lateinit var epochHistoryDao: EpochHistoryDao
    @Inject internal lateinit var keystoreManager: KeystoreManager
    @Inject lateinit var state: CollectionStateHolder
    @Inject lateinit var uploader: RecordUploader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectionJob: Job? = null
    /** elapsedRealtime of the last in-service upload; 0 = upload on the first epoch (backlog). */
    private var lastUploadAt = 0L
    /** [paramsKey] of the running collection. */
    private var runningParams: String? = null
    /** True while a parameter change restarts collection — the old job must not stop the service. */
    @Volatile private var restarting = false

    /** Everything that decides what is collected and for whom, from a start intent. */
    private fun paramsKey(intent: Intent): String {
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val sampling = (intent.getSerializableExtra(EXTRA_SAMPLING_SECONDS) as? HashMap<String, Int>).orEmpty()
        return listOf(
            intent.getStringExtra(EXTRA_PSEUDO_ID),
            intent.getStringExtra(EXTRA_STUDY_ID),
            intent.getStringArrayExtra(EXTRA_CONSENTED_TYPES)?.sorted()?.joinToString(","),
            sampling.toSortedMap().toString(),
            intent.getByteArrayExtra(EXTRA_PI_PUBLIC_KEY)?.contentHashCode()?.toString(),
            intent.getByteArrayExtra(EXTRA_WRAPPED_DEK)?.contentHashCode()?.toString()
        ).joinToString("|")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            stopCollection()
            return START_NOT_STICKY
        }

        if (intent == null) {
            // System restarted us with no redelivered intent — nothing to resume.
            Log.w(TAG, "Restarted with null intent — stopping")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground within the OS deadline on every start request.
        if (!startForegroundNotification()) return START_NOT_STICKY

        val params = paramsKey(intent)
        val running = collectionJob
        if (running?.isActive == true) {
            if (params == runningParams) {
                Log.d(TAG, "Collection already active — ignoring duplicate start")
                return START_REDELIVER_INTENT
            }
            // Consent, sampling or PI key changed (study update / re-consent):
            // restart so nothing is collected under the old parameters.
            Log.i(TAG, "Collection parameters changed — restarting collection")
            restarting = true
            scope.launch {
                running.cancelAndJoin()
                restarting = false
                runningParams = params
                startCollection(intent)
            }
            return START_REDELIVER_INTENT
        }

        runningParams = params
        startCollection(intent)
        // Redeliver the (RSA-wrapped) DEK + params so a killed session can resume.
        return START_REDELIVER_INTENT
    }

    private fun startCollection(intent: Intent) {
        val participantPseudoId = intent.getStringExtra(EXTRA_PSEUDO_ID).orEmpty()
        val studyId             = intent.getStringExtra(EXTRA_STUDY_ID).orEmpty()
        val wrappedDekBytes     = intent.getByteArrayExtra(EXTRA_WRAPPED_DEK) ?: ByteArray(0)
        val piPublicKeyBytes    = intent.getByteArrayExtra(EXTRA_PI_PUBLIC_KEY)
        val consentedTypeIds    = intent.getStringArrayExtra(EXTRA_CONSENTED_TYPES)?.toSet() ?: emptySet()

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val samplingSecondsByTypeId =
            (intent.getSerializableExtra(EXTRA_SAMPLING_SECONDS) as? HashMap<String, Int>).orEmpty()

        // Per-session sink: applies the study's per-type sampling interval.
        val epochSink = EpochSink(
            encryptedRecordDao, displayEpochDao, epochHistoryDao,
            samplingSecondsByTypeId = samplingSecondsByTypeId
        )

        Log.d(TAG, "startCollection — pseudoId=$participantPseudoId wrappedDek=${wrappedDekBytes.size}B " +
            "sampling=$samplingSecondsByTypeId")
        state.onStarting()

        val passive = MbPulseLibrary.config.collectionMode.isPassive

        collectionJob = scope.launch {
            var dekBytes: ByteArray? = null
            try {
                if (!keystoreManager.keyPairExists()) {
                    Log.e(TAG, "Key pair missing — onboarding may not have completed")
                }

                dekBytes = keystoreManager.unwrapDek(wrappedDekBytes)
                Log.d(TAG, "DEK unwrapped — ${dekBytes.size} bytes")

                val encryptor = EpochEncryptor(
                    keystoreManager     = keystoreManager,
                    participantPseudoId = participantPseudoId,
                    studyId             = studyId,
                    dekBytes            = dekBytes,
                    piPublicKeyBytes    = piPublicKeyBytes,
                    consentedTypeIds    = consentedTypeIds,
                    schemaToConsentType = MbPulseLibrary::consentTypeFor
                )

                // Session mode: one run of the sensor stream. Passive mode: the
                // stream ending (wearable out of range, Bluetooth off) or failing
                // is a disconnect, not the end — reconnect with backoff.
                var failures = 0
                while (true) {
                    val produced = try {
                        runSensorStream(epochSink, encryptor, wrappedDekBytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Sensor stream failed: ${e::class.simpleName} — ${e.message}", e)
                        if (!passive) throw e
                        0
                    }
                    SyncWorker.schedule(applicationContext)
                    if (!passive) break

                    failures = if (produced > 0) 0 else failures + 1
                    val waitMs = (RECONNECT_BASE_MS shl minOf(failures, 5)).coerceAtMost(RECONNECT_MAX_MS)
                    state.onReconnecting(waitMs / 1000)
                    Log.w(TAG, "Sensor stream ended — reconnecting in ${waitMs / 1000} s")
                    delay(waitMs)
                }

                state.onComplete()
                Log.d(TAG, "Collection complete — periodic sync scheduled")
            } catch (e: CancellationException) {
                Log.d(TAG, "Collection cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Collection failed: ${e::class.simpleName} — ${e.message}", e)
                state.onError("${e::class.simpleName}: ${e.message}")
            } finally {
                // Zero the DEK on every exit path, flush whatever was queued,
                // and tear the foreground service down.
                dekBytes?.fill(0)
                SyncWorker.syncNow(applicationContext)
                if (!restarting) stopForegroundAndSelf()
            }
        }
    }

    /**
     * Runs one sensor stream to completion through processor → sink. Sensor
     * source and processor are host-provided and stateful, so each run (and
     * each passive reconnect) gets fresh instances.
     *
     * @return the number of epochs produced.
     */
    private suspend fun runSensorStream(
        epochSink: EpochSink,
        encryptor: EpochEncryptor,
        wrappedDekBytes: ByteArray
    ): Int {
        val sensorSource    = MbPulseLibrary.config.sensorSource()
        val signalProcessor = MbPulseLibrary.config.signalProcessor()
        state.onRecording()

        var produced = 0
        try {
            sensorSource.samples().collect { sample ->
                DeviceConnection.onSample()
                // One sample can yield several epochs (a closed window + an event trigger).
                signalProcessor.feedAll(sample).forEach { epoch ->
                    val inserted = epochSink.store(epoch, encryptor, wrappedDekBytes)
                    if (inserted > 0) maybeUpload()
                    produced++
                    state.onEpoch()
                }
            }
        } finally {
            DeviceConnection.onStreamEnded()
        }
        return produced
    }

    /**
     * Uploads from the service itself every [MbPulseConfig.syncInterval] while
     * data comes in. A WorkManager job would be deferred by Doze / app-standby
     * buckets while the app sits in the background — the foreground service
     * keeps network access, so uploading here is what makes background sync
     * reliable. Checked on each stored epoch (the sensor stream keeps the CPU
     * awake, a timer would not); the upload runs beside the stream, never
     * blocking it, and [RecordUploader] skips a run while one is in progress.
     */
    private fun maybeUpload() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUploadAt < MbPulseLibrary.config.syncInterval.toMillis()) return
        lastUploadAt = now
        scope.launch { Log.d(TAG, "In-service upload: ${uploader.syncPending()}") }
    }

    private fun stopCollection() {
        val job = collectionJob
        if (job != null && job.isActive) {
            state.onStopped()
            job.cancel()            // the launch's finally{} tears the service down
        } else {
            stopForegroundAndSelf()
        }
        collectionJob = null
    }

    /** @return false if Android refused the foreground start (error published). */
    private fun startForegroundNotification(): Boolean {
        val mode = MbPulseLibrary.config.collectionMode
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mbpulse_notification_collection_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.mbpulse_notification_collection_title))
            .setContentText(getString(
                if (mode.isPassive) R.string.mbpulse_notification_collection_passive
                else R.string.mbpulse_notification_collection_session
            ))
            .setOngoing(true)
            .setSilent(true)
            .build()

        // The FOREGROUND_SERVICE_TYPE_* values are compile-time constants,
        // inlined on every API level; ServiceCompat ignores the type below API 29.
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                CollectionRequirements.serviceType(mode)
            )
            true
        } catch (e: Exception) {
            // SecurityException (prerequisite revoked since start() checked) or
            // ForegroundServiceStartNotAllowedException (background start).
            val msg = "Android refused to start $mode collection: ${e::class.simpleName} — ${e.message}"
            Log.e(TAG, msg, e)
            state.onError(msg)
            stopSelf()
            false
        }
    }

    private fun stopForegroundAndSelf() {
        // Service teardown touches the main thread; the collection loop runs on IO.
        Handler(Looper.getMainLooper()).post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID      = "mbpulse_collection_channel"
        private const val NOTIFICATION_ID = 1002

        private const val ACTION_STOP          = "pt.ua.mbpulse.action.STOP_COLLECTION"
        private const val EXTRA_PSEUDO_ID      = "extra_pseudo_id"
        private const val EXTRA_STUDY_ID       = "extra_study_id"
        private const val EXTRA_WRAPPED_DEK    = "extra_wrapped_dek"
        private const val EXTRA_PI_PUBLIC_KEY  = "extra_pi_public_key"
        private const val EXTRA_CONSENTED_TYPES = "extra_consented_types"
        private const val EXTRA_SAMPLING_SECONDS = "extra_sampling_seconds"

        private const val RECONNECT_BASE_MS = 15_000L        // passive: 15 s, 30 s, …
        private const val RECONNECT_MAX_MS  = 15 * 60_000L   // … capped at 15 min

        /**
         * Starts a foreground collection session. Safe to call when already running.
         *
         * Checks the configured mode's Android prerequisites first; if anything
         * is missing nothing is started, the reason is published to
         * [CollectionStateHolder.error] (shown on the collection/home screens)
         * and logged, and `false` is returned.
         */
        fun start(
            context: Context,
            participantPseudoId: String,
            studyId: String,
            wrappedDekBytes: ByteArray,
            piPublicKeyBytes: ByteArray?,
            consentedTypeIds: Set<String>,
            samplingSecondsByTypeId: Map<String, Int> = emptyMap()
        ): Boolean {
            val problems = CollectionRequirements.check(context)
            if (problems.isNotEmpty()) {
                val msg = "Collection cannot start (${MbPulseLibrary.config.collectionMode}):\n" +
                    problems.joinToString("\n") { "• $it" }
                Log.e(TAG, msg)
                EntryPointAccessors.fromApplication(
                    context.applicationContext, CollectionStateEntryPoint::class.java
                ).collectionState().onError(msg)
                return false
            }
            val intent = Intent(context, CollectionService::class.java).apply {
                putExtra(EXTRA_PSEUDO_ID, participantPseudoId)
                putExtra(EXTRA_STUDY_ID, studyId)
                putExtra(EXTRA_WRAPPED_DEK, wrappedDekBytes)
                putExtra(EXTRA_PI_PUBLIC_KEY, piPublicKeyBytes)
                putExtra(EXTRA_CONSENTED_TYPES, consentedTypeIds.toTypedArray())
                putExtra(EXTRA_SAMPLING_SECONDS, HashMap(samplingSecondsByTypeId))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /** Requests the current session stop. No-op if nothing is running. */
        fun stop(context: Context) {
            val intent = Intent(context, CollectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
