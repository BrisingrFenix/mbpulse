package pt.ua.mbpulse.ui.screens.consent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.theme.LocalMbPulseBranding

// ---------------------------------------------------------------------------
// Entry point — wire-up and state dispatch
// ---------------------------------------------------------------------------

/**
 * Drop this into your nav graph as the consent destination.
 *
 * @param dataTypes        Study data types fetched from Supabase by the caller.
 * @param studyId          Study identifier for the consent record.
 * @param studyName        Display name shown on the intro screen.
 * @param studyVersion     Version string included in the consent record.
 * @param definitionSha256 The study definition shown (its hash), named by the signed consent record.
 * @param participantPseudoId  The pseudonymous identifier already generated.
 * @param onComplete       Called with the finished [ConsentRecord] — caller
 *                         serialises and syncs to Supabase.
 * @param onWithdraw       Called if the participant withdraws at any point.
 * @param introTitle       Intro heading (re-consent: "The study has changed").
 * @param introNote        Optional text shown first on the intro (re-consent: what changed).
 * @param declineLabel     Intro's decline button.
 * @param onPostpone       Shows a "Not now" button on the intro when set (re-consent only).
 */
@Composable
internal fun ConsentFlowScreen(
    dataTypes: List<ConsentDataType>,
    studyId: String,
    studyName: String,
    studyVersion: String,
    definitionSha256: String,
    participantPseudoId: String,
    onComplete: (ConsentRecord) -> Unit,
    onWithdraw: () -> Unit,
    viewModel: ConsentViewModel = viewModel(),
    introTitle: String? = null,
    introNote: String? = null,
    declineLabel: String? = null,
    onPostpone: (() -> Unit)? = null
) {
    LaunchedEffect(dataTypes) {
        viewModel.initialise(dataTypes)
    }

    val uiState by viewModel.uiState.collectAsState()

    AnimatedContent(
        targetState = uiState,
        transitionSpec = {
            (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
        },
        label = "consent_transition"
    ) { state ->
        when (state) {
            is ConsentUiState.Intro -> ConsentIntroScreen(
                studyName = studyName,
                dataTypeCount = dataTypes.size,
                onBegin = { viewModel.startFlow() },
                onWithdraw = onWithdraw,
                title = introTitle ?: stringResource(R.string.mbpulse_consent_title),
                note = introNote,
                declineLabel = declineLabel ?: stringResource(R.string.mbpulse_consent_decline_participation),
                onPostpone = onPostpone
            )

            is ConsentUiState.DataTypeConsent -> DataTypeConsentScreen(
                dataType = state.current,
                index = state.index,
                total = state.total,
                onAccept = { viewModel.onAccept() },
                onDecline = { viewModel.onDecline() }
            )

            is ConsentUiState.EssentialDeclineWarning -> EssentialDeclineWarningScreen(
                dataType = state.dataType,
                onGoBack = { viewModel.onGoBackFromWarning() },
                onWithdraw = onWithdraw
            )

            is ConsentUiState.Summary -> ConsentSummaryScreen(
                decisions = state.decisions,
                onConfirm = {
                    val record = viewModel.buildRecord(
                        studyId, participantPseudoId, studyVersion, definitionSha256
                    )
                    onComplete(record)
                },
                onWithdraw = onWithdraw
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 1 — Intro
// ---------------------------------------------------------------------------

@Composable
private fun ConsentIntroScreen(
    studyName: String,
    dataTypeCount: Int,
    onBegin: () -> Unit,
    onWithdraw: () -> Unit,
    title: String,
    note: String?,
    declineLabel: String,
    onPostpone: (() -> Unit)?
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            val logo = LocalMbPulseBranding.current.logoRes
            if (logo != null) {
                Image(painterResource(logo), contentDescription = null, modifier = Modifier.height(72.dp))
            } else {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = studyName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            note?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.mbpulse_consent_what_next),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    ConsentBullet(
                        pluralStringResource(R.plurals.mbpulse_consent_bullet_categories, dataTypeCount, dataTypeCount)
                    )
                    ConsentBullet(
                        stringResource(R.string.mbpulse_consent_bullet_choice)

                    )
                    ConsentBullet(
                        stringResource(R.string.mbpulse_consent_bullet_essential)

                    )
                    ConsentBullet(
                        stringResource(R.string.mbpulse_consent_bullet_recorded)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.mbpulse_consent_rights),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    ConsentBullet(stringResource(R.string.mbpulse_consent_right_withdraw))
                    ConsentBullet(
                        stringResource(R.string.mbpulse_consent_right_deletion)

                    )
                    ConsentBullet(
                        stringResource(R.string.mbpulse_consent_right_access)

                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onBegin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.mbpulse_consent_review_categories))
            }

            onPostpone?.let {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = it,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.mbpulse_not_now))
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = onWithdraw,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    declineLabel,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 2 — Per-data-type consent
// ---------------------------------------------------------------------------

@Composable
private fun DataTypeConsentScreen(
    dataType: ConsentDataType,
    index: Int,
    total: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Progress bar
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.mbpulse_consent_category_progress, index + 1, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dataType.isEssential) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.mbpulse_consent_required),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (index + 1).toFloat() / total.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                Text(
                    text = dataType.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(24.dp))

                ConsentInfoSection(label = stringResource(R.string.mbpulse_consent_what), body = dataType.description)

                Spacer(Modifier.height(20.dp))

                ConsentInfoSection(label = stringResource(R.string.mbpulse_consent_why), body = dataType.purpose)

                Spacer(Modifier.height(20.dp))

                ConsentInfoSection(
                    label = stringResource(R.string.mbpulse_consent_retention),
                    // Enforced server-side by purge_expired_records (backend/retention.sql).
                    body = pluralStringResource(R.plurals.mbpulse_consent_retention_body, dataType.retentionDays, dataType.retentionDays)
                )

                dataType.samplingSeconds?.let { seconds ->
                    Spacer(Modifier.height(20.dp))
                    ConsentInfoSection(
                        label = stringResource(R.string.mbpulse_consent_sampling),
                        body = when {
                            seconds % 3600 == 0 -> pluralStringResource(R.plurals.mbpulse_consent_sampling_hours, seconds / 3600, seconds / 3600)
                            seconds % 60 == 0   -> pluralStringResource(R.plurals.mbpulse_consent_sampling_minutes, seconds / 60, seconds / 60)
                            else                -> pluralStringResource(R.plurals.mbpulse_consent_sampling_seconds, seconds, seconds)
                        }
                    )
                }

                if (dataType.isEssential) {
                    Spacer(Modifier.height(20.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.mbpulse_consent_essential_warning),

                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            // Action buttons — pinned to the bottom
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mbpulse_consent_decline))
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mbpulse_consent_accept))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 3 — Essential decline warning
// ---------------------------------------------------------------------------

@Composable
private fun EssentialDeclineWarningScreen(
    dataType: ConsentDataType,
    onGoBack: () -> Unit,
    onWithdraw: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                stringResource(R.string.mbpulse_consent_required_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.mbpulse_consent_required_text, dataType.name),

                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onGoBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.mbpulse_consent_reconsider))
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onWithdraw,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.mbpulse_withdraw_menu))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 4 — Summary and final confirmation
// ---------------------------------------------------------------------------

@Composable
private fun ConsentSummaryScreen(
    decisions: List<ConsentDecision>,
    onConfirm: () -> Unit,
    onWithdraw: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(32.dp))

                Text(
                    stringResource(R.string.mbpulse_consent_summary_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.mbpulse_consent_summary_intro),

                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                decisions.forEach { decision ->
                    DecisionRow(decision = decision)
                    Divider()
                }

                Spacer(Modifier.height(24.dp))
            }

            // Pinned footer
            Divider()
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.mbpulse_consent_confirm))
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onWithdraw,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.mbpulse_withdraw_menu),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable components
// ---------------------------------------------------------------------------

@Composable
private fun ConsentInfoSection(label: String, body: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ConsentBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DecisionRow(decision: ConsentDecision) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (decision.accepted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (decision.accepted) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = stringResource(if (decision.accepted) R.string.mbpulse_consent_accepted else R.string.mbpulse_consent_declined),
                tint = if (decision.accepted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = decision.dataTypeName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(if (decision.accepted) R.string.mbpulse_consent_accepted else R.string.mbpulse_consent_declined),
                style = MaterialTheme.typography.bodySmall,
                color = if (decision.accepted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}