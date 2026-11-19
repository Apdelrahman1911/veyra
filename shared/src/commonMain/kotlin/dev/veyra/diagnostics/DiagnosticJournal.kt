@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.veyra.diagnostics

import dev.veyra.platform.BlockingIo
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.Path
import okio.buffer
import okio.use

/** A session survives restarts. Each process gets a separate ID and a monotonically ordered stream. */
data class DiagnosticCaptureState(val enabled: Boolean = false, val session: Uuid? = null, val writeFailed: Boolean = false) {
    val sessionId: String? get() = session?.toString()
}

class DiagnosticJournal(
    owner: CoroutineScope,
    private val directory: Path,
    initial: DiagnosticCaptureState = DiagnosticCaptureState(),
    private val save: (DiagnosticCaptureState) -> Unit = {},
    private val prepareDirectory: (Path) -> Unit = {},
    key: () -> ByteString = { Uuid.random().toByteArray().toByteString() },
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val clock: Clock = SystemClock(),
    io: CoroutineDispatcher = BlockingIo,
    private val segmentBytes: Long = 1_048_576,
    capacity: Int = 256,
) {
    private val mutableState = MutableStateFlow(initial.copy(enabled = initial.enabled && initial.session != null))
    val state = mutableState.asStateFlow()
    private val configuration = Mutex()
    private val dropped = MutableStateFlow(0L)
    private val process = Uuid.random()
    // A private keyed alias joins a durable download across processes without exporting its ID.
    private val linkKey by lazy(key)
    val diagnostics = Diagnostics({ state.value.enabled }, ::offer) { id ->
        val digest = id.encodeUtf8().hmacSha256(linkKey)
        (0..7).fold(0L) { value, index -> (value shl 8) or (digest[index].toLong() and 255) }
    }
    private val messages = Channel<Message>(capacity, onUndeliveredElement = { message ->
        if (message is Message.Export) message.result.cancel()
    })
    private val current = directory / "current.jsonl"
    private val previous = directory / "previous.jsonl"
    private val exported = directory / "Veyra-diagnostics.jsonl"
    private val scratch = directory / "export.pending"
    private var sequence = 0L
    private var bytes: Long? = null

    init {
        require(segmentBytes >= 512 && capacity > 0)
        owner.launch(io) {
            for (message in messages) {
                try {
                    when (message) {
                        is Message.Entry -> {
                            flushDropped(message.session)
                            append(encode(message))
                        }
                        is Message.Export -> {
                            flushDropped(message.session)
                            message.result.complete(snapshot())
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (message is Message.Export) message.result.cancel(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    bytes = null
                    mutableState.update { it.copy(writeFailed = true) }
                    if (message is Message.Export) message.result.completeExceptionally(error)
                    else dropped.update { it + 1 }
                }
            }
        }.invokeOnCompletion { messages.cancel() }
    }

    suspend fun setEnabled(enabled: Boolean) = configuration.withLock {
        val before = state.value
        if (enabled == before.enabled) return@withLock
        val next = before.copy(enabled = enabled, session = before.session ?: Uuid.random())
        save(next)
        if (!enabled) diagnostics.record(DiagnosticComponent.APP, DiagnosticOperation.CAPTURE, phase = DiagnosticPhase.DISABLED)
        mutableState.update { next.copy(writeFailed = it.writeFailed) }
        if (enabled) diagnostics.record(DiagnosticComponent.APP, DiagnosticOperation.CAPTURE, phase = DiagnosticPhase.ENABLED)
    }

    suspend fun export(): Path = configuration.withLock {
        val session = checkNotNull(state.value.session)
        val result = CompletableDeferred<Path>()
        messages.send(Message.Export(session, result))
        result.await()
    }

    private fun offer(event: DiagnosticEvent) {
        val capture = state.value
        val session = capture.session ?: return
        if (!capture.enabled) return
        if (!messages.trySend(Message.Entry(session, clock.epochMs(), clock.monotonicMs(), event)).isSuccess) {
            dropped.update { it + 1 }
        }
    }

    private fun flushDropped(session: Uuid) {
        val count = dropped.getAndUpdate { 0 }
        if (count == 0L) return
        val marker = DiagnosticEvent(DiagnosticComponent.APP, DiagnosticOperation.CAPTURE, DiagnosticOutcome.DROPPED,
            null, null, null, null, null, count, null, null, null, null)
        try { append(encode(Message.Entry(session, clock.epochMs(), clock.monotonicMs(), marker))) }
        catch (error: Exception) { dropped.update { it + count }; throw error }
    }

    private fun encode(entry: Message.Entry): String = buildJsonObject {
        val event = entry.event
        put("schema", 1)
        put("session_id", entry.session.toString())
        put("process_id", process.toString())
        put("sequence", ++sequence)
        put("epoch_ms", entry.epochMs)
        put("monotonic_ms", entry.monotonicMs)
        put("severity", when (event.outcome) {
            DiagnosticOutcome.FAILED -> "error"
            DiagnosticOutcome.REJECTED, DiagnosticOutcome.RETRYING, DiagnosticOutcome.DROPPED -> "warning"
            else -> "info"
        })
        put("component", event.component.name)
        put("operation", event.operation.name)
        put("outcome", event.outcome.name)
        event.phase?.let { put("phase", it.name) }
        event.context?.let {
            put("operation_id", it.operationId)
            put("correlation_id", it.correlationId)
            it.parentId?.let { parent -> put("parent_id", parent) }
        }
        if (event.context == null) event.sourceCorrelationId?.let { put("correlation_id", it) }
        event.failure?.let { put("failure_code", it.name) }
        event.detail?.let { put("detail_code", it.name) }
        event.durationMs?.let { put("duration_ms", it) }
        event.value?.let { put("value", it) }
        event.expected?.let { put("expected", it) }
        event.generation?.let { put("generation", it) }
        event.link?.let { put("download_link", it.toULong().toString(16).padStart(16, '0')) }
    }.toString() + "\n"

    private fun append(line: String) {
        val encoded = line.encodeToByteArray()
        check(encoded.size <= segmentBytes)
        val size = bytes ?: openSegment()
        val rotate = size + encoded.size > segmentBytes
        if (rotate) fs.atomicMove(current, previous)
        fs.appendingSink(current).buffer().use { it.write(encoded) }
        bytes = (if (rotate) 0L else size) + encoded.size
    }

    private fun openSegment(): Long {
        fs.createDirectories(directory)
        prepareDirectory(directory)
        val size = fs.metadataOrNull(current)?.size ?: return 0
        check(size <= segmentBytes)
        // After a process kill, remove only a partial trailing JSON record before resuming.
        val tail = fs.read(current) { readByteArray() }
        val committed = tail.indexOfLast { it == 10.toByte() } + 1
        if (committed.toLong() != size) fs.openReadWrite(current, mustExist = true).use { it.resize(committed.toLong()) }
        return committed.toLong()
    }

    private fun snapshot(): Path {
        check(fs.exists(current) || fs.exists(previous))
        fs.write(scratch) {
            for (file in listOf(previous, current)) {
                val size = fs.metadataOrNull(file)?.size ?: continue
                check(size <= segmentBytes)
                val data = fs.read(file) { readByteArray() }
                val committed = data.indexOfLast { it == 10.toByte() } + 1
                write(data, 0, committed)
            }
        }
        fs.atomicMove(scratch, exported)
        return exported
    }

    private sealed interface Message {
        data class Entry(val session: Uuid, val epochMs: Long, val monotonicMs: Long, val event: DiagnosticEvent) : Message
        data class Export(val session: Uuid, val result: CompletableDeferred<Path>) : Message
    }
}
