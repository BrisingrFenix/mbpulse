package pt.ua.mbpulse.collection

import pt.ua.mbpulse.CollectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRequirementsTest {

    private val p = "android.permission."
    private val libraryDeclared = setOf(
        "${p}FOREGROUND_SERVICE_CONNECTED_DEVICE", "${p}FOREGROUND_SERVICE_HEALTH", "${p}FOREGROUND_SERVICE_DATA_SYNC"
    )
    /** connectedDevice | health | dataSync, as the library manifest declares. */
    private val allTypes = CollectionMode.entries.fold(0) { acc, m -> acc or CollectionRequirements.serviceType(m) }

    private fun problems(
        mode: CollectionMode,
        sdk: Int = 34,
        declared: Set<String> = libraryDeclared,
        granted: Set<String> = emptySet(),
        types: Int = allTypes,
    ) = CollectionRequirements.problems(mode, sdk, declared, granted, types)

    @Test
    fun `session mode needs nothing beyond the library manifest`() {
        assertEquals(emptyList<String>(), problems(CollectionMode.SESSION))
    }

    @Test
    fun `connected device without a prerequisite is reported with what to add`() {
        val out = problems(CollectionMode.PASSIVE_CONNECTED_DEVICE)
        assertEquals(1, out.size)
        assertTrue("BLUETOOTH_CONNECT" in out[0] && "CHANGE_NETWORK_STATE" in out[0])
    }

    @Test
    fun `connected device prerequisites - declared network permission or granted bluetooth`() {
        assertTrue(problems(CollectionMode.PASSIVE_CONNECTED_DEVICE,
            declared = libraryDeclared + "${p}CHANGE_NETWORK_STATE").isEmpty())
        assertTrue(problems(CollectionMode.PASSIVE_CONNECTED_DEVICE,
            granted = setOf("${p}BLUETOOTH_CONNECT")).isEmpty())
        // Declared but not granted is not enough for a runtime permission.
        assertEquals(1, problems(CollectionMode.PASSIVE_CONNECTED_DEVICE,
            declared = libraryDeclared + "${p}BLUETOOTH_CONNECT").size)
    }

    @Test
    fun `health prerequisites`() {
        assertEquals(1, problems(CollectionMode.PASSIVE_HEALTH).size)
        assertTrue(problems(CollectionMode.PASSIVE_HEALTH, granted = setOf("${p}BODY_SENSORS")).isEmpty())
        assertTrue(problems(CollectionMode.PASSIVE_HEALTH,
            declared = libraryDeclared + "${p}HIGH_SAMPLING_RATE_SENSORS").isEmpty())
        // A Health Connect read permission only counts from Android 16.
        val hc = setOf("android.permission.health.READ_HEART_RATE")
        assertEquals(1, problems(CollectionMode.PASSIVE_HEALTH, sdk = 35, granted = hc).size)
        assertTrue(problems(CollectionMode.PASSIVE_HEALTH, sdk = 36, granted = hc).isEmpty())
    }

    @Test
    fun `before Android 14 there are no type prerequisites`() {
        assertTrue(problems(CollectionMode.PASSIVE_CONNECTED_DEVICE, sdk = 33).isEmpty())
        assertTrue(problems(CollectionMode.PASSIVE_HEALTH, sdk = 33).isEmpty())
    }

    @Test
    fun `removed type permission and missing manifest type are reported`() {
        val out = problems(CollectionMode.SESSION,
            declared = libraryDeclared - "${p}FOREGROUND_SERVICE_DATA_SYNC",
            types = CollectionRequirements.serviceType(CollectionMode.PASSIVE_HEALTH))
        assertEquals(2, out.size)
        assertTrue(out.any { "FOREGROUND_SERVICE_DATA_SYNC" in it })
        assertTrue(out.any { "foregroundServiceType" in it })
    }
}
