package dev.veyra.source

import dev.veyra.model.AppFailure

/**
 * Explicit lifetime for a resolver which can own local validation readers or unhanded resources.
 * The existing playback controller joins its matching resolution jobs before a removal fence,
 * and all resolution jobs before close. Reader-only failure vetoes deletion/dependency release,
 * never unrelated audio. Implementations observe original native receipts; they do not retry an
 * unknown native close. A resolver with such owners MUST implement this capability.
 */
interface SourceReaderOwner {
    suspend fun retainedReaderFailure(): AppFailure?
    suspend fun closeReaders()
}
