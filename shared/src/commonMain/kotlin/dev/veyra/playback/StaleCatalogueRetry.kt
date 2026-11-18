package dev.veyra.playback

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.delay

const val STALE_CATALOGUE_DETAIL = "acquisition_stale_library_revision"

fun VeyraException.isStaleCatalogue(): Boolean =
    failure.code == FailureCode.INVALID_STATE && failure.detail == STALE_CATALOGUE_DETAIL

/**
 * Runs an action that reads the library and then writes with that read's revision as its
 * compare-and-set. A concurrent unrelated library write (artwork caching right after search
 * results appear, for example) makes the read stale without making the user's request wrong.
 * A refused compare-and-set writes nothing, so [block] must re-read the library on every attempt
 * and is retried a bounded number of times; a library that keeps changing still fails closed.
 * Use it only where the action rebuilds its inputs from the fresh read, never to replay a
 * caller-built command whose expected revision is part of its meaning.
 */
suspend fun <T> retryingStaleCatalogue(attempts: Int = STALE_CATALOGUE_ATTEMPTS, block: suspend () -> T): T {
    require(attempts >= 1)
    for (attempt in 1 until attempts) {
        try { return block() } catch (stale: VeyraException) {
            if (!stale.isStaleCatalogue()) throw stale
        }
        // Writers (e.g. one artwork link per search result) arrive in bursts; let the burst drain.
        delay(minOf(250L, 50L * attempt))
    }
    return block()
}

/** About 2.5 s of backoff in total: longer than a search page's artwork burst, still bounded. */
const val STALE_CATALOGUE_ATTEMPTS = 12
