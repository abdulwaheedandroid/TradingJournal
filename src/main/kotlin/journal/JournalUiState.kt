package journal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

// Serialize the desktop's blocking storage calls, including shutdown.
internal val journalIo = Dispatchers.IO.limitedParallelism(1)

internal class UiState(private val journal: Journal, private val scope: CoroutineScope) {
    private var snapshot by mutableStateOf(JournalSnapshot(null, emptyList(), emptyList()))
    val settings get() = snapshot.settings
    val trades get() = snapshot.trades
    val equity get() = snapshot.equity
    var screen by mutableStateOf(Screen.Dashboard)
    var selected by mutableStateOf<Long?>(null)
    var feedback by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var backupToRestore by mutableStateOf<Path?>(null)
    var restoreCount by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
        private set

    suspend fun refresh() {
        snapshot = withContext(journalIo) { journal.snapshot() }
    }

    fun action(message: String? = null, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                operation()
                if (message != null) feedback = message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The operation failed."
            } finally {
                busy = false
            }
        }
    }
}
