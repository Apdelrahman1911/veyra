package dev.veyra.desktop.audio

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real DesktopAssetValidator over a real authored non-audio file and an inert decoder double.
 * The Ready/Ended/Error answers are authored fakes, never libmpv/codec/audio or physical-device
 * evidence. The validator's local file path/kind/size/hash/sha checks are actual production code.
 */
class DesktopDownloadValidationOwnershipTest {
    @Test fun successfulAuthoredDecodeClosesTheExactDeckOnceBeforeReturningValid() = runBlocking {
        val file = AuthoredValidationFile.create("validator-valid")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck(
                "validation-valid",
                eventsOnPrepare = { generation ->
                    listOf(EngineEvent.Ready(generation, 5_000L, true), EngineEvent.Ended(generation))
                },
            )
            var opens = 0
            var published: AuthoredDesktopDeck? = null
            val validator = DesktopAssetValidator { owned ->
                opens++
                published = deck
                owned(deck)
            }

            val result = assertIs<AssetValidation.Valid>(validator.validateLocal(input))

            assertEquals(5_000L, result.durationMs)
            assertEquals(1, opens)
            assertSame(deck, published)
            assertEquals(1, deck.prepareInputs.size)
            val prepared = deck.prepareInputs.single()
            assertEquals(file.path.toAbsolutePath().normalize().toString(), prepared.uri)
            assertTrue(prepared.isLocal)
            assertEquals(dev.veyra.model.StreamTransport.PROGRESSIVE, prepared.transport)
            assertTrue(prepared.headers.isEmpty())
            assertEquals(listOf(1L), deck.playCalls.toList(), "Ready must be followed by the silent validation play")
            assertEquals(1, deck.closeCalls.get(), "The exact decoder is closed before Valid returns")
            assertTrue(deck.destroyed)
            assertTrue(deck.outputCalls.isEmpty())
            assertTrue(deck.seekCalls.isEmpty())
            assertTrue(deck.gainCalls.isEmpty(), "Validation starts no audible gain or app engine")
            assertTrue(deck.closeContextActive.all { it }, "The exact decoder close runs in NonCancellable cleanup")
            // The typed input is never rewritten; only an internal local path copy reaches the deck.
            assertEquals(file.size, input.sizeBytes)
            assertEquals(file.sha256, input.sha256)
            assertEquals("authored:validation:representation", input.representationId)
            assertEquals(7L, input.validatorGeneration)
            assertTrue(input.uri.startsWith("file:"))
        } finally {
            file.close()
        }
    }

    @Test fun authoredNegativeDecodeClosesTheExactDeckBeforeReturningInvalid() = runBlocking {
        val file = AuthoredValidationFile.create("validator-negative")
        try {
            val input = file.input()
            val authoredFailure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_decode_negative")
            val deck = AuthoredDesktopDeck(
                "validation-negative",
                eventsOnPrepare = { generation -> listOf(EngineEvent.Error(generation, authoredFailure)) },
            )
            val validator = DesktopAssetValidator { owned -> owned(deck) }

            val result = assertIs<AssetValidation.Invalid>(validator.validateLocal(input))

            assertEquals(authoredFailure, result.failure)
            assertEquals(1, deck.closeCalls.get())
            assertTrue(deck.destroyed)
            assertTrue(deck.playCalls.isEmpty(), "No play is requested after a negative decode event")
        } finally {
            file.close()
        }
    }

    @Test fun factoryFailureAfterPublicationClosesExactlyThatDeckOnce() = runBlocking {
        val file = AuthoredValidationFile.create("validator-after-publish")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck("validation-after-publish")
            var created: AuthoredDesktopDeck? = null
            var ownedValues = 0
            val validator = DesktopAssetValidator { owned ->
                created = deck
                ownedValues++
                owned(deck)
                throw IllegalStateException("authored factory failure after publication")
            }

            val result = assertIs<AssetValidation.Invalid>(validator.validateLocal(input))

            assertEquals("native_validator_unavailable", result.failure.detail)
            assertEquals(1, ownedValues)
            assertSame(deck, created)
            assertEquals(1, deck.closeCalls.get())
            assertTrue(deck.destroyed)
        } finally {
            file.close()
        }
    }

    @Test fun factoryFailureBeforePublicationCreatesNoPhantomOwnerOrReleaseReceipt() = runBlocking {
        val file = AuthoredValidationFile.create("validator-before-publish")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck("validation-before-publish")
            var created: AuthoredDesktopDeck? = null
            var ownedValues = 0
            val validator = DesktopAssetValidator {
                created = deck
                throw IllegalStateException("authored factory failure before publication")
            }

            val result = assertIs<AssetValidation.Invalid>(validator.validateLocal(input))

            assertEquals("native_validator_unavailable", result.failure.detail)
            assertEquals(0, ownedValues)
            assertSame(deck, created)
            assertEquals(0, deck.closeCalls.get(), "An unowned construction is not a reader release receipt")
            assertFalse(deck.destroyed)
        } finally {
            file.close()
        }
    }

    @Test fun factoryFailureAfterPublicationWithFailedCloseThrowsTheTypedOwner() = runBlocking {
        val file = AuthoredValidationFile.create("validator-factory-close-failure")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck("validation-factory-close-failure", failClose = true)
            val validator = DesktopAssetValidator { owned ->
                owned(deck)
                throw IllegalStateException("authored factory failure after publication")
            }

            val error = assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                validator.validateLocal(input)
            }

            assertEquals(input, error.input)
            assertFalse(error.reader.isReleased())
            assertEquals("local_validation_reader_unacknowledged", error.message)
            assertEquals(1, deck.closeCalls.get())
            assertFalse(deck.destroyed)
            assertFalse(error.message.orEmpty().contains(input.uri))
        } finally {
            file.close()
        }
    }

    @Test fun negativeValidationWithFailedCloseThrowsTheTypedOwnerRatherThanInvalid() = runBlocking {
        val file = AuthoredValidationFile.create("validator-negative-close-failure")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck(
                "validation-negative-close-failure",
                failClose = true,
                eventsOnPrepare = { generation ->
                    listOf(EngineEvent.Error(generation, AppFailure(FailureCode.CORRUPT_MEDIA, "authored_decode_negative")))
                },
            )
            val validator = DesktopAssetValidator { owned -> owned(deck) }

            val error = assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                validator.validateLocal(input)
            }

            assertEquals(input, error.input)
            assertFalse(error.reader.isReleased())
            assertEquals(1, deck.closeCalls.get())
            assertEquals(1, deck.closeCalls.get(), "isReleased observation must not retry close")
            assertFalse(deck.destroyed)
        } finally {
            file.close()
        }
    }

    @Test fun cancellationWaitsForTheHeldNativeCloseAndRemainsCancellation() = runBlocking {
        val file = AuthoredValidationFile.create("validator-cancellation")
        try {
            val input = file.input()
            val gate = CompletableDeferred<Unit>()
            val deck = AuthoredDesktopDeck(
                "validation-cancellation",
                closeGate = gate,
                eventsOnPrepare = { generation ->
                    listOf(EngineEvent.Ready(generation, 2_500L, true), EngineEvent.Ended(generation))
                },
            )
            val validator = DesktopAssetValidator { owned -> owned(deck) }
            val outcome = CompletableDeferred<Result<AssetValidation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                outcome.complete(runCatching { validator.validateLocal(input) })
            }
            withTimeout(10_000L) { deck.closeEntered.await() }
            assertFalse(job.isCompleted, "No Valid/Invalid outcome may escape before the held close is released")
            job.cancel()
            assertFalse(job.isCompleted, "NonCancellable cleanup must still await the actual held close")
            gate.complete(Unit)
            assertIs<CancellationException>(outcome.await().exceptionOrNull())
            assertEquals(1, deck.closeCalls.get())
            assertTrue(deck.destroyed)
            assertTrue(deck.closeContextActive.all { it }, "The held cleanup still runs in NonCancellable")
        } finally {
            file.close()
        }
    }

    @Test fun cancellationWithFailedCloseThrowsTheTypedOwner() = runBlocking {
        val file = AuthoredValidationFile.create("validator-cancellation-failure")
        try {
            val input = file.input()
            val gate = CompletableDeferred<Unit>()
            val deck = AuthoredDesktopDeck(
                "validation-cancellation-failure",
                failClose = true,
                closeGate = gate,
                eventsOnPrepare = { generation ->
                    listOf(EngineEvent.Ready(generation, 2_500L, true), EngineEvent.Ended(generation))
                },
            )
            val validator = DesktopAssetValidator { owned -> owned(deck) }
            val outcome = CompletableDeferred<Result<AssetValidation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                outcome.complete(runCatching { validator.validateLocal(input) })
            }
            withTimeout(10_000L) { deck.closeEntered.await() }
            job.cancel()
            gate.complete(Unit)
            val error = assertIs<LocalMediaValidationReaderUnacknowledged>(outcome.await().exceptionOrNull())
            assertEquals(input, error.input)
            assertFalse(error.reader.isReleased())
            assertEquals(1, deck.closeCalls.get())
            assertFalse(deck.destroyed)
        } finally {
            file.close()
        }
    }

    @Test fun fileKindSizeHashAndPathRejectionsNeverAllocateADecoder() = runBlocking {
        val file = AuthoredValidationFile.create("validator-rejections")
        try {
            var opens = 0
            val validator = DesktopAssetValidator {
                opens++
                error("A rejected file must not allocate a decoder")
            }

            fun detail(result: AssetValidation): String {
                val invalid = assertIs<AssetValidation.Invalid>(result)
                return invalid.failure.detail.orEmpty()
            }

            assertEquals("unsupported_offline_asset_kind",
                detail(validator.validateLocal(file.input(kind = OfflineAssetKind.MEDIA3_CACHE))))
            assertEquals("local_audio_missing",
                detail(validator.validateLocal(file.input(uri = file.directory.resolve("missing.bin").toUri().toString()))))
            assertEquals("offline_byte_count_mismatch",
                detail(validator.validateLocal(file.input(sizeBytes = file.size + 1))))
            assertEquals("offline_hash_mismatch",
                detail(validator.validateLocal(file.input(sha = "0".repeat(64)))))
            assertEquals("invalid_local_audio_path",
                detail(validator.validateLocal(file.input(uri = "\u0000authored-invalid"))))

            val sizeFailure = assertIs<AssetValidation.Invalid>(
                validator.validate(OfflineAsset("authored", "authored", file.path.toString(), 0L)),
            )
            assertEquals("offline_byte_count_mismatch", sizeFailure.failure.detail)
            val generationFailure = assertIs<AssetValidation.Invalid>(
                validator.validate(OfflineAsset("authored", "authored", file.path.toString(), file.size, validatorGeneration = -1L)),
            )
            assertEquals("offline_validation_generation_mismatch", generationFailure.failure.detail)
            assertEquals(0, opens, "No decoder factory may run before path/kind/size/hash admission")
        } finally {
            file.close()
        }
    }

    @Test fun repeatedReaderObservationIsSideEffectFree() = runBlocking {
        val file = AuthoredValidationFile.create("validator-observe")
        try {
            val input = file.input()
            val deck = AuthoredDesktopDeck(
                "validation-observe",
                failClose = true,
                eventsOnPrepare = { generation ->
                    listOf(EngineEvent.Error(generation, AppFailure(FailureCode.CORRUPT_MEDIA, "authored_decode_negative")))
                },
            )
            val validator = DesktopAssetValidator { owned -> owned(deck) }
            val error = assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                validator.validateLocal(input)
            }
            repeat(5) { assertFalse(error.reader.isReleased()) }
            assertEquals(1, deck.closeCalls.get(), "Observation never retries the native close")
            assertEquals(input, error.input)
            val sourceReads = deck.prepareInputs.size
            assertEquals(1, sourceReads)
        } finally {
            file.close()
        }
    }
}
