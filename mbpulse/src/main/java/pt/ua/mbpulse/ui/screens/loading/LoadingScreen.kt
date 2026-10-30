package pt.ua.mbpulse.ui.screens.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LoadingScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToStudySelection: () -> Unit,
    viewModel: LoadingViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Navigate as soon as state is Ready — the composable does not need to
    // render anything in that case; LaunchedEffect fires before the next frame.
    LaunchedEffect(state) {
        when (val s = state) {
            is LoadingState.NoStudySelected -> onNavigateToStudySelection()
            is LoadingState.Ready -> {
                if (s.onboardingComplete) onNavigateToHome()
                else onNavigateToOnboarding()
            }
            else -> Unit
        }
    }

    when (val s = state) {
        is LoadingState.InProgress, is LoadingState.Ready, is LoadingState.NoStudySelected -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is LoadingState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.mbpulse_loading_failed),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.retryLoad() }) {
                    Text(stringResource(R.string.mbpulse_retry))
                }
            }
        }
    }
}