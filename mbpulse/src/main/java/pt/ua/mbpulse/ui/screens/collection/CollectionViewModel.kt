package pt.ua.mbpulse.ui.screens.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.collection.CollectionService
import pt.ua.mbpulse.collection.CollectionStage
import pt.ua.mbpulse.collection.CollectionStateHolder
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val TAG = "CollectionViewModel"

public data class CollectionStatus(
    val isRunning: Boolean       = false,
    val epochsProcessed: Int     = 0,
    val recordsPending: Int      = 0,
    val recordsSynced: Int       = 0,
    val currentStage: CollectionStage = CollectionStage.Idle,
    val error: String?           = null
)

@HiltViewModel
public class CollectionViewModel @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val encryptedRecordDao: EncryptedRecordDao,
    private val state: CollectionStateHolder
) : ViewModel() {

    public val status: StateFlow<CollectionStatus> = combine(
        combine(state.isRunning, state.epochsProcessed, state.currentStage) { running, epochs, stage ->
            Triple(running, epochs, stage)
        },
        combine(state.error, encryptedRecordDao.observePendingCount(), encryptedRecordDao.observeSyncedCount()) { err, pending, synced ->
            Triple(err, pending, synced)
        }
    ) { (running, epochs, stage), (err, pending, synced) ->
        CollectionStatus(
            isRunning       = running,
            epochsProcessed = epochs,
            currentStage    = stage,
            error           = err,
            recordsPending  = pending,
            recordsSynced   = synced
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionStatus()
    )

    public fun startCollection(
        participantPseudoId: String,
        studyId: String,
        wrappedDekBytes: ByteArray,
        piPublicKeyBytes: ByteArray?,
        consentedTypeIds: Set<String>,
        samplingSecondsByTypeId: Map<String, Int> = emptyMap()
    ) {
        Log.d(TAG, "startCollection — pseudoId: $participantPseudoId, wrappedDek: ${wrappedDekBytes.size}B")
        CollectionService.start(
            context             = context,
            participantPseudoId = participantPseudoId,
            studyId             = studyId,
            wrappedDekBytes     = wrappedDekBytes,
            piPublicKeyBytes    = piPublicKeyBytes,
            consentedTypeIds    = consentedTypeIds,
            samplingSecondsByTypeId = samplingSecondsByTypeId
        )
    }

    public fun stopCollection() {
        Log.d(TAG, "stopCollection called")
        CollectionService.stop(context)
    }
}
