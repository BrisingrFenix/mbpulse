package pt.ua.mbpulse.collection

/**
 * A single timestamped sample from a sensor source.
 *
 * [signals] is a map from signal name to its current value. Signal names are
 * defined by the [SensorSource] implementation and must be consistent across
 * all samples in a session.
 *
 * Values are [Float] since raw sensor output is almost always floating-point.
 * Use null for signals that are not available at this sample's timestamp
 * (e.g. IBI is only present at beat boundaries, not at every 64 Hz tick).
 *
 * Example signal names for an Empatica E4:
 *   "bvp", "acc_x", "acc_y", "acc_z", "eda", "temp", "hr", "ibi"
 */
public data class SensorSample(
    /** UTC timestamp in milliseconds. */
    val timestampMs: Long,
    /** Signal name → current value (null = not available at this instant). */
    val signals: Map<String, Float?>
) {
    /** Convenience accessor — returns null if the signal is absent or null. */
    public fun get(name: String): Float? = signals[name]
}