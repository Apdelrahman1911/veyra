package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.ProviderId
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.SegmentResource
import dev.kmpyt.extraction.SabrClientInfo
import dev.kmpyt.extraction.SabrFormatId
import dev.kmpyt.extraction.YouTubeSabrResource
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class YouTubeAudioSelectionTest {
    private val videoId = "ABCDEFGHIJK"
    private val aac = Representation(
        "140", "https://media.example/videoplayback?id=authored&sig=first&expire=1", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128_000, sizeBytes = 100,
        audioTrackId = "en.0", audioLanguage = "en", audioChannels = 2, audioSampleRate = 44_100,
    )
    private val opus = aac.copy(id = "251", mimeType = "audio/webm", codecs = "opus", bitrate = 160_000)
    private val broadEngine = EngineCapabilities(mimeTypes = setOf("audio/mp4", "audio/webm"), codecs = setOf("aac", "opus"))

    private fun media(vararg formats: Representation) = ExtractedMedia(
        videoId, ProviderId.YOUTUBE, "Authored fixture", "https://www.youtube.com/watch?v=$videoId", formats.toList(),
    )

    @Test fun prefersActualAacLcMp4WithoutInventingAnItag() {
        val unusualId = aac.copy(id = "actual-format-401")
        assertEquals(unusualId, selectYouTubeAudio(media(opus, unusualId), broadEngine))
        assertEquals("m4a", unusualId.audioExtension())
        assertEquals(opus, selectYouTubeAudio(media(opus), broadEngine))
        assertEquals("webm", opus.audioExtension())
    }

    @Test fun refusesHeAacUnlessNativeCapabilitiesExplicitlyDeclareThatProfile() {
        val he = aac.copy(codecs = "mp4a.40.5")
        assertEquals(FailureCode.UNSUPPORTED_CODEC,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(he), EngineCapabilities()) }.failure.code)
        assertEquals(he, selectYouTubeAudio(media(he), EngineCapabilities(codecs = setOf("mp4a.40.5"))))
        assertEquals(FailureCode.UNSUPPORTED_CODEC,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(aac.copy(codecs = null)), broadEngine) }.failure.code)
    }

    @Test fun actualDefaultLanguageIsNotReplacedByAnEasierDubbedCodec() {
        val default = opus.copy(audioIsDefault = true)
        val dubbed = aac.copy(audioTrackId = "ar.4", audioLanguage = "ar", audioIsDefault = false)
        assertEquals(default, selectYouTubeAudio(media(default, dubbed), broadEngine))
        assertEquals(FailureCode.UNSUPPORTED_CODEC,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(default, dubbed), EngineCapabilities()) }.failure.code)
    }

    @Test fun sabrEndpointAndMuxedVideoNeverBecomeAudioUris() {
        assertEquals(FailureCode.UNSUPPORTED_TRANSPORT,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(aac.copy(transport = TransportKind.SABR)), broadEngine) }.failure.code)
        assertEquals(FailureCode.UNSUPPORTED_TRANSPORT,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(aac.copy(transport = TransportKind.HLS)), broadEngine) }.failure.code)
        assertEquals(FailureCode.UNSUPPORTED_SOURCE,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(aac.copy(role = TrackRole.MUXED)), broadEngine) }.failure.code)
    }

    @Test fun selectionKeysExcludeSignedResourcesButBindTrackAndContentIdentity() {
        val key = aac.audioSelectionKey(videoId)
        assertEquals(key, aac.copy(url = "https://media.example/videoplayback?id=authored&sig=second&expire=99", expiresAtEpochMs = 99_000).audioSelectionKey(videoId))
        assertEquals(key, aac.copy(id = "140#progressive-2").audioSelectionKey(videoId))
        assertFalse(key.contains("sig"))
        assertFalse(key.contains("https"))
        assertNotEquals(key, aac.audioSelectionKey("LMNOPQRSTUV"))
        listOf(
            aac.copy(sizeBytes = 101), aac.copy(audioLanguage = "ar"), aac.copy(audioTrackId = "en.1"),
            aac.copy(audioChannels = 1), aac.copy(audioSampleRate = 48_000), aac.copy(isDrc = true),
            aac.copy(codecs = "mp4a.40.5"), aac.copy(id = "141"),
        ).forEach { assertNotEquals(key, it.audioSelectionKey(videoId)) }
    }

    @Test fun exactSelectionCannotSilentlyChangeTrackOrBecomeAmbiguous() {
        val key = aac.audioSelectionKey(videoId)
        assertEquals(aac, selectYouTubeAudio(media(opus, aac), broadEngine, key))
        assertEquals(aac, selectYouTubeAudio(media(aac.copy(transport = TransportKind.SABR), aac), broadEngine, key))
        assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", assertFailsWith<VeyraException> {
            selectYouTubeAudio(media(aac.copy(audioTrackId = "en.1")), broadEngine, key)
        }.failure.detail)
        assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", assertFailsWith<VeyraException> {
            selectYouTubeAudio(media(aac, aac.copy(id = "140#progressive")), broadEngine, key)
        }.failure.detail)
        assertEquals(FailureCode.INVALID_STATE,
            assertFailsWith<VeyraException> { selectYouTubeAudio(media(aac), broadEngine, "https://signed.example") }.failure.code)
    }

    @Test fun downloadSelectsOfferedSabrHlsAndDashUsingTheMaterializedNativeContainer() {
        val sabr = aac.copy(transport = TransportKind.SABR,
            sabr = YouTubeSabrResource(videoId, requireNotNull(aac.url), "AQID", SabrFormatId(140, 1uL), SabrClientInfo(1, "authored")))
        val segment = SegmentResource("https://media.example/audio.m4s")
        for (platform in listOf("JVM", "Android", "iOS")) {
            for (format in listOf(sabr, aac.copy(transport = TransportKind.HLS, segments = listOf(segment)),
                aac.copy(transport = TransportKind.DASH, segments = listOf(segment)))) {
                assertEquals(format, selectYouTubeAudio(media(format), EngineCapabilities(), purpose = ResolvePurpose.DOWNLOAD, platformName = platform))
                assertEquals("audio/mp4", format.materializedAudioMime())
                assertEquals("m4a", format.materializedAudioExtension())
            }
        }
        val segmentedWebm = opus.copy(transport = TransportKind.DASH, segments = listOf(segment))
        val mp4OpusEngine = EngineCapabilities(mimeTypes = setOf("audio/mp4"), codecs = setOf("opus"))
        assertEquals(segmentedWebm, selectYouTubeAudio(media(segmentedWebm), mp4OpusEngine, purpose = ResolvePurpose.DOWNLOAD))
        for (platform in listOf("Android", "iOS")) {
            assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
                selectYouTubeAudio(media(segmentedWebm), mp4OpusEngine, purpose = ResolvePurpose.DOWNLOAD, platformName = platform)
            }.failure.code)
        }
    }

    @Test fun configuredSabrBridgeDoesNotInventAResourceOrAllowSegmentedPlayback() {
        val sabr = aac.copy(transport = TransportKind.SABR,
            sabr = YouTubeSabrResource(videoId, requireNotNull(aac.url), "AQID", SabrFormatId(140, 1uL), SabrClientInfo(1, "authored")))
        assertEquals(sabr, selectYouTubeAudio(media(sabr), broadEngine, supportsSabrPlayback = true))
        for (unusable in listOf(sabr.copy(sabr = null), sabr.copy(segments = listOf(SegmentResource("https://media.example/part"))),
            aac.copy(transport = TransportKind.HLS), aac.copy(transport = TransportKind.DASH))) {
            assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, assertFailsWith<VeyraException> {
                selectYouTubeAudio(media(unusable), broadEngine, supportsSabrPlayback = true)
            }.failure.code)
        }
    }

    @Test fun purposeAndMobileMuxChecksNeverPromoteANonDefaultLanguage() {
        val default = opus.copy(audioIsDefault = true, transport = TransportKind.DASH,
            segments = listOf(SegmentResource("https://media.example/original.m4s")))
        val dubbed = aac.copy(audioLanguage = "ar", audioTrackId = "ar.1", audioIsDefault = false)
        for (platform in listOf("Android", "iOS")) assertEquals(FailureCode.UNSUPPORTED_CODEC,
            assertFailsWith<VeyraException> {
                selectYouTubeAudio(media(default, dubbed), broadEngine, purpose = ResolvePurpose.DOWNLOAD, platformName = platform)
            }.failure.code)
        assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, assertFailsWith<VeyraException> {
            selectYouTubeAudio(media(default, dubbed), broadEngine)
        }.failure.code)
    }
}
