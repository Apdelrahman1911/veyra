package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.ExtractionDiagnostic
import dev.kmpyt.extraction.ExtractionReport
import dev.kmpyt.extraction.ProviderId
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.ResearchTransport
import dev.kmpyt.extraction.TrackRole
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Authored merged public-client report; real selection and playback planning, no media transfer. */
class YouTubeAudioFailureProvenanceTest {
    private val video = "ABCDEFGHIJK"
    private val watch = "https://www.youtube.com/watch?v=$video"
    private val audio = Representation("140", "https://media.example/audio.m4a?sig=authored", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 32, bitrate = 128_000)

    private fun report(code: String, usable: Boolean = true) = ExtractionReport(watch, ProviderId.YOUTUBE,
        listOf(ExtractedMedia(video, ProviderId.YOUTUBE, "Authored source", watch,
            listOf(if (usable) audio else audio.copy(unavailableReason = "Player reports UNPLAYABLE")))),
        listOf(ExtractionDiagnostic("youtube-player", code, "Authored alternate client response")))

    private suspend fun resolve(report: ExtractionReport): String {
        val client = YouTubeClient(ResearchTransport { error("A supplied report must not repeat discovery or inspect a different video") })
        try {
            val execution = YouTubeAudioExecution(client, { 1_000 }, {})
            return execution.resolve(YouTubeAudioExecution.Input(video, { error("Unexpected reinspection") }, report),
                EngineCapabilities(), ResolvePurpose.PLAYBACK) { it.uri }
        } finally { client.close() }
    }

    @Test fun unavailableAlternateClientDoesNotDiscardActualPlayableAudioForTheSameVideo() = runTest {
        assertEquals(audio.url, resolve(report("PLAYER_UNAVAILABLE")))
    }

    @Test fun unavailableVideoWithoutAUsableDeclaredTrackStillFails() = runTest {
        val failure = assertFailsWith<VeyraException> { resolve(report("PLAYER_UNAVAILABLE", usable = false)) }.failure
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, failure.code)
        assertEquals("YOUTUBE_PLAYER_UNAVAILABLE", failure.detail)
    }

    @Test fun authorizationAndRateLimitsRemainBlockingEvenWithOtherPlayableRepresentations() = runTest {
        for ((diagnostic, expected) in listOf("AUTHORIZATION_REQUIRED" to FailureCode.PROVIDER_ACCESS_REQUIRED,
            "CLIENT_FALLBACK_AUTHORIZATION_REQUIRED" to FailureCode.PROVIDER_ACCESS_REQUIRED,
            "PUBLIC_RESPONSE_ACCESS_DENIED" to FailureCode.PROVIDER_ACCESS_REQUIRED,
            "PUBLIC_RESPONSE_RATE_LIMITED" to FailureCode.RATE_LIMITED)) {
            assertEquals(expected, assertFailsWith<VeyraException> { resolve(report(diagnostic)) }.failure.code)
        }
    }
}
