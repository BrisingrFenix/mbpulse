package pt.ua.mbpulse

import androidx.health.connect.client.HealthConnectClient
import pt.ua.mbpulse.collection.HealthConnectSource
import pt.ua.mbpulse.collection.ProcessedEpoch
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SensorSource
import pt.ua.mbpulse.collection.SignalProcessor
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

class MbPulseConfigValidatorTest {

    private companion object {
        val STUDY_KEY: String = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        )
    }

    // ---- fakes -------------------------------------------------------------

    private val fakeSource = object : SensorSource {
        override fun samples() = emptyFlow<SensorSample>()
    }
    private val fakeProcessor = object : SignalProcessor {
        override fun feed(sample: SensorSample): ProcessedEpoch? = null
    }

    private fun hcSource(sourceId: String, perms: Set<String> = setOf("perm")) =
        object : HealthConnectSource {
            override val id = sourceId
            override val displayName = sourceId
            override val permissions = perms
            override suspend fun read(
                client: HealthConnectClient, since: Instant, until: Instant
            ): List<ProcessedEpoch> = emptyList()
        }

    private fun config(
        url: String = "https://example.supabase.co",
        key: String = "sb_publishable_key",
        mapper: (String) -> String? = { schema -> if (schema == "omh:heart-rate") "heart_rate" else null },
        supported: Set<String>? = null,
        hcSources: List<HealthConnectSource> = emptyList(),
        receiptKey: String? = null,
        mode: CollectionMode = CollectionMode.PASSIVE_CONNECTED_DEVICE,
        screen: CollectionScreenAccess? = null,
        studyKey: String? = STUDY_KEY
    ) = MbPulseConfig(
        studySigningPublicKeyB64 = studyKey,
        collectionMode = mode,
        collectionScreen = screen,
        supabaseUrl = url,
        supabaseKey = key,
        sensorSource = { fakeSource },
        signalProcessor = { fakeProcessor },
        schemaToConsentType = mapper,
        supportedDataTypeIds = supported,
        healthConnectSources = hcSources,
        deletionReceiptPublicKeyB64 = receiptKey
    )

    // ---- credentials ---------------------------------------------------------

    @Test
    fun `valid minimal config has no errors`() {
        val result = MbPulseConfigValidator.validate(config())
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun `blank url and key are both reported together`() {
        val result = MbPulseConfigValidator.validate(config(url = " ", key = ""))
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { "supabaseUrl" in it && "local.properties" in it })
        assertTrue(result.errors.any { "supabaseKey" in it })
    }

    @Test
    fun `url without scheme is an error`() {
        val result = MbPulseConfigValidator.validate(config(url = "example.supabase.co"))
        assertTrue(result.errors.any { "https://" in it })
    }

    // ---- device selection -----------------------------------------------------

    @Test
    fun `device selection with a valid pattern is accepted`() {
        val result = MbPulseConfigValidator.validate(
            config().copy(deviceSelection = DeviceSelection(listOf(DeviceSelection.bluetoothUuid(0x180D)), "Polar.*"))
        )
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun `invalid device name pattern is an error`() {
        val result = MbPulseConfigValidator.validate(config().copy(deviceSelection = DeviceSelection(namePattern = "Polar(")))
        assertTrue(result.errors.any { "namePattern" in it })
    }

    @Test
    fun `device selection in health mode is a warning`() {
        val result = MbPulseConfigValidator.validate(
            config(mode = CollectionMode.PASSIVE_HEALTH).copy(deviceSelection = DeviceSelection())
        )
        assertTrue(result.warnings.any { "PASSIVE_CONNECTED_DEVICE" in it })
    }

    @Test
    fun `bluetooth short ids expand to the SIG base UUID`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", DeviceSelection.bluetoothUuid(0x180D).toString())
    }

    // ---- consent mapping ------------------------------------------------------

    @Test
    fun `mapper throwing is an error naming the schema`() {
        val result = MbPulseConfigValidator.validate(
            config(mapper = { schema -> if (schema == "omh:heart-rate") error("boom") else null })
        )
        assertTrue(result.errors.any { "omh:heart-rate" in it && "schemaToConsentType" in it })
    }

    @Test
    fun `mapping outside supportedDataTypeIds is a warning`() {
        val result = MbPulseConfigValidator.validate(
            config(
                mapper = { schema -> if (schema == "omh:heart-rate") "bvp_hr_ibi" else null },
                supported = setOf("accelerometry")
            )
        )
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.any { "bvp_hr_ibi" in it && "supportedDataTypeIds" in it })
    }

    @Test
    fun `mapping inside supportedDataTypeIds is clean`() {
        val result = MbPulseConfigValidator.validate(
            config(
                mapper = { schema -> if (schema == "omh:heart-rate") "bvp_hr_ibi" else null },
                supported = setOf("bvp_hr_ibi")
            )
        )
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `mapper that maps nothing is a warning`() {
        val result = MbPulseConfigValidator.validate(config(mapper = { null }))
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.any { "maps no schema" in it && "nothing will be collected" in it })
    }

    // ---- Health Connect sources ------------------------------------------------

    @Test
    fun `duplicate source ids are an error`() {
        val result = MbPulseConfigValidator.validate(
            config(hcSources = listOf(hcSource("heart-rate"), hcSource("heart-rate")))
        )
        assertTrue(result.errors.any { "duplicate" in it && "heart-rate" in it })
    }

    @Test
    fun `source without permissions is a warning`() {
        val result = MbPulseConfigValidator.validate(
            config(hcSources = listOf(hcSource("steps", perms = emptySet())))
        )
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.any { "steps" in it && "permissions" in it })
    }

    // ---- receipt verification key -------------------------------------------------

    @Test
    fun `garbage receipt key is an error with keygen hint`() {
        val result = MbPulseConfigValidator.validate(config(receiptKey = "not-base64!!"))
        assertTrue(result.errors.any { "deletionReceiptPublicKeyB64" in it && "keygen.sh" in it })
    }

    @Test
    fun `valid SPKI RSA receipt key passes`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val spkiB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val result = MbPulseConfigValidator.validate(config(receiptKey = spkiB64))
        assertEquals(emptyList<String>(), result.errors)
    }

    // ---- Health Connect background import ------------------------------------------

    @Test
    fun `background import interval below 15 minutes is an error`() {
        val result = MbPulseConfigValidator.validate(
            config().copy(healthConnectBackgroundImport = java.time.Duration.ofMinutes(5))
        )
        assertTrue(result.errors.any { "healthConnectBackgroundImport" in it && "15" in it })
    }

    @Test
    fun `source without recordTypes warns only when background import is on`() {
        val on = MbPulseConfigValidator.validate(config(hcSources = listOf(hcSource("hr"))))
        assertTrue(on.warnings.any { "'hr'" in it && "recordTypes" in it })
        val off = MbPulseConfigValidator.validate(
            config(hcSources = listOf(hcSource("hr"))).copy(healthConnectBackgroundImport = null)
        )
        assertTrue(off.warnings.none { "recordTypes" in it })
    }

    @Test
    fun `sync interval below 15 minutes is an error`() {
        val result = MbPulseConfigValidator.validate(config().copy(syncInterval = java.time.Duration.ofMinutes(10)))
        assertTrue(result.errors.any { "syncInterval" in it && "15" in it })
        assertTrue(MbPulseConfigValidator.validate(config()).errors.none { "syncInterval" in it })
    }

    @Test
    fun `import period must be 1 to 90 days`() {
        listOf(0, 91, -5).forEach { days ->
            val result = MbPulseConfigValidator.validate(config().copy(healthConnectImportDays = days))
            assertTrue("$days", result.errors.any { "healthConnectImportDays" in it && "90" in it })
        }
        listOf(1, 30, 90).forEach { days ->
            val result = MbPulseConfigValidator.validate(config().copy(healthConnectImportDays = days))
            assertTrue("$days", result.errors.none { "healthConnectImportDays" in it })
        }
    }

    @Test
    fun `import period above 30 days warns about the history permission`() {
        val result = MbPulseConfigValidator.validate(
            config(hcSources = listOf(hcSource("hr"))).copy(healthConnectImportDays = 90)
        )
        assertTrue(result.warnings.any { "READ_HEALTH_DATA_HISTORY" in it })
    }

    // ---- study-signing key ------------------------------------------------------------

    @Test
    fun `missing study-signing key is a warning`() {
        val result = MbPulseConfigValidator.validate(config(studyKey = null))
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.any { "studySigningPublicKeyB64" in it && "NOT verified" in it })
    }

    @Test
    fun `garbage study-signing key is an error`() {
        val result = MbPulseConfigValidator.validate(config(studyKey = "nope!!"))
        assertTrue(result.errors.any { "studySigningPublicKeyB64" in it && "AGENT_BRIEF" in it })
    }

    @Test
    fun `study-signing key reused as receipt key is an error`() {
        val result = MbPulseConfigValidator.validate(config(receiptKey = STUDY_KEY))
        assertTrue(result.errors.any { "equals deletionReceiptPublicKeyB64" in it })
    }

    // ---- collection mode ------------------------------------------------------------

    @Test
    fun `screen defaults follow the mode`() {
        assertEquals(CollectionScreenAccess.DEBUG_BUILDS_ONLY, config().collectionScreenAccess)
        assertEquals(CollectionScreenAccess.DEBUG_BUILDS_ONLY,
            config(mode = CollectionMode.PASSIVE_HEALTH).collectionScreenAccess)
        assertEquals(CollectionScreenAccess.ALWAYS,
            config(mode = CollectionMode.SESSION).collectionScreenAccess)
    }

    @Test
    fun `session mode without a reachable collection screen is an error`() {
        for (screen in listOf(CollectionScreenAccess.NEVER, CollectionScreenAccess.DEBUG_BUILDS_ONLY)) {
            val result = MbPulseConfigValidator.validate(config(mode = CollectionMode.SESSION, screen = screen))
            assertTrue(result.errors.any { "SESSION" in it && "collectionScreen" in it })
        }
        assertTrue(MbPulseConfigValidator.validate(config(mode = CollectionMode.SESSION)).errors.isEmpty())
    }

    @Test
    fun `passive mode with the screen always reachable is a warning`() {
        val result = MbPulseConfigValidator.validate(config(screen = CollectionScreenAccess.ALWAYS))
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.any { "DEBUG_BUILDS_ONLY" in it })
        assertTrue(MbPulseConfigValidator.validate(config(screen = CollectionScreenAccess.NEVER)).warnings.isEmpty())
    }
}
