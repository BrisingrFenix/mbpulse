package pt.ua.mbpulse.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Connection state of the wearable, as the host's sensor source reports it ([DeviceConnection.report]). */
public enum class DeviceConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Whether the chosen device ([SelectedDevice]) is connected right now — shown
 * on the home screen's device card and the device screen.
 *
 * Two inputs: the host's sensor source **reports** its link state (only it
 * knows the device protocol — e.g. CONNECTING when it opens the connection,
 * CONNECTED once data is subscribed, DISCONNECTED when the link drops or the
 * stream is closed); the library itself records when the last sample arrived
 * from the sensor stream. A source that never reports still gets "Receiving
 * data" / "Last data … ago" from the samples.
 *
 * In memory, per process: collection and UI share the app process, and a new
 * process starts without a state until the source reports again.
 */
public object DeviceConnection {

    /** Called by the host's sensor source whenever its link state changes. Thread-safe. */
    public fun report(state: DeviceConnectionState) {
        val now = System.currentTimeMillis()
        _status.update { if (it.state == state) it else it.copy(state = state, sinceMs = now) }
    }

    internal data class Status(
        /** Last reported state; null = the source never reported. */
        val state: DeviceConnectionState? = null,
        val sinceMs: Long? = null,
        val lastSampleAtMs: Long? = null,
    )

    /** What the device card says. */
    internal enum class Display { CONNECTED, CONNECTING, DISCONNECTED, RECEIVING, NO_DATA }

    private val _status = MutableStateFlow(Status())
    internal val status: StateFlow<Status> = _status.asStateFlow()

    /** A sample arrived from the sensor stream (CollectionService). At most one update per second. */
    internal fun onSample(nowMs: Long = System.currentTimeMillis()) {
        val last = _status.value.lastSampleAtMs
        if (last == null || nowMs - last >= 1_000L) _status.update { it.copy(lastSampleAtMs = nowMs) }
    }

    /** The sensor stream ended or collection stopped: a reported link is gone with it. */
    internal fun onStreamEnded(nowMs: Long = System.currentTimeMillis()) {
        _status.update {
            if (it.state == null || it.state == DeviceConnectionState.DISCONNECTED) it
            else it.copy(state = DeviceConnectionState.DISCONNECTED, sinceMs = nowMs)
        }
    }

    /** Pure: the reported state wins; without one, recent samples (≤ [RECEIVING_WINDOW_MS]) mean receiving. */
    internal fun display(status: Status, nowMs: Long): Display = when (status.state) {
        DeviceConnectionState.CONNECTED    -> Display.CONNECTED
        DeviceConnectionState.CONNECTING   -> Display.CONNECTING
        DeviceConnectionState.DISCONNECTED -> Display.DISCONNECTED
        null -> status.lastSampleAtMs
            ?.takeIf { nowMs - it <= RECEIVING_WINDOW_MS }
            ?.let { Display.RECEIVING } ?: Display.NO_DATA
    }

    internal const val RECEIVING_WINDOW_MS: Long = 2 * 60_000L

    /** Tests only. */
    internal fun resetForTest() { _status.value = Status() }
}
