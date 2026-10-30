package pt.ua.mbpulse.ui.screens.questionnaire

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.RecordAvailability
import pt.ua.mbpulse.ui.components.TaskCard
import pt.ua.mbpulse.ui.navigation.LocalMbPulseNavigator
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel

/**
 * Keeps [viewModel] in step with the enrolled study: refreshes the
 * questionnaires (server, then cache) on every resume. Used by the library's
 * home screen and by [QuestionnaireCards].
 */
@Composable
internal fun ObserveQuestionnaires(viewModel: QuestionnairesViewModel) {
    val activity = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel = hiltViewModel(activity)
    val loadingState by loadingViewModel.state.collectAsStateWithLifecycle()
    val ready = (loadingState as? LoadingState.Ready)?.takeIf { it.onboardingComplete }
    LifecycleResumeEffect(ready?.studyId, ready?.consentedTypeIds) {
        ready?.let { viewModel.refresh(it.studyId, it.consentedTypeIds) }
        onPauseOrDispose { }
    }
}

/** One home card per [QuestionnaireEntry]: title + "Ready" / "Done · next from …" / its description. */
@Composable
internal fun QuestionnaireCard(entry: QuestionnaireEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val q = entry.questionnaire
    val subtitle = when (val a = entry.availability) {
        is RecordAvailability.TooSoon ->
            stringResource(R.string.mbpulse_questionnaire_card_done, nextTimeText(context, a.nextAllowedAtMs))
        else -> q.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mbpulse_questionnaire_card_due)
    }
    TaskCard(
        title    = q.title,
        subtitle = subtitle,
        icon     = Icons.AutoMirrored.Filled.Assignment,
        onClick  = onClick,
        modifier = modifier
    )
}

/**
 * The study's questionnaires as cards, ready to drop into a custom home
 * screen (the library's home screen shows them already). Inside
 * `MbPulseNavHost` only; renders nothing when there are none.
 */
@Composable
public fun QuestionnaireCards(modifier: Modifier = Modifier) {
    QuestionnaireCards(modifier, hiltViewModel())
}

@Composable
internal fun QuestionnaireCards(modifier: Modifier, viewModel: QuestionnairesViewModel) {
    ObserveQuestionnaires(viewModel)
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val navigator = LocalMbPulseNavigator.current
    if (entries.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { entry ->
            QuestionnaireCard(entry, onClick = { navigator.openQuestionnaire(entry.questionnaire.questionnaireId) })
        }
    }
}
