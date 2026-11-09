package dev.veyra.downloads

import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import org.junit.Assume.assumeNoException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real local files/hash, authored NON-AUDIO bytes and a fake validator; not native decode proof. */
class DownloadPhysicalIntegrityTest {
    @Test fun validNeutralFileUsesIdentityFreeInputAndTheExistingStreamingHash() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        var received: LocalMediaValidationInput? = null
        val duration = fixture.files.validateMaterialization(fixture.input, LocalMediaValidator {
            received = it
            AssetValidation.Valid(777)
        })
        assertEquals(777L, duration)
        assertEquals(fixture.input, received)
        assertEquals(fixture.input.sha256, fixture.files.sha256(fixture.files.ownedPath(fixture.input.uri)))
    }

    @Test fun wrongFileSizeIsRejectedBeforeNativeAdmission() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        assertCorruptBeforeNative(fixture, fixture.input.copy(sizeBytes = fixture.input.sizeBytes + 1))
    }

    @Test fun sameSizeChangedBytesAreRejectedBeforeNativeAdmission() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        Files.write(fixture.path, ByteArray(fixture.bytes.size) { 0 })
        assertCorruptBeforeNative(fixture)
    }

    @Test fun aDirectoryCannotMasqueradeAsACompletedFile() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        Files.delete(fixture.path)
        Files.createDirectory(fixture.path)
        assertCorruptBeforeNative(fixture)
    }

    @Test fun anOutsideRootFileNeverBecomesOwnedJustBecauseItsHashMatches() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        val outside = fixture.directory.resolve("outside.mp3")
        Files.write(outside, fixture.bytes)
        assertCorruptBeforeNative(fixture, fixture.input.copy(uri = outside.toString()))
    }

    @Test fun aSymlinkBelowTheOwnedRootIsNotAnOwnedRegularAsset() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        val target = fixture.directory.resolve("outside.mp3")
        Files.write(target, fixture.bytes)
        Files.delete(fixture.path)
        try { Files.createSymbolicLink(fixture.path, target) }
        catch (error: UnsupportedOperationException) { assumeNoException("Host filesystem does not support symlinks", error) }
        catch (error: AccessDeniedException) { assumeNoException("Host does not grant symlink creation", error) }
        catch (error: FileSystemException) {
            // Windows may report a missing create-symbolic-link privilege without mapping it
            // to AccessDeniedException. Do not skip arbitrary storage/corruption failures.
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                error.reason?.contains("privilege", ignoreCase = true) == true)
                assumeNoException("Windows does not grant symlink creation privilege", error)
            else throw error
        }
        assertCorruptBeforeNative(fixture)
    }

    @Test fun aFileUriIsNotAcceptedAsAnOwnedNativeFilesystemPath() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        assertCorruptBeforeNative(fixture, fixture.input.copy(uri = fixture.path.toUri().toString()))
    }

    @Test fun missingAndMalformedHashCannotRelyOnAValidNativeResult() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        for (hash in listOf(null, "", "a".repeat(63), "A".repeat(64))) {
            assertCorruptBeforeNative(fixture, fixture.input.copy(sha256 = hash))
        }
    }

    @Test fun sameSizeMutationDuringNativeValidationFailsTheSecondFileHash() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        var checked = 0
        val error = assertFailsWith<VeyraException> {
            fixture.files.validateMaterialization(fixture.input, LocalMediaValidator {
                checked++
                Files.write(fixture.path, ByteArray(fixture.bytes.size) { 7 })
                AssetValidation.Valid(777)
            })
        }
        assertEquals(FailureCode.CORRUPT_MEDIA, error.failure.code)
        assertEquals(1, checked)
    }

    @Test fun aTypedNativeValidationFailureIsNotTurnedIntoReadyByTheFileHash() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        val failure = AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_decoder_refusal")
        val error = assertFailsWith<VeyraException> {
            fixture.files.validateMaterialization(fixture.input, LocalMediaValidator { AssetValidation.Invalid(failure) })
        }
        assertEquals(failure, error.failure)
    }

    @Test fun cancellationAfterNativeReturnNeverProducesAValidationReceipt() = runBlocking {
        val fixture = PhysicalIntegrityFixture()
        supervisorScope {
            val operation = async {
                fixture.files.validateMaterialization(fixture.input, LocalMediaValidator {
                    currentCoroutineContext().cancel()
                    AssetValidation.Valid(777)
                })
            }
            assertFailsWith<CancellationException> { operation.await() }
        }
    }

    @Test fun materializationConversionPreservesExactRepresentationAndValidatorGeneration() {
        val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("neutral"), ExecutionBindingId("r1"), SourceRecordingId("c"))
        val asset = MaterializationAsset(MaterializationAssetId("asset"), producer, "/authored/owned.mp3", 9,
            mimeType = "audio/mpeg", codecs = "mp3", sha256 = "b".repeat(64), representationId = "exact-key",
            validatorGeneration = 17, materializerDownloadId = "job", materializerAttemptId = "attempt", ownershipGeneration = 23)
        assertEquals(LocalMediaValidationInput(asset.uri, asset.kind, asset.sizeBytes, asset.mimeType, asset.codecs,
            asset.sha256, "exact-key", 17), asset.validationInput())
    }

    private suspend fun assertCorruptBeforeNative(fixture: PhysicalIntegrityFixture, input: LocalMediaValidationInput = fixture.input) {
        var nativeCalls = 0
        val error = assertFailsWith<VeyraException> {
            fixture.files.validateMaterialization(input, LocalMediaValidator { nativeCalls++; AssetValidation.Valid(777) })
        }
        assertEquals(FailureCode.CORRUPT_MEDIA, error.failure.code)
        assertEquals(0, nativeCalls)
    }
}

internal fun physicalDownloadTestDirectory(): Path {
    val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    val parent = root.resolve(".work/d010-download-tests")
    Files.createDirectories(parent)
    return Files.createTempDirectory(parent, "authored-")
}

private class PhysicalIntegrityFixture {
    val directory = physicalDownloadTestDirectory()
    val files = DownloadFiles(directory.resolve("media").toString())
    val bytes = "Veyra authored fixture, not audio. بيانات اختبار فقط.".encodeToByteArray()
    val path: Path
    val input: LocalMediaValidationInput
    init {
        files.initialize()
        path = Path.of(files.finalPath("neutral", 1, "audio/mpeg", "authored").toString())
        Files.write(path, bytes)
        input = LocalMediaValidationInput(path.toString(), OfflineAssetKind.FILE, bytes.size.toLong(), "audio/mpeg", "mp3",
            bytes.toByteString().sha256().hex(), "authored-key", 1)
    }
}
