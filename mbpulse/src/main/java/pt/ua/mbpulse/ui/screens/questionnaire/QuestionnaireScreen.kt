package pt.ua.mbpulse.ui.screens.questionnaire

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.RecordAvailability
import pt.ua.mbpulse.collection.RecordResult
import pt.ua.mbpulse.data.model.Questionnaire
import pt.ua.mbpulse.data.model.QuestionnaireItem
import pt.ua.mbpulse.data.model.QuestionnaireItemType
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import kotlin.math.roundToInt

/**
 * Library-owned form for a backend questionnaire ([Questionnaire]): one
 * section per item, rendered by type. Saving goes through
 * [pt.ua.mbpulse.collection.ParticipantDataRecorder] (consent,
 * frequency, encryption, upload).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestionnaireScreen(
    questionnaireId: String,
    onBack: () -> Unit,
    viewModel: QuestionnaireViewModel = hiltViewModel()
) {
    LaunchedEffect(questionnaireId) { viewModel.load(questionnaireId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = (state as? QuestionnaireFormState.Form)?.questionnaire?.title
        ?: pt.ua.mbpulse.data.model.QuestionnaireRegistry.find(questionnaireId)?.title.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mbpulse_questionnaire_back))
                    }
                },
                colors = mbPulseTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val close = stringResource(R.string.mbpulse_questionnaire_close)
            when (val s = state) {
                QuestionnaireFormState.Loading, QuestionnaireFormState.Saving ->
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                is QuestionnaireFormState.Form -> Form(s.questionnaire, viewModel)
                is QuestionnaireFormState.Unavailable -> Message(unavailableText(s.availability), close, onBack)
                is QuestionnaireFormState.Finished -> when (val r = s.result) {
                    is RecordResult.Recorded, RecordResult.Duplicate ->
                        Message(stringResource(R.string.mbpulse_questionnaire_saved), close, onBack)
                    is RecordResult.Failed ->
                        Message(stringResource(R.string.mbpulse_questionnaire_failed),
                            stringResource(R.string.mbpulse_questionnaire_retry), viewModel::retry)
                    is RecordResult.NotConsented -> Message(unavailableText(RecordAvailability.NotConsented), close, onBack)
                    is RecordResult.TooSoon -> Message(unavailableText(RecordAvailability.TooSoon(r.nextAllowedAtMs)), close, onBack)
                    RecordResult.NotEnrolled -> Message(unavailableText(RecordAvailability.NotEnrolled), close, onBack)
                }
            }
        }
    }
}

@Composable
private fun Form(questionnaire: Questionnaire, viewModel: QuestionnaireViewModel) {
    questionnaire.intro?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge)
    }
    val invalid = if (viewModel.showErrors) viewModel.invalidItems().toSet() else emptySet()
    questionnaire.items.forEach { item ->
        Item(item, viewModel.answers[item.id], item.id in invalid) { viewModel.answer(item.id, it) }
    }
    Button(onClick = viewModel::submit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.mbpulse_questionnaire_submit))
    }
    if (invalid.isNotEmpty()) {
        Text(stringResource(R.string.mbpulse_questionnaire_incomplete),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.mbpulse_questionnaire_encrypted_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Item(item: QuestionnaireItem, value: Any?, invalid: Boolean, onAnswer: (Any?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.text, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (item.required) R.string.mbpulse_questionnaire_required else R.string.mbpulse_questionnaire_optional),
            style = MaterialTheme.typography.labelSmall,
            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (item.type) {
            QuestionnaireItemType.SCALE -> Scale(item, value as? Int, onAnswer)
            QuestionnaireItemType.NUMBER -> NumberField(item, invalid, onAnswer)
            QuestionnaireItemType.CHOICE -> item.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = value == option.value, role = Role.RadioButton,
                        onClick = { onAnswer(if (value == option.value && !item.required) null else option.value) }
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = value == option.value, onClick = null)
                    Text(option.label, Modifier.padding(start = 12.dp))
                }
            }
            QuestionnaireItemType.MULTICHOICE -> {
                @Suppress("UNCHECKED_CAST") val chosen = (value as? Set<String>).orEmpty()
                Text(stringResource(R.string.mbpulse_questionnaire_select_all), style = MaterialTheme.typography.bodySmall)
                item.options.forEach { option ->
                    val checked = option.value in chosen
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked, role = Role.Checkbox,
                            onValueChange = { on -> onAnswer(if (on) chosen + option.value else chosen - option.value) }
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(option.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
            QuestionnaireItemType.TEXT -> {
                val text = value as? String ?: ""
                OutlinedTextField(
                    value = text,
                    onValueChange = { onAnswer(it.take(item.textLimit)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 6,
                    isError = invalid,
                    supportingText = { Text(stringResource(R.string.mbpulse_questionnaire_text_count, text.length, item.textLimit)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default)
                )
            }
        }
    }
}

/** Up to 11 points as chips (0–10 fits a phone), larger ranges as a slider. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Scale(item: QuestionnaireItem, value: Int?, onAnswer: (Any?) -> Unit) {
    val range = item.scaleRange
    if (range.last - range.first <= 10) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            range.forEach { n ->
                FilterChip(selected = value == n, onClick = { onAnswer(n) }, label = { Text(n.toString()) })
            }
        }
    } else {
        Text(value?.toString() ?: stringResource(R.string.mbpulse_questionnaire_choose_value),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Slider(
            value = (value ?: (range.first + range.last) / 2).toFloat(),
            onValueChange = { onAnswer(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
    if (item.minLabel != null || item.maxLabel != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${range.first} · ${item.minLabel.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("${range.last} · ${item.maxLabel.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberField(item: QuestionnaireItem, invalid: Boolean, onAnswer: (Any?) -> Unit) {
    var text by rememberSaveable(item.id) { mutableStateOf("") }
    val hint = when {
        item.min != null && item.max != null -> stringResource(R.string.mbpulse_questionnaire_number_range, fmt(item.min), fmt(item.max))
        item.min != null -> stringResource(R.string.mbpulse_questionnaire_number_min, fmt(item.min))
        item.max != null -> stringResource(R.string.mbpulse_questionnaire_number_max, fmt(item.max))
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            // Unparseable input is kept as NaN so it counts as invalid, not as unanswered.
            onAnswer(t.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: Double.NaN })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid,
        suffix = item.unit?.let { { Text(it) } },
        supportingText = hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
    )
}

private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge)
    OutlinedButton(onClick = onAction) { Text(action) }
}

@Composable
private fun unavailableText(availability: RecordAvailability?): String = when (availability) {
    null, RecordAvailability.Available -> stringResource(R.string.mbpulse_questionnaire_unavailable)
    RecordAvailability.NotConsented -> stringResource(R.string.mbpulse_questionnaire_not_consented)
    RecordAvailability.NotEnrolled -> stringResource(R.string.mbpulse_questionnaire_not_enrolled)
    is RecordAvailability.TooSoon ->
        stringResource(R.string.mbpulse_questionnaire_too_soon, nextTimeText(LocalContext.current, availability.nextAllowedAtMs))
}

internal fun nextTimeText(context: android.content.Context, atMs: Long): String =
    DateUtils.formatDateTime(
        context, atMs,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_WEEKDAY
    )
