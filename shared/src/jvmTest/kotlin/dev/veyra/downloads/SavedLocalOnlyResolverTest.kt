package dev.veyra.downloads

import dev.veyra.data.executionContext
import dev.veyra.model.AcquisitionOutcome
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.AcquisitionPlaybackResolver
import dev.veyra.playback.EngineCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saved intent must never prepare an online source when the exact local authority is absent. */
class SavedLocalOnlyResolverTest {
    @Test fun localOnlyWithoutExactUseFailsBeforeAnyRemotePreparationEvenWhenOnline() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner, label = "saved-local-only-missing", video = "ABCDEFGHIJK")
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val context = owner.store.acquisitionView().acquisition.executionContext(bindingId)
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files,
            owner.localValidator, owner.materializationEvidence)
        try {
            val failure = assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = false, localOnly = true)
            }
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals("saved_local_authority_lost", failure.failure.detail)
            assertTrue(owner.gateway.preparations.isEmpty(), "Saved intent must not prepare a remote source")
            assertTrue(owner.gateway.transfers.isEmpty(), "Saved intent must not transfer")
        } finally {
            resolver.close()
        }
    }
}
