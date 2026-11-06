package dev.veyra.source.execution

import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EphemeralPlaybackEndpoint
import dev.veyra.playback.PlaybackResource
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.podcast.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.test.*

class AppSourceResolverTest {
    private class TestResource(val endpoint: String? = null, val closeFailure: Throwable? = null) : PlaybackResource {
        override val localEndpoint = endpoint?.let(::EphemeralPlaybackEndpoint)
        var closes = 0
        override suspend fun close() { closes++; closeFailure?.let { throw it } }
    }

    private class Fixture(scope: TestScope) {
        val persistence = ExecutionTestPersistence()
        val store = LibraryStore(persistence, StandardTestDispatcher(scope.testScheduler))
        val gateway = ExecutionTestGateway()
        val clock = ExecutionTestClock()
        var nativeValidations = 0
        private val noFileIo = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path =
                error("Authored online contract tests must not access any filesystem path")
        }
        // A virtual, unreachable path: the guard refuses ALL real filesystem operations. Real
        // owned-file coverage lives in JVM tests under the project's .work directory.
        val resolver = AppSourceResolver(store, DownloadFiles("/__veyra_unused_unit_path__", noFileIo), AssetValidator {
            nativeValidations++
            AssetValidation.Valid(1_000L)
        }, gateway, clock)
    }

    private suspend fun TestScope.withFixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try { fixture.block() } finally { fixture.store.close() }
    }

    @Test fun rssCompleteTitleReachesFirstResultAndOriginalMetadataIsNotRelabelledAsAudio() = runTest { withFixture {
        val title = "Complete episode عنوان <5> &copy; ".repeat(50) + " — identifying final suffix"
        val encoded = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val feed = RssParser.parse("""<rss><channel><title>Publisher show</title><item><guid>stable</guid>
            <title>$encoded</title><enclosure url="https://publisher.example.com/direct.mp3" type="audio/mpeg"/>
            </item></channel></rss>""", "https://publisher.example.com/feed")
        val original = feed.episodes.single().copy(durationMs = 9_999_999L)
        store.upsertCatalog(listOf(original), listOf(feed.show)).getOrThrow()
        gateway.execute = { binding, _, _ ->
            assertEquals(binding, store.state.value.playbackBindings[binding.id], "Receipt must be durable before execution")
            dev.veyra.playback.ResolvedPlayable("youtube:$FIRST_VIDEO_ID", "https://media.example.com/authored",
                mimeType = "audio/mp4", codecs = "aac", durationMs = 123_456L)
        }
        val result = resolver.resolve(original, EngineCapabilities())
        assertEquals(listOf(title), gateway.queries)
        assertEquals(original, result.item)
        assertEquals(original.id, result.mediaId)
        assertEquals(123_456L, result.durationMs)
        assertEquals(title, result.executionBinding?.queryTitle)
        assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
        assertEquals(original, store.state.value.media[original.id])
        assertEquals(0, nativeValidations)
        assertEquals(0, gateway.downloads)
    } }

    @Test fun everyDiscoveryProviderIncludingExistingYoutubeIdentityUsesCompleteTitleFirst() = runTest { withFixture {
        for (provider in SourceProvider.entries) {
            val item = executionTestItem(provider, id = provider.name)
            val result = resolver.resolve(item, EngineCapabilities())
            assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
            assertEquals(item, result.item)
            assertEquals(item.title, gateway.queries.last())
        }
        assertEquals(SourceProvider.entries.size, gateway.queries.size)
        assertEquals(SourceProvider.entries.size, gateway.resolutions.size)
    } }

    @Test fun channelPlaylistAndUnknownFirstAreRecordedAndNeverSkippedForLaterVideo() = runTest { withFixture {
        for (kind in listOf(FirstResultKind.CHANNEL, FirstResultKind.PLAYLIST, FirstResultKind.UNKNOWN)) {
            val item = executionTestItem(id = kind.name)
            gateway.first = { title ->
                KmpFirstResult(title, kind, if (kind == FirstResultKind.UNKNOWN) "" else "first-collection",
                    if (kind == FirstResultKind.UNKNOWN) "" else "First collection title")
            }
            val failure = assertFailsWith<VeyraException> { resolver.resolve(item, EngineCapabilities()) }
            assertEquals(FailureCode.UNSUPPORTED_SOURCE, failure.failure.code)
            val receipt = store.state.value.playbackBindings.values.single { it.mediaId == item.id }
            assertEquals(kind, receipt.firstResultKind)
            assertNull(receipt.selectedVideoId)
            assertEquals(0, receipt.selectionOrdinal)
        }
        assertEquals(3, gateway.queries.size)
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun unavailableFirstPreservesItsReceiptAndTypedFailureWithoutExecution() = runTest { withFixture {
        val denied = AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED")
        gateway.first = { title -> KmpFirstResult(title, FirstResultKind.VIDEO, FIRST_VIDEO_ID, "Unavailable first", failure = denied) }
        val failure = assertFailsWith<VeyraException> { resolver.resolve(executionTestItem(), EngineCapabilities()) }
        assertEquals(denied, failure.failure)
        assertEquals(FIRST_VIDEO_ID, store.state.value.playbackBindings.values.single().firstResultId)
        assertTrue(gateway.resolutions.isEmpty())
        assertEquals(1, gateway.queries.size)
    } }

    @Test fun actualEmptySearchIsNotAuthenticationFailureAndInventsNoBinding() = runTest { withFixture {
        gateway.first = { null }
        assertEquals(FailureCode.NOT_FOUND, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertTrue(store.state.value.playbackBindings.isEmpty())
        assertEquals(1, gateway.queries.size)
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun authenticationRateLimitAndNetworkErrorsNeverBecomeEmptySearchOrFallback() = runTest { withFixture {
        for (code in listOf(FailureCode.PROVIDER_ACCESS_REQUIRED, FailureCode.RATE_LIMITED, FailureCode.NETWORK_UNAVAILABLE)) {
            val expected = AppFailure(code, "authored_classification", retryAfterMs = if (code == FailureCode.RATE_LIMITED) 60_000L else null)
            gateway.first = { throw VeyraException(expected) }
            assertEquals(expected, assertFailsWith<VeyraException> {
                resolver.resolve(executionTestItem(), EngineCapabilities())
            }.failure)
        }
        assertEquals(3, gateway.queries.size)
        assertTrue(gateway.resolutions.isEmpty())
        assertTrue(store.state.value.playbackBindings.isEmpty())
    } }

    @Test fun selectedFirstVideoAuthFailureKeepsOriginalBindingAndDoesNotSearchAgain() = runTest { withFixture {
        val expected = AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED")
        gateway.execute = { _, _, _ -> throw VeyraException(expected) }
        assertEquals(expected, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure)
        assertEquals(1, gateway.queries.size)
        assertEquals(1, gateway.resolutions.size)
        assertEquals(FIRST_VIDEO_ID, store.state.value.playbackBindings.values.single().selectedVideoId)
    } }

    @Test fun expiredExecutionNeverTriggersNewSearchOrSecondCandidate() = runTest { withFixture {
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).copy(expiresAtEpochMs = clock.now - 1) }
        assertEquals(FailureCode.RECOVERY_REQUIRED, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, gateway.queries.size)
        assertEquals(1, gateway.resolutions.size)
    } }

    @Test fun downloadIntentUsesSameFirstReceiptContractNeverPublisherUrl() = runTest { withFixture {
        val item = executionTestItem()
        val result = resolver.resolve(item, EngineCapabilities(), purpose = ResolvePurpose.DOWNLOAD)
        assertEquals(listOf(item.title), gateway.queries)
        assertEquals(ResolvePurpose.DOWNLOAD, gateway.resolutions.single().second)
        assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
        assertEquals(0, gateway.downloads)
    } }

    @Test fun downloadPlanningChecksMaterializedOutputNotNativeAcquisitionTransport() = runTest { withFixture {
        val ordinary = gateway.execute
        for (transport in listOf(StreamTransport.HLS, StreamTransport.DASH, StreamTransport.SABR)) {
            gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).copy(transport = transport) }
            val item = executionTestItem(id = "download-$transport")
            val plan = resolver.resolve(item, EngineCapabilities(), purpose = ResolvePurpose.DOWNLOAD)
            assertEquals(transport, plan.transport, "The central acquisition plan must retain its real transport")
            assertEquals("audio/mp4", plan.mimeType)
            assertEquals(FIRST_VIDEO_ID, plan.executionBinding?.selectedVideoId)
            assertNull(plan.resource, "Download planning does not open a native streaming resource")
        }
        assertEquals(3, gateway.queries.size)
        assertEquals(0, gateway.downloads, "The resolver is not a competing media transfer implementation")
    } }

    @Test fun downloadMaterializedCodecAndPlaybackActualTransportRemainRequired() = runTest { withFixture {
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).copy(transport = StreamTransport.SABR) }
        assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(id = "native-sabr"), EngineCapabilities(), purpose = ResolvePurpose.PLAYBACK)
        }.failure.code)
        assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(id = "unsupported-output"),
                EngineCapabilities(mimeTypes = setOf("audio/mpeg"), codecs = setOf("mp3")), purpose = ResolvePurpose.DOWNLOAD)
        }.failure.code)
        assertEquals(0, gateway.downloads)
    } }

    @Test fun resumedExecutionReusesPinnedBindingWithoutSearchingOrChangingIdentity() = runTest { withFixture {
        val item = executionTestItem()
        val binding = resolver.acquireBinding(item)
        gateway.first = { error("A resume/renewal must not repeat search") }
        assertEquals(binding, resolver.acquireBinding(item, binding.id))
        val renewed = resolver.resolve(item, EngineCapabilities(), reuseBindingId = binding.id)
        assertEquals(binding, renewed.executionBinding)
        assertEquals(1, gateway.queries.size)
        assertEquals(binding, gateway.resolutions.single().first)
        assertEquals(1, store.state.value.playbackBindings.size)
    } }

    @Test fun reuseRejectsMissingAndOtherMediaReceiptsWithoutNewSearch() = runTest { withFixture {
        val item = executionTestItem()
        val binding = resolver.acquireBinding(item)
        val other = executionTestItem(id = "other-media")
        assertEquals(FailureCode.RECOVERY_REQUIRED, assertFailsWith<VeyraException> {
            resolver.acquireBinding(item, "missing-receipt")
        }.failure.code)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            resolver.acquireBinding(other, binding.id)
        }.failure.code)
        assertEquals(binding, store.state.value.playbackBindings[binding.id])
        assertEquals(1, gateway.queries.size)
    } }

    @Test fun titleCorrectionKeepsHistoricalResumeButFreshSelectionUsesCurrentCompleteTitle() = runTest { withFixture {
        val item = executionTestItem()
        val originalBinding = resolver.acquireBinding(item)
        val changed = item.copy(title = item.title + " — publisher correction")
        store.upsertCatalog(listOf(changed)).getOrThrow()
        assertEquals(originalBinding, resolver.acquireBinding(changed, originalBinding.id))
        // An already-running job may still hold the prior metadata value: it may renew only its
        // existing receipt, not silently issue a new search with that historical title.
        val resumed = resolver.resolve(item, EngineCapabilities(), reuseBindingId = originalBinding.id)
        assertEquals(changed, resumed.item)
        assertEquals(originalBinding, resumed.executionBinding)
        assertEquals(listOf(item.title), gateway.queries)
        val fresh = resolver.acquireBinding(changed)
        assertNotEquals(originalBinding.id, fresh.id)
        assertEquals(changed.title, fresh.queryTitle)
        assertEquals(listOf(item.title, changed.title), gateway.queries)
        assertEquals(item.title, store.state.value.playbackBindings.getValue(originalBinding.id).queryTitle)
    } }

    @Test fun shortenedQueryOrNonzeroOrdinalReceiptsCannotBecomeExecutionBindings() = runTest { withFixture {
        for (ordinal in listOf(0, 1)) {
            gateway.first = { title -> KmpFirstResult(if (ordinal == 0) title.take(5) else title,
                FirstResultKind.VIDEO, FIRST_VIDEO_ID, "First result", selectionOrdinal = ordinal) }
            assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
                resolver.resolve(executionTestItem(), EngineCapabilities())
            }.failure.code)
        }
        assertTrue(store.state.value.playbackBindings.isEmpty())
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun executionUrlsAndExtraQueryParametersNeverEnterProvenanceStore() = runTest { withFixture {
        for (url in listOf("https://media.example.com/signed?token=authored", "https://www.youtube.com/watch?v=$FIRST_VIDEO_ID&token=authored")) {
            gateway.first = { title -> KmpFirstResult(title, FirstResultKind.VIDEO, FIRST_VIDEO_ID, "First result", url) }
            assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
                resolver.resolve(executionTestItem(), EngineCapabilities())
            }.failure.code)
        }
        assertTrue(store.state.value.playbackBindings.isEmpty())
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun mismatchedExecutionVideoIsRejectedDespiteCompatibleCodec() = runTest { withFixture {
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).let { result ->
            result.copy(item = result.item!!.copy(source = SourceRef(SourceProvider.YOUTUBE, LATER_VIDEO_ID)))
        } }
        assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(FIRST_VIDEO_ID, store.state.value.playbackBindings.values.single().selectedVideoId)
        assertEquals(1, gateway.queries.size)
    } }

    @Test fun missingAndOversizedCompleteTitlesFailBeforeAnySearch() = runTest { withFixture {
        for (title in listOf(" ", "x".repeat(16_385))) {
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.resolve(executionTestItem(title = title), EngineCapabilities())
            }.failure.code)
        }
        assertTrue(gateway.queries.isEmpty())
    } }

    @Test fun offlineParameterAndPersistedOfflineSettingBlockGatewayCompletely() = runTest { withFixture {
        val item = executionTestItem()
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
            resolver.resolve(item, EngineCapabilities(), offlineOnly = true)
        }.failure.code)
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
            resolver.resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertTrue(gateway.queries.isEmpty())
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun enablingOfflineDuringResolutionPreventsHandoffOfTheRemoteSource() = runTest { withFixture {
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose ->
            store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            ordinary(binding, caps, purpose)
        }
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, gateway.queries.size)
        assertEquals(1, gateway.resolutions.size)
    } }

    @Test fun cancellationPropagatesWithoutClassificationOrSecondAttempt() = runTest { withFixture {
        val cancellation = CancellationException("authored test cancellation")
        gateway.first = { throw cancellation }
        assertSame(cancellation, assertFailsWith<CancellationException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        })
        assertEquals(1, gateway.queries.size)
        assertTrue(gateway.resolutions.isEmpty())
    } }

    @Test fun durableBindingWriteFailurePreventsAllExecution() = runTest { withFixture {
        persistence.failBindingWrites = true
        assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, gateway.queries.size)
        assertTrue(gateway.resolutions.isEmpty())
        assertTrue(store.state.value.playbackBindings.isEmpty())
    } }

    @Test fun staleCallerCannotOverwriteCorrectedOriginalTitleToReuseOldQuery() = runTest { withFixture {
        val item = executionTestItem()
        store.upsertCatalog(listOf(item.copy(title = "Corrected complete source title"))).getOrThrow()
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            resolver.resolve(item, EngineCapabilities())
        }.failure.code)
        assertTrue(gateway.queries.isEmpty())
        assertEquals("Corrected complete source title", store.state.value.media[item.id]?.title)
    } }

    @Test fun boundOriginalIdentityCannotBeReassignedByChangingProviderCanonicalIdOrKind() = runTest { withFixture {
        val item = executionTestItem()
        val binding = resolver.acquireBinding(item)
        val changed = listOf(
            item.copy(source = item.source.copy(canonicalId = "different-original-content")),
            item.copy(source = item.source.copy(provider = SourceProvider.COMMONS)),
            item.copy(kind = MediaKind.MUSIC),
        )
        for (impostor in changed) {
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                store.upsertCatalog(listOf(impostor)).getOrThrow()
            }.failure.code)
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.acquireBinding(impostor, binding.id)
            }.failure.code)
        }
        assertEquals(item, store.state.value.media[item.id])
        assertEquals(binding, store.state.value.playbackBindings[binding.id])
        assertEquals(1, gateway.queries.size)
    } }

    @Test fun exactTypedOwnedLoopbackCanHandoffButNeverBecomesAnOfflineAsset() = runTest { withFixture {
        val endpoint = "http://127.0.0.1:34567/authored-unguessable-session/audio"
        val resource = TestResource(endpoint)
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).copy(uri = endpoint, resource = resource) }
        val result = resolver.resolve(executionTestItem(), EngineCapabilities())
        assertEquals(endpoint, result.uri)
        assertFalse(result.isLocal)
        assertNull(result.assetId)
        assertSame(resource, result.resource)
        assertEquals(0, resource.closes, "The successful caller owns the producer until native handoff")
        assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
    } }

    @Test fun arbitraryLoopbackUrlCannotBorrowAnOwnedEndpointException() = runTest { withFixture {
        val ordinary = gateway.execute
        for (resource in listOf(null, TestResource(), TestResource("http://127.0.0.1:34567/other-session/audio"))) {
            gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose)
                .copy(uri = "http://127.0.0.1:34567/requested-session/audio", resource = resource) }
            assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> {
                resolver.resolve(executionTestItem(), EngineCapabilities())
            }.failure.code)
            resource?.let { assertEquals(1, it.closes) }
        }
    } }

    @Test fun ownedEndpointCannotBeAttachedToAnUnrelatedHttpsSource() = runTest { withFixture {
        val resource = TestResource("http://127.0.0.1:34567/owned-session/audio")
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose).copy(resource = resource) }
        assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, resource.closes)
    } }

    @Test fun enablingOfflineBeforeOwnedProducerHandoffClosesItWithoutReturningLocalSuccess() = runTest { withFixture {
        val endpoint = "http://127.0.0.1:34567/authored-session/audio"
        val resource = TestResource(endpoint)
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose ->
            store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            ordinary(binding, caps, purpose).copy(uri = endpoint, resource = resource)
        }
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, resource.closes)
        assertEquals(1, gateway.queries.size)
    } }

    @Test fun everyPostGatewayValidationFailureClosesUnhandedResources() = runTest { withFixture {
        val ordinary = gateway.execute
        val transforms: List<(dev.veyra.playback.ResolvedPlayable) -> dev.veyra.playback.ResolvedPlayable> = listOf(
            { it.copy(isLocal = true) },
            { it.copy(codecs = "authored-unsupported-codec") },
            { it.copy(expiresAtEpochMs = clock.now - 1) },
            { it.copy(headers = mapOf("Authorization" to "authored-never-send")) },
            { it.copy(item = it.item!!.copy(source = SourceRef(SourceProvider.YOUTUBE, LATER_VIDEO_ID))) },
        )
        for (transform in transforms) {
            val resource = TestResource()
            gateway.execute = { binding, caps, purpose -> transform(ordinary(binding, caps, purpose).copy(resource = resource)) }
            assertFailsWith<VeyraException> { resolver.resolve(executionTestItem(), EngineCapabilities()) }
            assertEquals(1, resource.closes)
        }
    } }

    @Test fun producerCleanupFailureDoesNotHideTheOriginalFailure() = runTest { withFixture {
        val resource = TestResource(closeFailure = IllegalStateException("authored cleanup failure"))
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose -> ordinary(binding, caps, purpose)
            .copy(expiresAtEpochMs = clock.now - 1, resource = resource) }
        assertEquals(FailureCode.RECOVERY_REQUIRED, assertFailsWith<VeyraException> {
            resolver.resolve(executionTestItem(), EngineCapabilities())
        }.failure.code)
        assertEquals(1, resource.closes)
    } }

    @Test fun cancellationAfterGatewayReturnsClosesTheUnhandedProducer() = runTest { withFixture {
        val resource = TestResource()
        val ordinary = gateway.execute
        gateway.execute = { binding, caps, purpose ->
            currentCoroutineContext().cancel(CancellationException("authored cancelled handoff"))
            ordinary(binding, caps, purpose).copy(resource = resource)
        }
        val operation = async { resolver.resolve(executionTestItem(), EngineCapabilities()) }
        assertFailsWith<CancellationException> { operation.await() }
        assertEquals(1, resource.closes)
        assertEquals(1, gateway.queries.size)
    } }
}
