package dev.veyra.diagnostics

import dev.kmpyt.ResearchTraceContext
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext

enum class DiagnosticComponent { APP, UI, PLAYBACK, DOWNLOAD, NATIVE_AUDIO, NETWORK, DATABASE, STORAGE }

enum class DiagnosticOperation {
    CAPTURE, PROCESS, INITIALIZE, RESTORE, CLOSE, FOREGROUND, BACKGROUND, INACTIVE, TERMINATE,
    NAVIGATE, PLAYER_UI, NOTICE, SEARCH_EDIT, SEARCH, DISCOVER, COLLECTION, SHOW, FEED, FOLLOW, SETTINGS,
    FAVORITES, PLAYLIST, HISTORY, QUEUE, PLAY, PAUSE, RESUME, TOGGLE_PLAYBACK, SEEK, SKIP, SWITCH, REPEAT,
    SHUFFLE, SPEED, SLEEP_TIMER, CROSSFADE, PLAYER_STATE, STARTUP, INTERRUPTION, ROUTE, REMOTE,
    DOWNLOAD, DOWNLOAD_PAUSE, DOWNLOAD_RESUME, DOWNLOAD_CANCEL, DOWNLOAD_REMOVE, DOWNLOAD_RETRY,
    DOWNLOAD_VALIDATE, DOWNLOAD_PROGRESS, DOWNLOAD_STAGE, NATIVE_TRANSFER, DECODER, SCHEDULER,
    HTTP, ONLINE_ACCESS, MEDIA_SOURCE, ROOM_READ, ROOM_WRITE, STORAGE_USAGE, OPEN_EXTERNAL, ARTWORK,
}

enum class DiagnosticOutcome { STARTED, SUCCEEDED, FAILED, CANCELLED, REJECTED, OBSERVED, RETRYING, DROPPED }

enum class DiagnosticPhase {
    ACCEPTED, SUPERSEDED, EMPTY, PARTIAL, SELECTION, SOURCE_READY, ENGINE_READY, PLAYING,
    OS_MEDIA_COMMAND,
    FIRST_SAMPLE_SEEK, FIRST_SAMPLE_READ, LAST_SAMPLE_SEEK, LAST_SAMPLE_READ,
    OPEN, FORMAT, LENGTH, CLIENT_FORMAT, DISPOSE, ENABLED, DISABLED,
    NATIVE_DEADLINE, WATCHDOG_DEADLINE,
}

/** Only program-defined labels and numbers enter the journal. Never pass user text, URI or an exception message. */
data class DiagnosticEvent internal constructor(
    val component: DiagnosticComponent,
    val operation: DiagnosticOperation,
    val outcome: DiagnosticOutcome,
    val phase: Enum<*>?,
    val context: DiagnosticContext?,
    val failure: FailureCode?,
    val detail: Enum<*>?,
    val durationMs: Long?,
    val value: Long?,
    val expected: Long?,
    val generation: Long?,
    val link: Long?,
    val sourceCorrelationId: Long?,
)

class DiagnosticContext internal constructor(
    val operationId: Long,
    val correlationId: Long,
    val parentId: Long?,
    val link: Long?,
    internal val started: TimeMark,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticContext>
}

class Diagnostics(
    private val active: () -> Boolean,
    private val offer: (DiagnosticEvent) -> Unit,
    private val correlate: (String) -> Long? = { null },
) {
    private val ids = MutableStateFlow(0L)
    val enabled: Boolean get() = active()
    fun downloadKey(downloadId: String): Long? = if (enabled) correlate(downloadId) else null

    fun begin(component: DiagnosticComponent, operation: DiagnosticOperation,
        parent: DiagnosticContext? = null, phase: Enum<*>? = null, generation: Long? = null, link: Long? = null): DiagnosticContext? {
        if (!enabled) return null
        val id = ids.updateAndGet { it + 1 }
        return DiagnosticContext(id, parent?.correlationId ?: id, parent?.operationId, link ?: parent?.link, TimeSource.Monotonic.markNow()).also {
            record(component, operation, DiagnosticOutcome.STARTED, phase, it, generation = generation)
        }
    }

    fun end(component: DiagnosticComponent, operation: DiagnosticOperation, context: DiagnosticContext?,
        outcome: DiagnosticOutcome = DiagnosticOutcome.SUCCEEDED, failure: FailureCode? = null,
        phase: Enum<*>? = null, detail: Enum<*>? = null, value: Long? = null, generation: Long? = null) {
        if (context == null || !enabled) return
        record(component, operation, outcome, phase, context, failure, detail,
            context.started.elapsedNow().inWholeMilliseconds, value, generation = generation)
    }

    fun record(component: DiagnosticComponent, operation: DiagnosticOperation,
        outcome: DiagnosticOutcome = DiagnosticOutcome.OBSERVED, phase: Enum<*>? = null,
        context: DiagnosticContext? = null, failure: FailureCode? = null, detail: Enum<*>? = null,
        durationMs: Long? = null, value: Long? = null, expected: Long? = null,
        generation: Long? = null, link: Long? = null, sourceCorrelationId: Long? = null) {
        if (!enabled) return
        offer(DiagnosticEvent(component, operation, outcome, phase, context, failure, detail, durationMs, value, expected, generation,
            link ?: context?.link, sourceCorrelationId))
    }

    suspend fun <T> trace(component: DiagnosticComponent, operation: DiagnosticOperation,
        phase: Enum<*>? = null, link: Long? = null, resultFailure: (T) -> FailureCode? = { null }, block: suspend () -> T): T {
        if (!enabled) return block()
        val context = begin(component, operation, currentCoroutineContext()[DiagnosticContext], phase, link = link)
        return execute(component, operation, context, phase, resultFailure, block)
    }

    suspend fun <T> execute(component: DiagnosticComponent, operation: DiagnosticOperation, context: DiagnosticContext?,
        phase: Enum<*>? = null, resultFailure: (T) -> FailureCode? = { null }, block: suspend () -> T): T {
        if (context == null) return block()
        try {
            val result = withContext(context + ResearchTraceContext(context.correlationId)) { block() }
            val failure = resultFailure(result)
            end(component, operation, context, if (failure == null) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.FAILED,
                failure, phase)
            return result
        } catch (cancelled: CancellationException) {
            end(component, operation, context, DiagnosticOutcome.CANCELLED, FailureCode.CANCELLED, phase)
            throw cancelled
        } catch (error: Exception) {
            end(component, operation, context, DiagnosticOutcome.FAILED,
                (error as? VeyraException)?.failure?.code ?: FailureCode.INVALID_STATE, phase)
            throw error
        }
    }

    companion object { val None = Diagnostics({ false }, {}) }
}
