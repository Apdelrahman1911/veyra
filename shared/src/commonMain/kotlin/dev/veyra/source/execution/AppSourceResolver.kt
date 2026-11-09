package dev.veyra.source.execution

import dev.veyra.data.LibraryStore
import dev.veyra.data.LibraryTransactionOwned
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.playback.SystemClock
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import dev.veyra.playback.PlaybackResource
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.SourceReaderOwner
import dev.veyra.source.SourceResolver
import dev.veyra.source.network.PublicUrlPolicy
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * The app's only media resolution path. Discovery identity and execution identity are separate:
 * every fresh online selection submits the COMPLETE original title to kmpYT and binds its FIRST
 * entry. Never inspect a publisher enclosure, select a later hit, or silently replace a failed
 * local artifact. Native playback still has to prove decoding; resolution alone is not playback.
 */
class AppSourceResolver(
    private val store: LibraryStore,
    private val files: DownloadFiles,
    private val validator: AssetValidator,
    private val gateway: KmpMediaGateway,
    private val clock: Clock = SystemClock(),
    private val materializationEvidence: DownloadMaterializationEvidence,
) : SourceResolver, SourceReaderOwner, LibraryTransactionOwned {
    override val transactionOwner get() = store.transactionOwner
    init {
        require(materializationEvidence.transactionOwner === transactionOwner) {
            "Legacy local playback and materialization evidence require the same Room transaction owner"
        }
    }
    private val cleanup = DownloadCleanupRegistry()
    private class RetainedValidation(
        val failure: LocalMediaValidationReaderUnacknowledged,
        val lease: DownloadLeaseCleanup,
    )
    private val retainedValidation = MutableStateFlow<List<RetainedValidation>>(emptyList())
    private val failedResources = MutableStateFlow<List<PlaybackResource>>(emptyList())
    private var readersClosed = false // Controller closes only after all registered jobs join.
    override suspend fun resolve(
        item: MediaItem,
        capabilities: EngineCapabilities,
        offlineOnly: Boolean,
        purpose: ResolvePurpose,
        reuseBindingId: String?,
    ): ResolvedPlayable = resolveWithBinding(item, capabilities, offlineOnly, purpose, reuseBindingId)

    /** Explicit session/expired-link renewal may pin the existing receipt without another search. */
    suspend fun resolveWithBinding(
        item: MediaItem,
        capabilities: EngineCapabilities,
        offlineOnly: Boolean = false,
        purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
        reuseBindingId: String? = null,
    ): ResolvedPlayable {
        checkReadersOpen()
        val snapshot = rememberOriginal(item)
        val original = snapshot.media.getValue(item.id)
        if (snapshot.downloads.values.any { it.mediaId == item.id && it.cleanupPending }) {
            // Durable remove intent fences both native re-open and a replacement remote search
            // until the coordinator has released native readers and completed owned-file cleanup.
            fail(FailureCode.INVALID_STATE, "offline_asset_removal_pending")
        }
        val completed = snapshot.downloads.values.filter {
            it.mediaId == item.id && it.status == DownloadStatus.COMPLETE
        }.maxWithOrNull(compareBy<DownloadRecord> { it.asset?.completedEpochMs ?: 0 }.thenBy { it.updatedEpochMs })
        if (completed != null) return resolveLocal(original, completed, capabilities, reuseBindingId)
        snapshot.downloads.values.firstOrNull {
            it.mediaId == item.id && it.status == DownloadStatus.FAILED && it.asset != null
        }?.let { failed ->
            // Keep failed owned bytes visible for explicit Remove/Retry. Pressing play again must
            // not silently swap the broken downloaded selection for a newly searched remote hit.
            throw VeyraException(failed.failure ?: AppFailure(FailureCode.CORRUPT_MEDIA, "offline_asset_requires_recovery"))
        }
        requireOnline(offlineOnly)
        if (reuseBindingId == null && original.title != item.title) fail(FailureCode.INVALID_STATE, "stale_discovery_identity")
        val binding = acquireBinding(original, reuseBindingId)
        requireOnline(offlineOnly)
        requireNoCleanup(original.id)
        val result = gatewayCall { gateway.resolve(binding, capabilities, purpose) }
        try {
            currentCoroutineContext().ensureActive()
            requireOnline(offlineOnly)
            requireNoCleanup(original.id)
            // A bridge can return native YouTube metadata, but it must describe the bound VIDEO.
            // Original RSS duration/title/license must NEVER be relabelled as properties of that audio.
            if (result.isLocal || result.item?.let {
                    it.source.provider != SourceProvider.YOUTUBE || it.source.canonicalId != binding.selectedVideoId
                } == true || result.executionBinding?.let { it != binding } == true) {
                fail(FailureCode.PARSE_FAILURE, "kmpyt_execution_identity_mismatch")
            }
            // A central-owned streaming bridge is NOT a downloaded asset. Only its exact typed
            // ephemeral capability may use loopback; arbitrary local URLs stay forbidden, and
            // the offline gate above applies even though the native player connects locally.
            if (result.ownedLoopbackUriOrNull() == null) PublicUrlPolicy.normalize(result.uri)
            if (result.resource?.localEndpoint != null && result.ownedLoopbackUriOrNull() == null) {
                fail(FailureCode.PARSE_FAILURE, "kmpyt_owned_endpoint_mismatch")
            }
            if (result.headers.isNotEmpty()) fail(FailureCode.UNSUPPORTED_SOURCE, "kmpyt_private_execution_not_supported")
            if (result.expiresAtEpochMs?.let { it <= clock.epochMs() } == true) {
                // Renewal belongs to the gateway and must retain this binding. Never search again.
                fail(FailureCode.RECOVERY_REQUIRED, "kmpyt_source_expired")
            }
            // DOWNLOAD describes central acquisition (possibly HLS/DASH/SABR), not a URI for
            // native GET. kmpYT materializes that plan into a local file with the advertised
            // output MIME/codec; the native decoder only needs that progressive file format.
            // Playback must still support the actual transport/owned bridge returned to it.
            val nativeTransport = if (purpose == ResolvePurpose.DOWNLOAD) StreamTransport.PROGRESSIVE else result.transport
            if (!capabilities.supports(nativeTransport, result.mimeType, result.codecs)) {
                fail(FailureCode.UNSUPPORTED_CODEC, "kmpyt_execution_not_supported")
            }
            return result.copy(mediaId = original.id, item = original, executionBinding = binding)
        } catch (failure: Throwable) {
            // The engine has not taken ownership yet. Cancellation and every validation/race
            // failure must dispose the producer without hiding the original typed diagnostic.
            withContext(NonCancellable) {
                result.resource?.let { resource ->
                    failedResources.update { it + resource }
                    try {
                        resource.close()
                        failedResources.update { it.filterNot { owner -> owner === resource } }
                    } catch (_: Throwable) { /* Preserve primary failure AND its strongly held owner. */ }
                }
            }
            throw failure
        }
    }

    /**
     * Public download-orchestrator entry point. A caller explicitly supplies a prior receipt for
     * resume/renewal; its stable media and selected-video identities MUST still match. A publisher
     * title correction does not invalidate the historical full-query receipt or remap the video.
     * New receipts are durable BEFORE any execution or typed first-entry failure.
     */
    suspend fun acquireBinding(item: MediaItem, reuseBindingId: String? = null): PlaybackBinding {
        checkReadersOpen()
        val snapshot = rememberOriginal(item)
        val original = snapshot.media.getValue(item.id)
        requireOnline()
        if (reuseBindingId != null) {
            val binding = snapshot.playbackBindings[reuseBindingId]
                ?: fail(FailureCode.RECOVERY_REQUIRED, "execution_binding_missing")
            requireMatchingBinding(binding, original)
            requireVideo(binding)
            return binding
        }
        if (original.title != item.title) fail(FailureCode.INVALID_STATE, "stale_discovery_identity")
        val first = gatewayCall { gateway.firstResult(original.title) }
            ?: fail(FailureCode.NOT_FOUND, "kmpyt_no_first_result")
        if (first.queryTitle != item.title || first.selectionOrdinal != 0) {
            fail(FailureCode.PARSE_FAILURE, "kmpyt_first_result_receipt_mismatch")
        }
        if (first.title.length > MAX_TITLE_LENGTH || first.id.length > MAX_LOCATOR_LENGTH ||
            (first.kind != FirstResultKind.UNKNOWN && (first.id.isBlank() || first.title.isBlank()))) {
            fail(FailureCode.PARSE_FAILURE, "kmpyt_invalid_first_result_metadata")
        }
        requireCanonicalLocator(first.kind, first.id, first.canonicalUrl)
        if (first.kind == FirstResultKind.VIDEO && !VIDEO_ID.matches(first.id)) {
            fail(FailureCode.PARSE_FAILURE, "kmpyt_invalid_first_video_identity")
        }
        val binding = PlaybackBinding(
            id = newEntityId("execution"), mediaId = item.id, queryTitle = item.title,
            firstResultKind = first.kind, firstResultId = first.id, firstResultTitle = first.title,
            firstResultUrl = first.canonicalUrl, selectedVideoId = first.id.takeIf { first.kind == FirstResultKind.VIDEO },
            resolvedEpochMs = clock.epochMs(), selectionOrdinal = 0,
        )
        store.recordPlaybackBinding(binding).getOrThrow()
        first.failure?.let { throw VeyraException(it) }
        requireVideo(binding)
        requireOnline()
        return binding
    }

    private suspend fun rememberOriginal(item: MediaItem): AppSnapshot {
        if (item.id.isBlank() || item.title.isBlank() || item.title.length > MAX_TITLE_LENGTH) {
            fail(FailureCode.INVALID_STATE, "complete_media_title_required")
        }
        store.initialize().getOrThrow()
        // Use the repository transaction rather than replacing newer discovery metadata with a
        // stale resolver argument. Enclosure metadata may legitimately change for the same GUID.
        return store.update { snapshot ->
            val current = snapshot.media[item.id]
            when {
                current == null -> snapshot.copy(media = snapshot.media + (item.id to item))
                current.kind != item.kind ||
                    current.source.provider != item.source.provider || current.source.canonicalId != item.source.canonicalId ->
                    fail(FailureCode.INVALID_STATE, "stale_discovery_identity")
                else -> snapshot
            }
        }.getOrThrow()
    }

    private fun requireOnline(offlineOnly: Boolean = false) {
        if (offlineOnly || store.state.value.settings.offlineOnly) fail(FailureCode.OFFLINE_POLICY, "offline_asset_unavailable")
    }

    private fun requireNoCleanup(mediaId: String) {
        if (store.state.value.downloads.values.any { it.mediaId == mediaId && it.cleanupPending }) {
            fail(FailureCode.INVALID_STATE, "offline_asset_removal_pending")
        }
    }

    private suspend fun resolveLocal(
        item: MediaItem,
        record: DownloadRecord,
        capabilities: EngineCapabilities,
        reuseBindingId: String?,
    ): ResolvedPlayable {
        val asset = record.asset
            ?: rejectLocal(record, AppFailure(FailureCode.CORRUPT_MEDIA, "offline_asset_missing"))
        val binding = asset.executionBindingId?.let { store.state.value.playbackBindings[it] }
        if (asset.mediaId != item.id || binding == null || record.executionBindingId != binding.id ||
            asset.executionBindingId != binding.id || asset.kind != OfflineAssetKind.FILE ||
            asset.representationId.isNullOrBlank() || record.representationId != asset.representationId) {
            rejectLocal(record, AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_mismatch"))
        }
        try { requireMatchingBinding(binding, item); requireVideo(binding) }
        catch (failure: VeyraException) { rejectLocal(record, AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_mismatch")) }
        if (reuseBindingId != null && reuseBindingId != binding.id) {
            // The artifact can still be valid, but is not the requested session's execution.
            fail(FailureCode.INVALID_STATE, "offline_execution_binding_changed")
        }
        if (!capabilities.supportsLocalFiles) fail(FailureCode.UNSUPPORTED_TRANSPORT, "local_files_not_supported")
        if (!capabilities.supports(StreamTransport.PROGRESSIVE, asset.mimeType, asset.codecs)) {
            fail(FailureCode.UNSUPPORTED_CODEC, "offline_codec_not_supported")
        }
        requireNoCleanup(item.id)
        val scope = DownloadLeaseCleanupScope(cleanup)
        var acquired: DownloadLeaseCleanup? = null
        var nativeRetained = false
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val lease = files.tryLeasePaths(listOf(asset.uri), scope)
                    ?: fail(FailureCode.INVALID_STATE, "offline_file_busy")
                acquired = DownloadLeaseCleanup(listOf(lease)).also(cleanup::register)
            }
            currentCoroutineContext().ensureActive()
            requireNoCleanup(item.id)
            // A schema3 historical MediaItem path still belongs to the SAME canonical file
            // owner. Rejoin AFTER the lease; never invent a typed request/member or AssetUse.
            val canonical = requireCanonicalLocal(record, asset, binding)
            val input = canonical?.asset?.validationInput()
            val attempt = canonical?.let { materializationEvidence.admit(it.job.id, it.asset,
                ExecutionBindingId(binding.id), requestId = null) }
            val onRejected: (DownloadMaterializationNegative) -> Unit = { negative ->
                attempt?.let { materializationEvidence.reject(it, negative) }
            }
            val path = try {
                if (input == null) files.verifiedFile(asset)
                else files.verifiedFile(input, DownloadMaterializationCheckPhase.PRE_NATIVE, onRejected)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: VeyraException) { rejectLocal(record, failure.failure) }
            catch (_: Exception) { rejectLocal(record, AppFailure(FailureCode.STORAGE_IO, "offline_file_validation_failed")) }
            val native = try { validator.validate(asset) }
            catch (failure: LocalMediaValidationReaderUnacknowledged) {
                // Even a mismatched input is NOT proof that this invocation released our file.
                retainedValidation.update { it + RetainedValidation(failure, requireNotNull(acquired)) }
                nativeRetained = true
                throw failure
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: VeyraException) { rejectLocal(record, failure.failure) }
            catch (_: Exception) { rejectLocal(record, AppFailure(FailureCode.CORRUPT_MEDIA, "offline_native_validation_failed")) }
            val measuredDuration = when (native) {
                is AssetValidation.Invalid -> {
                    // Synchronous invalidation precedes rejectLocal's legacy policy/Room path
                    // and the OS cleanup finally. Thrown generic native errors are not evidence.
                    input?.let { onRejected(DownloadMaterializationNegative.Native(it, native.failure)) }
                    rejectLocal(record, native.failure)
                }
                is AssetValidation.Valid -> native.durationMs ?: asset.measuredDurationMs
            }
            currentCoroutineContext().ensureActive()
            if (input == null) files.verifiedFile(asset)
            else files.verifiedFile(input, DownloadMaterializationCheckPhase.POST_NATIVE, onRejected)
            requireNoCleanup(item.id)
            val rejoined = requireCanonicalLocal(record, asset, binding)
            if (canonical != null && (rejoined == null || rejoined.job.id != canonical.job.id || rejoined.asset != canonical.asset))
                fail(FailureCode.INVALID_STATE, "offline_asset_generation_changed")
            val latest = store.state.value.downloads[record.id]
            if (latest?.status != DownloadStatus.COMPLETE || latest.asset != asset || latest.cleanupPending) {
                fail(FailureCode.INVALID_STATE, "offline_asset_generation_changed")
            }
            return ResolvedPlayable(
                mediaId = item.id, uri = path.toString(), mimeType = asset.mimeType, codecs = asset.codecs,
                durationMs = measuredDuration, seekableHint = true, isLocal = true, assetId = asset.id,
                representationId = asset.representationId, item = item, executionBinding = binding,
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!nativeRetained) acquired?.let { lease ->
                    try { lease.close(); cleanup.remove(lease) }
                    catch (_: Throwable) { fail(FailureCode.STORAGE_IO, "offline_file_lease_release") }
                }
            }
        }
    }

    private data class CanonicalLocal(val job: PhysicalDownloadJob, val asset: MaterializationAsset)

    /**
     * Read-only identity join for the historical schema2 path on a schema3 store. It confers no
     * typed playback permission. Projection-only legacy stores keep their existing behavior;
     * canonical stores must never write their physical graph through rejectLocal's old path.
     */
    private suspend fun requireCanonicalLocal(record: DownloadRecord, asset: OfflineAsset,
        binding: PlaybackBinding): CanonicalLocal? {
        if (!store.requiresTypedPlayback) return null
        val view = store.acquisitionView()
        val state = view.acquisition
        val current = view.snapshot.downloads[record.id]
        val job = state.physicalDownloads[record.id]
        val physical = job?.assetId?.let(state.materializations::get)
        val producer = job?.producer as? PhysicalDownloadProducer.Schema2
        val execution = producer?.bindingId?.let(state.bindings::get)
        val receipt = execution?.firstReceiptId?.let(state.receipts::get) as? FirstResultReceipt.Schema2
        val recording = producer?.recordingId?.let(state.recordings::get)
        if (view.snapshot.revision != state.libraryRevision || current?.status != DownloadStatus.COMPLETE ||
            current.asset != asset || current.cleanupPending || current.executionBindingId != binding.id ||
            view.snapshot.playbackBindings[binding.id] != binding || job == null || job.id != record.id ||
            job.status != DownloadStatus.COMPLETE || job.cleanupPending || job.maintenanceKind != null ||
            job.leaseOwner != null || job.leaseExpiresEpochMs != null || job.activeAttemptId != null ||
            job.id in state.physicalFinalizations || physical == null || producer == null ||
            producer.mediaId != record.mediaId || producer.mediaId != asset.mediaId ||
            producer.bindingId?.value != binding.id || physical.producer != producer ||
            physical.id.value != asset.id || physical.uri != asset.uri || physical.sizeBytes != asset.sizeBytes ||
            physical.kind != asset.kind || physical.mimeType != asset.mimeType || physical.codecs != asset.codecs ||
            physical.sha256 != asset.sha256 || physical.representationId != asset.representationId ||
            physical.validatorGeneration != asset.validatorGeneration || physical.completedEpochMs != asset.completedEpochMs ||
            physical.measuredDurationMs != asset.measuredDurationMs || state.materializationReceipts[physical.id] != physical.receipt() ||
            execution?.origin != ExecutionBindingOrigin.SCHEMA2_EXECUTABLE || execution.subjectId != producer.subjectId ||
            execution.recordingId != producer.recordingId || recording == null || recording.canonicalId != binding.selectedVideoId ||
            state.subjects[producer.subjectId] != ExecutableSubject.CatalogMedia(producer.subjectId, record.mediaId) ||
            receipt == null || receipt != FirstResultReceipt.Schema2(receipt.id, binding.mediaId, binding.queryTitle,
                binding.firstResultKind, binding.firstResultId, binding.firstResultTitle, binding.firstResultUrl,
                binding.selectedVideoId, binding.resolvedEpochMs, binding.selectionOrdinal)) {
            fail(FailureCode.INVALID_STATE, "offline_materialization_binding_mismatch")
        }
        physicalMaterializationIdentityFailure(job, state.physicalFinalizations[job.id], physical,
            state.physicalAttempts.values.filter { it.downloadId == job.id })?.let { throw VeyraException(it) }
        return CanonicalLocal(job, physical)
    }

    private suspend fun rejectLocal(record: DownloadRecord, failure: AppFailure): Nothing {
        requireNoCleanup(record.mediaId)
        // Schema3 physical status/lease edits belong to the existing typed download owner.
        // Do not turn a playback validation failure into a forbidden legacy graph transform.
        if (store.requiresTypedPlayback) throw VeyraException(failure)
        store.update { snapshot ->
            val current = snapshot.downloads[record.id]
            if (current?.cleanupPending == true) fail(FailureCode.INVALID_STATE, "offline_asset_removal_pending")
            // Do not corrupt a newer completed generation because an older validation raced it.
            if (current?.status == DownloadStatus.COMPLETE && current.asset == record.asset) {
                snapshot.copy(downloads = snapshot.downloads + (record.id to current.copy(
                    status = DownloadStatus.FAILED, failure = failure, updatedEpochMs = clock.epochMs(),
                )))
            } else snapshot
        }.getOrThrow()
        throw VeyraException(failure)
    }

    override suspend fun retainedReaderFailure(): AppFailure? {
        withContext(NonCancellable + Dispatchers.IO) {
            retainedValidation.value.forEach { owner ->
                owner.lease.lock.withLock {
                    if (retainedValidation.value.none { it === owner }) return@withLock
                    val released = try { owner.failure.reader.isReleased() } catch (_: Throwable) { false }
                    if (released && !owner.lease.failed.value) {
                        try {
                            owner.lease.close()
                            cleanup.remove(owner.lease)
                            retainedValidation.update { it.filterNot { value -> value === owner } }
                        } catch (_: Throwable) { /* Keep the failed file owner; no implicit retry. */ }
                    }
                }
            }
        }
        return when {
            retainedValidation.value.isNotEmpty() -> AppFailure(FailureCode.STORAGE_IO, "local_validation_reader_unacknowledged")
            cleanup.owners.value.values.any { it.failed.value } -> AppFailure(FailureCode.STORAGE_IO, "offline_file_lease_release")
            failedResources.value.isNotEmpty() -> AppFailure(FailureCode.STORAGE_IO, "source_resource_release_unacknowledged")
            else -> null
        }
    }

    override suspend fun closeReaders() {
        readersClosed = true
        retainedReaderFailure()?.let { throw VeyraException(it) }
        if (cleanup.owners.value.isNotEmpty()) fail(FailureCode.STORAGE_IO, "offline_file_lease_release")
    }

    private fun checkReadersOpen() {
        if (readersClosed) fail(FailureCode.INVALID_STATE, "source_resolver_closed")
        if (retainedValidation.value.isNotEmpty()) fail(FailureCode.STORAGE_IO, "local_validation_reader_unacknowledged")
        if (cleanup.owners.value.values.any { it.failed.value } || failedResources.value.isNotEmpty()) {
            fail(FailureCode.STORAGE_IO, "source_reader_release_unacknowledged")
        }
    }

    private fun requireMatchingBinding(binding: PlaybackBinding, item: MediaItem) {
        if (binding.mediaId != item.id || binding.queryTitle.isBlank() || binding.queryTitle.length > MAX_TITLE_LENGTH || binding.selectionOrdinal != 0) {
            fail(FailureCode.INVALID_STATE, "execution_binding_identity_mismatch")
        }
        requireCanonicalLocator(binding.firstResultKind, binding.firstResultId, binding.firstResultUrl)
    }

    private fun requireVideo(binding: PlaybackBinding) {
        if (binding.firstResultKind != FirstResultKind.VIDEO) fail(FailureCode.UNSUPPORTED_SOURCE, "kmpyt_first_result_not_video")
        if (binding.selectedVideoId != binding.firstResultId || !VIDEO_ID.matches(binding.firstResultId)) {
            fail(FailureCode.PARSE_FAILURE, "kmpyt_execution_identity_mismatch")
        }
    }

    /** Receipt persistence is not a place for signed execution URLs or arbitrary redirect links. */
    private fun requireCanonicalLocator(kind: FirstResultKind, id: String, value: String?) {
        if (value == null) return
        if (value.length > MAX_LOCATOR_LENGTH || value.any { it.code < 32 || it.code == 127 }) {
            fail(FailureCode.PARSE_FAILURE, "kmpyt_noncanonical_first_result_locator")
        }
        val url = try { Url(value) } catch (_: Exception) { fail(FailureCode.PARSE_FAILURE, "kmpyt_noncanonical_first_result_locator") }
        val originSafe = url.protocol.name == "https" && url.port == 443 && url.user == null && url.password == null &&
            url.host == "www.youtube.com" && url.fragment.isEmpty()
        val canonicalPath = when (kind) {
            FirstResultKind.VIDEO -> url.encodedPath == "/watch" && url.parameters.names() == setOf("v") && url.parameters.getAll("v") == listOf(id)
            FirstResultKind.PLAYLIST -> url.encodedPath == "/playlist" && url.parameters.names() == setOf("list") && url.parameters.getAll("list") == listOf(id)
            FirstResultKind.CHANNEL -> url.parameters.isEmpty() && url.encodedPath == "/channel/$id"
            FirstResultKind.UNKNOWN -> false // Unknown cards have no known safe canonical identity.
        }
        val exactCanonical = when (kind) {
            FirstResultKind.VIDEO -> "https://www.youtube.com/watch?v=$id"
            FirstResultKind.PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
            FirstResultKind.CHANNEL -> "https://www.youtube.com/channel/$id"
            FirstResultKind.UNKNOWN -> null
        }
        if (!originSafe || !canonicalPath || value != exactCanonical) fail(FailureCode.PARSE_FAILURE, "kmpyt_noncanonical_first_result_locator")
    }

    private suspend fun <T> gatewayCall(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: VeyraException) { throw failure }
    catch (_: Exception) { fail(FailureCode.UNEXPECTED, "kmpyt_gateway_failed") }

    private fun fail(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))

    private companion object {
        const val MAX_TITLE_LENGTH = 16_384
        const val MAX_LOCATOR_LENGTH = 8_192
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
