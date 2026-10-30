package pt.ua.mbpulse.collection

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The device the participant chose in the device-selection step
 * ([pt.ua.mbpulse.DeviceSelection]). A sensor source reads it to
 * know what to connect to — [observe] it so choosing another device (or
 * forgetting it) takes effect in the running collection session.
 *
 * Kept with the enrolment: cleared on withdrawal and reset.
 *
 * @param address Bluetooth address, upper case (`BluetoothAdapter.getRemoteDevice`).
 * @param name the name the device advertised, if any.
 */
public data class SelectedDevice(
    public val address: String,
    public val name: String?,
) {
    public companion object {
        /** The chosen device, and every change to it; null while none is chosen. */
        public fun observe(context: Context): Flow<SelectedDevice?> =
            DeviceSelectionStore.observe(context).map { it?.device }.distinctUntilChanged()

        /** The chosen device right now, or null. */
        public suspend fun current(context: Context): SelectedDevice? = observe(context).first()
    }
}

/** Persistence of the [SelectedDevice], in the enrolment store (so withdrawal clears it). */
internal object DeviceSelectionStore {

    private const val KEY = "selected_device"
    private val json = Json { ignoreUnknownKeys = true }

    /** What is stored: the device plus its Companion Device Manager association (API 33+). */
    @Serializable
    data class Stored(val address: String, val name: String? = null, val associationId: Int? = null) {
        val device: SelectedDevice get() = SelectedDevice(address, name)
    }

    fun observe(context: Context): Flow<Stored?> =
        EnrollmentStore.observeString(context, KEY).map { raw ->
            raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        }.distinctUntilChanged()

    suspend fun current(context: Context): Stored? = observe(context).first()

    suspend fun save(context: Context, stored: Stored) {
        EnrollmentStore.put(context, strings = mapOf(KEY to json.encodeToString(Stored.serializer(), stored)))
    }

    suspend fun clear(context: Context) {
        EnrollmentStore.put(context, strings = mapOf(KEY to null))
    }
}
