package pt.ua.mbpulse.collection

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import android.os.ParcelUuid
import pt.ua.mbpulse.DeviceSelection
import pt.ua.mbpulse.logging.Log
import java.util.regex.Pattern

private const val TAG = "DeviceAssociation"

/**
 * The device chooser of the device-selection step, through Android's
 * Companion Device Manager: the system scans and shows the matching devices,
 * so the app needs no scan or location permission, and an associated app may
 * restart its foreground service from the background.
 *
 * Flow: [associate] → the system hands an [IntentSender] (the chooser) to
 * launch → the participant picks → API 33+: `onAssociationCreated`; below 33:
 * the device arrives in the chooser's result ([fromResult]).
 */
internal object DeviceAssociation {

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** One BLE filter per service UUID (any of them matches); none = every BLE device. */
    fun request(selection: DeviceSelection): AssociationRequest {
        val name = selection.namePattern?.let { Pattern.compile(it) }
        val builder = AssociationRequest.Builder().setSingleDevice(false)
        val uuids = selection.serviceUuids.ifEmpty { listOf(null) }
        uuids.forEach { uuid ->
            val scan = ScanFilter.Builder().apply { uuid?.let { setServiceUuid(ParcelUuid(it)) } }.build()
            val filter = BluetoothLeDeviceFilter.Builder().setScanFilter(scan)
            name?.let { filter.setNamePattern(it) }
            builder.addDeviceFilter(filter.build())
        }
        return builder.build()
    }

    /**
     * Opens the chooser: [onChooser] receives the [IntentSender] to launch;
     * on API 33+ [onCreated] receives the chosen device. [onFailure] when the
     * system cannot offer one (no device found, Bluetooth off, …).
     *
     * [activity]: the manager must come from an Activity — below API 33
     * `CompanionDeviceManager.associate` casts its context to `Activity`
     * (an application context throws ClassCastException).
     */
    fun associate(
        activity: Activity,
        selection: DeviceSelection,
        onChooser: (IntentSender) -> Unit,
        onCreated: (DeviceSelectionStore.Stored) -> Unit,
        onFailure: (CharSequence?) -> Unit,
    ) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
            ?: return onFailure("Companion Device Manager unavailable")
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

            @Deprecated("API < 33")
            override fun onDeviceFound(intentSender: IntentSender) = onChooser(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                val address = associationInfo.deviceMacAddress?.toString()?.uppercase()
                    ?: return onFailure("The chosen device has no Bluetooth address")
                onCreated(DeviceSelectionStore.Stored(address, associationInfo.displayName?.toString(), associationInfo.id))
            }

            override fun onFailure(error: CharSequence?) = onFailure.invoke(error)
        }
        val request = request(selection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, activity.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, callback, null)
        }
    }

    /** API < 33: the device the participant picked, from the chooser's result. */
    @SuppressLint("MissingPermission")   // the name is optional — a SecurityException leaves it null
    fun fromResult(data: Intent?): DeviceSelectionStore.Stored? {
        @Suppress("DEPRECATION")
        return when (val picked = data?.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> DeviceSelectionStore.Stored(
                address = picked.device.address.uppercase(),
                name    = picked.scanRecord?.deviceName ?: runCatching { picked.device.name }.getOrNull()
            )
            is BluetoothDevice -> DeviceSelectionStore.Stored(
                address = picked.address.uppercase(),
                name    = runCatching { picked.name }.getOrNull()
            )
            else -> null
        }
    }

    /** Ends the association (device changed or forgotten, withdrawal). Best effort. */
    fun forget(context: Context, stored: DeviceSelectionStore.Stored) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            val id = stored.associationId
            if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.disassociate(id)
            else @Suppress("DEPRECATION") cdm.disassociate(stored.address)
        }.onFailure { Log.w(TAG, "Could not end the device association: ${it.message}") }
    }
}
