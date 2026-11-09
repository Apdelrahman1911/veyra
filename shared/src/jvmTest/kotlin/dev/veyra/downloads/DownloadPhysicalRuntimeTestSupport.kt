package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** REAL owned paths/hash/OS leases; deliberately FAKE canonical commands, source and native gate. */
internal class PhysicalRuntimeFixture(
    private val removalOwnerGraceMs: Long = 5_000L,
) {
    val directory = physicalDownloadTestDirectory()
    val events = CopyOnWriteArrayList<String>()
    val leases = PhysicalRuntimeLeaseProbe(events)
    val files = DownloadFiles(directory.resolve("media").toString(), FileSystem.SYSTEM, DownloadFileLeaseFactory(leases::acquire))
    val repository = PhysicalRuntimeTestRepository(events)
    val clock = DownloadTestClock()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val barriers = CopyOnWriteArrayList<DownloadBarrier>()
    val gateway = PhysicalRuntimeGateway(events)
    val legacy = PhysicalRuntimeLegacyGateway(events)
    val gate = PhysicalRuntimeGate(events)
    val hostState = MutableStateFlow(DownloadPhysicalHostState(true, false, false, true, 0, 0, null,
        false, 2, DownloadNetworkState.CONNECTED))
    val cleanup = DownloadCleanupRegistry()
    val catalog = MutableStateFlow(AppSnapshot())
    @Volatile var coherentView: suspend () -> LibraryAcquisitionView = {
        // Deliberate fake read barrier; real same-owner Room publication is tested separately.
        val acquisition = repository.state.value
        LibraryAcquisitionView(catalog.value.copy(revision = acquisition.libraryRevision), acquisition)
    }
    @Volatile var signalWork: () -> Unit = { }
    val host = DownloadPhysicalHost(Mutex(), Mutex(), mutableMapOf(), cleanup, { hostState.value }, { coherentView() },
        { }, { signalWork() }, { events += "reported:${it.code}" }, { publishCleanupFailure() },
        { hostState.update { it.copy(failure = it.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED,
            "download_owner_drain_failed_restart_required")) } }, { expired(it) })
    val validated = CopyOnWriteArrayList<LocalMediaValidationInput>()
    @Volatile var validation: suspend (LocalMediaValidationInput) -> AssetValidation = { AssetValidation.Valid(733) }
    @Volatile var beforeDelete: suspend (MaterializationAsset) -> Unit = { }
    @Volatile var beforeAdoption: suspend (PhysicalDownloadProducer.Schema2) -> Unit = { }
    @Volatile var admission: () -> Unit = { }
    val acquisition = AcquisitionCoordinator(repository, AcquisitionTestGateway(), clock = clock,
        offline = { hostState.value.offlineOnly })
    val batches = DownloadBatchCoordinator(repository, acquisition, clock)
    val materializationEvidence = DownloadMaterializationEvidence(repository.transactionOwner)
    val dependencies = DownloadAcquisitionDependencies(repository, gateway, LocalMediaValidator {
        validated += it
        events += "validate"
        validation(it)
    }, { beforeDelete(it) }, { beforeAdoption(it) }, { admission() }, acquisition, batches, materializationEvidence)
    val runtime = DownloadPhysicalRuntime(repository, dependencies, files, legacy, EngineCapabilities(), gate,
        clock, leaseDurationMs = 60_000, heartbeatMs = 10_000, host = host,
        removalOwnerGraceMs = removalOwnerGraceMs)
    val contextR1 = physicalContext("r1")
    val contextR2 = physicalContext("r2")
    val memberR2 = AcquisitionDownloadMemberKey(DownloadBatchId("authored-batch"), 0, 0,
        ExecutionOccurrenceId("authored-occurrence"), contextR2.request.id, contextR2.binding.id)
    val claimR2 = DownloadClaim(DownloadClaimId("authored-r2-owner"), DownloadClaimOwner.BatchMember(memberR2.batchId, 0),
        JOB, contextR2.binding.id, DownloadBatchIntent.ACTIVE, true, 10_000)

    init {
        files.initialize()
        repository.seed(contextR1, contextR2, PhysicalDownloadJob(JOB, producer(contextR1)), listOf(claimR2))
        // Minimal authored fake-port lineage for the stronger read-only validation admission.
        // Real normalized observation/scope/Room lineage is exercised by the Room fixture.
        val scope = AdmissionScope(AdmissionScopeId("authored-validation-scope"), AdmissionScopeKind.SELECTED_SUBSET,
            null, null, PlaybackControlsMode.PODCAST, listOf(AdmissionScopeMember(0, null,
                contextR2.request.id, AdmissionDisposition.INCLUDED, null)), true, 10_000)
        val occurrence = ExecutionOccurrence(memberR2.occurrenceId, AcquisitionOrigin.TYPED, contextR2.request.id,
            null, AdmissionScopeMemberRef(scope.id, 0), contextR2.subject.id, contextR2.binding.id,
            scope.controlsMode, OccurrenceSelectionState.BOUND, 10_000)
        val batch = DownloadBatch(memberR2.batchId, scope.id, claimR2.intent, memberR2.intentRevision,
            listOf(DownloadBatchMember(0, 0, occurrence.id, contextR2.request.id, contextR2.binding.id,
                claimR2.id, DownloadBatchMemberState.AVAILABLE)), 10_000, 10_000)
        repository.state.value = repository.state.value.copy(scopes = mapOf(scope.id to scope),
            occurrences = mapOf(occurrence.id to occurrence), batches = mapOf(batch.id to batch))
    }

    fun barrier() = DownloadBarrier().also { barriers += it }

    suspend fun start(existingScope: CoroutineScope = scope, executor: DownloadPhysicalRuntime = runtime): DownloadPhysicalRun {
        val admittingJob = currentCoroutineContext().job
        return host.commands.withLock {
            host.activeLock.withLock {
                withContext(NonCancellable) {
                    val run = assertNotNull(executor.claimExecution(JOB, admittingJob))
                    executor.installExecution(run, existingScope)
                    run
                }
            }
        }
    }

    suspend fun joined(run: DownloadPhysicalRun) {
        withTimeout(10_000) { assertNotNull(run.epoch.job).join() }
    }

    fun currentJob() = repository.state.value.physicalDownloads.getValue(JOB)

    fun seedLegacy(savedKey: String? = null): PlaybackBinding {
        val item = authoredMedia("legacy-media", "Original complete title — العنوان التاريخي")
        val binding = PlaybackBinding("legacy-binding", item.id, item.title, FirstResultKind.VIDEO,
            "LMNOPQRSTUV", "Original recorded first result", null, "LMNOPQRSTUV", 2_000)
        val producer = PhysicalDownloadProducer.Schema2(item.id, ExecutionBindingId(binding.id), SourceRecordingId("legacy-c"))
        val receipt = FirstResultReceipt.Schema2(FirstResultReceiptId("legacy-receipt"), item.id, binding.queryTitle,
            binding.firstResultKind, binding.firstResultId, binding.firstResultTitle, binding.firstResultUrl,
            binding.selectedVideoId, binding.resolvedEpochMs, binding.selectionOrdinal)
        val execution = ExecutionBinding(ExecutionBindingId(binding.id), producer.subjectId, receipt.id,
            producer.recordingId, ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
        val recording = SourceRecording(checkNotNull(producer.recordingId), checkNotNull(binding.selectedVideoId),
            "https://www.youtube.com/watch?v=${binding.selectedVideoId}")
        val claim = DownloadClaim(DownloadClaimId("legacy-owner"), DownloadClaimOwner.Schema2Standalone(JOB), JOB,
            producer.bindingId, DownloadBatchIntent.ACTIVE, true, 2_000)
        catalog.value = AppSnapshot(media = mapOf(item.id to item), playbackBindings = mapOf(binding.id to binding))
        repository.savedLegacyBindings[binding.id] = binding
        repository.state.value = AcquisitionRepositoryState(0, receipts = mapOf(receipt.id to receipt),
            bindings = mapOf(execution.id to execution), recordings = mapOf(recording.id to recording),
            subjects = mapOf(producer.subjectId to ExecutableSubject.CatalogMedia(producer.subjectId, item.id)),
            physicalDownloads = mapOf(JOB to PhysicalDownloadJob(JOB, producer, representationId = savedKey)),
            claims = mapOf(claim.id to claim))
        return binding
    }

    fun seedFinalization(published: Boolean = false, attemptGeneration: Long = 3): PhysicalDownloadFinalization {
        val part = files.prepareStaging(contextR1.subject.id.value, "historical-attempt")
        Files.write(Path.of(part.toString()), PHYSICAL_BYTES)
        val final = files.finalPath(contextR1.subject.id.value, 7, "audio/mpeg", "historical-attempt")
        val attempt = PhysicalDownloadAttempt("historical-attempt", JOB, producer(contextR1), 4,
            attemptGeneration, DownloadAttemptStatus.FINALIZING, stagingPath = part.toString(),
            representationId = PHYSICAL_REPRESENTATION.representationId, validatorGeneration = 7)
        val intent = PhysicalDownloadFinalization(JOB, producer(contextR1), part.toString(), final.toString(),
            PHYSICAL_BYTES.toByteString().sha256().hex(), PHYSICAL_BYTES.size.toLong(), attempt.id, 733,
            "audio/mpeg", "mp3", PHYSICAL_REPRESENTATION.representationId, 7, attemptGeneration,
            MaterializationAssetId("historical-asset"))
        val old = repository.state.value
        repository.state.value = old.copy(physicalDownloads = mapOf(JOB to currentJob().copy(
            status = DownloadStatus.VERIFYING, stagingPath = part.toString(), representationId = intent.representationId,
            activeAttemptId = attempt.id, attemptCount = 4, ownershipGeneration = attemptGeneration, validatorGeneration = 7)),
            physicalAttempts = mapOf(attempt.id to attempt), physicalFinalizations = mapOf(JOB to intent))
        if (published) files.atomicPublish(intent.stagingPath, intent.finalPath)
        return intent
    }

    suspend fun seedComplete(): MaterializationAsset {
        val run = start()
        joined(run)
        return assertNotNull(repository.state.value.materializations.values.singleOrNull())
    }

    fun standaloneOnly(bindingId: ExecutionBindingId? = contextR1.binding.id,
        intent: DownloadBatchIntent = DownloadBatchIntent.ACTIVE, retain: Boolean = true): DownloadClaim {
        val schema2 = currentJob().producer is PhysicalDownloadProducer.Schema2
        val claim = DownloadClaim(DownloadClaimId("authored-standalone"), if (schema2) DownloadClaimOwner.Schema2Standalone(JOB)
            else DownloadClaimOwner.Standalone(JOB), JOB, bindingId, intent, retain, 10_000)
        repository.state.value = repository.state.value.copy(claims = mapOf(claim.id to claim))
        return claim
    }

    /** Historical non-executable receipt is retained, never rewritten into the new typed C. */
    fun seedUnknownLegacy(withStage: Boolean = false, withBinding: Boolean = true): AcquisitionExecutionContext {
        val item = authoredMedia("unknown-legacy-media", "Historical complete title — العنوان القديم")
        val oldId = ExecutionBindingId("unknown-legacy-binding")
        val legacy = PlaybackBinding(oldId.value, item.id, item.title, FirstResultKind.PLAYLIST,
            "PLauthored-history", "Historical first collection", null, null, 2_000)
        val receipt = FirstResultReceipt.Schema2(FirstResultReceiptId("unknown-legacy-receipt"), item.id, item.title,
            legacy.firstResultKind, legacy.firstResultId, legacy.firstResultTitle, null, null, 2_000, 0)
        val old = PhysicalDownloadProducer.Schema2(item.id, oldId.takeIf { withBinding }, null)
        val claim = DownloadClaim(DownloadClaimId("unknown-legacy-owner"), DownloadClaimOwner.Schema2Standalone(JOB),
            JOB, old.bindingId, DownloadBatchIntent.ACTIVE, true, 2_000)
        val part = if (withStage) files.prepareStaging(item.id, "unknown-retired-attempt").toString() else null
        if (part != null) {
            Files.write(Path.of(part), PHYSICAL_BYTES)
            Files.writeString(Path.of(part).parent.resolve("authored-private-journal"), "Authored staging marker, not source state")
        }
        val attempt = part?.let { PhysicalDownloadAttempt("unknown-retired-attempt", JOB, old, 1, 0,
            DownloadAttemptStatus.FAILED, 1_000, 2_000, PHYSICAL_BYTES.size.toLong(), stagingPath = it) }
        catalog.value = AppSnapshot(media = mapOf(item.id to item),
            playbackBindings = if (withBinding) mapOf(legacy.id to legacy) else emptyMap())
        repository.state.value = AcquisitionRepositoryState(0,
            receipts = if (withBinding) mapOf(receipt.id to receipt) else emptyMap(),
            bindings = if (withBinding) mapOf(oldId to ExecutionBinding(oldId, old.subjectId, receipt.id, null,
                ExecutionBindingOrigin.SCHEMA2_NON_EXECUTABLE)) else emptyMap(),
            subjects = mapOf(old.subjectId to ExecutableSubject.CatalogMedia(old.subjectId, item.id)),
            physicalDownloads = mapOf(JOB to PhysicalDownloadJob(JOB, old, stagingPath = part,
                bytesDownloaded = if (part != null) PHYSICAL_BYTES.size.toLong() else 0,
                attemptCount = if (attempt != null) 1 else 0)),
            physicalAttempts = listOfNotNull(attempt).associateBy { it.id }, claims = mapOf(claim.id to claim))
        return physicalMediaContext("explicit-legacy-adoption", item).also(repository::addContext)
    }

    private suspend fun expired(run: DownloadPhysicalRun) {
        host.commands.withLock {
            host.activeLock.withLock current@ {
                if (hostState.value.closed || hostState.value.generation != run.hostGeneration ||
                    !run.allowanceCallbacksOpen.value || run.actionFinished.value || run.epoch.job == null ||
                    host.active[run.fence.downloadId] !== run.epoch.job) return@current
                hostState.value = hostState.value.copy(suspended = true, generation = hostState.value.generation + 1)
                runtime.requestHostSuspension(DownloadStatus.NEEDS_FOREGROUND)
                host.active.values.forEach { it.cancel() }
            }
        }
    }

    private suspend fun publishCleanupFailure() {
        if (cleanup.owners.value.values.none { it.failed.value }) return
        host.commands.withLock {
            hostState.value = hostState.value.copy(suspended = true,
                failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_retained_owner"))
            host.activeLock.withLock { host.active.values.forEach { it.cancel() } }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        barriers.forEach { it.release() }
        signalWork = { }
        leases.beforeClose = { _, _ -> }
        gate.beforeClose = { }
        hostState.value = hostState.value.copy(closed = true, suspended = true)
        scope.coroutineContext.job.cancelAndJoin()
        for (owner in cleanup.owners.value.values.toList()) {
            if (owner is DownloadPhysicalCleanup) owner.runtime.attemptCleanup(owner, retryFailed = true, report = false)
            else if (owner is DownloadLeaseCleanup) { owner.close(); cleanup.remove(owner) }
        }
        assertEquals(0, leases.open.get(), "Test fixture must not leak an actual file lease")
        assertTrue(cleanup.owners.value.isEmpty(), "Test fixture retains every failed owner until explicit cleanup")
    }

    companion object { const val JOB = "authored-physical-job" }
}

internal fun physicalRuntimeCase(removalOwnerGraceMs: Long = 5_000L,
    block: suspend PhysicalRuntimeFixture.() -> Unit) = runBlocking {
    val fixture = PhysicalRuntimeFixture(removalOwnerGraceMs)
    try { withTimeout(30_000) { fixture.block() } }
    finally { fixture.close() }
}

internal val PHYSICAL_BYTES = "Veyra authored runtime bytes; NOT AUDIO. ليس تسجيلاً صوتياً.".encodeToByteArray()
internal val PHYSICAL_REPRESENTATION = AcquisitionRepresentation("authored-fixed-key", "audio/mpeg", "mp3", "mp3")

internal fun physicalContext(label: String): AcquisitionExecutionContext {
    val request = authoredRequest(label, AlbumCapture(Album("authored-edition-$label", "Complete edition title $label — العنوان الكامل")))
        .copy(capturedEpochMs = 1_000)
    val selected = authoredSingle(request)
    val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("receipt-$label"), selected.receipt, 10_000)
    val recording = SourceRecording(SourceRecordingId("authored-recording-c"), selected.recording.id, selected.recording.publicUrl)
    val subject = ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("authored-neutral-subject"), recording.id)
    val binding = ExecutionBinding(ExecutionBindingId("binding-$label"), subject.id, receipt.id, recording.id, ExecutionBindingOrigin.TYPED)
    return AcquisitionExecutionContext(request, receipt, binding, subject, recording)
}

internal fun physicalMediaContext(label: String, item: MediaItem,
    recordingId: SourceRecordingId = SourceRecordingId("authored-recording-c")): AcquisitionExecutionContext {
    val request = authoredRequest(label, MediaCapture(item)).copy(capturedEpochMs = 1_000)
    val selected = authoredSingle(request)
    val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("receipt-$label"), selected.receipt, 10_000)
    val recording = SourceRecording(recordingId, selected.recording.id, selected.recording.publicUrl)
    val subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId(item.id), item.id)
    val binding = ExecutionBinding(ExecutionBindingId("binding-$label"), subject.id, receipt.id, recording.id, ExecutionBindingOrigin.TYPED)
    return AcquisitionExecutionContext(request, receipt, binding, subject, recording)
}

internal fun producer(context: AcquisitionExecutionContext) = PhysicalDownloadProducer.Typed(
    context.subject.id, context.binding.id, context.recording.id)

internal class PhysicalRuntimeLeaseProbe(private val events: MutableList<String>) {
    val open = AtomicInteger()
    @Volatile var beforeClose: (String, Int) -> Unit = { _, _ -> }
    @Volatile var refuse: (String) -> Boolean = { false }
    fun acquire(path: String): DownloadFileLease? {
        val name = Path.of(path).fileName.toString()
        if (refuse(name)) return null
        val actual = tryAcquireDownloadFileLease(path) ?: return null
        open.incrementAndGet()
        return object : DownloadFileLease {
            private var consumed = false
            private var calls = 0
            override fun close() {
                if (consumed) return
                events += "close:$name"
                beforeClose(name, ++calls)
                actual.close()
                consumed = true
                open.decrementAndGet()
            }
        }
    }
}

internal class PhysicalRuntimeGate(private val events: MutableList<String>) : DownloadExecutionGate {
    val acquires = AtomicInteger()
    val closes = AtomicInteger()
    val expirations = CopyOnWriteArrayList<suspend () -> Unit>()
    @Volatile var beforeAcquire: suspend (suspend () -> Unit) -> Unit = { }
    @Volatile var beforeClose: suspend () -> Unit = { }
    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        acquires.incrementAndGet()
        expirations += onExpiration
        beforeAcquire(onExpiration)
        var consumed = false
        return DownloadExecutionLease {
            if (!consumed) {
                closes.incrementAndGet()
                events += "allowance-close"
                beforeClose()
                consumed = true
            }
        }
    }
}

internal class PhysicalRuntimeGateway(private val events: MutableList<String>) : AcquisitionExecutionGateway {
    val preparations = CopyOnWriteArrayList<PreparedAcquisitionDownload>()
    val transfers = CopyOnWriteArrayList<PreparedAcquisitionDownload>()
    @Volatile var prepareResult: (AcquisitionExecutionContext, String?) -> PreparedAcquisitionDownload = { context, key ->
        object : PreparedAcquisitionDownload {
            override val context = context
            override val requiredRepresentationId = key
            override val representation = PHYSICAL_REPRESENTATION
        }
    }
    @Volatile var transfer: suspend (PreparedAcquisitionDownload, String, suspend (AcquisitionTransferProgress) -> Unit) -> AcquisitionTransferResult =
        { prepared, path, progress ->
            Files.write(Path.of(path), PHYSICAL_BYTES)
            progress(AcquisitionTransferProgress(PHYSICAL_BYTES.size.toLong(), PHYSICAL_BYTES.size.toLong()))
            AcquisitionTransferResult(prepared.context, PHYSICAL_BYTES.size.toLong(), prepared.representation)
        }
    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource = error("Playback is forbidden in this download fixture")
    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
        events += "prepare"
        return prepareResult(context, requiredRepresentationId).also { preparations += it }
    }
    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
        transfers += prepared
        events += "transfer"
        return transfer(prepared, destinationPath, onProgress)
    }
}

internal class PhysicalRuntimeLegacyGateway(private val events: MutableList<String>,
    val split: DownloadTestGateway = DownloadTestGateway().apply { denyAcquisition() }) :
    KmpMediaGateway by split, LegacyPreparedDownloadGateway {
    val preparations = CopyOnWriteArrayList<PreparedLegacyDownload>()
    val transfers = CopyOnWriteArrayList<PreparedLegacyDownload>()
    override suspend fun prepareDownload(binding: PlaybackBinding, capabilities: EngineCapabilities,
        requiredRepresentationId: String?): PreparedLegacyDownload {
        events += "legacy-prepared"
        return object : PreparedLegacyDownload {
            override val binding = binding
            override val requiredRepresentationId = requiredRepresentationId
            override val representation = PHYSICAL_REPRESENTATION.copy(
                representationId = requiredRepresentationId ?: PHYSICAL_REPRESENTATION.representationId)
        }.also { preparations += it }
    }
    override suspend fun download(prepared: PreparedLegacyDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): LegacyTransferResult {
        transfers += prepared
        Files.write(Path.of(destinationPath), PHYSICAL_BYTES)
        onProgress(AcquisitionTransferProgress(PHYSICAL_BYTES.size.toLong(), PHYSICAL_BYTES.size.toLong()))
        return LegacyTransferResult(prepared.binding, PHYSICAL_BYTES.size.toLong(), prepared.representation)
    }
    fun assertUnused() {
        split.assertUnused()
        assertTrue(preparations.isEmpty())
        assertTrue(transfers.isEmpty())
    }
}

internal enum class PhysicalFinishBehavior { CONFIRMED, STALE, UNCONFIRMED }

/**
 * Test double for the narrow runtime protocol, NOT the Room implementation or its validation.
 * Commands capture exact arguments and preserve original materializer history. Tests against
 * this class must never be reported as proving canonical transaction/readback/migration rules.
 */
internal class PhysicalRuntimeTestRepository(private val events: MutableList<String>,
    private val acquisition: AcquisitionTestRepository = AcquisitionTestRepository()) :
    AcquisitionLibraryRepository, AcquisitionRepository by acquisition {
    override val transactionOwner get() = acquisition.transactionOwner
    override val state get() = acquisition.state
    override suspend fun playbackView(): PlaybackAcquisitionView = error("Playback is outside this narrow runtime fake")
    override suspend fun applyPlayback(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView =
        error("Playback is outside this narrow runtime fake")
    private val lock = Mutex()
    val contexts = mutableMapOf<ExecutionBindingId, AcquisitionExecutionContext>()
    val attemptClaims = CopyOnWriteArrayList<ClaimPhysicalDownloadAttempt>()
    val finishes = CopyOnWriteArrayList<FinishPhysicalDownloadAttempt>()
    val maintenanceClaims = CopyOnWriteArrayList<ClaimPhysicalDownloadMaintenance>()
    val maintenanceFinishes = CopyOnWriteArrayList<FinishPhysicalDownloadMaintenance>()
    val publications = CopyOnWriteArrayList<PublishPhysicalDownloadFinalization>()
    val uses = CopyOnWriteArrayList<CommitMaterializationUse>()
    val standaloneAdmissions = CopyOnWriteArrayList<AdmitStandalonePhysicalDownload>()
    val schema2Admissions = CopyOnWriteArrayList<AdmitSchema2StandalonePhysicalDownload>()
    val standaloneIntents = CopyOnWriteArrayList<UpdateStandaloneDownloadIntent>()
    val standaloneUses = CopyOnWriteArrayList<CommitStandaloneMaterializationUse>()
    val adoptions = CopyOnWriteArrayList<AdoptSchema2PhysicalDownload>()
    val savedLegacyBindings = mutableMapOf<String, PlaybackBinding>()
    @Volatile var afterStandaloneAdmission: suspend () -> Unit = { }
    @Volatile var beforeAdoptionCommit: (AdoptSchema2PhysicalDownload) -> Unit = { }
    @Volatile var afterAttemptClaim: suspend () -> Unit = { }
    @Volatile var finishBehavior = PhysicalFinishBehavior.CONFIRMED
    @Volatile var maintenanceBehavior = PhysicalFinishBehavior.CONFIRMED
    /** Test-only coherent committed-identity oracle; default preserves the canonical claim. */
    @Volatile var maintenanceClaimOutcome: (ClaimPhysicalDownloadMaintenance, ClaimedPhysicalDownload) -> ClaimedPhysicalDownload =
        { _, value -> value }

    fun addContext(context: AcquisitionExecutionContext) {
        contexts[context.binding.id] = context
        state.value = state.value.copy(
            requests = state.value.requests + (context.request.id to AcquisitionRecord(context.request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(context.request.id, context.receipt.id, context.binding.id), updatedEpochMs = 10_000)),
            receipts = state.value.receipts + (context.receipt.id to context.receipt),
            recordings = state.value.recordings + (context.recording.id to context.recording),
            subjects = state.value.subjects + (context.subject.id to context.subject),
            bindings = state.value.bindings + (context.binding.id to context.binding))
    }

    fun seed(r1: AcquisitionExecutionContext, r2: AcquisitionExecutionContext, job: PhysicalDownloadJob, claims: List<DownloadClaim>) {
        contexts[r1.binding.id] = r1
        contexts[r2.binding.id] = r2
        state.value = AcquisitionRepositoryState(0,
            requests = listOf(r1, r2).associate { it.request.id to AcquisitionRecord(it.request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(it.request.id, it.receipt.id, it.binding.id), updatedEpochMs = 10_000) },
            receipts = listOf(r1.receipt, r2.receipt).associateBy { it.id }, recordings = mapOf(r1.recording.id to r1.recording),
            subjects = mapOf(r1.subject.id to r1.subject), bindings = listOf(r1.binding, r2.binding).associateBy { it.id },
            claims = claims.associateBy { it.id }, physicalDownloads = mapOf(job.id to job))
    }

    private fun joined(id: String): PhysicalDownloadJoin? {
        val current = state.value
        val job = current.physicalDownloads[id] ?: return null
        val attempts = current.physicalAttempts.values.filter { it.downloadId == id }
        val intent = current.physicalFinalizations[id]
        val asset = job.assetId?.let(current.materializations::get)
        val attempt = job.activeAttemptId?.let(current.physicalAttempts::get)
        val claims = current.claims.values.filter { it.physicalJobId == id }
        val reservation = if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP && job.leaseOwner != null)
            PhysicalDownloadCleanupReservation(PhysicalDownloadFence(id, job.producer, job.ownershipGeneration,
                job.leaseOwner, attempt?.id, attempt?.number),
                setOfNotNull(job.stagingPath, intent?.stagingPath) + attempts.mapNotNull { it.stagingPath }, intent,
                asset, job.removeWhenClean, job.removeWhenClean) else null
        return PhysicalDownloadJoin(current.libraryRevision, job, attempt, intent, asset, claims,
            job.producer.bindingId?.let(contexts::get), attempts, reservation)
    }

    private fun publish(next: AcquisitionRepositoryState) { state.value = next.copy(libraryRevision = state.value.libraryRevision + 1) }
    private fun committed(id: String) = PhysicalDownloadCommit.Committed(state.value.libraryRevision, checkNotNull(joined(id)))
    private fun matches(fence: PhysicalDownloadFence): Boolean = joined(fence.downloadId)?.job?.let {
        it.producer == fence.producer && it.ownershipGeneration == fence.ownershipGeneration && it.leaseOwner == fence.leaseOwner
    } == true

    override suspend fun physicalDownload(id: String) = lock.withLock { joined(id) }

    /** Narrow independent fake: records executor selectors; NOT canonical Room test coverage. */
    private fun standalone(producer: PhysicalDownloadProducer, proposedJob: String, proposedClaim: DownloadClaimId,
        requested: ExecutionBindingId, now: Long, schema2: Boolean): Pair<DownloadClaim, PhysicalDownloadJoin> {
        val previous = state.value.physicalDownloads.values.singleOrNull { it.producer.subjectId == producer.subjectId }
        if (previous != null && (previous.producer.recordingId == null || previous.producer.recordingId != producer.recordingId ||
            previous.cleanupPending)) throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "authored_conflicting_recording"))
        val job = previous ?: PhysicalDownloadJob(proposedJob, producer, createdEpochMs = now, updatedEpochMs = now)
        val existing = state.value.claims.values.singleOrNull { it.physicalJobId == job.id && it.owner !is DownloadClaimOwner.BatchMember }
        val claim = existing ?: DownloadClaim(proposedClaim, if (schema2) DownloadClaimOwner.Schema2Standalone(job.id)
            else DownloadClaimOwner.Standalone(job.id), job.id, requested, DownloadBatchIntent.ACTIVE, true, now)
        publish(state.value.copy(physicalDownloads = state.value.physicalDownloads + (job.id to job),
            claims = state.value.claims + (claim.id to claim)))
        return claim to checkNotNull(joined(job.id))
    }

    override suspend fun admitStandalonePhysicalDownload(command: AdmitStandalonePhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission> {
        val result = lock.withLock {
            standaloneAdmissions += command
            events += "admit-standalone"
            val context = contexts.getValue(command.requiredBindingId)
            check(context.request.id == command.requestId)
            val (claim, join) = standalone(producer(context), command.proposedDownloadId, command.proposedClaimId,
                command.requiredBindingId, command.nowEpochMs, schema2 = false)
            PhysicalDownloadCommit.Committed(state.value.libraryRevision, StandalonePhysicalDownloadAdmission(context, claim, join))
        }
        afterStandaloneAdmission()
        return result
    }

    override suspend fun admitSchema2StandalonePhysicalDownload(command: AdmitSchema2StandalonePhysicalDownload): PhysicalDownloadCommit<Schema2StandalonePhysicalDownloadAdmission> = lock.withLock {
        schema2Admissions += command
        events += "admit-schema2"
        val original = savedLegacyBindings.getValue(command.requiredBindingId.value)
        val binding = state.value.bindings.getValue(command.requiredBindingId)
        check(original.mediaId == command.mediaId && binding.origin == ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
        val producer = PhysicalDownloadProducer.Schema2(command.mediaId, binding.id, binding.recordingId)
        val (claim, join) = standalone(producer, command.proposedDownloadId, command.proposedClaimId,
            command.requiredBindingId, command.nowEpochMs, schema2 = true)
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, Schema2StandalonePhysicalDownloadAdmission(original, claim, join))
    }

    override suspend fun updateStandaloneDownloadIntent(command: UpdateStandaloneDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin> = lock.withLock {
        standaloneIntents += command
        events += "standalone-intent:${command.intent}"
        val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
        val claim = before.claims.singleOrNull { it.id == command.claimId } ?: return@withLock PhysicalDownloadCommit.Stale
        if (before.job.producer != command.expectedProducer || before.job.ownershipGeneration != command.expectedOwnershipGeneration ||
            claim.generation != command.expectedClaimGeneration || claim.ownerIntentRevision != command.expectedOwnerIntentRevision ||
            claim.owner is DownloadClaimOwner.BatchMember || before.job.cleanupPending && command.intent != DownloadBatchIntent.CANCELLED)
            return@withLock PhysicalDownloadCommit.Stale
        if (command.intent == DownloadBatchIntent.ACTIVE && before.job.producer.recordingId == null)
            throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required"))
        val updated = if (claim.intent == command.intent && claim.retainCompletedAsset == command.retainCompletedAsset && !command.allowRetryFailure)
            claim else claim.copy(intent = command.intent, retainCompletedAsset = command.retainCompletedAsset,
                generation = claim.generation + 1, ownerIntentRevision = claim.ownerIntentRevision + 1, updatedEpochMs = command.nowEpochMs)
        publish(state.value.copy(claims = state.value.claims + (claim.id to updated)))
        val joined = checkNotNull(joined(command.downloadId))
        publish(jobState(reconciledJob(joined, command.allowRetryFailure)))
        committed(command.downloadId)
    }

    override suspend fun commitStandaloneMaterializationUse(command: CommitStandaloneMaterializationUse): PhysicalDownloadCommit<AssetUse> = lock.withLock {
        standaloneUses += command
        val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
        val claim = before.claims.singleOrNull { it.id == command.claimId } ?: return@withLock PhysicalDownloadCommit.Stale
        if (before.job.ownershipGeneration != command.expectedOwnershipGeneration || claim.generation != command.expectedClaimGeneration ||
            before.asset != command.expectedAsset || before.job.cleanupPending || claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset)
            return@withLock PhysicalDownloadCommit.Stale
        val use = AssetUse(command.requestedBindingId, command.expectedAsset.id, command.compatibilityPolicyVersion, command.nowEpochMs)
        publish(state.value.copy(assetUses = state.value.assetUses + use))
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, use)
    }

    override suspend fun adoptSchema2PhysicalDownload(command: AdoptSchema2PhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission> = lock.withLock {
        val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
        val claim = before.claims.singleOrNull { it.id == command.claimId } ?: return@withLock PhysicalDownloadCommit.Stale
        if (before.job.producer != command.expectedProducer || before.job.ownershipGeneration != command.expectedOwnershipGeneration ||
            claim.generation != command.expectedClaimGeneration || claim.ownerIntentRevision != command.expectedOwnerIntentRevision)
            return@withLock PhysicalDownloadCommit.Stale
        check(before.job.assetId == null && before.job.stagingPath == null && before.finalization == null && !before.job.cleanupPending)
        check(claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset)
        beforeAdoptionCommit(command)
        command.drainReceipt.consume(transactionOwner, before.job, before.claims)
        adoptions += command
        events += "adopted"
        val context = contexts.getValue(command.requiredBindingId)
        check(context.request.id == command.requestId && context.subject.id == before.job.producer.subjectId)
        val updated = claim.copy(intent = DownloadBatchIntent.ACTIVE, retainCompletedAsset = true,
            generation = claim.generation + 1, ownerIntentRevision = claim.ownerIntentRevision + 1, updatedEpochMs = command.nowEpochMs)
        val job = before.job.copy(producer = producer(context), ownershipGeneration = before.job.ownershipGeneration + 1,
            status = DownloadStatus.QUEUED, representationId = null, etag = null, lastModified = null,
            validatorGeneration = 0, bytesDownloaded = 0, totalBytes = null, userPaused = false, removeWhenClean = false,
            failure = null, nextRetryEpochMs = null, automaticRetryCount = 0, lastProgressEpochMs = null)
        publish(jobState(job).copy(claims = state.value.claims + (updated.id to updated)))
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, StandalonePhysicalDownloadAdmission(context, updated, checkNotNull(joined(job.id))))
    }

    override suspend fun retryBatchPhysicalDownload(command: RetryBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission> =
        error("Explicit retry tests require the canonical real Room command")

    override suspend fun admitBatchPhysicalDownload(command: AdmitBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission> = lock.withLock {
        events += "admit-member"
        val claim = state.value.claims.values.single { it.owner == DownloadClaimOwner.BatchMember(command.member.batchId, command.member.memberPosition) }
        val physical = checkNotNull(joined(claim.physicalJobId))
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, BatchPhysicalDownloadAdmission(command.member,
            claim, contexts.getValue(command.member.requestedBindingId), physical))
    }
    override suspend fun reconcilePhysicalDownloadClaim(command: ReconcilePhysicalDownloadClaim): PhysicalDownloadCommit<PhysicalDownloadJoin> = lock.withLock {
        val claim = state.value.claims[command.claimId] ?: return@withLock PhysicalDownloadCommit.Stale
        if (claim.generation != command.expectedClaimGeneration) return@withLock PhysicalDownloadCommit.Stale
        committed(claim.physicalJobId)
    }

    override suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload> {
        val result = lock.withLock {
            attemptClaims += command
            val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
            if (before.job.producer != command.expectedProducer || before.job.ownershipGeneration != command.expectedOwnershipGeneration ||
                before.job.livePhysicalLease(command.nowEpochMs)) return@withLock PhysicalDownloadCommit.Stale
            val fence = PhysicalDownloadFence(command.downloadId, command.expectedProducer, command.expectedOwnershipGeneration + 1,
                command.owner, command.attemptId, before.job.attemptCount + 1)
            val attempt = PhysicalDownloadAttempt(command.attemptId, command.downloadId, command.expectedProducer,
                checkNotNull(fence.attemptNumber), fence.ownershipGeneration, startedEpochMs = command.nowEpochMs,
                stagingPath = command.stagingPath, representationId = before.job.representationId,
                validatorGeneration = before.job.validatorGeneration)
            val newPath = command.stagingPath != before.job.stagingPath
            val job = before.job.copy(status = DownloadStatus.RESOLVING, activeAttemptId = attempt.id,
                attemptCount = attempt.number, ownershipGeneration = fence.ownershipGeneration, leaseOwner = command.owner,
                leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs, stagingPath = command.stagingPath,
                bytesDownloaded = if (newPath) 0 else before.job.bytesDownloaded,
                totalBytes = if (newPath) null else before.job.totalBytes,
                etag = if (newPath) null else before.job.etag, lastModified = if (newPath) null else before.job.lastModified)
            publish(state.value.copy(physicalDownloads = state.value.physicalDownloads + (job.id to job),
                physicalAttempts = state.value.physicalAttempts + (attempt.id to attempt)))
            events += "attempt-claimed"
            PhysicalDownloadCommit.Committed(state.value.libraryRevision, ClaimedPhysicalDownload(fence, checkNotNull(joined(job.id))))
        }
        if (result is PhysicalDownloadCommit.Committed) afterAttemptClaim()
        return result
    }

    private suspend fun owned(fence: PhysicalDownloadFence, matchesExtra: (PhysicalDownloadJoin) -> Boolean = { true },
        change: (PhysicalDownloadJoin) -> AcquisitionRepositoryState): PhysicalDownloadCommit<PhysicalDownloadJoin> = lock.withLock {
        if (!matches(fence)) return@withLock PhysicalDownloadCommit.Stale
        val before = checkNotNull(joined(fence.downloadId))
        if (!matchesExtra(before)) return@withLock PhysicalDownloadCommit.Stale
        publish(change(before))
        committed(fence.downloadId)
    }
    private fun jobState(job: PhysicalDownloadJob) = state.value.copy(physicalDownloads = state.value.physicalDownloads + (job.id to job))

    override suspend fun heartbeatPhysicalDownload(command: HeartbeatPhysicalDownload) = owned(command.fence) { before ->
        jobState(before.job.copy(bytesDownloaded = command.bytesDownloaded, totalBytes = command.totalBytes,
            leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs))
    }
    override suspend fun selectPhysicalDownloadRepresentation(command: SelectPhysicalDownloadRepresentation) = owned(command.fence) { before ->
        check(before.job.representationId == null || before.job.representationId == command.representationId)
        jobState(before.job.copy(status = DownloadStatus.RUNNING, representationId = command.representationId)).copy(
            physicalAttempts = state.value.physicalAttempts.mapValues { (_, attempt) ->
                if (attempt.id == command.fence.attemptId) attempt.copy(representationId = command.representationId,
                    validatorGeneration = command.validatorGeneration) else attempt
            })
    }
    override suspend fun beginPhysicalDownloadValidation(command: BeginPhysicalDownloadValidation) = owned(command.fence) { before ->
        events += "begin-validation"
        jobState(before.job.copy(status = DownloadStatus.VERIFYING))
    }
    override suspend fun preparePhysicalDownloadFinalization(command: PreparePhysicalDownloadFinalization) = owned(command.fence) { before ->
        events += "finalization-reserved"
        jobState(before.job).copy(physicalFinalizations = state.value.physicalFinalizations + (before.job.id to command.finalization))
    }
    override suspend fun publishPhysicalDownloadFinalization(command: PublishPhysicalDownloadFinalization) = owned(command.fence) { before ->
        check(before.finalization == command.expectedFinalization)
        publications += command
        events += "published"
        jobState(before.job.copy(status = DownloadStatus.COMPLETE, assetId = command.asset.id, stagingPath = null,
            activeAttemptId = null, leaseOwner = null, leaseExpiresEpochMs = null, maintenanceKind = null)).copy(
            physicalFinalizations = state.value.physicalFinalizations - before.job.id,
            physicalAttempts = state.value.physicalAttempts.mapValues { (_, attempt) ->
                if (attempt.id == command.expectedFinalization.attemptId) attempt.copy(status = DownloadAttemptStatus.SUCCEEDED) else attempt
            },
            materializations = state.value.materializations + (command.asset.id to command.asset),
            materializationReceipts = state.value.materializationReceipts + (command.asset.id to command.asset.receipt()))
    }

    override suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        finishes += command
        events += "finish-attempt"
        when (finishBehavior) {
            PhysicalFinishBehavior.STALE -> return PhysicalDownloadCommit.Stale
            PhysicalFinishBehavior.UNCONFIRMED -> throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_durable_failure"))
            PhysicalFinishBehavior.CONFIRMED -> Unit
        }
        return owned(command.fence) { before ->
            val demand = summarizeDownloadClaims(before.job.id, before.claims).demand
            val status = when (demand) {
                DownloadClaimDemand.PAUSE_AND_RETAIN -> DownloadStatus.PAUSED
                DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE, DownloadClaimDemand.REMOVE_CANDIDATE -> DownloadStatus.CANCELLED
                else -> command.status
            }
            val attempts = state.value.physicalAttempts.mapValues { (_, attempt) ->
                if (attempt.id == command.fence.attemptId) attempt.copy(status = command.attemptStatus,
                    finishedEpochMs = command.nowEpochMs, failure = command.failure) else attempt
            }
            jobState(before.job.copy(status = status, failure = command.failure, nextRetryEpochMs = command.nextRetryEpochMs,
                automaticRetryCount = command.automaticRetryCount, leaseOwner = null, leaseExpiresEpochMs = null,
                activeAttemptId = null)).copy(physicalAttempts = attempts)
        }
    }

    override suspend fun reconcilePhysicalDownloadIntent(command: ReconcilePhysicalDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin> = lock.withLock {
        val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
        if (before.job.producer != command.expectedProducer || before.job.ownershipGeneration != command.expectedOwnershipGeneration ||
            before.claims.toSet() != command.expectedClaims.toSet()) return@withLock PhysicalDownloadCommit.Stale
        val job = reconciledJob(before, command.allowHostResume)
        publish(jobState(job))
        committed(job.id)
    }

    private fun reconciledJob(before: PhysicalDownloadJoin, allowHostResume: Boolean): PhysicalDownloadJob {
        val demand = summarizeDownloadClaims(before.job.id, before.claims).demand
        val hasStages = before.job.stagingPath != null || before.finalization != null ||
            before.attempts.any { it.stagingPath != null }
        return when (demand) {
            DownloadClaimDemand.PAUSE_AND_RETAIN -> before.job.copy(userPaused = true,
                status = if (before.job.status == DownloadStatus.COMPLETE || before.job.leaseOwner != null) before.job.status else DownloadStatus.PAUSED)
            DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE -> if (before.job.status == DownloadStatus.COMPLETE) before.job
                else before.job.copy(userPaused = true, status = DownloadStatus.CANCELLED,
                    cleanupPending = before.job.cleanupPending || before.job.stagingPath != null || before.finalization != null)
            DownloadClaimDemand.REMOVE_CANDIDATE -> before.job.copy(userPaused = true, status = DownloadStatus.CANCELLED,
                removeWhenClean = true, cleanupPending = before.job.cleanupPending || before.asset != null || before.job.stagingPath != null || before.finalization != null ||
                    hasStages && !before.job.removeWhenClean)
            DownloadClaimDemand.CONTINUE -> if (allowHostResume) before.job.copy(userPaused = false,
                status = if (before.asset == null) DownloadStatus.QUEUED else DownloadStatus.COMPLETE, failure = null)
                else before.job.copy(userPaused = false)
            DownloadClaimDemand.UNOWNED_UNKNOWN -> before.job
        }
    }

    override suspend fun claimPhysicalDownloadMaintenance(command: ClaimPhysicalDownloadMaintenance): PhysicalDownloadCommit<ClaimedPhysicalDownload> = lock.withLock {
        maintenanceClaims += command
        val before = joined(command.downloadId) ?: return@withLock PhysicalDownloadCommit.Stale
        if (before.job.producer != command.expectedProducer || before.job.ownershipGeneration != command.expectedOwnershipGeneration ||
            before.job.livePhysicalLease(command.nowEpochMs)) return@withLock PhysicalDownloadCommit.Stale
        val attempt = if (command.kind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION)
            before.finalization?.attemptId?.let { state.value.physicalAttempts.getValue(it) } else before.activeAttempt
        val fence = PhysicalDownloadFence(command.downloadId, command.expectedProducer, command.expectedOwnershipGeneration + 1,
            command.owner, attempt?.id, attempt?.number)
        publish(jobState(before.job.copy(ownershipGeneration = fence.ownershipGeneration, leaseOwner = command.owner,
            leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs, maintenanceKind = command.kind)))
        val normal = ClaimedPhysicalDownload(fence, checkNotNull(joined(command.downloadId)))
        val returned = maintenanceClaimOutcome(command, normal)
        if (returned !== normal) {
            publish(state.value.copy(
                physicalDownloads = state.value.physicalDownloads + (command.downloadId to returned.physical.job),
                claims = state.value.claims + returned.physical.claims.associateBy { it.id }))
        }
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, returned)
    }
    override suspend fun finishPhysicalDownloadMaintenance(command: FinishPhysicalDownloadMaintenance): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        maintenanceFinishes += command
        events += "finish-maintenance"
        when (maintenanceBehavior) {
            PhysicalFinishBehavior.STALE -> return PhysicalDownloadCommit.Stale
            PhysicalFinishBehavior.UNCONFIRMED -> throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_durable_failure"))
            PhysicalFinishBehavior.CONFIRMED -> Unit
        }
        return owned(command.fence, matchesExtra = { before ->
            before.job.maintenanceKind == command.kind && before.claims.size == command.expectedClaims.size &&
                command.expectedClaims.map { it.id }.toSet().size == command.expectedClaims.size &&
                before.claims.toSet() == command.expectedClaims.toSet()
        }) { before ->
            val cleaned = command.kind == PhysicalDownloadMaintenanceKind.CLEANUP && command.cleanupCompleted
            val removeAsset = cleaned && before.job.removeWhenClean
            jobState(before.job.copy(leaseOwner = null, leaseExpiresEpochMs = null, maintenanceKind = null,
                status = command.status ?: before.job.status, failure = command.failure, nextRetryEpochMs = command.nextRetryEpochMs,
                stagingPath = if (cleaned) null else before.job.stagingPath, cleanupPending = if (cleaned) false else before.job.cleanupPending,
                activeAttemptId = if (cleaned) null else before.job.activeAttemptId,
                bytesDownloaded = if (cleaned && before.asset == null || removeAsset) 0 else before.job.bytesDownloaded,
                totalBytes = if (cleaned && before.asset == null || removeAsset) null else before.job.totalBytes,
                etag = if (cleaned) null else before.job.etag, lastModified = if (cleaned) null else before.job.lastModified,
                assetId = if (removeAsset) null else before.job.assetId)).copy(
                physicalFinalizations = if (cleaned) state.value.physicalFinalizations - before.job.id else state.value.physicalFinalizations,
                materializations = if (removeAsset) state.value.materializations - setOfNotNull(before.job.assetId) else state.value.materializations)
        }
    }
    override suspend fun commitMaterializationUse(command: CommitMaterializationUse): PhysicalDownloadCommit<AssetUse> = lock.withLock {
        uses += command
        val use = AssetUse(command.member.requestedBindingId, command.expectedAsset.id, command.compatibilityPolicyVersion, command.nowEpochMs)
        publish(state.value.copy(assetUses = state.value.assetUses + use))
        PhysicalDownloadCommit.Committed(state.value.libraryRevision, use)
    }
}
