package dev.veyra.ui.downloads

import dev.veyra.data.executionContext
import dev.veyra.downloads.DownloadAcquisitionPresentation
import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadClaimOwner
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.MaterializationAssetId
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.PhysicalDownloadMaintenanceKind
import dev.veyra.model.PhysicalDownloadProducer
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.receipt

/**
 * A pure display projection of one current download row. [id] is always the real physical job
 * id (or the existing schema2 record id, which is that same stable job id). No row is an
 * OfflineAsset, PlaybackBinding, MediaItem, source capability or Room entity.
 */
enum class DownloadSurfaceOrigin { LEGACY, TYPED_CATALOGUE, TYPED_SOURCE }

/**
 * Exact persisted target for the one explicit local Validate action of a retained FAILED job.
 * A standalone owner carries only the binding; a batch member also carries its exact batch,
 * member position and current intent revision. This is durable evidence only: it is not an
 * `AssetUse`, playback binding or native permission, and no sibling/member may be inferred.
 */
data class RetainedValidationTarget(
    val jobId: String,
    val bindingId: String,
    val batchId: String? = null,
    val memberPosition: Int? = null,
    val expectedIntentRevision: Long? = null,
) {
    init {
        require(jobId.isNotBlank() && bindingId.isNotBlank())
        require((batchId == null) == (memberPosition == null && expectedIntentRevision == null))
        require(batchId == null || (batchId.isNotBlank() && memberPosition != null && memberPosition >= 0 &&
            expectedIntentRevision != null && expectedIntentRevision >= 0))
    }
    val isBatchMember: Boolean get() = batchId != null
}

/**
 * [ready] is row-level exact-use checked physical readiness for ANY producer: the existing
 * schema2 readiness for legacy rows, or a typed job whose paired current-process states and exact
 * `AssetUse`/claim/asset/receipt proof all match. A neutral source row can therefore be physically
 * verified/removable/stored, but it has no [mediaId], no catalogue binding, no catalogue
 * membership and no Play action; catalogue playback readiness remains [readyMediaIds] over a
 * non-null mediaId. [retainedValidation]/[retainedValidationChoices] are durable local-validation
 * targets only, never a playback grant or fabricated catalogue membership. A historical `COMPLETE`
 * flag, a persisted materialization, a process-history
 * check or a producer completion are not enough. [checking] means owned bytes exist and this
 * process is currently verifying/blocked. [displayTitle] is only the real captured
 * request/observed title of a neutral source row; it never creates an RSS/catalogue item. No
 * private path, signed URL or native handle is exposed.
 */
data class DownloadSurfaceEntry(
    val id: String,
    val origin: DownloadSurfaceOrigin,
    val mediaId: String? = null,
    val displayTitle: String? = null,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val failure: AppFailure? = null,
    val updatedEpochMs: Long = 0,
    val ready: Boolean = false,
    val checking: Boolean = false,
    val hasOwnedBytes: Boolean = false,
    val cleanupPending: Boolean = false,
    val bindingId: String? = null,
    val measuredDurationMs: Long? = null,
    val assetId: String? = null,
    val assetSizeBytes: Long? = null,
    /** Exact standalone/member target for explicit retained local validation; null when multiple. */
    val retainedValidation: RetainedValidationTarget? = null,
    /**
     * Every exact persisted batch-member target when more than one legitimate retained member
     * shares this physical row. The UI must present an explicit localized choice; it must never
     * pick the first/newest member or infer sibling grants.
     */
    val retainedValidationChoices: List<RetainedValidationTarget> = emptyList(),
) {
    init {
        require(id.isNotBlank() && bytesDownloaded >= 0 && (totalBytes == null || totalBytes >= 0))
        require(!checking || hasOwnedBytes)
        require(!ready || hasOwnedBytes)
        require(bindingId == null || origin == DownloadSurfaceOrigin.LEGACY || origin == DownloadSurfaceOrigin.TYPED_CATALOGUE)
        require(mediaId != null || origin == DownloadSurfaceOrigin.TYPED_SOURCE)
        require(origin != DownloadSurfaceOrigin.TYPED_SOURCE || mediaId == null)
    }
    val isActive: Boolean get() = status !in setOf(DownloadStatus.COMPLETE, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
    val needsAttention: Boolean get() = status in setOf(DownloadStatus.FAILED, DownloadStatus.NEEDS_FOREGROUND) ||
        status == DownloadStatus.COMPLETE && !hasOwnedBytes
}

/**
 * The complete UI download projection. It carries only current physical rows plus their honest
 * display identity. Storage counts each physical asset once even when several exact bindings
 * share it. Neutral source rows are never catalogue/library membership and never become Ready.
 */
data class DownloadSurface(val entries: List<DownloadSurfaceEntry>) {
    val catalogueMediaIds: Set<String> get() = entries.mapNotNullTo(linkedSetOf()) { it.mediaId }
    val readyMediaIds: Set<String> get() = entries.filter { it.ready }.mapNotNullTo(linkedSetOf()) { it.mediaId }

    fun forMedia(mediaId: String): DownloadSurfaceEntry? {
        val candidates = entries.filter { it.mediaId == mediaId }
        val newest = compareBy<DownloadSurfaceEntry> { it.updatedEpochMs }.thenBy { it.id }
        return candidates.filter { it.ready }.maxWithOrNull(newest)
            ?: candidates.filter { it.isActive }.maxWithOrNull(newest)
            ?: candidates.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.NEEDS_FOREGROUND }
                .maxWithOrNull(newest)
            ?: candidates.maxWithOrNull(newest)
    }

    fun readyEntryFor(mediaId: String): DownloadSurfaceEntry? =
        entries.filter { it.mediaId == mediaId && it.ready }
            .maxWithOrNull(compareBy<DownloadSurfaceEntry> { it.updatedEpochMs }.thenBy { it.id })

    /**
     * Exact saved playback choice for a media row/detail: one unambiguous ready typed catalogue
     * binding. Two different ready recordings are an explicit Downloads-surface choice, never a
     * newest-row authority. Physical download-row selection remains [forMedia].
     */
    fun unambiguousCatalogueBinding(mediaId: String): String? = entries
        .asSequence()
        .filter { it.mediaId == mediaId && it.ready && it.origin == DownloadSurfaceOrigin.TYPED_CATALOGUE }
        .mapNotNull { it.bindingId?.takeIf(String::isNotBlank) }
        .distinct()
        .singleOrNull()

    fun isDownloaded(mediaId: String): Boolean = readyMediaIds.contains(mediaId)

    fun storageBytes(): Long = entries.asSequence().filter { it.ready }
        .mapNotNull { entry -> entry.assetId?.let { it to (entry.assetSizeBytes ?: 0L) } }
        .distinctBy { it.first }
        .sumOf { it.second }

    /** Stable list order for the Downloads screen: recent first, ID as the deterministic tie-break. */
    fun rows(): List<DownloadSurfaceEntry> =
        entries.sortedWith(compareByDescending<DownloadSurfaceEntry> { it.updatedEpochMs }.thenBy { it.id })
}

/** Schema2-only projection used when no typed presentation was supplied (tests/legacy callers). */
fun legacyDownloadSurface(snapshot: AppSnapshot): DownloadSurface =
    DownloadSurface(snapshot.downloads.values.map { it.toDownloadSurfaceEntry() })

/**
 * Paired projection consumed by production screens. Legacy rows still come from the existing
 * coordinator projection (which is deliberately schema2-only); typed rows and every typed
 * readiness decision come from this ONE [DownloadAcquisitionPresentation] view/states pair.
 * We never combine a newer acquisition view with a separately delayed Map of checked assets.
 */
fun downloadSurface(snapshot: AppSnapshot, presentation: DownloadAcquisitionPresentation): DownloadSurface {
    val verifiedByJob = verifiedDownloads(presentation).groupBy { it.jobId }
        .mapValues { (_, uses) -> uses.maxWith(compareBy<VerifiedDownload> { it.createdEpochMs }.thenBy { it.bindingId }) }
    val typed = typedEntries(presentation, verifiedByJob)
    val typedIds = typed.mapTo(hashSetOf()) { it.id }
    val legacy = snapshot.downloads.values.map { it.toDownloadSurfaceEntry() }.filter { it.id !in typedIds }
    return DownloadSurface(legacy + typed)
}

internal fun DownloadRecord.toDownloadSurfaceEntry(): DownloadSurfaceEntry {
    val owned = asset
    val ready = status == DownloadStatus.COMPLETE && owned != null && owned.mediaId == mediaId &&
        owned.sizeBytes > 0L && owned.uri.isNotBlank()
    val checking = owned != null && !cleanupPending &&
        status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND)
    return DownloadSurfaceEntry(
        id = id, origin = DownloadSurfaceOrigin.LEGACY, mediaId = mediaId, status = status,
        bytesDownloaded = bytesDownloaded, totalBytes = totalBytes, failure = failure,
        updatedEpochMs = updatedEpochMs, ready = ready, checking = checking, hasOwnedBytes = owned != null,
        cleanupPending = cleanupPending, bindingId = owned?.executionBindingId,
        measuredDurationMs = owned?.measuredDurationMs, assetId = owned?.id, assetSizeBytes = owned?.sizeBytes,
    )
}

private fun typedEntries(presentation: DownloadAcquisitionPresentation,
    verifiedByJob: Map<String, VerifiedDownload>): List<DownloadSurfaceEntry> {
    val state = presentation.view.acquisition
    return state.physicalDownloads.values.mapNotNull { canonical ->
        val producer = canonical.producer as? PhysicalDownloadProducer.Typed ?: return@mapNotNull null
        val projected = presentation.states[canonical.id] ?: canonical
        val asset = canonical.assetId?.let(state.materializations::get)
        val mediaId = (state.subjects[producer.subjectId] as? ExecutableSubject.CatalogMedia)?.mediaId
        val verified = verifiedByJob[canonical.id]
        val validationTargets = retainedValidationTargets(presentation, projected, producer)
        DownloadSurfaceEntry(
            id = canonical.id,
            origin = if (mediaId == null) DownloadSurfaceOrigin.TYPED_SOURCE else DownloadSurfaceOrigin.TYPED_CATALOGUE,
            mediaId = mediaId,
            displayTitle = if (mediaId == null) sourceRecordingTitle(presentation, producer) else null,
            status = projected.status,
            bytesDownloaded = projected.bytesDownloaded,
            totalBytes = projected.totalBytes,
            failure = projected.failure,
            updatedEpochMs = projected.updatedEpochMs,
            ready = verified != null,
            checking = asset != null && !projected.cleanupPending &&
                projected.status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND),
            hasOwnedBytes = asset != null,
            cleanupPending = projected.cleanupPending,
            // Persisted exact producer binding is visible for catalogue rows even when this
            // process has not verified readiness (for example a retained FAILED row); it is not
            // an AssetUse/readiness grant. Neutral source rows never expose a binding.
            bindingId = (verified?.bindingId ?: producer.bindingId?.value)?.takeIf { mediaId != null },
            measuredDurationMs = asset?.measuredDurationMs,
            assetId = asset?.id?.value,
            assetSizeBytes = asset?.sizeBytes,
            retainedValidation = validationTargets.singleOrNull(),
            retainedValidationChoices = if (validationTargets.size > 1) validationTargets else emptyList(),
        )
    }
}

/**
 * Resolve every exact persisted validation target for the failed row. Repeated members that
 * request the same binding may share one physical job; the UI must present an explicit localized
 * choice among these exact member identities. Never choose a newest/first member. A changed batch
 * revision or mismatched member claim is omitted rather than acted on stale identity.
 */
private fun retainedValidationTargets(presentation: DownloadAcquisitionPresentation,
    job: dev.veyra.model.PhysicalDownloadJob,
    producer: PhysicalDownloadProducer.Typed,
): List<RetainedValidationTarget> {
    val state = presentation.view.acquisition
    if (presentation.view.snapshot.revision != state.libraryRevision) return emptyList()
    if (job.status != DownloadStatus.FAILED || job.assetId == null || job.cleanupPending) return emptyList()
    val bindingId = producer.bindingId
    return state.claims.values.asSequence()
        .filter { claim ->
            claim.physicalJobId == job.id && claim.requestedBindingId == bindingId &&
                (claim.intent != DownloadBatchIntent.CANCELLED || claim.retainCompletedAsset)
        }
        .mapNotNull { claim ->
            when (val owner = claim.owner) {
                is DownloadClaimOwner.Standalone, is DownloadClaimOwner.Schema2Standalone ->
                    RetainedValidationTarget(job.id, bindingId.value)
                is DownloadClaimOwner.BatchMember -> {
                    val batch = state.batches[owner.batchId] ?: return@mapNotNull null
                    val member = batch.members.singleOrNull { it.position == owner.position }
                        ?: return@mapNotNull null
                    if (batch.intentRevision != claim.ownerIntentRevision ||
                        member.claimId != claim.id || member.requiredBindingId != bindingId) return@mapNotNull null
                    RetainedValidationTarget(job.id, bindingId.value, batch.id.value, owner.position,
                        batch.intentRevision)
                }
            }
        }
        .distinct()
        .sortedWith(compareBy<RetainedValidationTarget> { it.memberPosition ?: -1 }.thenBy { it.batchId ?: "" })
        .toList()
}

/** Captured request title, then the actual observed entry title; never a synthesized catalogue row. */
private fun sourceRecordingTitle(presentation: DownloadAcquisitionPresentation,
    producer: PhysicalDownloadProducer.Typed): String? {
    val state = presentation.view.acquisition
    val binding = producer.bindingId.let(state.bindings::get) ?: return producer.recordingId?.value
    val receipt = state.receipts[binding.firstReceiptId] as? FirstResultReceipt.Observed
    val request = receipt?.observation?.requestId?.let(state.requests::get)
    return request?.request?.completeTitle?.takeIf { it.isNotBlank() }
        ?: receipt?.observation?.entry?.title?.takeIf { it.isNotBlank() }
        ?: producer.recordingId?.value
}

internal data class VerifiedDownload(
    val jobId: String,
    val assetId: String,
    val bindingId: String,
    val mediaId: String?,
    val recordingId: String,
    val createdEpochMs: Long = 0,
)

/**
 * Narrow read-only UI readiness projection. It mirrors the strict, existing
 * `AppAcquisitionOwner.hasVerifiedLocal` conditions over the SAME paired view/states; it does
 * not instantiate that owner, write Room, perform a file/native check, create an AssetUse or
 * grant playback. `successfulChecks` is deliberately NOT consumed: historical C0 ALONE is not
 * readiness. The projected states map must still equal the canonical COMPLETE job, which only
 * happens from current-process checked evidence. Neutral subjects never become Ready.
 */
internal fun DownloadAcquisitionPresentation.verifiedDownload(bindingId: ExecutionBindingId): VerifiedDownload? {
    val state = view.acquisition
    if (view.snapshot.revision != state.libraryRevision) return null
    return state.assetUses.asSequence()
        .filter { it.requestedBindingId == bindingId && it.compatibilityPolicyVersion == 1 }
        .mapNotNull { use -> verifiedUse(use.requestedBindingId, use.materializationAssetId, use.createdEpochMs) }
        .maxByOrNull { it.createdEpochMs }
}

internal fun verifiedDownloads(presentation: DownloadAcquisitionPresentation): List<VerifiedDownload> {
    val state = presentation.view.acquisition
    if (presentation.view.snapshot.revision != state.libraryRevision) return emptyList()
    return state.assetUses.asSequence()
        .filter { it.compatibilityPolicyVersion == 1 }
        .mapNotNull { use -> presentation.verifiedUse(use.requestedBindingId, use.materializationAssetId, use.createdEpochMs) }
        .toList()
}

private fun DownloadAcquisitionPresentation.verifiedUse(requestedBindingId: ExecutionBindingId,
    materializationAssetId: MaterializationAssetId, createdEpochMs: Long): VerifiedDownload? {
    val state = view.acquisition
    // Exact accepted-Single request/receipt/binding/subject/recording lineage, not a title/ID match.
    val context = try { state.executionContext(requestedBindingId) } catch (_: Exception) { return null }
    val asset = state.materializations[materializationAssetId] ?: return null
    val job = state.physicalDownloads.values.singleOrNull { it.assetId == asset.id } ?: return null
    if (asset.producer.subjectId != context.subject.id || asset.producer.recordingId != context.recording.id) return null
    if (asset.kind != OfflineAssetKind.FILE || asset.representationId.isNullOrBlank()) return null
    if (state.materializationReceipts[asset.id] != asset.receipt()) return null
    if (job.producer != asset.producer || job.status != DownloadStatus.COMPLETE) return null
    // Projected equality is the current-process checked evidence; successfulChecks is not used.
    if (states[job.id] != job) return null
    if (job.cleanupPending || job.maintenanceKind != null || job.leaseOwner != null) return null
    if (job.id in state.physicalFinalizations) return null
    if (job.representationId != asset.representationId || job.validatorGeneration != asset.validatorGeneration) return null
    val claimRetained = state.claims.values.any { claim ->
        claim.physicalJobId == job.id && claim.requestedBindingId == requestedBindingId &&
            (claim.intent != dev.veyra.model.DownloadBatchIntent.CANCELLED || claim.retainCompletedAsset)
    }
    if (!claimRetained) return null
    val removing = state.physicalDownloads.values.any { other ->
        other.producer.subjectId == context.subject.id &&
            (other.producer.recordingId == null || other.producer.recordingId == context.recording.id) &&
            (other.cleanupPending || other.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP)
    }
    if (removing) return null
    val mediaId = (context.subject as? ExecutableSubject.CatalogMedia)?.mediaId
    return VerifiedDownload(job.id, asset.id.value, requestedBindingId.value, mediaId,
        context.recording.id.value, createdEpochMs)
}
