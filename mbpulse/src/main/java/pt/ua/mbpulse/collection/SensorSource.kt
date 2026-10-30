package pt.ua.mbpulse.collection

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over any physical or simulated sensor device.
 *
 * The library's collection pipeline consumes a [Flow<SensorSample>] without
 * knowledge of where the samples originate. Implementations might include:
 *  - A BLE-connected wearable (e.g. Empatica E4, Polar H10)
 *  - A dataset replay (e.g. DREAMT CSV for development and testing)
 *  - A mock that generates synthetic data for UI testing
 *
 * ## Contract
 * - [samples] must be a **cold** flow: a new independent stream is started on
 *   each call to [collect].
 * - The flow completes naturally when the data source is exhausted (e.g. end
 *   of a dataset file), or runs indefinitely for a live device until the
 *   collecting coroutine is cancelled.
 * - Signal names in [SensorSample.signals] must be consistent across all
 *   emissions within a session so that the [SignalProcessor] can accumulate
 *   them correctly.
 *
 * ## Example
 * ```kotlin
 * class MyDeviceSource(private val context: Context) : SensorSource {
 *     override fun samples(): Flow<SensorSample> = callbackFlow {
 *         val device = MyBleDevice(context)
 *         device.onSample = { ts, bvp, hr ->
 *             trySend(SensorSample(ts, mapOf("bvp" to bvp, "hr" to hr)))
 *         }
 *         device.start()
 *         awaitClose { device.stop() }
 *     }
 * }
 * ```
 */
public interface SensorSource {
    public fun samples(): Flow<SensorSample>
}