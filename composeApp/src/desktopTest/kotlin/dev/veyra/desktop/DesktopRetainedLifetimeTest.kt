package dev.veyra.desktop

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.app.AppContainer
import dev.veyra.app.AppInitialization
import dev.veyra.app.PlatformServices
import dev.veyra.data.executionContext
import dev.veyra.data.getOrThrow
import dev.veyra.data.StoreStatus
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.desktop.DesktopServiceResources.Slot
import dev.veyra.desktop.runtime.AlreadyRunningException
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.DesktopStartupJobs
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.execution.AcquisitionExecutionGateway
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.AcquisitionRepresentation
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.execution.PreparedAcquisitionDownload
import dev.veyra.source.execution.SourceCollectionPage
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpBytes
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.SafeHttpResponse
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.ui.music.MusicUiAction
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Actual AppContainer/download ownership and Room, plus real repository-private process locks.
 * The public Download action runs through canonical typed capture/selection/admission, the real
 * physical job/claim/attempt/finalization owner and owned files on disk. Source selection,
 * transfer, native local validation and the OS execution allowance are explicit authored fakes
 * over authored NON-AUDIO bytes; Room, files, application lifetime, host lifetime and OS process
 * locks are real. This is a host/OS-lock lifetime regression, NOT native playback verification.
 */
class DesktopRetainedLifetimeTest {
    private class NoAudioEngine : AudioEngine {
        override val capabilities = EngineCapabilities()
        override val events = MutableSharedFlow<EngineEvent>()
        val closeCalls = AtomicInteger()
        val preparationCalls = AtomicInteger()
        override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
            preparationCalls.incrementAndGet()
            error("This host-lifetime fixture did not authorize playback")
        }
        override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
            startPositionMs: Long, generation: Long) {
            preparationCalls.incrementAndGet()
            error("This host-lifetime fixture did not authorize playback")
        }
        override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult =
            NativeReaderDrainResult.Drained(expectedGeneration)
        override suspend fun play(generation: Long) = Unit
        override suspend fun pause(generation: Long) = Unit
        override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = Unit
        override suspend fun setRate(speed: Float, generation: Long) = Unit
        override suspend fun stop(generation: Long) = Unit
        override suspend fun close() { closeCalls.incrementAndGet() }
    }

    private class NoHttp : SafeHttp {
        val requests = AtomicInteger()
        val closeCalls = AtomicInteger()
        private fun denied(): Nothing {
            requests.incrementAndGet()
            error("This host-lifetime fixture did not authorize HTTP or DNS")
        }
        override suspend fun get(request: SafeHttpRequest, maxBytes: Int): SafeHttpBytes = denied()
        override suspend fun <T> stream(request: SafeHttpRequest, consume: suspend (SafeHttpResponse, ByteReadChannel) -> T): T = denied()
        override suspend fun validateUrl(url: String): String = denied()
        override fun close() { closeCalls.incrementAndGet() }
    }

    private class PlatformProbe(directory: Path) : PlatformServices {
        override val dataDirectory = directory.toString()
        override val store = createPlatformLibraryStore(directory.resolve("library").toString())
        override val engine = NoAudioEngine()
        val assetValidations = AtomicInteger()
        /** Legacy asset validation must not be reached by the typed physical download path. */
        override val validator = AssetValidator { assetValidations.incrementAndGet(); AssetValidation.Valid(1_000L) }
        val localValidations = AtomicInteger()
        val localValidationInputs = ConcurrentLinkedQueue<LocalMediaValidationInput>()
        @Volatile var localValidationFailure: Throwable? = null
        /**
         * Authored identity-free local validation. Exact input assertions run in-flow, while the
         * original path lease/generation is still owned, then return an authored success.
         */
        override val localValidator = LocalMediaValidator { input ->
            localValidations.incrementAndGet()
            localValidationInputs += input
            try {
                assertEquals(OfflineAssetKind.FILE, input.kind)
                assertEquals(NON_AUDIO.size.toLong(), input.sizeBytes)
                assertEquals("audio/mpeg", input.mimeType)
                assertEquals("mp3", input.codecs)
                assertEquals(NON_AUDIO_SHA256, input.sha256)
                assertEquals(REPRESENTATION, input.representationId)
                assertEquals(0L, input.validatorGeneration)
                assertTrue(Path.of(input.uri).startsWith(directory), "Validation must read this app-owned path")
                assertEquals("transfer.part", Path.of(input.uri).fileName.toString())
                assertTrue(Files.isRegularFile(Path.of(input.uri)))
            } catch (failure: Throwable) {
                localValidationFailure = failure
                throw failure
            }
            AssetValidation.Valid(1_000L)
        }
        override val systemLanguage = LanguagePreference.ENGLISH
        val youtubeRequests = AtomicInteger()
        val youtubeCloseCalls = AtomicInteger()
        override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            youtubeRequests.incrementAndGet()
            error("This host-lifetime fixture did not authorize a source request")
        }, onClose = { youtubeCloseCalls.incrementAndGet() }))
        val runtimeCloseCalls = AtomicInteger()
        override suspend fun closeMediaRuntime() { runtimeCloseCalls.incrementAndGet() }
        override suspend fun openExternalUrl(url: String): Unit = error("No external URL is authorized")
        val allowanceAcquires = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val allowanceConsumed = AtomicBoolean()
        val permitAllowanceClose = AtomicBoolean()
        val cleanupActive = ConcurrentLinkedQueue<Boolean>()
        @Volatile var beforeAllowanceClose: suspend (Int) -> Unit = {}
        override val downloadExecution = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                allowanceAcquires.incrementAndGet()
                return DownloadExecutionLease {
                    val attempt = allowanceCloses.incrementAndGet()
                    cleanupActive += currentCoroutineContext().isActive
                    beforeAllowanceClose(attempt)
                    if (!permitAllowanceClose.get()) throw IllegalStateException("authored private allowance was not consumed")
                    allowanceConsumed.set(true)
                }
            }
        }
    }

    /**
     * One central gateway fake. Typed acquisition/execution is the ONLY authorized path; the
     * legacy first-result/resolve/download and playback/collection paths fail loudly and are
     * asserted to stay at zero.
     */
    private class GatewayProbe : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway {
        val acquisitions = AtomicInteger()
        val queries = AtomicInteger()
        val preparations = AtomicInteger()
        val transferInvocations = AtomicInteger()
        val transferCompletions = AtomicInteger()
        val legacyFirstResults = AtomicInteger()
        val legacyResolves = AtomicInteger()
        val legacyTransfers = AtomicInteger()
        val playbackPreparations = AtomicInteger()

        override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
            legacyFirstResults.incrementAndGet()
            throw IllegalStateException("This host-lifetime fixture authorizes only typed acquisition")
        }

        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
            purpose: ResolvePurpose): ResolvedPlayable {
            legacyResolves.incrementAndGet()
            throw IllegalStateException("This host-lifetime fixture authorizes only typed download execution")
        }

        override suspend fun download(binding: PlaybackBinding, destinationPath: String,
            capabilities: EngineCapabilities, representationId: String?,
            onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
            legacyTransfers.incrementAndGet()
            throw IllegalStateException("This host-lifetime fixture authorizes only typed download execution")
        }

        override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
            acquisitions.incrementAndGet()
            queries.incrementAndGet()
            assertEquals(ITEM.title, request.completeTitle, "Exactly the displayed complete title is queried")
            assertEquals(ITEM.id, assertIs<MediaCapture>(request.metadata).media.id)
            val entry = SourceEntry(
                kind = AcquisitionEntryKind.VIDEO,
                id = FIRST,
                title = "Authored selected recording",
                publicUrl = FIRST_RESULT_URL,
                ordinal = 0,
            )
            val receipt = FirstResultObservation(request.id, request.completeTitle, entry, FIRST_RESULT_URL)
            return SourceAcquisitionResult.Single(request.id, receipt,
                SourceDisplayItem(FIRST, "Authored selected recording", FIRST_RESULT_URL, AcquisitionEntryKind.VIDEO))
        }

        /** Pure structural restore; no I/O, membership reload or historical attestation. */
        override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
            val entry = receipt.entry
            if (receipt.requestId != request.id || receipt.queryTitle != request.completeTitle || entry.ordinal != 0) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "fixture_receipt_mismatch"))
            }
            val id = entry.id
            val title = entry.title
            val publicUrl = entry.publicUrl
            return if (entry.kind == AcquisitionEntryKind.VIDEO && entry.issue == null && id != null && title != null &&
                publicUrl != null) SourceAcquisitionResult.Single(request.id, receipt,
                SourceDisplayItem(id, title, publicUrl, AcquisitionEntryKind.VIDEO))
            else SourceAcquisitionResult.Unplayable(request.id, receipt,
                AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
        }

        override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
            limits: CollectionLimits): SourceCollectionPage =
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unsupported"))
        override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unsupported"))
        override suspend fun inspect(request: AcquisitionRequest,
            selection: SourceAcquisitionResult.Single): AcquisitionInspection = object : AcquisitionInspection {
            override val selection = selection
        }

        override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
            playbackPreparations.incrementAndGet()
            throw IllegalStateException("This host-lifetime fixture did not authorize playback preparation")
        }

        override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
            preparations.incrementAndGet()
            assertEquals(ITEM.id, assertIs<MediaCapture>(context.request.metadata).media.id)
            assertEquals(ITEM.title, context.request.completeTitle)
            assertEquals(0, context.receipt.observation.entry.ordinal)
            assertEquals(AcquisitionEntryKind.VIDEO, context.receipt.observation.entry.kind)
            assertEquals(FIRST, context.recording.canonicalId)
            assertEquals(FIRST_RESULT_URL, context.receipt.observation.canonicalUrl)
            assertEquals(ITEM.id, assertIs<ExecutableSubject.CatalogMedia>(context.subject).mediaId)
            assertNull(inspection, "The standalone physical executor supplies no optional inspection")
            val representation = AcquisitionRepresentation(REPRESENTATION, "audio/mpeg", "mp3", "mp3")
            // A required key must be an actually offered key, never a silent relabel.
            if (requiredRepresentationId != null && requiredRepresentationId != representation.representationId) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "authored_download_representation_unavailable"))
            }
            return Prepared(this, context, requiredRepresentationId, representation)
        }

        override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
            onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
            val owned = prepared as? Prepared
                ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_type_mismatch"))
            // Issuer/context/single-consumption checks all precede the first I/O.
            owned.consume(this)
            transferInvocations.incrementAndGet()
            val destination = Path.of(destinationPath)
            assertTrue(destination.isAbsolute)
            assertEquals("transfer.part", destination.fileName.toString())
            Files.createDirectories(destination.parent)
            Files.write(destination, NON_AUDIO)
            onProgress(AcquisitionTransferProgress(NON_AUDIO.size.toLong(), NON_AUDIO.size.toLong()))
            transferCompletions.incrementAndGet()
            return AcquisitionTransferResult(owned.context, NON_AUDIO.size.toLong(), owned.representation)
        }

        private class Prepared(
            private val issuer: GatewayProbe,
            override val context: AcquisitionExecutionContext,
            override val requiredRepresentationId: String?,
            override val representation: AcquisitionRepresentation,
        ) : PreparedAcquisitionDownload {
            private val consumed = AtomicBoolean()
            fun consume(gateway: GatewayProbe) {
                if (issuer !== gateway) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_foreign_issuer"))
                }
                if (!consumed.compareAndSet(false, true)) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_reused"))
                }
                if (requiredRepresentationId != null && representation.representationId != requiredRepresentationId) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_representation_mismatch"))
                }
                if (context.request.completeTitle != ITEM.title ||
                    (context.request.metadata as? MediaCapture)?.media?.id != ITEM.id ||
                    (context.subject as? ExecutableSubject.CatalogMedia)?.mediaId != ITEM.id ||
                    context.receipt.observation.entry.ordinal != 0) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_context_mismatch"))
                }
            }
        }
    }

    private class HostSlot(val slot: Slot) {
        val closeCalls = AtomicInteger()
        val consumed = AtomicBoolean()
    }

    private class Fixture(val directory: Path) {
        val context = DesktopProcessContext.acquire(directory)
        val host = DesktopLifetime(context)
        val hostJob = checkNotNull(host.scope.coroutineContext[Job])
        private val previousChildren = hostJob.children.toSet()
        val platform = PlatformProbe(directory)
        val http = NoHttp()
        val gateway = GatewayProbe()
        val app = AppContainer(platform, host.scope, http, gateway).also { host.container = it }
        // Capture the actual AppContainer child, not the host's callback SupervisorJob.
        val appJob = hostJob.children.single { it !in previousChildren }
        val slots = Slot.entries.associateWith(::HostSlot)
        val verificationCloses = AtomicInteger()
        val downloadFailures = ConcurrentLinkedQueue<AppFailure>()

        suspend fun attachHostOwners() {
            DesktopPlatformServices.createOwned(context, silentAudio = true, onOwned = { host.services = it }) {
                for (slot in Slot.entries) acquire(slot, release = { value: HostSlot ->
                    value.closeCalls.incrementAndGet() // Count even an incorrectly early attempt.
                    assertTrue(app.dependencyReleaseSafeAfterClose)
                    assertFalse(app.retainedForCleanup)
                    value.consumed.set(true)
                }) { slots.getValue(slot) }
            }
            host.verification = AutoCloseable {
                verificationCloses.incrementAndGet()
                assertTrue(app.dependencyReleaseSafeAfterClose)
                assertFalse(app.retainedForCleanup)
            }
        }

        /**
         * Real public Download action over an actually inserted catalogue row: one complete-title
         * typed selection, one physical claim/attempt/transfer/local validation/finalization and
         * an authored NON-AUDIO file. Only the execution-allowance close is deliberately failed.
         */
        suspend fun failInitialAllowanceCleanup() {
            assertFalse(app.dependencyReleaseSafeAfterClose)
            assertFalse(app.retainedForCleanup, "An empty pin registry is not a positive release receipt")
            app.initialize()
            assertEquals(AppInitialization.Ready, app.initialization.value)
            platform.store.upsertCatalog(listOf(ITEM)).getOrThrow()
            assertEquals(ITEM, platform.store.state.value.media.getValue(ITEM.id))
            val failureCollector = host.scope.launch {
                app.downloads.lastFailure.filterNotNull().collect { downloadFailures += it }
            }
            app.onMusicAction(MusicUiAction.Download(ITEM))
            try {
                app.downloads.lastFailure.first { it == CLEANUP_FAILURE }
            } finally {
                failureCollector.cancel()
            }
            assertCanonicalPublication()
            assertEquals(1, platform.allowanceCloses.get())
            assertFalse(platform.allowanceConsumed.get())
            assertAuthoredDownloadStagesOnce()
        }

        private suspend fun assertCanonicalPublication() {
            // One same-owner read barrier: completed job, immutable asset/use and exact lineage.
            val view = platform.store.acquisitionView()
            assertEquals(1, view.acquisition.requests.size)
            assertEquals(1, view.acquisition.receipts.size)
            assertEquals(1, view.acquisition.bindings.size)
            assertEquals(1, view.acquisition.physicalDownloads.size)
            val job = assertNotNull(view.acquisition.physicalDownloads.values.singleOrNull())
            if (job.status != DownloadStatus.COMPLETE) {
                val inputs = platform.localValidationInputs.joinToString("; ") {
                    "size=${it.sizeBytes},mime=${it.mimeType},codecs=${it.codecs},hash=${it.sha256}," +
                        "rep=${it.representationId},gen=${it.validatorGeneration},file=${Path.of(it.uri).fileName}"
                }
                assertEquals(DownloadStatus.COMPLETE, job.status,
                    "finalization=${view.acquisition.physicalFinalizations[job.id] != null} " +
                        "attempt=${job.activeAttemptId?.let { view.acquisition.physicalAttempts[it]?.status }} " +
                        "localValidationFailure=${platform.localValidationFailure?.message} " +
                        "downloadFailures=[${downloadFailures.joinToString { "${it.code}:${it.detail}" }}] inputs=[$inputs]")
            }
            assertEquals(DownloadStatus.COMPLETE, job.status)
            assertNull(job.failure)
            assertFalse(job.cleanupPending)
            assertNull(job.leaseOwner)
            assertNull(job.activeAttemptId)
            val producer = assertIs<PhysicalDownloadProducer.Typed>(job.producer)
            val context = view.acquisition.executionContext(producer.bindingId)
            assertEquals(ITEM.title, context.request.completeTitle)
            assertEquals(ITEM.id, assertIs<MediaCapture>(context.request.metadata).media.id)
            assertEquals(0, context.receipt.observation.entry.ordinal)
            assertEquals(AcquisitionEntryKind.VIDEO, context.receipt.observation.entry.kind)
            assertEquals(FIRST, context.receipt.observation.entry.id)
            assertEquals(FIRST_RESULT_URL, context.receipt.observation.canonicalUrl)
            assertEquals(FIRST, context.recording.canonicalId)
            val asset = assertNotNull(job.assetId?.let { view.acquisition.materializations[it] })
            assertEquals(job.assetId, asset.id)
            assertEquals(producer, asset.producer)
            assertEquals(OfflineAssetKind.FILE, asset.kind)
            assertEquals(REPRESENTATION, asset.representationId)
            assertEquals(0L, asset.validatorGeneration)
            assertEquals(NON_AUDIO.size.toLong(), asset.sizeBytes)
            assertEquals(NON_AUDIO_SHA256, asset.sha256)
            assertEquals(job.id, asset.materializerDownloadId)
            val attempt = assertNotNull(asset.materializerAttemptId?.let { view.acquisition.physicalAttempts[it] })
            assertEquals(job.id, attempt.downloadId)
            assertEquals(DownloadAttemptStatus.SUCCEEDED, attempt.status)
            assertTrue(Path.of(asset.uri).startsWith(directory.resolve("media")), "Published path must be app-owned")
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
            val use = assertNotNull(view.acquisition.assetUses.singleOrNull {
                it.requestedBindingId == producer.bindingId && it.materializationAssetId == asset.id
            })
            assertEquals(1, use.compatibilityPolicyVersion)
            assertEquals(asset.id, use.materializationAssetId)
            assertEquals(asset.receipt(), view.acquisition.materializationReceipts[asset.id])
        }

        /** Earlier typed stages must be exactly once; cleanup retries never replay source/validation. */
        fun assertAuthoredDownloadStagesOnce() {
            assertEquals(1, gateway.acquisitions.get(), "Exactly one typed acquisition")
            assertEquals(1, gateway.queries.get(), "Exactly one complete-title query")
            assertEquals(1, gateway.preparations.get(), "Exactly one prepared typed download")
            assertEquals(1, gateway.transferInvocations.get(), "Cleanup retries must never replay a transfer")
            assertEquals(1, gateway.transferCompletions.get(), "One transfer completed successfully")
            assertEquals(1, platform.localValidations.get(), "One local validation for the authored bytes")
            assertEquals(1, platform.allowanceAcquires.get(), "Cleanup retries must not re-acquire the allowance")
            assertEquals(0, gateway.legacyFirstResults.get())
            assertEquals(0, gateway.legacyResolves.get())
            assertEquals(0, gateway.legacyTransfers.get())
            assertEquals(0, gateway.playbackPreparations.get())
        }

        suspend fun assertRetained(engineCloses: Int = 1) {
            assertTrue(app.retainedForCleanup)
            assertFalse(app.dependencyReleaseSafeAfterClose)
            assertTrue(host.retainedForCleanup, "The host/context need their own strong process pin")
            assertTrue(hostJob.isActive)
            assertTrue(appJob.isActive)
            assertEquals(engineCloses, platform.engine.closeCalls.get())
            assertEquals(0, platform.runtimeCloseCalls.get())
            assertEquals(0, platform.youtubeCloseCalls.get())
            assertEquals(0, http.closeCalls.get())
            assertIs<StoreStatus.Ready>(platform.store.status.value)
            platform.store.flush().getOrThrow() // Actual Room still works while cleanup needs it.
            assertTrue(slots.values.all { it.closeCalls.get() == 0 && !it.consumed.get() })
            assertEquals(0, verificationCloses.get())
            assertFalse(host.submitMediaCallback { error("Retired callback must never run") })
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
            assertTrue(platform.cleanupActive.isNotEmpty() && platform.cleanupActive.all { it },
                "Every retained cleanup attempt, including a held retry, must still be NonCancellable")
            assertFalse(platform.allowanceConsumed.get(), "Retained cleanup is not a positive allowance receipt")
            assertAuthoredDownloadStagesOnce()
        }

        suspend fun assertReleased() {
            assertFalse(app.retainedForCleanup)
            assertTrue(app.dependencyReleaseSafeAfterClose)
            assertFalse(host.retainedForCleanup)
            assertTrue(hostJob.isCancelled)
            withTimeout(10_000L) { hostJob.join() }
            assertTrue(hostJob.isCompleted)
            assertTrue(appJob.isCompleted)
            assertEquals(1, platform.engine.closeCalls.get())
            assertEquals(1, platform.runtimeCloseCalls.get())
            assertEquals(1, platform.youtubeCloseCalls.get())
            assertEquals(1, http.closeCalls.get())
            assertEquals(StoreStatus.Closed, platform.store.status.value)
            assertTrue(slots.values.all { it.closeCalls.get() == 1 && it.consumed.get() })
            assertEquals(1, verificationCloses.get())
            assertEquals(0, http.requests.get())
            assertEquals(0, platform.youtubeRequests.get())
            assertEquals(0, platform.engine.preparationCalls.get())
            assertEquals(0, platform.assetValidations.get())
            assertEquals(0, gateway.legacyFirstResults.get())
            assertEquals(0, gateway.legacyResolves.get())
            assertEquals(0, gateway.legacyTransfers.get())
            assertEquals(0, gateway.playbackPreparations.get())
            assertTrue(platform.cleanupActive.all { it }, "All cleanup, including explicit retries, is NonCancellable")
        }

        suspend fun finish() {
            platform.permitAllowanceClose.set(true)
            // Only the deliberately latched cleanup classification is allowed during teardown.
            val error = runCatching { host.close() }.exceptionOrNull()
            if (error != null) assertEquals(CLEANUP_FAILURE, assertIs<VeyraException>(error).failure)
            assertReleased()
            DesktopProcessContext.acquire(directory).close()
        }
    }

    private fun checkCase(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val fixture = Fixture(workDirectory())
        var primary: Throwable? = null
        try {
            withTimeout(45_000L) {
                fixture.attachHostOwners()
                fixture.block()
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            withContext(NonCancellable) {
                try { fixture.finish() }
                catch (error: Throwable) { primary?.addSuppressed(error) ?: throw error }
            }
        }
    }

    @Test fun retainedDownloadAllowancePinsHostGraphAndLockUntilExplicitRetryAcknowledgesConsumption() = checkCase {
        failInitialAllowanceCleanup()
        val startupEntered = CompletableDeferred<Unit>()
        val callbackEntered = CompletableDeferred<Unit>()
        val startupFinalizers = AtomicInteger()
        val callbackFinalizers = AtomicInteger()
        val startup = host.scope.launch {
            try { startupEntered.complete(Unit); awaitCancellation() }
            finally { startupFinalizers.incrementAndGet() }
        }
        assertTrue(host.startupJobs.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, startup))
        assertTrue(host.submitMediaCallback {
            try { callbackEntered.complete(Unit); awaitCancellation() }
            finally { callbackFinalizers.incrementAndGet() }
        })
        startupEntered.await(); callbackEntered.await()
        val first = assertFailsWith<VeyraException> { host.close() }
        assertEquals(CLEANUP_FAILURE, first.failure)
        assertNull(first.cause)
        assertTrue(first.suppressedExceptions.isEmpty())
        assertEquals(2, platform.allowanceCloses.get())
        assertRetained()
        assertTrue(startup.isCompleted)
        assertEquals(1, startupFinalizers.get())
        assertEquals(1, callbackFinalizers.get())
        val lateStartup = Job()
        try { assertFalse(host.startupJobs.admit(DesktopStartupJobs.Kind.PLATFORM, lateStartup)) }
        finally { lateStartup.cancel() }

        platform.permitAllowanceClose.set(true)
        assertSame(first, runCatching { host.close() }.exceptionOrNull(), "Consumption cannot erase the first failed outcome")
        assertReleased()
        assertEquals(3, platform.allowanceCloses.get())
        assertTrue(platform.allowanceConsumed.get())
        assertAuthoredDownloadStagesOnce()
        DesktopProcessContext.acquire(directory).use {
            assertSame(first, runCatching { host.close() }.exceptionOrNull())
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
        }
        assertReleased()
        assertEquals(3, platform.allowanceCloses.get())
        assertEquals(1, startupFinalizers.get())
        assertEquals(1, callbackFinalizers.get())
    }

    @Test fun concurrentAndCancelledCallersShareOneRetainedAttemptWithoutDuplicatingCleanup() = checkCase {
        failInitialAllowanceCleanup()
        val first = assertFailsWith<VeyraException> { host.close() }
        assertEquals(CLEANUP_FAILURE, first.failure)
        val retryEntered = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        platform.beforeAllowanceClose = { attempt ->
            if (attempt == 3) { retryEntered.complete(Unit); finishRetry.await() }
        }
        try {
            supervisorScope {
                val leader = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.close() }.exceptionOrNull() }
                try {
                    retryEntered.await()
                    val other = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.close() }.exceptionOrNull() }
                    val cancelledOutcome = CompletableDeferred<Throwable?>()
                    val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                        cancelledOutcome.complete(runCatching { host.close() }.exceptionOrNull())
                    }
                    cancelled.cancel(CancellationException("authored caller cancellation"))
                    assertFalse(leader.isCompleted)
                    assertFalse(other.isCompleted)
                    assertFalse(cancelled.isCompleted)
                    assertFalse(cancelledOutcome.isCompleted)
                    assertEquals(3, platform.allowanceCloses.get())
                    assertRetained()
                    // This attempt still fails. A mutex-only queue of explicit retries would
                    // incorrectly call the retained lease again for each already-waiting caller.
                    finishRetry.complete(Unit)
                    assertSame(first, leader.await())
                    assertSame(first, other.await())
                    cancelled.join()
                    assertSame(first, cancelledOutcome.await())
                    assertEquals(3, platform.allowanceCloses.get())
                    assertRetained()
                } finally { finishRetry.complete(Unit) }
            }
        } finally { finishRetry.complete(Unit) }
        platform.permitAllowanceClose.set(true)
        assertSame(first, runCatching { host.close() }.exceptionOrNull())
        assertReleased()
        assertEquals(4, platform.allowanceCloses.get())
        assertAuthoredDownloadStagesOnce()
    }

    @Test fun cancellationCannotReleaseTheHostWhileFirstApplicationCloseStillAwaitsItsPositiveReceipt() = checkCase {
        failInitialAllowanceCleanup()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        platform.beforeAllowanceClose = { attempt ->
            if (attempt == 2) { cleanupEntered.complete(Unit); finishCleanup.await() }
        }
        val outcome = CompletableDeferred<Throwable?>()
        try {
            supervisorScope {
                val closing = launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome.complete(runCatching { host.close() }.exceptionOrNull())
                }
                try {
                    cleanupEntered.await()
                    closing.cancel(CancellationException("authored first close caller cancellation"))
                    assertFalse(closing.isCompleted)
                    assertFalse(outcome.isCompleted)
                    assertRetained(engineCloses = 0)
                    platform.permitAllowanceClose.set(true)
                    finishCleanup.complete(Unit)
                    closing.join()
                } finally { finishCleanup.complete(Unit) }
            }
        } finally { finishCleanup.complete(Unit) }
        val first = assertIs<VeyraException>(outcome.await())
        assertEquals(CLEANUP_FAILURE, first.failure)
        assertReleased()
        assertTrue(platform.allowanceConsumed.get())
        assertEquals(2, platform.allowanceCloses.get())
        assertAuthoredDownloadStagesOnce()
        assertSame(first, runCatching { host.close() }.exceptionOrNull())
        assertEquals(2, platform.allowanceCloses.get())
    }

    @Test fun neverInitializedApplicationMustCloseAndAcknowledgeBeforeTheHostReleasesItsLock() = checkCase {
        assertEquals(AppInitialization.Loading, app.initialization.value)
        assertEquals(StoreStatus.Uninitialized, platform.store.status.value)
        assertFalse(app.retainedForCleanup)
        assertFalse(app.dependencyReleaseSafeAfterClose)
        host.close()
        assertReleased()
        assertEquals(0, platform.allowanceAcquires.get())
        assertEquals(0, gateway.acquisitions.get())
        assertEquals(0, gateway.queries.get())
        assertEquals(0, gateway.preparations.get())
        assertEquals(0, gateway.transferInvocations.get())
        assertEquals(0, platform.localValidations.get())
        DesktopProcessContext.acquire(directory).close()
        host.close()
        assertReleased()
    }

    @Test fun partialStartupWithoutAnApplicationStillClosesPublishedServiceSlotsExactlyOnce(): Unit = runBlocking {
        val directory = workDirectory()
        val context = DesktopProcessContext.acquire(directory)
        val host = DesktopLifetime(context)
        val closedSlots = ConcurrentLinkedQueue<Slot>()
        val verificationCloses = AtomicInteger()
        try {
            DesktopPlatformServices.createOwned(context, silentAudio = true, onOwned = { host.services = it }) {
                for (slot in listOf(Slot.ENGINE, Slot.STORE)) {
                    acquire(slot, release = { value: Slot -> closedSlots.add(value); Unit }) { slot }
                }
            }
            host.verification = AutoCloseable { verificationCloses.incrementAndGet(); Unit }
            assertNull(host.container)
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
            withTimeout(10_000L) { host.close(); checkNotNull(host.scope.coroutineContext[Job]).join() }
            assertFalse(host.retainedForCleanup)
            assertEquals(listOf(Slot.ENGINE, Slot.STORE), closedSlots.toList())
            assertEquals(1, verificationCloses.get())
            DesktopProcessContext.acquire(directory).close()
            host.close()
            assertEquals(2, closedSlots.size)
            assertEquals(1, verificationCloses.get())
        } finally { host.close(); checkNotNull(host.scope.coroutineContext[Job]).join() }
    }

    private companion object {
        const val FIRST = "AAAAAAAAAAA"
        const val REPRESENTATION = "authored-host-lifetime-representation"
        const val FIRST_RESULT_URL = "https://www.youtube.com/watch?v=$FIRST"
        val CLEANUP_FAILURE = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
        val NON_AUDIO = "Authored host ownership fixture. NOT AUDIO; validator is deliberately fake.".encodeToByteArray()
        val NON_AUDIO_SHA256 = java.security.MessageDigest.getInstance("SHA-256").digest(NON_AUDIO)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val ITEM = MediaItem("authored-host-retained-download", "Complete authored title — العنوان الكامل", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-host-retained-download"))

        fun workDirectory(): Path {
            val root = Path.of(requireNotNull(System.getenv("VEYRA_PROJECT_ROOT")))
                .resolve(".work/desktop-audio/retained-lifetime-tests")
            Files.createDirectories(root)
            return Files.createTempDirectory(root, "authored-room-")
        }
    }
}
