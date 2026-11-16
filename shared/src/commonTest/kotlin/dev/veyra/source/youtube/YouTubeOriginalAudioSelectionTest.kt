package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.ProviderId
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YouTubeOriginalAudioSelectionTest {
    private val videoId = "ABCDEFGHIJK"
    private val original = Representation(
        id = "140-ar",
        url = "https://media.example/authored-original.m4a",
        role = TrackRole.AUDIO,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
        bitrate = 128_000,
        sizeBytes = 1024,
        audioTrackId = "ar.4",
        audioLanguage = "ar",
        audioIsDefault = false,
        audioDisplayName = "Arabic original",
        audioIsAutoDubbed = false,
    )
    private val dubbed = original.copy(
        id = "140-en",
        url = "https://media.example/authored-dub.m4a",
        audioTrackId = "en-US.10",
        audioLanguage = "en-US",
        audioIsDefault = true,
        audioDisplayName = "English (auto-dubbed)",
        audioIsAutoDubbed = true,
    )

    private fun media(vararg formats: Representation) = ExtractedMedia(
        videoId, ProviderId.YOUTUBE, "Authored multilingual fixture",
        "https://www.youtube.com/watch?v=$videoId", formats.toList(),
    )

    @Test
    fun declaredOriginalWinsOverDefaultDubForPlaybackAndDownload() {
        for (purpose in listOf(ResolvePurpose.PLAYBACK, ResolvePurpose.DOWNLOAD)) {
            assertEquals(original, selectYouTubeAudio(
                media(dubbed, original), EngineCapabilities(), purpose = purpose, platformName = "iOS",
            ))
        }
    }

    @Test
    fun originalPreferenceDoesNotInferAnArabicLanguagePreference() {
        val englishOriginal = original.copy(
            audioTrackId = "en.4", audioLanguage = "en", audioDisplayName = "English original",
        )
        val arabicDub = dubbed.copy(
            audioTrackId = "ar.10", audioLanguage = "ar", audioDisplayName = "Arabic (auto-dubbed)",
        )
        assertEquals(englishOriginal, selectYouTubeAudio(media(arabicDub, englishOriginal), EngineCapabilities()))
    }

    @Test
    fun automaticOriginalSelectionIsIndependentOfAnyFixedLanguageForBothPurposes() {
        for ((language, label) in listOf("en" to "English", "fr" to "French", "es" to "Spanish",
            "ja" to "Japanese", "hi" to "Hindi", "ar" to "Arabic")) {
            val actualOriginal = original.copy(id = "140-$language", audioTrackId = "$language.4",
                audioLanguage = language, audioDisplayName = "$label original")
            val defaultDub = dubbed.copy(audioTrackId = "dub.10", audioLanguage = "und")
            for (purpose in ResolvePurpose.entries) for (platform in listOf("iOS", "Android", "JVM")) {
                assertEquals(actualOriginal, selectYouTubeAudio(media(defaultDub, actualOriginal),
                    EngineCapabilities(), purpose = purpose, platformName = platform))
            }
        }
    }

    @Test
    fun declaredOriginalDoesNotRequireAnInferredLanguageCode() {
        val actualOriginal = original.copy(audioTrackId = "ja.4", audioLanguage = null,
            audioDisplayName = "Japanese original")
        assertEquals(actualOriginal, selectYouTubeAudio(media(dubbed, actualOriginal), EngineCapabilities()))
    }

    @Test
    fun unsupportedOriginalDoesNotSilentlyFallBackToADub() {
        val unsupported = original.copy(id = "251-ar", mimeType = "audio/webm", codecs = "opus")
        for (purpose in listOf(ResolvePurpose.PLAYBACK, ResolvePurpose.DOWNLOAD)) {
            assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
                selectYouTubeAudio(media(dubbed, unsupported), EngineCapabilities(), purpose = purpose, platformName = "iOS")
            }.failure.code)
        }
    }

    @Test
    fun unsupportedOriginalTransportDoesNotPromoteAProgressiveDub() {
        val segmentedOriginal = original.copy(transport = TransportKind.HLS)
        assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, assertFailsWith<VeyraException> {
            selectYouTubeAudio(media(dubbed, segmentedOriginal), EngineCapabilities())
        }.failure.code)
    }

    @Test
    fun unknownOriginalMetadataRetainsTheDeclaredDefaultFallback() {
        val unknown = original.copy(audioDisplayName = "Arabic", audioIsAutoDubbed = null)
        assertEquals(dubbed, selectYouTubeAudio(media(unknown, dubbed), EngineCapabilities()))
    }

    @Test
    fun renewalKeepsExactOriginalWhenDefaultAndOrderChange() {
        val selectedKey = original.audioSelectionKey(videoId)
        val renewedOriginal = original.copy(
            url = "https://media.example/authored-renewed.m4a", audioIsDefault = true,
        )
        assertEquals(renewedOriginal, selectYouTubeAudio(
            media(dubbed.copy(audioIsDefault = false), renewedOriginal), EngineCapabilities(), selectedKey,
        ))
        assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", assertFailsWith<VeyraException> {
            selectYouTubeAudio(media(dubbed), EngineCapabilities(), selectedKey)
        }.failure.detail)
    }

    @Test
    fun retainedExplicitDubIdentityIsNotRewrittenByTheNewAutomaticPolicy() {
        assertEquals(dubbed, selectYouTubeAudio(
            media(original, dubbed), EngineCapabilities(), dubbed.audioSelectionKey(videoId),
        ))
    }
}
