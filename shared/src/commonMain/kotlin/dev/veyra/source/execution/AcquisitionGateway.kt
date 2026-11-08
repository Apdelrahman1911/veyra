package dev.veyra.source.execution

import dev.veyra.model.*

/** Client-owned opaque capability. Never serialize, reconstruct, log or transfer between clients. */
interface CollectionContinuation

/** Only [data] can cross the normalized repository boundary. */
data class SourceCollectionPage(val data: CollectionPageData, val continuation: CollectionContinuation?) {
    override fun toString() = "SourceCollectionPage(disposition=${data.disposition},continuation=redacted)"
}

/**
 * Opaque same-client inspection report, including an unsuccessful source inspection.
 * Its existence is NOT a compatible playback plan, authorization, download or Ready proof.
 * Provider execution code may consume its private report later, without a second search.
 * No URI/header/report serialization and no catalogue MediaItem stand-in is permitted.
 */
interface AcquisitionInspection {
    val selection: SourceAcquisitionResult.Single
}

/** Source selection/observation only. Veyra supplies detached captures; kmpYT owns all I/O. */
interface AcquisitionGateway {
    /** Exactly one unchanged-title first-result operation for an already claimed durable request. */
    suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult

    /** Pure structural validation; never I/O, membership reload, or attestation of historical truth. */
    fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult

    /**
     * A new observation of this selected collection; never an original-catalogue track/episode list.
     * Reconstruct from this exact detached request, not a hidden request map or guessed kind.
     * Validate request ID/query/member reference against the selection before source I/O.
     */
    suspend fun collection(
        request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits = CollectionLimits(),
    ): SourceCollectionPage

    /** One explicit cursor use; no hidden retry/automatic pagination loop. */
    suspend fun more(continuation: CollectionContinuation): SourceCollectionPage

    /** Inspect this exact request/Single directly, never query its returned title or substitute a hit. */
    suspend fun inspect(request: AcquisitionRequest, selection: SourceAcquisitionResult.Single): AcquisitionInspection
}
