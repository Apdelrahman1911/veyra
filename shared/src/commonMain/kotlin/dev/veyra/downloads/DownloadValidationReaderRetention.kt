package dev.veyra.downloads

import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Strongly owns the EXACT original decoder-release failure alongside its existing file owner.
 * This is a cleanup barrier, not another native owner, registry, scope or retry mechanism.
 * Capture precedes all generic classification, cancellation unwind and lease release. Even a
 * mismatched reported input cannot be thrown away as an ordinary validation error.
 */
internal class DownloadValidationReaderRetention {
    private val readers = MutableStateFlow<List<LocalMediaValidationReaderUnacknowledged>>(emptyList())

    suspend fun <T> validate(block: suspend () -> T): T = try {
        block()
    } catch (failure: LocalMediaValidationReaderUnacknowledged) {
        readers.update { current -> if (current.any { it === failure }) current else current + failure }
        throw failure
    }

    /** First step in the existing cleanup owner. Explicit retry only OBSERVES, never destroys. */
    fun requireReleased() {
        readers.value.forEach { retained ->
            // A throwing observation is also not release; the existing cleanup keeps this owner.
            if (!retained.reader.isReleased()) throw retained
        }
    }
}
