package pt.ua.mbpulse

import java.util.Locale
import java.util.UUID

/**
 * Turns on the library's **device-selection step** for a study that collects
 * from a Bluetooth LE wearable ([MbPulseConfig.deviceSelection]).
 *
 * After onboarding, the participant picks their device in Android's own
 * device chooser (Companion Device Manager — no scan or location permission,
 * a system dialog the participant recognises). The choice is remembered per
 * enrolment and read by the host's [pt.ua.mbpulse.collection.SensorSource]
 * through [pt.ua.mbpulse.collection.SelectedDevice]; the home
 * screen shows it with "Change". Until a device is chosen the step is shown
 * on every app start ("Not now" postpones it).
 *
 * The library only selects the device — connecting to it and decoding its
 * data stays in the host's sensor source (the library is sensor-agnostic;
 * `template-app/.../BleSensorSource.kt` is the base example).
 *
 * Host manifest: `BLUETOOTH_CONNECT` (requested by the step on Android 12+,
 * needed to connect, and the connectedDevice prerequisite on Android 14+);
 * `BLUETOOTH` and `BLUETOOTH_ADMIN` with `android:maxSdkVersion="30"`, their
 * install-time equivalents on Android 11 and lower;
 * optionally `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
 * and `REQUEST_COMPANION_RUN_IN_BACKGROUND`, which an associated companion
 * app holds to restart collection from the background.
 *
 * @param serviceUuids only devices advertising one of these GATT services are
 *   offered, e.g. `listOf(bluetoothUuid(0x180D))` for heart-rate sensors.
 *   Empty = every Bluetooth LE device nearby.
 * @param namePattern optional regular expression the advertised name must
 *   match, e.g. `"Polar H10.*"`.
 */
public data class DeviceSelection(
    val serviceUuids: List<UUID> = emptyList(),
    val namePattern: String? = null,
) {
    public companion object {
        /** A 16-bit Bluetooth SIG id (e.g. `0x180D`, Heart Rate) as a full UUID. */
        public fun bluetoothUuid(shortId: Int): UUID =
            UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", shortId))
    }
}
