package pt.ua.mbpulse.collection

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import pt.ua.mbpulse.CollectionMode
import pt.ua.mbpulse.CollectionScreenAccess
import pt.ua.mbpulse.MbPulseLibrary

/**
 * What Android requires before the collection service may start as the
 * foreground-service type of a [CollectionMode], checked up front so a missing
 * permission becomes an actionable message instead of a SecurityException /
 * "did not call startForeground" crash.
 *
 * [problems] is pure (no Android calls) so the rules are unit-testable;
 * [check] gathers the facts from the PackageManager.
 */
internal object CollectionRequirements {

    /** The `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` value a mode runs as. */
    fun serviceType(mode: CollectionMode): Int = when (mode) {
        CollectionMode.PASSIVE_CONNECTED_DEVICE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        CollectionMode.PASSIVE_HEALTH           -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        CollectionMode.SESSION                  -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private const val P = "android.permission."

    /** Manifest permission each service type needs (API 34+). */
    private val TYPE_PERMISSION = mapOf(
        CollectionMode.PASSIVE_CONNECTED_DEVICE to "${P}FOREGROUND_SERVICE_CONNECTED_DEVICE",
        CollectionMode.PASSIVE_HEALTH           to "${P}FOREGROUND_SERVICE_HEALTH",
        CollectionMode.SESSION                  to "${P}FOREGROUND_SERVICE_DATA_SYNC",
    )

    /** connectedDevice prerequisites satisfied by merely declaring the permission. */
    private val CONNECTED_DECLARED = listOf(
        "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "NFC", "TRANSMIT_IR"
    ).map { P + it }
    /** connectedDevice prerequisites that must be granted at runtime. */
    private val CONNECTED_GRANTED = listOf(
        "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "UWB_RANGING"
    ).map { P + it }
    private val HEALTH_GRANTED = listOf("BODY_SENSORS", "ACTIVITY_RECOGNITION").map { P + it }
    private val HEALTH_DECLARED = listOf("${P}HIGH_SAMPLING_RATE_SENSORS")

    /**
     * @param sdk                 device API level.
     * @param declared            permissions in the merged manifest.
     * @param granted             runtime permissions currently granted.
     * @param manifestServiceTypes the collection service's declared
     *                            `foregroundServiceType` bits.
     * @return human-readable problems; empty = the service may start.
     */
    fun problems(
        mode: CollectionMode,
        sdk: Int,
        declared: Set<String>,
        granted: Set<String>,
        manifestServiceTypes: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        val type = serviceType(mode)
        if (sdk >= 29 && manifestServiceTypes and type == 0) {
            out += "CollectionService is not declared with the foreground service type for $mode in the " +
                "manifest — do not override android:foregroundServiceType on it, or include this type."
        }
        if (sdk < 34) return out   // type permissions and prerequisites start at Android 14

        val typePermission = TYPE_PERMISSION.getValue(mode)
        if (typePermission !in declared) {
            out += "$mode needs <uses-permission android:name=\"$typePermission\"/> " +
                "(the library declares it — it was removed from the merged manifest)."
        }
        when (mode) {
            CollectionMode.PASSIVE_CONNECTED_DEVICE ->
                if (CONNECTED_DECLARED.none { it in declared } && CONNECTED_GRANTED.none { it in granted }) {
                    out += "PASSIVE_CONNECTED_DEVICE needs a connected-device prerequisite on Android 14+: " +
                        "for a Bluetooth wearable declare BLUETOOTH_CONNECT and request it at runtime " +
                        "before collection starts; for a network sensor declare CHANGE_NETWORK_STATE " +
                        "(USB devices: UsbManager.requestPermission is not detectable here — declare one " +
                        "of the above as well)."
                }
            CollectionMode.PASSIVE_HEALTH -> {
                val healthConnect = sdk >= 36 && granted.any { it.startsWith("android.permission.health.READ_") }
                if (HEALTH_GRANTED.none { it in granted } && HEALTH_DECLARED.none { it in declared } && !healthConnect) {
                    out += "PASSIVE_HEALTH needs a health prerequisite on Android 14+: BODY_SENSORS or " +
                        "ACTIVITY_RECOGNITION granted at runtime, or HIGH_SAMPLING_RATE_SENSORS declared" +
                        (if (sdk >= 36) ", or a Health Connect read permission granted." else ".")
                }
            }
            CollectionMode.SESSION -> Unit   // dataSync has no runtime prerequisite
        }
        return out
    }

    /** [problems] for this app and device. */
    fun check(context: Context, mode: CollectionMode = MbPulseLibrary.config.collectionMode): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions.orEmpty().toSet()
        val granted = info.requestedPermissions.orEmpty().filterIndexed { i, _ ->
            (info.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        }.toSet()
        val serviceTypes = if (Build.VERSION.SDK_INT >= 29) runCatching {
            pm.getServiceInfo(ComponentName(context, CollectionService::class.java), 0).foregroundServiceType
        }.getOrDefault(0) else 0
        return problems(mode, Build.VERSION.SDK_INT, declared, granted, serviceTypes)
    }

    /** True if this is a debuggable build (the `debug` build type). */
    fun isDebuggableBuild(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Whether the collection screen is reachable in this build. */
    fun collectionScreenEnabled(context: Context): Boolean =
        when (MbPulseLibrary.config.collectionScreenAccess) {
            CollectionScreenAccess.ALWAYS            -> true
            CollectionScreenAccess.DEBUG_BUILDS_ONLY -> isDebuggableBuild(context)
            CollectionScreenAccess.NEVER             -> false
        }
}
