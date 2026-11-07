package dev.veyra.downloads

import dev.veyra.data.LibraryTransactionOwner
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real authored bytes/hash/OS leases; fake native outcomes and synthetic initial history. */
class DownloadMaterializationPrimitiveRejectionTest {
    @Test fun everyActualPreNativeFileNegativeRevokesSynchronouslyAndPreservesItsExactC0() = runBlocking {
        for (problem in listOf(DownloadMaterializationFileProblem.KIND, DownloadMaterializationFileProblem.MISSING, DownloadMaterializationFileProblem.NOT_REGULAR,
            DownloadMaterializationFileProblem.SIZE, DownloadMaterializationFileProblem.EXPECTED_HASH,
            DownloadMaterializationFileProblem.HASH)) {
            val fixture = PrimitiveEvidenceFixture(problem)
            var native = 0
            val lease = assertNotNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri)))
            try {
                val attempt = fixture.attempt()
                val failure = assertFailsWith<VeyraException> {
                    fixture.files.validateMaterialization(fixture.asset.validationInput(), LocalMediaValidator {
                        native++; AssetValidation.Valid(733)
                    }) { fixture.record(attempt, it) }
                }
                assertEquals(FailureCode.CORRUPT_MEDIA, failure.failure.code)
                assertEquals(0, native)
                val negative = assertIs<DownloadMaterializationNegative.File>(fixture.negatives.single())
                assertEquals(problem, negative.problem)
                assertEquals(DownloadMaterializationCheckPhase.PRE_NATIVE, negative.phase)
                fixture.assertRevoked()
            } finally { lease.close() }
        }
    }

    @Test fun explicitNativeInvalidIsTypedEvidenceEvenWhenItMeansUnsupportedNotCorruptBytes() = runBlocking {
        val fixture = PrimitiveEvidenceFixture()
        val failure = AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_native_refusal")
        val lease = assertNotNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri)))
        try {
            val attempt = fixture.attempt()
            val thrown = assertFailsWith<VeyraException> {
                fixture.files.validateMaterialization(fixture.asset.validationInput(), LocalMediaValidator {
                    AssetValidation.Invalid(failure)
                }) { fixture.record(attempt, it) }
            }
            assertEquals(failure, thrown.failure)
            assertEquals(failure, assertIs<DownloadMaterializationNegative.Native>(fixture.negatives.single()).failure)
            fixture.assertRevoked()
        } finally { lease.close() }
    }

    @Test fun postNativeHashMutationRevokesTheSameAdmittedAttemptBeforeLeaseCleanup() = runBlocking {
        val fixture = PrimitiveEvidenceFixture()
        val lease = assertNotNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri)))
        try {
            val attempt = fixture.attempt()
            assertFailsWith<VeyraException> {
                fixture.files.validateMaterialization(fixture.asset.validationInput(), LocalMediaValidator {
                    Files.write(Path.of(it.uri), ByteArray(it.sizeBytes.toInt()) { 7 })
                    AssetValidation.Valid(733)
                }) { fixture.record(attempt, it) }
            }
            val negative = assertIs<DownloadMaterializationNegative.File>(fixture.negatives.single())
            assertEquals(DownloadMaterializationCheckPhase.POST_NATIVE, negative.phase)
            assertEquals(DownloadMaterializationFileProblem.HASH, negative.problem)
            fixture.assertRevoked()
            assertNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri)), "The rejected caller still owns cleanup")
        } finally { lease.close() }
        assertNotNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri))).close()
    }

    @Test fun genericNativeExceptionAndCancellationNeverBecomePhysicalRejectionByFailureCode() = runBlocking {
        for (failure in listOf(VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "not_a_native_invalid")),
            CancellationException("authored_cancelled_before_negative"))) {
            val fixture = PrimitiveEvidenceFixture()
            val before = fixture.evidence.checks.value
            val lease = assertNotNull(fixture.files.tryLeasePaths(listOf(fixture.asset.uri)))
            try {
                val attempt = fixture.attempt()
                val thrown = try {
                    fixture.files.validateMaterialization(fixture.asset.validationInput(), LocalMediaValidator { throw failure }) {
                        fixture.record(attempt, it)
                    }
                    fail("A thrown validator cannot succeed")
                } catch (actual: Exception) { actual }
                assertSame(failure, thrown)
                assertTrue(fixture.negatives.isEmpty())
                assertTrue(fixture.stamps.isEmpty())
                assertSame(before, fixture.evidence.checks.value)
            } finally { lease.close() }
        }
    }

    @Test fun concurrentLateC0RejectionCannotUndoC1OrASeparateAsset() = runBlocking {
        val fixture = PrimitiveEvidenceFixture()
        val other = fixture.asset.copy(id = MaterializationAssetId("separate-asset"), uri = fixture.asset.uri + "-other")
        fixture.evidence.recordPhysicalSuccess("other-job", other)
        val c0 = fixture.attempt()
        val published = CompletableDeferred<Unit>()
        val rejected = async(Dispatchers.Default) {
            published.await()
            fixture.evidence.reject(c0, DownloadMaterializationNegative.Native(fixture.asset.validationInput(),
                AppFailure(FailureCode.CORRUPT_MEDIA, "authored_late_c0")))
        }
        val c1 = async(Dispatchers.Default) {
            fixture.evidence.recordPhysicalSuccess("job", fixture.asset)
            fixture.evidence.checks.value.also { published.complete(Unit) }
        }.await()
        val stamp = assertNotNull(rejected.await())
        assertSame(c0.successfulCheck, stamp.successfulCheck)
        assertNotSame(stamp.successfulCheck, c1.successfulChecks.getValue("job"))
        assertSame(c1, fixture.evidence.checks.value)
        assertEquals(setOf("job", "other-job"), c1.currentValidAssets.keys)
    }

    @Test fun concurrentWritersNeverExposeCurrentValidityWithoutItsPairedFullAssetHistory() = runBlocking {
        val fixture = PrimitiveEvidenceFixture()
        coroutineScope {
            val writers = (0 until 8).map { number -> launch(Dispatchers.Default) {
                repeat(32) { round ->
                    val id = "job-${number % 2}"
                    val asset = fixture.asset.copy(id = MaterializationAssetId("asset-$id"), validatorGeneration = round.toLong())
                    fixture.evidence.recordPhysicalSuccess(id, asset)
                    val attempt = fixture.evidence.admit(id, asset, ExecutionBindingId("r2"), AcquisitionRequestId("request"))
                    yield()
                    fixture.evidence.reject(attempt, DownloadMaterializationNegative.Native(asset.validationInput(),
                        AppFailure(FailureCode.CORRUPT_MEDIA, "authored_concurrent_negative")))
                    val paired = fixture.evidence.checks.value
                    paired.currentValidAssets.forEach { (key, value) ->
                        assertEquals(key, paired.successfulChecks.getValue(key).downloadId)
                        assertEquals(value, paired.successfulChecks.getValue(key).asset)
                    }
                }
            } }
            writers.joinAll()
        }
    }
}

private class PrimitiveEvidenceFixture(problem: DownloadMaterializationFileProblem? = null) {
    val directory = physicalDownloadTestDirectory()
    val files = DownloadFiles(directory.resolve("media").toString())
    private val bytes = "Authored physical rejection fixture. NOT AUDIO. اختبار فقط.".encodeToByteArray()
    val asset: MaterializationAsset
    val evidence = DownloadMaterializationEvidence(LibraryTransactionOwner())
    val negatives = CopyOnWriteArrayList<DownloadMaterializationNegative>()
    val stamps = CopyOnWriteArrayList<DownloadMaterializationRejection>()
    private val c0: DownloadMaterializationCheck
    init {
        files.initialize()
        val path = files.finalPath("neutral", 1, "audio/mpeg", "authored")
        Files.write(Path.of(path.toString()), bytes)
        asset = MaterializationAsset(MaterializationAssetId("asset"), PhysicalDownloadProducer.Typed(
            ExecutableSubjectId("neutral"), ExecutionBindingId("r1"), SourceRecordingId("c")), path.toString(),
            bytes.size.toLong(), kind = if (problem == DownloadMaterializationFileProblem.KIND) OfflineAssetKind.MEDIA3_CACHE else OfflineAssetKind.FILE,
            mimeType = "audio/mpeg", codecs = "mp3",
            sha256 = if (problem == DownloadMaterializationFileProblem.EXPECTED_HASH) "malformed" else bytes.toByteString().sha256().hex(),
            representationId = "authored-key", validatorGeneration = 1)
        when (problem) {
            DownloadMaterializationFileProblem.MISSING -> Files.delete(Path.of(asset.uri))
            DownloadMaterializationFileProblem.NOT_REGULAR -> { Files.delete(Path.of(asset.uri)); Files.createDirectory(Path.of(asset.uri)) }
            DownloadMaterializationFileProblem.SIZE -> Files.write(Path.of(asset.uri), byteArrayOf(1))
            DownloadMaterializationFileProblem.HASH -> Files.write(Path.of(asset.uri), ByteArray(bytes.size) { 0 })
            else -> Unit
        }
        evidence.recordPhysicalSuccess("job", asset) // Synthetic C0 only; runtime/Room cases obtain C0 via publication.
        c0 = evidence.checks.value.successfulChecks.getValue("job")
    }
    fun attempt() = evidence.admit("job", asset, ExecutionBindingId("r2"), AcquisitionRequestId("request"))
    fun record(attempt: DownloadMaterializationValidationAttempt, negative: DownloadMaterializationNegative) {
        negatives += negative
        evidence.reject(attempt, negative)?.let { stamps += it }
    }
    fun assertRevoked() {
        assertTrue(evidence.checks.value.currentValidAssets.isEmpty())
        assertSame(c0, evidence.checks.value.successfulChecks.getValue("job"))
        assertSame(c0, stamps.single().successfulCheck)
        assertEquals(asset, stamps.single().asset)
        assertTrue(evidence.owns(stamps.single()))
    }
}
