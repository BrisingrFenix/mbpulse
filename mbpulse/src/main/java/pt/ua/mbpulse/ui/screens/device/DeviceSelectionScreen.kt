package pt.ua.mbpulse.ui.screens.device

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import pt.ua.mbpulse.collection.DeviceConnection
import pt.ua.mbpulse.ui.components.relativeTimeText
import pt.ua.mbpulse.ui.navigation.LocalMbPulseNavigator
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.DeviceAssociation
import pt.ua.mbpulse.collection.DeviceSelectionStore
import pt.ua.mbpulse.collection.PassiveCollection
import pt.ua.mbpulse.collection.SelectedDevice
import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DeviceSelection"

internal enum class DeviceMessage { SAVED, CANCELLED, FAILED, BLUETOOTH_OFF, PERMISSION_DENIED, NOT_SUPPORTED, FORGOTTEN }

internal data class DeviceSelectionUi(
    val searching: Boolean = false,
    val message: DeviceMessage? = null,
    /** The system chooser to launch (consumed by the screen). */
    val chooser: IntentSender? = null,
    /** A device was chosen — the screen closes. */
    val done: Boolean = false,
)

@HiltViewModel
internal class DeviceSelectionViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val selected: StateFlow<SelectedDevice?> = DeviceSelectionStore.observe(context).map { it?.device }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _ui = MutableStateFlow(DeviceSelectionUi())
    val ui: StateFlow<DeviceSelectionUi> = _ui.asStateFlow()

    /** Runtime permissions to request before choosing: connecting needs BLUETOOTH_CONNECT on Android 12+. */
    fun missingPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun onPermissionsResult(activity: Activity, granted: Boolean) {
        if (granted) choose(activity) else _ui.value = DeviceSelectionUi(message = DeviceMessage.PERMISSION_DENIED)
    }

    /**
     * Opens the system device chooser (after [missingPermissions] were
     * granted). [activity] hosts it — see [DeviceAssociation.associate].
     */
    fun choose(activity: Activity) {
        val selection = MbPulseLibrary.config.deviceSelection ?: return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null || !DeviceAssociation.isSupported(context) ->
                return show(DeviceMessage.NOT_SUPPORTED)
            !adapter.isEnabled -> return show(DeviceMessage.BLUETOOTH_OFF)
        }
        _ui.value = DeviceSelectionUi(searching = true)
        runCatching {
            DeviceAssociation.associate(
                activity  = activity,
                selection = selection,
                onChooser = { sender -> _ui.update { it.copy(chooser = sender) } },
                onCreated = ::save,
                onFailure = { error ->
                    Log.i(TAG, "No device offered: $error")
                    show(DeviceMessage.FAILED)
                }
            )
        }.onFailure {
            Log.w(TAG, "Could not open the device chooser: ${it.message}")
            show(DeviceMessage.FAILED)
        }
    }

    fun onChooserLaunched() = _ui.update { it.copy(chooser = null) }

    fun onChooserResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return show(DeviceMessage.CANCELLED)
        // API 33+ reports the device through onAssociationCreated instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            DeviceAssociation.fromResult(data)?.let(::save) ?: show(DeviceMessage.FAILED)
        }
    }

    private fun save(device: DeviceSelectionStore.Stored) {
        viewModelScope.launch {
            val previous = DeviceSelectionStore.current(context)
            DeviceSelectionStore.save(context, device)
            // A replaced device's association is ended — but never the new one
            // (below API 33 an association is identified by its address only).
            if (previous != null && (previous.address != device.address ||
                    (previous.associationId != null && previous.associationId != device.associationId))
            ) DeviceAssociation.forget(context, previous)
            Log.i(TAG, "Device chosen")
            // The permission may have been granted only now — start collection
            // without waiting for the next app start (no-op if it is running).
            PassiveCollection.resume(context)
            _ui.value = DeviceSelectionUi(message = DeviceMessage.SAVED, done = true)
        }
    }

    fun forget() {
        viewModelScope.launch {
            DeviceSelectionStore.current(context)?.let { DeviceAssociation.forget(context, it) }
            DeviceSelectionStore.clear(context)
            show(DeviceMessage.FORGOTTEN)
        }
    }

    private fun show(message: DeviceMessage) {
        _ui.value = DeviceSelectionUi(message = message)
    }
}

/**
 * The device-selection step ([pt.ua.mbpulse.DeviceSelection]):
 * shown after onboarding until a device is chosen, and from the home screen's
 * device card. Library-owned — wording via the `mbpulse_device_*` strings.
 *
 * @param onClose "Not now" / "Done" / back, and after a device was chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSelectionScreen(
    onClose: () -> Unit,
    viewModel: DeviceSelectionViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val activity = LocalActivity.current

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onChooserResult(it.resultCode, it.data)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        activity?.let { viewModel.onPermissionsResult(it, grants.values.all { granted -> granted }) }
    }
    LaunchedEffect(ui.chooser) {
        ui.chooser?.let {
            chooser.launch(IntentSenderRequest.Builder(it).build())
            viewModel.onChooserLaunched()
        }
    }
    LaunchedEffect(ui.done) { if (ui.done) onClose() }

    fun choose() {
        val missing = viewModel.missingPermissions()
        if (missing.isNotEmpty()) permissions.launch(missing)
        else activity?.let(viewModel::choose)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mbpulse_device_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mbpulse_device_back))
                    }
                },
                colors = mbPulseTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.mbpulse_device_intro), style = MaterialTheme.typography.bodyLarge)

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Column {
                        Text(
                            selected?.let { stringResource(R.string.mbpulse_device_current, it.name ?: stringResource(R.string.mbpulse_device_unnamed)) }
                                ?: stringResource(R.string.mbpulse_device_current_none),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (selected != null) {
                            val status by DeviceConnection.status.collectAsState()
                            val nowMs = tickingNow()
                            Text(
                                connectionText(DeviceConnection.display(status, nowMs), status, nowMs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            ui.message?.let { message ->
                Text(
                    stringResource(message.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            if (ui.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.mbpulse_device_opening))
                }
            } else {
                Button(onClick = ::choose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (selected == null) R.string.mbpulse_device_choose else R.string.mbpulse_device_choose_other))
                }
            }
            if (selected != null) {
                OutlinedButton(onClick = viewModel::forget, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mbpulse_device_forget))
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(if (selected == null) R.string.mbpulse_device_not_now else R.string.mbpulse_device_done))
            }
        }
    }
}

private val DeviceMessage.text: Int get() = when (this) {
    DeviceMessage.SAVED             -> R.string.mbpulse_device_saved
    DeviceMessage.CANCELLED         -> R.string.mbpulse_device_cancelled
    DeviceMessage.FAILED            -> R.string.mbpulse_device_failed
    DeviceMessage.BLUETOOTH_OFF     -> R.string.mbpulse_device_bluetooth_off
    DeviceMessage.PERMISSION_DENIED -> R.string.mbpulse_device_permission_denied
    DeviceMessage.NOT_SUPPORTED     -> R.string.mbpulse_device_not_supported
    DeviceMessage.FORGOTTEN         -> R.string.mbpulse_device_forgotten
}

private val DeviceMessage.isProblem: Boolean
    get() = this != DeviceMessage.SAVED && this != DeviceMessage.FORGOTTEN && this != DeviceMessage.CANCELLED

/**
 * The chosen device and whether it is connected ([DeviceConnection]), or a
 * prompt to choose one; tapping opens the device step. Shown on the library's
 * home screen when `MbPulseConfig.deviceSelection` is set — usable in a
 * replaced home screen too (nothing is shown without `deviceSelection`).
 */
@Composable
public fun DeviceStatusCard(modifier: Modifier = Modifier) {
    if (MbPulseLibrary.config.deviceSelection == null) return
    val navigator = LocalMbPulseNavigator.current
    DeviceStatusCard(onClick = { navigator.openDeviceSelection() }, modifier = modifier)
}

@Composable
internal fun DeviceStatusCard(onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val device by remember(context) { SelectedDevice.observe(context) }.collectAsState(initial = null)
    val status by DeviceConnection.status.collectAsState()
    val nowMs = tickingNow()
    val display = DeviceConnection.display(status, nowMs)
    val attention = device == null ||
        (display == DeviceConnection.Display.DISCONNECTED || display == DeviceConnection.Display.NO_DATA)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (attention) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val receiving = display == DeviceConnection.Display.CONNECTED || display == DeviceConnection.Display.RECEIVING
            Icon(
                if (device != null && receiving) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                contentDescription = null
            )
            Column(Modifier.weight(1f)) {
                Text(
                    device?.let { it.name ?: stringResource(R.string.mbpulse_device_unnamed) }
                        ?: stringResource(R.string.mbpulse_device_card_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (device == null) stringResource(R.string.mbpulse_device_card_none)
                    else connectionText(display, status, nowMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (device != null) {
                TextButton(onClick = onClick) { Text(stringResource(R.string.mbpulse_device_card_change)) }
            }
        }
    }
}

/** "Connected · last data 1 min ago", "Not connected since …", … */
@Composable
private fun connectionText(display: DeviceConnection.Display, status: DeviceConnection.Status, nowMs: Long): String {
    val lastData = status.lastSampleAtMs?.let { stringResource(R.string.mbpulse_device_last_data, relativeTimeText(it, nowMs)) }
    return when (display) {
        DeviceConnection.Display.CONNECTED    -> listOfNotNull(stringResource(R.string.mbpulse_device_status_connected), lastData)
        DeviceConnection.Display.CONNECTING   -> listOf(stringResource(R.string.mbpulse_device_status_connecting))
        DeviceConnection.Display.DISCONNECTED -> listOfNotNull(
            status.sinceMs?.let { stringResource(R.string.mbpulse_device_status_disconnected_since, relativeTimeText(it, nowMs)) }
                ?: stringResource(R.string.mbpulse_device_status_disconnected),
            lastData
        )
        DeviceConnection.Display.RECEIVING    -> listOfNotNull(stringResource(R.string.mbpulse_device_status_receiving), lastData)
        DeviceConnection.Display.NO_DATA      -> listOf(lastData ?: stringResource(R.string.mbpulse_device_status_no_data))
    }.joinToString(" · ")
}

/** Wall clock for relative times, ticking every 15 s while shown. */
@Composable
private fun tickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}
