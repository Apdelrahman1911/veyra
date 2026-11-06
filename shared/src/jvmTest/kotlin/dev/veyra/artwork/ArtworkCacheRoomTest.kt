package dev.veyra.artwork

import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.source.network.KtorSafeHttp
import dev.veyra.source.network.PublicDnsResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual Room + owned filesystem; authored PNG bytes and MockEngine, no live image/audio claims. */
class ArtworkCacheRoomTest {
    private class Fixture(val directory: Path, fileSystem: FileSystem = FileSystem.SYSTEM) {
        val item = MediaItem("authored-artwork-item", "Authored metadata title", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-image-test"), artworkUrl = "https://images.example.com/image.png")
        val show = PodcastShow("authored-show", "Authored show", "https://publisher.example.com/feed",
            artworkUrl = "https://images.example.com/show.png")
        var store: LibraryStore = createPlatformLibraryStore(directory.resolve("library").toString())
        val files = ArtworkFiles(directory.resolve("artwork").toString(), fileSystem)
        val calls = AtomicInteger()
        val dnsHosts = ConcurrentLinkedQueue<String>()
        var addresses: (String) -> List<String> = { listOf("93.184.216.34") }
        var reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respond(authoredArtworkPng(), HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
        }
        val engine = MockEngine { request -> calls.incrementAndGet(); reply(request) }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { host -> dnsHosts += host; addresses(host) })
        var cache = ArtworkCache(store, files, http)

        suspend fun seed() { store.upsertCatalog(listOf(item), listOf(show)).getOrThrow(); cache.initialize() }
        suspend fun reopen() {
            cache.close(); store.close()
            store = createPlatformLibraryStore(directory.resolve("library").toString())
            cache = ArtworkCache(store, files, http)
            cache.initialize()
        }
        suspend fun deterministicCoalescing() {
            cache.close()
            // Unconfined + UNDISPATCHED callers enter the per-ID map deterministically before
            // releasing the authored network barrier; no wall-clock sleeps establish ordering.
            cache = ArtworkCache(store, files, http, dispatcher = Dispatchers.Unconfined)
            cache.initialize()
        }
        fun imageFiles(): List<Path> = Files.list(directory.resolve("artwork/images")).use { it.toList() }
        fun stagingFiles(): List<Path> = Files.list(directory.resolve("artwork/.partial")).use { it.toList() }
        suspend fun close() { cache.close(); store.close(); http.close() }
    }

    private fun workDirectory(): Path {
        val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val parent = root.resolve(".work/artwork-cache-tests")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, "room-")
    }

    private fun checkCase(fileSystem: FileSystem = FileSystem.SYSTEM, block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(30_000) {
            val fixture = Fixture(workDirectory(), fileSystem)
            try { fixture.seed(); fixture.block() } finally { fixture.close() }
        }
    }

    @Test fun streamsBoundedImageToOwnedAtomicFileAndPersistsRoomPath() = checkCase {
        val path = assertNotNull(cache.cacheMedia(item))
        assertEquals(path, store.state.value.media.getValue(item.id).localArtworkPath)
        assertEquals(path, cache.cachedArtworkPath(item.id))
        assertContentEquals(authoredArtworkPng(), Files.readAllBytes(Path.of(path)))
        assertTrue(Path.of(path).startsWith(directory.resolve("artwork/images")))
        assertEquals(1, imageFiles().size)
        assertTrue(stagingFiles().isEmpty())
        assertEquals(1, calls.get())
        assertEquals(listOf("images.example.com"), dnsHosts.toList())
        assertEquals(ArtworkImage.ACCEPT, engine.requestHistory.single().headers["Accept"])
    }

    @Test fun roomCloseReopenKeepsHistoricalImageOfflineWithoutDnsOrHttp() = checkCase {
        val path = cache.cacheMedia(item)
        val corrected = item.copy(title = "Corrected title", artworkUrl = "https://images.example.com/new.png")
        store.upsertCatalog(listOf(corrected)).getOrThrow()
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        reopen()
        assertEquals(path, cache.cachedArtworkPath(item.id))
        assertEquals(path, cache.cacheMedia(corrected))
        assertEquals(1, calls.get())
        assertEquals(1, dnsHosts.size)
        assertEquals("Corrected title", store.state.value.media[item.id]?.title)
    }

    @Test fun showCacheAndEpisodeFallbackUseOnlyLocalArtwork() = checkCase {
        val episode = item.copy(id = "authored-episode", kind = MediaKind.EPISODE,
            source = SourceRef(SourceProvider.RSS, "authored-episode"), showId = show.id, artworkUrl = null)
        store.upsertCatalog(listOf(episode)).getOrThrow()
        val path = cache.cacheShow(show)
        assertEquals(path, cache.cachedShowArtworkPath(show.id))
        assertEquals(path, cache.cachedArtworkPath(episode.id))
        assertNull(cache.cacheMedia(episode))
        assertEquals(1, calls.get())
        reopen()
        assertEquals(path, cache.cachedArtworkPath(episode.id))
    }

    @Test fun offlineWithoutCachedImageFailsBeforeDnsAndHttp() = checkCase {
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertNull(cache.cachedArtworkPath(item.id))
        assertEquals(0, calls.get())
        assertTrue(dnsHosts.isEmpty())
        assertTrue(imageFiles().isEmpty())
    }

    @Test fun sameIdConcurrentCallersShareOneSuccessfulRequest() = checkCase {
        deterministicCoalescing()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); release.await(); respond(authoredArtworkPng(), headers = headersOf("Content-Type", "image/png")) }
        coroutineScope {
            val requests = List(8) { async(start = CoroutineStart.UNDISPATCHED) { cache.cacheMedia(item) } }
            entered.await()
            assertEquals(1, calls.get())
            release.complete(Unit)
            val paths = requests.awaitAll()
            assertEquals(1, paths.distinct().size)
            assertNotNull(paths.first())
        }
        assertEquals(1, calls.get())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun sameIdConcurrentFailureIsCoalescedInsteadOfAnAutomaticRetry() = checkCase {
        deterministicCoalescing()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); release.await(); respond("", HttpStatusCode.TooManyRequests) }
        coroutineScope {
            val requests = List(5) { async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code
            } }
            entered.await(); release.complete(Unit)
            assertEquals(listOf(FailureCode.RATE_LIMITED), requests.awaitAll().distinct())
        }
        assertEquals(1, calls.get())
        assertNull(cache.cachedArtworkPath(item.id))
    }

    @Test fun cancellingOneWaiterDoesNotCancelAnotherSharedRequest() = checkCase {
        deterministicCoalescing()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); release.await(); respond(authoredArtworkPng(), headers = headersOf("Content-Type", "image/png")) }
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.cacheMedia(item) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.cacheMedia(item) }
            first.cancelAndJoin(); release.complete(Unit)
            assertNotNull(second.await())
        }
        assertEquals(1, calls.get())
    }

    @Test fun distinctIdsRespectTheBoundedParallelRequestLimit() = checkCase {
        val active = AtomicInteger(); val peak = AtomicInteger(); val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        reply = {
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count) { previous, next -> maxOf(previous, next) }
            if (count == 2) twoStarted.complete(Unit)
            try { release.await(); respond(authoredArtworkPng(), headers = headersOf("Content-Type", "image/png")) }
            finally { active.decrementAndGet() }
        }
        coroutineScope {
            val requests = List(5) { number -> async { cache.cacheMedia(item.copy(id = "parallel-$number")) } }
            twoStarted.await()
            assertEquals(2, calls.get())
            release.complete(Unit)
            assertTrue(requests.awaitAll().all { it != null })
        }
        assertEquals(2, peak.get())
        assertEquals(5, imageFiles().size)
    }

    @Test fun mimeAudioAndMislabeledBytesNeverPublishAnArtworkPath() = checkCase {
        for ((body, mime) in listOf(authoredArtworkPng() to "audio/mpeg", "ID3-not-image".encodeToByteArray() to "image/png")) {
            reply = { respond(body, headers = headersOf("Content-Type", mime)) }
            assertFailsWith<VeyraException> { cache.cacheMedia(item) }
            assertNull(store.state.value.media[item.id]?.localArtworkPath)
            assertTrue(imageFiles().isEmpty())
            assertTrue(stagingFiles().isEmpty())
        }
    }

    @Test fun declaredOversizedAndInexactResponseLengthsCannotPublish() = checkCase {
        for (declared in listOf(ArtworkImage.MAX_BYTES + 1, 1L, authoredArtworkPng().size + 1L)) {
            reply = { respond(authoredArtworkPng(), headers = headersOf("Content-Type" to listOf("image/png"),
                "Content-Length" to listOf(declared.toString()))) }
            assertFailsWith<VeyraException> { cache.cacheMedia(item) }
            assertNull(cache.cachedArtworkPath(item.id))
            assertTrue(imageFiles().isEmpty())
            assertTrue(stagingFiles().isEmpty())
        }
    }

    @Test fun unannouncedOversizedStreamIsStoppedAtTheImageLimit() = checkCase {
        val oversized = ByteArray(ArtworkImage.MAX_BYTES.toInt() + 1)
        reply = { respond(oversized, headers = headersOf("Content-Type", "image/png")) }
        assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertTrue(imageFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun partialAndEncodedImageResponsesAreNotTreatedAsComplete() = checkCase {
        reply = { respond(authoredArtworkPng(), HttpStatusCode.PartialContent, headersOf("Content-Type", "image/png")) }
        assertFailsWith<VeyraException> { cache.cacheMedia(item) }
        reply = { respond(authoredArtworkPng(), headers = headersOf("Content-Type" to listOf("image/png"), "Content-Encoding" to listOf("gzip"))) }
        assertFailsWith<VeyraException> { cache.cacheMedia(item) }
        assertTrue(imageFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun privateDnsAndRedirectOriginsAreRejectedBeforeFetchingImages() = checkCase {
        addresses = { listOf("127.0.0.1") }
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(0, calls.get())
        addresses = { listOf("93.184.216.34") }
        reply = { respond("", HttpStatusCode.Found, headersOf("Location", "https://127.0.0.1/never-open")) }
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(1, calls.get())
        assertTrue(imageFiles().isEmpty())
    }

    @Test fun unsafeOriginalArtworkUrlNeverReachesDns() = checkCase {
        store.upsertCatalog(listOf(item.copy(artworkUrl = "https://user:authored@example.com/image"))).getOrThrow()
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(0, calls.get())
        assertTrue(dnsHosts.isEmpty())
    }

    @Test fun offlineToggleBetweenRedirectsPreventsTheNextDnsAndRequest() = checkCase {
        reply = {
            store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            respond("", HttpStatusCode.Found, headersOf("Location", "https://other.example.com/image.png"))
        }
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(1, calls.get())
        assertEquals(listOf("images.example.com"), dnsHosts.toList())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun offlineToggleCancelsAWaitingResponseAndPreservesThePriorImage() = checkCase {
        val original = cache.cacheMedia(item)
        store.upsertCatalog(listOf(item.copy(artworkUrl = "https://images.example.com/new.png"))).getOrThrow()
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        coroutineScope {
            val request = async { assertFailsWith<VeyraException> { cache.cacheMedia(item) } }
            entered.await()
            store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            assertEquals(FailureCode.OFFLINE_POLICY, request.await().failure.code)
            cancelled.await()
        }
        assertEquals(original, cache.cachedArtworkPath(item.id))
        assertEquals(original, store.state.value.media[item.id]?.localArtworkPath)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun changedMetadataDuringTransferDoesNotOverwriteTheNewerDiscoveryItem() = checkCase {
        val corrected = item.copy(title = "New current title", artworkUrl = "https://images.example.com/corrected.png")
        reply = {
            store.upsertCatalog(listOf(corrected)).getOrThrow()
            respond(authoredArtworkPng(), headers = headersOf("Content-Type", "image/png"))
        }
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(corrected, store.state.value.media[item.id])
        assertNull(cache.cachedArtworkPath(item.id))
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun corruptedPersistedImageIsClearedOnReopenWithoutNetworkFallback() = checkCase {
        val path = assertNotNull(cache.cacheMedia(item))
        val bytes = Files.readAllBytes(Path.of(path)); bytes[bytes.lastIndex - 15] = 42
        Files.write(Path.of(path), bytes)
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        reopen()
        assertNull(store.state.value.media[item.id]?.localArtworkPath)
        assertNull(cache.cachedArtworkPath(item.id))
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertEquals(1, calls.get())
    }

    @Test fun missingImageIsClearedOnReopenWhileMetadataRemainsAvailable() = checkCase {
        val path = assertNotNull(cache.cacheShow(show))
        Files.delete(Path.of(path))
        reopen()
        assertNull(cache.cachedShowArtworkPath(show.id))
        assertNull(store.state.value.shows[show.id]?.localArtworkPath)
        assertEquals(show.title, store.state.value.shows[show.id]?.title)
        assertEquals(1, calls.get())
    }

    @Test fun externalMetadataPathIsNeverReadOrDeletedAsOwnedArtwork() = checkCase {
        val external = directory.resolve("not-in-image-store.png")
        Files.write(external, authoredArtworkPng())
        store.upsertCatalog(listOf(item.copy(localArtworkPath = external.toString()))).getOrThrow()
        reopen()
        assertNull(cache.cachedArtworkPath(item.id))
        assertNull(store.state.value.media[item.id]?.localArtworkPath)
        assertContentEquals(authoredArtworkPng(), Files.readAllBytes(external))
        assertEquals(0, calls.get())
    }

    @Test fun imageCannotBeAssignedToAnotherMediaIdByCopyingItsManagedPath() = checkCase {
        val path = cache.cacheMedia(item)
        val other = item.copy(id = "different-owner", localArtworkPath = path)
        store.upsertCatalog(listOf(other)).getOrThrow()
        reopen()
        assertEquals(path, cache.cachedArtworkPath(item.id))
        assertNull(cache.cachedArtworkPath(other.id))
        assertNull(store.state.value.media[other.id]?.localArtworkPath)
    }

    @Test fun symlinkImageAndSymlinkParentAreRejectedWithoutTouchingTheirTargets() = checkCase {
        val path = Path.of(assertNotNull(cache.cacheMedia(item)))
        val preserved = directory.resolve("preserved-image.png")
        Files.move(path, preserved); Files.createSymbolicLink(path, preserved)
        reopen()
        assertNull(cache.cachedArtworkPath(item.id))
        assertTrue(Files.isRegularFile(preserved))
        val linked = directory.resolve("linked-parent")
        Files.createSymbolicLink(linked, directory.resolve("not-created-target"))
        assertFailsWith<VeyraException> { ArtworkFiles(linked.resolve("artwork").toString()).initialize() }
        assertFalse(Files.exists(directory.resolve("not-created-target")))
    }

    @Test fun storageFullIsTypedAndDoesNotLinkPartialArtwork() = checkCase(object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
            if (functionName == "sink" && path.name.endsWith(".part")) throw java.io.IOException("ENOSPC authored disk full")
            return path
        }
    }) {
        assertEquals(FailureCode.STORAGE_FULL, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertNull(store.state.value.media[item.id]?.localArtworkPath)
        assertTrue(imageFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun failedRoomCommitDoesNotAdvertisePublishedBytesAsCached() = checkCase {
        reply = {
            store.close()
            respond(authoredArtworkPng(), headers = headersOf("Content-Type", "image/png"))
        }
        assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertNull(cache.cachedArtworkPath(item.id))
        assertNull(store.state.value.media[item.id]?.localArtworkPath)
        assertTrue(stagingFiles().isEmpty())
        // Complete but unreferenced bytes are safe; never delete a possibly committed Room file.
        assertEquals(1, imageFiles().size)
    }

    @Test fun diskFullDuringBufferedWriteIsNotMisclassifiedAsAnHttpFailure() = checkCase(object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun sink(file: okio.Path, mustCreate: Boolean): okio.Sink {
            val underlying = super.sink(file, mustCreate)
            return object : ForwardingSink(underlying) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    throw java.io.IOException("ENOSPC authored buffered-write failure")
                }
            }
        }
    }) {
        assertEquals(FailureCode.STORAGE_FULL, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertNull(store.state.value.media[item.id]?.localArtworkPath)
        assertTrue(imageFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun closeCancelsTheProducerAndPreventsSubsequentHttpWork() = checkCase {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        coroutineScope {
            val request = async { cache.cacheMedia(item) }
            entered.await(); cache.close(); cancelled.await()
            assertFailsWith<CancellationException> { request.await() }
        }
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { cache.cacheMedia(item) }.failure.code)
        assertNull(cache.cachedArtworkPath(item.id))
        assertEquals(1, calls.get())
        assertTrue(stagingFiles().isEmpty())
    }
}
