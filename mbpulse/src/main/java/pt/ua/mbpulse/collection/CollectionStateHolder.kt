package pt.ua.mbpulse.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What collection is doing — data, not wording: screens turn it into text
 * from string resources (`stageText` in the UI layer), so it can be translated.
 */
public sealed interface CollectionStage {
    public data object Idle : CollectionStage
    public data object Starting : CollectionStage
    public data object Recording : CollectionStage
    /** Passive mode: the sensor stream ended; the next attempt is in [seconds]. */
    public data class Reconnecting(val seconds: Long) : CollectionStage
    public data class Complete(val epochs: Int) : CollectionStage
    public data object Stopped : CollectionStage
    public data object Error : CollectionStage
}

/**
 * Process-wide, lifecycle-independent holder for live collection progress.
 *
 * [CollectionService] is the sole writer; UI layers (e.g. CollectionViewModel)
 * are readers. Keeping the progress here — rather than inside a ViewModel —
 * means a recording survives the collection screen leaving the back stack and
 * the app being backgrounded, which is essential for long (e.g. overnight)
 * sessions. The state resets to its defaults on process death; the service is
 * restarted with a redelivered intent in that case and republishes progress.
 */
@Singleton
internal class CollectionStateHolder @Inject constructor() {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _epochsProcessed = MutableStateFlow(0)
    val epochsProcessed: StateFlow<Int> = _epochsProcessed.asStateFlow()

    private val _currentStage = MutableStateFlow<CollectionStage>(CollectionStage.Idle)
    val currentStage: StateFlow<CollectionStage> = _currentStage.asStateFlow()

    /** Why collection could not start or stopped (technical detail; shown under a translated title). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun onStarting() {
        _isRunning.value = true
        _epochsProcessed.value = 0
        _currentStage.value = CollectionStage.Starting
        _error.value = null
    }

    fun onRecording() {
        _currentStage.value = CollectionStage.Recording
    }

    fun onReconnecting(seconds: Long) {
        _currentStage.value = CollectionStage.Reconnecting(seconds)
    }

    fun onEpoch() {
        _epochsProcessed.value = _epochsProcessed.value + 1
        _currentStage.value = CollectionStage.Recording
    }

    fun onComplete() {
        _isRunning.value = false
        _currentStage.value = CollectionStage.Complete(_epochsProcessed.value)
    }

    fun onStopped() {
        _isRunning.value = false
        _currentStage.value = CollectionStage.Stopped
    }

    fun onError(message: String) {
        _isRunning.value = false
        _currentStage.value = CollectionStage.Error
        _error.value = message
    }
}
