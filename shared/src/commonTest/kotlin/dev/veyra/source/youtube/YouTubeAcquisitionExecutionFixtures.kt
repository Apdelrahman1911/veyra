package dev.veyra.source.youtube

import dev.kmpyt.*
import dev.kmpyt.extraction.*
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.release.*
import io.ktor.http.Url
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** Authored protocol-shaped metadata and adapters only; no real source/native/Room claims. */
internal object AcquisitionExecutionFixtures {
    const val ORIGIN = "https://www.youtube.com"
    const val VIDEO_B = "ABCDEFGHIJK"
    const val VIDEO_C = "LMNOPQRSTUV"
    const val PLAYLIST = "PLabcdefghijklmnopqrstuv"
    const val TITLE = "  Complete requested title — الحلقة الأخيرة 尾声  "
    const val RETURNED_TITLE = "Actual first source recording C"
    const val WATCH = "$ORIGIN/watch?v=$VIDEO_C"
    const val MEDIA_ORIGIN = "https://media.example"
    const val SABR_ENDPOINT = "$MEDIA_ORIGIN/sabr?sig=authored-secret"
    const val LOOPBACK = "http://127.0.0.1:32123/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/audio"
    const val CONFIG = "<script>ytcfg.set({\"INNERTUBE_CONTEXT_CLIENT_NAME\":1,\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"authored\"}}});</script>"

    fun request(kind: AcquisitionKind = AcquisitionKind.RELEASE, title: String = TITLE): AcquisitionRequest {
        val metadata = when (kind) {
            AcquisitionKind.MEDIA -> MediaCapture(MediaItem("catalog:authored-track", title, MediaKind.MUSIC,
                SourceRef(SourceProvider.COMMONS, "authored", "https://commons.wikimedia.org/wiki/Authored")))
            AcquisitionKind.ALBUM -> AlbumCapture(Album("catalog:authored-album", title, trackIds = listOf("unrelated-track")))
            AcquisitionKind.RELEASE -> ReleaseCapture(CapturedReleaseObservation(ReleaseObservationId("captured-release"), release(title)))
            AcquisitionKind.SHOW -> ShowCapture(PodcastShow("rss:authored-show", title, "https://podcast.example/feed.xml",
                episodeIds = listOf("unrelated-rss-guid")))
            AcquisitionKind.SOURCE_COLLECTION -> SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE,
                "PLrequested", title, "$ORIGIN/playlist?list=PLrequested", AcquisitionEntryKind.PLAYLIST, CollectionScope.PLAYLIST_MEMBERS))
            AcquisitionKind.COLLECTION_MEMBER -> CollectionMemberCapture(CollectionSnapshotId("saved-snapshot"), member(title))
        }
        return AcquisitionRequest(AcquisitionRequestId("${kind.name.lowercase()}-request"), title, metadata,
            capturedEpochMs = 123_000, catalogRevision = 7, providerRevision = "saved-metadata-revision")
    }

    fun member(title: String = TITLE): CollectionMember {
        val reference = CollectionMemberReference(CollectionObservationId("saved-observation"), AcquisitionRequestId("parent-request"),
            CollectionIdentity(AcquisitionEntryKind.PLAYLIST, PLAYLIST, "$ORIGIN/playlist?list=$PLAYLIST"),
            CollectionScope.PLAYLIST_MEMBERS, 2)
        return CollectionMember(reference, SourceEntry(AcquisitionEntryKind.VIDEO, VIDEO_B, title, "$ORIGIN/watch?v=$VIDEO_B", ordinal = 2),
            SourceDisplayItem(VIDEO_B, title, "$ORIGIN/watch?v=$VIDEO_B", AcquisitionEntryKind.VIDEO))
    }

    fun receipt(request: AcquisitionRequest) = FirstResultObservation(request.id, request.completeTitle,
        SourceEntry(AcquisitionEntryKind.VIDEO, VIDEO_C, RETURNED_TITLE, WATCH), WATCH)

    fun context(
        request: AcquisitionRequest = AcquisitionExecutionFixtures.request(),
        receipt: FirstResultObservation = AcquisitionExecutionFixtures.receipt(request),
    ): AcquisitionExecutionContext {
        val recording = SourceRecording(SourceRecordingId("source:C"), VIDEO_C, WATCH)
        val subject = if (request.kind == AcquisitionKind.MEDIA) {
            val media = (request.metadata as MediaCapture).media
            ExecutableSubject.CatalogMedia(ExecutableSubjectId(media.id), media.id)
        } else ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("neutral:C"), recording.id)
        val observed = FirstResultReceipt.Observed(FirstResultReceiptId("receipt:${request.id.value}"), receipt, 124_000)
        val binding = ExecutionBinding(ExecutionBindingId("binding:${request.id.value}"), subject.id, observed.id,
            recording.id, ExecutionBindingOrigin.TYPED)
        return AcquisitionExecutionContext(request, observed, binding, subject, recording)
    }

    fun format(
        signature: String = "authored-secret",
        expirySeconds: Long? = null,
        language: String? = "en",
        default: Boolean = true,
        itag: Int = 140,
        mime: String = "audio/mp4",
        codec: String = "mp4a.40.2",
        size: Long = 32,
    ) = buildJsonObject {
        put("itag", itag)
        put("url", "$MEDIA_ORIGIN/videoplayback?id=authored&sig=$signature" + (expirySeconds?.let { "&expire=$it" } ?: ""))
        put("mimeType", "$mime; codecs=\"$codec\""); put("contentLength", size.toString()); put("bitrate", 128_000)
        put("audioChannels", 2); put("audioSampleRate", "44100")
        language?.let { putJsonObject("audioTrack") { put("id", "$it.0"); put("languageCode", it); put("audioIsDefault", default) } }
    }

    fun player(
        streaming: JsonObject = buildJsonObject { put("adaptiveFormats", JsonArray(listOf(format()))) },
        status: String = "OK",
        videoId: String = VIDEO_C,
        sabr: Boolean = false,
    ): String = buildJsonObject {
        putJsonObject("videoDetails") {
            put("videoId", videoId); put("title", "Inspected metadata may differ from the raw first title")
            put("author", "Authored uploader"); put("lengthSeconds", "3")
            putJsonObject("thumbnail") { putJsonArray("thumbnails") { add(buildJsonObject {
                put("url", "https://i.ytimg.com/vi/$videoId/default.jpg")
            }) } }
        }
        putJsonObject("playabilityStatus") { put("status", status) }
        put("streamingData", streaming)
        if (sabr) putJsonObject("playerConfig") { putJsonObject("mediaCommonConfig") {
            putJsonObject("mediaUstreamerRequestConfig") { put("videoPlaybackUstreamerConfig", "AQID") }
        } }
    }.toString()

    fun watch(formats: List<JsonObject> = listOf(format()), status: String = "OK", videoId: String = VIDEO_C) =
        "<script>var ytInitialPlayerResponse=" + player(buildJsonObject { put("adaptiveFormats", JsonArray(formats)) }, status, videoId) + ";</script>"

    fun sabrPlayer() = player(buildJsonObject {
        put("serverAbrStreamingUrl", SABR_ENDPOINT)
        putJsonArray("adaptiveFormats") { add(buildJsonObject {
            put("itag", 140); put("lastModified", "123"); put("contentLength", "32")
            put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""); put("audioChannels", 2); put("audioSampleRate", "44100")
            putJsonObject("audioTrack") { put("id", "en.0"); put("languageCode", "en"); put("audioIsDefault", true) }
        }) }
    }, sabr = true)

    fun search() = "<script>var ytInitialData=" + buildJsonObject {
        putJsonArray("contents") { add(buildJsonObject { putJsonObject("videoRenderer") {
            put("videoId", VIDEO_C); putJsonObject("title") { put("simpleText", RETURNED_TITLE) }
            putJsonObject("ownerText") { put("simpleText", "Acquired ancillary uploader") }
            putJsonObject("lengthText") { put("simpleText", "3:42") }
            putJsonObject("thumbnail") { putJsonArray("thumbnails") { add(buildJsonObject {
                put("url", "https://i.ytimg.com/vi/$VIDEO_C/hqdefault.jpg")
            }) } }
        } }) }
    }.toString() + ";</script>"

    private fun release(title: String) = ReleaseObservation(
        requestedReleaseMbid = "00000000-0000-4000-8000-000000000001",
        metadata = ReleaseCoreMetadata(ReleaseSummaryMetadata(
            releaseMbid = "00000000-0000-4000-8000-000000000001", completeTitle = title,
            releaseGroup = ReleaseGroupMetadata("00000000-0000-4000-8000-000000000002", "Different group title", "EP", emptyList()),
            artistCredits = emptyList(), status = "Official", date = "2026-11", country = "GB", releaseEvents = emptyList(),
            labels = emptyList(), barcode = null, disambiguation = "Authored edition", textLanguage = "ara", textScript = "Arab",
            packaging = null, packagingMbid = null, problems = emptyList(),
        ), emptyList(), ReleaseMembershipStatus.OBSERVED_COMPLETE, emptyList()),
        fetchedAtEpochMs = 123_000, coreDigest = "a".repeat(64), etag = null,
    )
}

internal class AcquisitionExecutionFixture(
    handler: suspend (ResearchRequest, Int) -> ResearchResponse = { request, _ ->
        assertEquals(AcquisitionExecutionFixtures.WATCH, request.url)
        assertEquals("youtube-watch", request.stage)
        ResearchResponse(request.url, 200, AcquisitionExecutionFixtures.watch())
    },
    downloader: YouTubeDownloader? = YouTubeDownloader { _, destination, _ -> YouTubeDownloadResult(destination, 32, "audio/mp4") },
    playbackAdapter: YouTubePlaybackAdapter? = null,
    platformName: String = "JVM",
    nowEpochMs: (() -> Long)? = null,
    onClose: () -> Unit = {},
) {
    val requests = mutableListOf<ResearchRequest>()
    var watches = 0
        private set
    val client = YouTubeClient(ResearchTransport { request ->
        requests += request
        if (request.stage == "youtube-watch") watches++
        handler(request, watches)
    }, downloader = downloader, playbackAdapter = playbackAdapter, platformName = platformName, onClose = onClose)
    val source = if (nowEpochMs == null) YouTubeMediaSource(client) else YouTubeMediaSource(client, nowEpochMs)
    val acquisition = source.acquisitionGateway
    val execution = YouTubeAcquisitionExecutionGateway(source)
    suspend fun close() = source.close()
    fun paths() = requests.map { Url(it.url).encodedPath }
}
