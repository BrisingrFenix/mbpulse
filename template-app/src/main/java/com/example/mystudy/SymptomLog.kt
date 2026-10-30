package com.example.mystudy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.collection.ParticipantDataRecorder
import pt.ua.mbpulse.collection.RecordAvailability
import pt.ua.mbpulse.collection.RecordResult
import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhSchemaId
import pt.ua.mbpulse.data.model.SchemaDefinition
import pt.ua.mbpulse.data.model.SchemaField
import pt.ua.mbpulse.data.model.SchemaTerminology
import pt.ua.mbpulse.ui.navigation.HostScreen
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WORKED EXAMPLE — a host screen that records participant-entered data.
 *
 * Questionnaires need no app code: publish them in the setup wizard (step 3
 * → Questionnaires) and the library renders them. Write a screen like this
 * one only for input a questionnaire form does not fit — here a one-tap
 * symptom log: an EVENT (instant time frame, "it started now"), logged any
 * number of times a day.
 *
 * Three pieces, none of which touch the library:
 *  1. [schema] — what is stored; registered in `MbPulseConfig.customSchemas`
 *     and mapped in `schemaToConsentType` (`"mystudy:symptom-log" -> "symptom_log"`).
 *  2. [screen] — a [HostScreen] passed to `MbPulseApp(hostScreens = …)`; it
 *     appears as a card on the home screen.
 *  3. [SymptomLogScreen] — saves through the library's [ParticipantDataRecorder]
 *     (consent filter, encryption, upload).
 * The study needs the matching data type (backend/study.example.json,
 * "symptom_log"); without it the screen says it is not consented.
 *
 * TODO(agent): adapt or delete this file and its registrations
 * (`customSchemas`, `schemaToConsentType`, `hostScreens`, the `symptom_*` strings).
 */
object SymptomLog {

    const val SCREEN_ID = "symptom-log"

    val SYMPTOMS = listOf("headache", "dizziness", "nausea", "palpitations", "other")

    val schema = SchemaDefinition(
        id = OmhSchemaId(namespace = "mystudy", name = "symptom-log", version = "1.0"),
        terminology = SchemaTerminology(
            loincCode = null, display = "Symptom log", category = "survey", ucumUnit = null, ucumCode = null
        ),
        fields = listOf(
            SchemaField("symptom", FieldKind.ENUM, values = SYMPTOMS, label = "Symptom"),
            SchemaField("severity", FieldKind.NUMBER, label = "Severity (1-5)"),
            SchemaField("notes", FieldKind.TEXT, label = "Notes")
        ),
        isEvent = true      // a moment, not a period: never limited by samplingSeconds
    )

    val screen = HostScreen(
        id       = SCREEN_ID,
        titleRes = R.string.symptom_title,
        icon     = Icons.Filled.Add,
        subtitle = { symptomCardSubtitle() },
        content  = { onBack -> SymptomLogScreen(onBack = onBack) }
    )
}

@Composable
private fun symptomCardSubtitle(): String? {
    val context = LocalContext.current
    val availability by produceState<RecordAvailability?>(null) {
        value = MbPulseLibrary.recorder(context).availability(SymptomLog.schema.schemaName)
    }
    return when (availability) {
        RecordAvailability.NotConsented -> stringResource(R.string.symptom_card_not_consented)
        null, RecordAvailability.NotEnrolled -> null
        else -> stringResource(R.string.symptom_card)
    }
}

sealed interface SymptomLogState {
    data object Form : SymptomLogState
    data object Saving : SymptomLogState
    data class Finished(val result: RecordResult) : SymptomLogState
}

@HiltViewModel
class SymptomLogViewModel @Inject constructor(
    private val recorder: ParticipantDataRecorder
) : ViewModel() {

    private val _state = MutableStateFlow<SymptomLogState>(SymptomLogState.Form)
    val state: StateFlow<SymptomLogState> = _state

    var symptom by mutableStateOf<String?>(null)
    var severity by mutableStateOf<Int?>(null)
    var notes by mutableStateOf("")

    fun submit() {
        val s = symptom ?: return
        _state.value = SymptomLogState.Saving
        viewModelScope.launch {
            val point = SymptomLog.schema.dataPoint(
                sourceName = "MyStudy symptom log",
                startMs    = System.currentTimeMillis(),      // event: the instant it was logged
                values     = mapOf("symptom" to s, "severity" to severity,
                                   "notes" to notes.trim().ifEmpty { null }),
                modality   = "self-reported"
            )
            _state.value = SymptomLogState.Finished(recorder.record(point))
        }
    }

    fun reset() {
        symptom = null; severity = null; notes = ""
        _state.value = SymptomLogState.Form
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SymptomLogScreen(onBack: () -> Unit, viewModel: SymptomLogViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.symptom_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.symptom_back))
                    }
                },
                colors = mbPulseTopAppBarColors()      // same bar style as the library screens
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                SymptomLogState.Saving -> CircularProgressIndicator()
                is SymptomLogState.Finished -> {
                    Text(stringResource(when (s.result) {
                        is RecordResult.Recorded, RecordResult.Duplicate -> R.string.symptom_saved
                        is RecordResult.NotConsented -> R.string.symptom_not_consented
                        RecordResult.NotEnrolled -> R.string.symptom_not_enrolled
                        else -> R.string.symptom_failed
                    }), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::reset) { Text(stringResource(R.string.symptom_log_another)) }
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.symptom_close)) }
                }
                SymptomLogState.Form -> {
                    Text(stringResource(R.string.symptom_q_which), style = MaterialTheme.typography.titleMedium)
                    val labels = stringArrayResource(R.array.symptom_names)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymptomLog.SYMPTOMS.forEachIndexed { i, code ->
                            FilterChip(selected = viewModel.symptom == code, onClick = { viewModel.symptom = code },
                                label = { Text(labels[i]) })
                        }
                    }
                    Text(stringResource(R.string.symptom_q_severity), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { n ->
                            FilterChip(selected = viewModel.severity == n,
                                onClick = { viewModel.severity = if (viewModel.severity == n) null else n },
                                label = { Text(n.toString()) })
                        }
                    }
                    OutlinedTextField(
                        value = viewModel.notes, onValueChange = { viewModel.notes = it.take(300) },
                        label = { Text(stringResource(R.string.symptom_notes)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Button(onClick = viewModel::submit, enabled = viewModel.symptom != null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.symptom_submit))
                    }
                }
            }
        }
    }
}
