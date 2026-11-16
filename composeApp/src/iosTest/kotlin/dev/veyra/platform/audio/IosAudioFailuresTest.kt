@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSError
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorTimedOut

class IosAudioFailuresTest {
    @Test fun remoteAccessDenialKeepsTheMeaningNeededForBoundedSourceRenewal() {
        val denied = NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorNoPermissionsToReadFile, null)
        assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, classifyIosAudioFailure(denied, local = false).code)
        assertEquals("native_audio_-1102", classifyIosAudioFailure(denied, local = false).detail)
        val wrapped = NSError.errorWithDomain("AVFoundationErrorDomain", -11800,
            mapOf(NSUnderlyingErrorKey to denied))
        assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, classifyIosAudioFailure(wrapped, local = false).code)
        assertEquals(FailureCode.CORRUPT_MEDIA, classifyIosAudioFailure(denied, local = true).code)
    }

    @Test fun networkCodecAndActualHttpErrorsStayDistinct() {
        val timeout = NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorTimedOut, null)
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, classifyIosAudioFailure(timeout, false).code)
        val decode = NSError.errorWithDomain("AVFoundationErrorDomain", -11821, null)
        assertEquals(FailureCode.UNSUPPORTED_CODEC, classifyIosAudioFailure(decode, false).code)
        for ((status, expected) in listOf(401 to FailureCode.PROVIDER_ACCESS_REQUIRED,
            403 to FailureCode.PROVIDER_ACCESS_REQUIRED, 404 to FailureCode.NOT_FOUND,
            410 to FailureCode.NOT_FOUND, 429 to FailureCode.RATE_LIMITED, 503 to FailureCode.NETWORK_UNAVAILABLE)) {
            assertEquals(expected, classifyIosAudioFailure(null, false, status).code)
        }
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, classifyIosAudioFailure(null, false).code)
    }
}
