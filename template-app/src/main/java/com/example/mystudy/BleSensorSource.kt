package com.example.mystudy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import pt.ua.mbpulse.DeviceSelection
import pt.ua.mbpulse.collection.DeviceConnection
import pt.ua.mbpulse.collection.DeviceConnectionState
import pt.ua.mbpulse.collection.SelectedDevice
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SensorSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.UUID

private const val TAG = "BleSensorSource"

/**
 * Base example of a Bluetooth LE [SensorSource]: connects to the device the
 * participant chose in the library's device-selection step ([SelectedDevice];
 * on with `MbPulseConfig.deviceSelection`), subscribes to ONE notifying GATT
 * characteristic and turns each notification into a [SensorSample].
 *
 * It is a starting point, not a driver — the parts a study fills in are
 * marked `TODO(agent)`:
 *  - [SERVICE_UUID] / [CHARACTERISTIC_UUID]: the device's GATT service and the
 *    characteristic that carries the data (device documentation, or a GATT
 *    browser such as nRF Connect). Use the same service in
 *    `DeviceSelection(serviceUuids = …)` so the chooser lists only this kind
 *    of device.
 *  - [decode]: bytes of one notification → signal values for the processor.
 *
 * Kept as is: following the selected device (choosing another one or
 * forgetting it takes effect in the running session), reconnecting with
 * backoff (1 s … 60 s) when the link drops, Bluetooth is off or the
 * permission is missing, strictly increasing timestamps, and reporting the
 * link state to the library ([DeviceConnection.report] — the home screen's
 * device card shows "Connected" / "Connecting…" / "Not connected since …"). Devices that need
 * several characteristics, writes (start commands) or a vendor SDK extend
 * [connectOnce] or replace this class.
 *
 * Needs `BLUETOOTH_CONNECT` (Android 12+) in the manifest — the device step
 * requests it — and, for Android 11 and lower, `BLUETOOTH` / `BLUETOOTH_ADMIN`
 * with `maxSdkVersion="30"` (both in the template manifest's Bluetooth block).
 *
 * @param whenNoDevice samples while no device is chosen — e.g. the CSV replay
 *   in debug builds, so the app runs without hardware; default none.
 */
class BleSensorSource(
    private val context: Context,
    private val whenNoDevice: () -> SensorSource? = { null }
) : SensorSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(): Flow<SensorSample> =
        SelectedDevice.observe(context).flatMapLatest { device ->
            if (device != null) reconnecting(device.address)
            else whenNoDevice()?.samples() ?: emptyFlow()
        }

    /**
     * TODO(agent): decode one notification of [CHARACTERISTIC_UUID] into the
     * signals the study's SignalProcessor reads (names must stay the same
     * across the session). Return null to skip a notification (no reading,
     * no skin contact …). Example — the standard Heart Rate Measurement
     * (0x2A37): flags byte, then beats/min as uint8, or uint16 little-endian
     * when flag bit 0 is set:
     * ```kotlin
     * val flags = value[0].toInt() and 0xFF
     * val bpm = if (flags and 0x01 != 0)
     *     (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
     * else value[1].toInt() and 0xFF
     * return mapOf("hr" to bpm.toFloat())
     * ```
     */
    private fun decode(value: ByteArray): Map<String, Float?>? {
        Log.d(TAG, "Notification of ${value.size} bytes — decode() is not implemented yet")
        return null
    }

    /** [connectOnce], reopened after every failure; the backoff resets once readings arrive. */
    private fun reconnecting(address: String): Flow<SensorSample> = flow {
        var attempt = 0
        while (true) {
            var received = false
            val reason = try {
                connectOnce(address).collect { received = true; emit(it) }
                "stream completed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (received) attempt = 0
            val backoffMs = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
            attempt++
            Log.w(TAG, "Device stream ended ($reason) — reconnecting in ${backoffMs / 1000} s")
            delay(backoffMs)
        }
    }

    /** One GATT connection: connect → discover → enable notifications → emit; fails when the link drops. */
    @SuppressLint("MissingPermission")   // a missing permission throws → retried by reconnecting()
    private fun connectOnce(address: String): Flow<SensorSample> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DeviceConnection.report(DeviceConnectionState.DISCONNECTED)
            close(IOException("Bluetooth is off or unavailable"))
            awaitClose { }
            return@callbackFlow
        }
        DeviceConnection.report(DeviceConnectionState.CONNECTING)

        var lastTimestampMs = 0L
        fun onNotification(value: ByteArray) {
            val signals = decode(value) ?: return
            // Timestamps must increase strictly within a session.
            val ts = maxOf(System.currentTimeMillis(), lastTimestampMs + 1).also { lastTimestampMs = it }
            trySend(SensorSample(ts, signals))
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> close(IOException("GATT error (status $status)"))
                    newState == BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    newState == BluetoothProfile.STATE_DISCONNECTED -> close(IOException("Device disconnected"))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                val cccd = characteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || cccd == null) {
                    close(IOException("The device has no notifying $CHARACTERISTIC_UUID in service $SERVICE_UUID"))
                    return
                }
                gatt.setCharacteristicNotification(characteristic, true)
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, enable)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = enable
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Notifications enabled")
                    DeviceConnection.report(DeviceConnectionState.CONNECTED)
                } else {
                    close(IOException("Could not enable notifications (status $status)"))
                }
            }

            // API 33+
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) onNotification(value)
            }

            // API < 33
            @Deprecated("Replaced by the overload with value on API 33+")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == CHARACTERISTIC_UUID) onNotification(value)
            }
        }

        // autoConnect = true: waits for the wearable to come into range instead of timing out.
        val gatt = adapter.getRemoteDevice(address).connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) close(IOException("Could not open a GATT connection"))
        awaitClose {
            DeviceConnection.report(DeviceConnectionState.DISCONNECTED)
            gatt?.disconnect()
            gatt?.close()
        }
    }

    companion object {
        // TODO(agent): the study device's GATT service and data characteristic.
        // The values below are the standard Heart Rate service (0x180D) and
        // Heart Rate Measurement (0x2A37) — replace them for any other device.
        val SERVICE_UUID: UUID = DeviceSelection.bluetoothUuid(0x180D)
        val CHARACTERISTIC_UUID: UUID = DeviceSelection.bluetoothUuid(0x2A37)

        /** Standard descriptor that switches notifications on. */
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID = DeviceSelection.bluetoothUuid(0x2902)
    }
}
