package dev.veyra.app

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Mandatory hooks into the EXISTING application policy/lifetime owner. No permissive defaults.
 * [online] invokes its block once, checks offline/closed policy and cancels the actual operation
 * on an active policy change. [checkOpen] is the final synchronous handoff check.
 *
 * Started/finished register/unregister the SAME completion with that owner's shutdown barrier;
 * started may reject admission even before registration. Finished is a nonthrowing,
 * nonsuspending, idempotent unregister operation, not a new admission/policy check. The
 * completion includes rejected-resource cleanup and is completed even when admission fails.
 * [releaseRejectedResource] must retain the resource BEFORE trying to close it and preserve
 * failed ownership for the owner's later explicit drain; returning an exception is not an ACK.
 * [reportRejectedResourceFailure] receives only sanitized diagnostics, not native exceptions.
 */
internal interface PolicyMediaGatewayHooks {
    suspend fun <T> online(block: suspend () -> T): T
    suspend fun prepareMediaRuntime()
    fun checkOpen()
    fun acquisitionStarted(completion: CompletableDeferred<Unit>)
    fun acquisitionFinished(completion: CompletableDeferred<Unit>)
    suspend fun releaseRejectedResource(resource: PlaybackResource)
    fun reportRejectedResourceFailure(failure: AppFailure)
}

/**
 * Policy decorator of ONE supplied central gateway. Capability references are captured once;
 * an older/custom delegate without a port fails explicitly, never by reaching around injection
 * to a platform source, constructing another client/engine or splitting prepare/transfer.
 *
 * Requests, raw receipts, inspections and collection continuations pass through unchanged.
 * Restore is pure/offline and does not start policy, runtime or lifecycle I/O. Collection
 * cursor retry semantics remain those of the source, not the terminal download one-shot rule.
 * This class owns no Room store, native player, transfer, policy observer or source close.
 */
internal class PolicyMediaGateway(
    private val delegate: KmpMediaGateway,
    private val hooks: PolicyMediaGatewayHooks,
) : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway, LegacyPreparedDownloadGateway {
    private val acquisitionDelegate = delegate as? AcquisitionGateway
    private val executionDelegate = delegate as? AcquisitionExecutionGateway
    private val legacyPreparedDelegate = delegate as? LegacyPreparedDownloadGateway

    override suspend fun firstResult(completeTitle: String): KmpFirstResult? = operation(runtime = false) {
        delegate.firstResult(completeTitle)
    }

    override suspend fun resolve(
        binding: PlaybackBinding,
        capabilities: EngineCapabilities,
        purpose: ResolvePurpose,
    ): ResolvedPlayable = operation(runtime = true, resource = { it.resource }) {
        delegate.resolve(binding, capabilities, purpose)
    }

    override suspend fun download(
        binding: PlaybackBinding,
        destinationPath: String,
        capabilities: EngineCapabilities,
        representationId: String?,
        onProgress: suspend (YouTubeTransferProgress) -> Unit,
    ): YouTubeTransferResult = operation(runtime = true) {
        delegate.download(binding, destinationPath, capabilities, representationId, onProgress)
    }

    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
        val target = requireAcquisition()
        return operation(runtime = false) { target.acquire(request) }
    }

    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult =
        requireAcquisition().restore(request, receipt)

    override suspend fun collection(
        request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits,
    ): SourceCollectionPage {
        val target = requireAcquisition()
        return operation(runtime = false) { target.collection(request, selection, limits) }
    }

    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage {
        val target = requireAcquisition()
        return operation(runtime = false) { target.more(continuation) }
    }

    override suspend fun inspect(request: AcquisitionRequest, selection: SourceAcquisitionResult.Single): AcquisitionInspection {
        val target = requireAcquisition()
        return operation(runtime = false) { target.inspect(request, selection) }
    }

    override suspend fun preparePlayback(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
        inspection: AcquisitionInspection?,
    ): AcquisitionPlaybackSource {
        val target = requireExecution()
        return operation(runtime = true, resource = { it.resource }) {
            target.preparePlayback(context, capabilities, requiredRepresentationId, inspection)
        }
    }

    override suspend fun prepareDownload(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
        inspection: AcquisitionInspection?,
    ): PreparedAcquisitionDownload {
        val target = requireExecution()
        return operation(runtime = true) {
            TypedPrepared(this, target.prepareDownload(context, capabilities, requiredRepresentationId, inspection))
        }
    }

    override suspend fun download(
        prepared: PreparedAcquisitionDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit,
    ): AcquisitionTransferResult {
        // Cast and issuer check precede ALL caller-defined getters, hooks and suspension.
        val owned = prepared as? TypedPrepared ?: invalidPrepared()
        if (owned.issuer !== this) invalidPrepared()
        consume(owned)
        val target = requireExecution()
        return operation(runtime = true) { target.download(owned.inner, destinationPath, onProgress) }
    }

    override suspend fun prepareDownload(
        binding: PlaybackBinding,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
    ): PreparedLegacyDownload {
        val target = requireLegacyPrepared()
        return operation(runtime = true) {
            LegacyPrepared(this, target.prepareDownload(binding, capabilities, requiredRepresentationId))
        }
    }

    override suspend fun download(
        prepared: PreparedLegacyDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit,
    ): LegacyTransferResult {
        val owned = prepared as? LegacyPrepared ?: invalidPrepared()
        if (owned.issuer !== this) invalidPrepared()
        consume(owned)
        val target = requireLegacyPrepared()
        return operation(runtime = true) { target.download(owned.inner, destinationPath, onProgress) }
    }

    /** No dispatcher change/I/O/policy callback here: cancellation cannot leave this cap reusable. */
    private suspend fun consume(owned: OwnedPrepared) = withContext(NonCancellable) {
        if (!owned.consumed.compareAndSet(false, true)) {
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "policy_media_prepared_download_consumed"))
        }
    }

    /**
     * Capture a returned resource INSIDE the policy block, before coroutineScope/withContext
     * can discard its value on cancellable return. The SAME root-owned completion remains
     * active until noncancellable rejection cleanup has returned or retained a sticky failure.
     * A successful handoff leaves the resource open for the caller's native ownership protocol.
     */
    private suspend fun <T> operation(
        runtime: Boolean,
        resource: (T) -> PlaybackResource? = { null },
        block: suspend () -> T,
    ): T {
        var acquiredResource: PlaybackResource? = null
        val completion = CompletableDeferred<Unit>()
        try {
            hooks.acquisitionStarted(completion)
            currentCoroutineContext().ensureActive()
            val result = hooks.online {
                currentCoroutineContext().ensureActive()
                if (runtime) hooks.prepareMediaRuntime()
                currentCoroutineContext().ensureActive()
                block().also { acquiredResource = resource(it) }
            }
            currentCoroutineContext().ensureActive()
            hooks.checkOpen()
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { acquiredResource?.let { hooks.releaseRejectedResource(it) } }
                catch (releaseFailure: Throwable) {
                    val sanitized = sanitizedFailure(releaseFailure)
                    failure.addSuppressed(VeyraException(sanitized))
                    // Reporting cannot replace the primary failure or expose a private native
                    // diagnostic. The release hook, not this report, retains failed ownership.
                    try { hooks.reportRejectedResourceFailure(sanitized) }
                    catch (reportFailure: Throwable) { failure.addSuppressed(VeyraException(sanitizedFailure(reportFailure))) }
                }
            }
            throw failure
        } finally {
            completion.complete(Unit)
            hooks.acquisitionFinished(completion)
        }
    }

    private sealed class OwnedPrepared(val issuer: PolicyMediaGateway) {
        val consumed = MutableStateFlow(false)
    }

    private class TypedPrepared(issuer: PolicyMediaGateway, val inner: PreparedAcquisitionDownload) :
        OwnedPrepared(issuer), PreparedAcquisitionDownload {
        override val context = inner.context
        override val requiredRepresentationId = inner.requiredRepresentationId
        override val representation = inner.representation
        override fun toString() = "PolicyPreparedAcquisitionDownload(identity=redacted,capability=redacted)"
    }

    private class LegacyPrepared(issuer: PolicyMediaGateway, val inner: PreparedLegacyDownload) :
        OwnedPrepared(issuer), PreparedLegacyDownload {
        override val binding = inner.binding
        override val requiredRepresentationId = inner.requiredRepresentationId
        override val representation = inner.representation
        override fun toString() = "PolicyPreparedLegacyDownload(identity=redacted,capability=redacted)"
    }

    private fun requireAcquisition(): AcquisitionGateway = acquisitionDelegate
        ?: unavailable("policy_media_acquisition_unavailable")

    private fun requireExecution(): AcquisitionExecutionGateway = executionDelegate
        ?: unavailable("policy_media_execution_unavailable")

    private fun requireLegacyPrepared(): LegacyPreparedDownloadGateway = legacyPreparedDelegate
        ?: unavailable("policy_media_legacy_prepared_unavailable")

    private fun unavailable(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, detail))

    private fun invalidPrepared(): Nothing = throw VeyraException(
        AppFailure(FailureCode.INVALID_STATE, "policy_media_prepared_download_mismatch"),
    )

    private fun sanitizedFailure(error: Throwable): AppFailure = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }
}
