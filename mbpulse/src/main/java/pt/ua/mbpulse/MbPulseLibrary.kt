package pt.ua.mbpulse

import android.app.Application

/**
 * Entry point for the mbPulse library.
 *
 * Call [init] from your [Application.onCreate] before any library component
 * is accessed. Hilt reads the config from this object at module binding time.
 *
 * ## Minimal setup
 *
 * ```kotlin
 * // MyApp.kt
 * @HiltAndroidApp
 * class MyApp : Application(), Configuration.Provider {
 *
 *     @Inject lateinit var workerFactory: HiltWorkerFactory
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         MbPulseLibrary.init(
 *             app    = this,
 *             config = MbPulseConfig(
 *                 supabaseUrl      = "https://xxx.supabase.co",
 *                 supabaseKey      = "eyJ...",
 *                 sensorSource     = { MyDeviceSensorSource(this) },
 *                 signalProcessor  = { MySignalProcessor() }
 *             )
 *         )
 *     }
 *
 *     override val workManagerConfiguration get() =
 *         Configuration.Builder().setWorkerFactory(workerFactory).build()
 * }
 *
 * // MainActivity.kt
 * @AndroidEntryPoint
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         setContent { MbPulseApp() }   // turnkey: library owns the full UI
 *     }
 * }
 * ```
 *
 * ## Custom embedding
 *
 * If your app has its own navigation graph and you want to embed the mbPulse
 * flow as a nested destination:
 *
 * ```kotlin
 * NavHost(navController, startDestination = "home") {
 *     composable("home") { HomeScreen() }
 *     composable("mbpulse") {
 *         MbPulseNavHost(
 *             onExit = { navController.navigateUp() }
 *         )
 *     }
 * }
 * ```
 */
public object MbPulseLibrary {

    @Volatile
    private var _config: MbPulseConfig? = null

    /**
     * The active configuration. Throws if [init] has not been called.
     */
    public val config: MbPulseConfig
        get() = _config ?: error(
            "MbPulseLibrary.init() has not been called. " +
                    "Call it from Application.onCreate() before any library component is used."
        )

    /**
     * Initialises the library with the provided [config].
     * Must be called from [Application.onCreate].
     *
     * The config is validated first: misconfiguration throws immediately with
     * a list of actionable messages (instead of surfacing later as silently
     * dropped data or dead network calls); non-fatal issues are logged as
     * warnings under the `MbPulseLibrary` tag.
     */
    /** The host's branding, or the default before [init] (e.g. in Compose previews). */
    public val branding: MbPulseBranding get() = _config?.branding ?: MbPulseBranding()

    public fun init(config: MbPulseConfig) {
        val result = MbPulseConfigValidator.validate(config)
        result.warnings.forEach { android.util.Log.w("MbPulseLibrary", "Config warning: $it") }
        require(result.errors.isEmpty()) {
            "Invalid MbPulseConfig — fix the following and rebuild:\n" +
                result.errors.joinToString("\n") { " • $it" }
        }
        pt.ua.mbpulse.data.model.OmhSchemas.register(config.customSchemas)
        _config = config
    }

    /** True after [init] has been called successfully. */
    public val isInitialised: Boolean get() = _config != null

    /**
     * Whether the collection screen is reachable in this build
     * ([MbPulseConfig.collectionScreen]; `DEBUG_BUILDS_ONLY` = debuggable APKs
     * only). Custom home screens use it to show or hide their collection
     * button; navigation to the screen is blocked when false either way.
     */
    public fun isCollectionScreenEnabled(context: android.content.Context): Boolean =
        pt.ua.mbpulse.collection.CollectionRequirements.collectionScreenEnabled(context)

    /**
     * The consent data type of [schemaName] — what every pipeline stage uses
     * (consent filter, sampling, cards). Backend questionnaire answers
     * (`questionnaire:<id>`) belong to the data type their verified
     * questionnaire names; an answer schema of no loaded questionnaire maps to
     * a type nobody consented to, so it is dropped.
     * Everything else: the host's [MbPulseConfig.schemaToConsentType].
     */
    public fun consentTypeFor(schemaName: String): String? =
        if (pt.ua.mbpulse.data.model.QuestionnaireRegistry.isQuestionnaireSchema(schemaName))
            pt.ua.mbpulse.data.model.QuestionnaireRegistry.dataTypeFor(schemaName)
                ?: UNKNOWN_QUESTIONNAIRE_TYPE
        else config.schemaToConsentType(schemaName)

    /** Consent type of answers to a questionnaire that is not loaded — never consented. */
    internal const val UNKNOWN_QUESTIONNAIRE_TYPE = "\u0000unverified-questionnaire"

    /**
     * Records participant-entered data (questionnaires, diaries, manual
     * readings) through the encrypted pipeline — for code outside Hilt; Hilt
     * classes inject [pt.ua.mbpulse.collection.ParticipantDataRecorder].
     */
    public fun recorder(context: android.content.Context): pt.ua.mbpulse.collection.ParticipantDataRecorder =
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            pt.ua.mbpulse.collection.ParticipantDataRecorderEntryPoint::class.java
        ).participantDataRecorder()
}