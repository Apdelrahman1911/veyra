@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import platform.Foundation.NSError
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNoPermissionsToReadFile

/** Preserve native failure meaning so the shared, bounded same-recording renewal can run. */
internal fun classifyIosAudioFailure(error: NSError?, local: Boolean, httpStatus: Int? = null): AppFailure {
    val chain = buildList {
        var next = error
        repeat(4) {
            val value = next ?: return@repeat
            add(value)
            next = value.userInfo[NSUnderlyingErrorKey] as? NSError
        }
    }
    val code = when {
        local -> FailureCode.CORRUPT_MEDIA
        httpStatus == 401 || httpStatus == 403 -> FailureCode.PROVIDER_ACCESS_REQUIRED
        httpStatus == 404 || httpStatus == 410 -> FailureCode.NOT_FOUND
        httpStatus == 429 -> FailureCode.RATE_LIMITED
        httpStatus != null && httpStatus in 500..599 -> FailureCode.NETWORK_UNAVAILABLE
        chain.any { it.domain == NSURLErrorDomain && it.code == NSURLErrorNoPermissionsToReadFile } ->
            FailureCode.PROVIDER_ACCESS_REQUIRED
        chain.any { it.domain == NSURLErrorDomain && (it.code in setOf(-1001L, -1003L, -1004L, -1005L, -1006L, -1009L, -1018L) ||
            it.code in -1206L..-1200L) } -> FailureCode.NETWORK_UNAVAILABLE
        // AVFoundation/AVError.h: DecodeFailed, FileFormatNotRecognized, DecoderNotFound.
        chain.any { it.domain == "AVFoundationErrorDomain" && it.code in setOf(-11821L, -11828L, -11833L) } ->
            FailureCode.UNSUPPORTED_CODEC
        else -> FailureCode.UNSUPPORTED_SOURCE
    }
    return AppFailure(code, "native_audio_${error?.code ?: 0}")
}
