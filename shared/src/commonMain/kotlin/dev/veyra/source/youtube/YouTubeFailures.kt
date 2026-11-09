package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractionReport
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CancellationException

internal fun youtubeFailure(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))

/** Inspect only public stable diagnostic codes, never the accompanying response/error text. */
internal fun ExtractionReport.sourceFailure(): AppFailure? {
    val source = diagnostics.filter { it.stage != "youtube-oembed" }
    fun has(vararg codes: String) = source.any { it.code in codes }
    return when {
        has("AUTHORIZATION_REQUIRED", "CLIENT_FALLBACK_AUTHORIZATION_REQUIRED", "PUBLIC_RESPONSE_ACCESS_DENIED", "REDIRECT_NOT_FOLLOWED") ->
            AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED")
        has("PUBLIC_RESPONSE_RATE_LIMITED") -> AppFailure(FailureCode.RATE_LIMITED, "YOUTUBE_RATE_LIMITED")
        has("PUBLIC_VIDEO_NOT_FOUND") -> AppFailure(FailureCode.NOT_FOUND, "YOUTUBE_NOT_FOUND")
        has("VIDEO_ID_MISMATCH", "VIDEO_ID_MISSING") -> AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_IDENTITY_MISMATCH")
        has("PLAYER_UNAVAILABLE") -> AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_PLAYER_UNAVAILABLE")
        has("TRANSPORT_TIMEOUT", "TRANSPORT_DNS_FAILURE", "TRANSPORT_TLS_FAILURE", "TRANSPORT_CONNECTION_FAILURE", "TRANSPORT_FAILURE", "NETWORK_OR_RESPONSE_LIMIT", "PUBLIC_RESPONSE_HTTP_ERROR") ->
            AppFailure(FailureCode.NETWORK_UNAVAILABLE, "YOUTUBE_NETWORK_UNAVAILABLE")
        else -> null
    }
}

internal fun ExtractionReport.noMediaFailure(): AppFailure = sourceFailure() ?: when {
    diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_UNAVAILABLE" } ->
        AppFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_RUNTIME_UNAVAILABLE")
    else -> AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_METADATA_UNAVAILABLE")
}

internal data class BrowseNotice(val failure: AppFailure, val blocking: Boolean = false)

/** Compatibility mapping for the pinned library's authored notices; no arbitrary text escapes. */
internal fun youtubeBrowseNotice(notice: String?): BrowseNotice? = when (notice) {
    null -> null
    "YouTube requires access or sign-in for this page. Open it in YouTube.",
    "YouTube asks for consent, sign-in or another page. Open this page in YouTube to continue.",
    "YouTube redirected this request. Open the page in YouTube to continue.",
    "YouTube redirected the next-page request. Reload the list or open it in YouTube." ->
        BrowseNotice(AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED"), true)
    "This YouTube page was not found. Check the link or try searching." ->
        BrowseNotice(AppFailure(FailureCode.NOT_FOUND, "YOUTUBE_NOT_FOUND"), true)
    "YouTube temporarily limited requests. Wait a little and try again." ->
        BrowseNotice(AppFailure(FailureCode.RATE_LIMITED, "YOUTUBE_RATE_LIMITED"), true)
    "YouTube could not be reached. Check your connection and try again.",
    "YouTube returned too many redirects. Try again later." ->
        BrowseNotice(AppFailure(FailureCode.NETWORK_UNAVAILABLE, "YOUTUBE_NETWORK_UNAVAILABLE"), true)
    "YouTube returned no public recommendations. Search for a video, or open YouTube in the browser to sign in." ->
        BrowseNotice(AppFailure(FailureCode.NOT_FOUND, "YOUTUBE_NO_PUBLIC_RECOMMENDATIONS"))
    "No public videos, channels or playlists were returned. Search again or open this page in YouTube.",
    "No more public results were returned." ->
        BrowseNotice(AppFailure(FailureCode.NOT_FOUND, "YOUTUBE_NO_PUBLIC_RESULTS"))
    "Unavailable entries were omitted from this public list." ->
        BrowseNotice(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_UNAVAILABLE_ENTRIES_OMITTED"))
    "More results are available in YouTube. This page did not provide a usable public continuation.",
    "YouTube returned an unsupported next-page destination. Open the full list in YouTube.",
    "YouTube repeated its continuation. Reload the list to check for more results.",
    "The page limit was reached. Refine your search or open the full list in YouTube.",
    "The page limit was reached. Open the full list in YouTube to continue.",
    "YouTube returned more results than this page limit. Some entries were omitted; refine your search or open the full list in YouTube." ->
        BrowseNotice(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_BROWSE_LIMIT"))
    else -> if (Regex("YouTube could not load this page \\(HTTP [0-9]{3}\\). Try again later\\.").matches(notice)) {
        BrowseNotice(AppFailure(FailureCode.NETWORK_UNAVAILABLE, "YOUTUBE_NETWORK_UNAVAILABLE"), true)
    } else BrowseNotice(AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_BROWSE_UNREADABLE"), true)
}

/** The pinned native adapters do not yet export a common typed transfer error. */
internal fun Throwable.isYouTubeExpiredLink(): Boolean =
    this::class.simpleName == "ExpiredResourceException" ||
        (this is IllegalStateException && message == "The media link is no longer available. Inspect the video again and retry.")

internal fun Throwable.asYouTubeFailure(download: Boolean = false): Nothing {
    if (this is CancellationException) throw this
    if (this is VeyraException) throw this
    val names = generateSequence(this) { it.cause }.take(4).mapNotNull { it::class.simpleName }.toSet()
    val failure = when {
        isYouTubeExpiredLink() -> AppFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
        "InsufficientMediaStorageException" in names -> AppFailure(FailureCode.STORAGE_FULL, "YOUTUBE_STORAGE_FULL")
        names.any { it in setOf("IosMediaStorageException", "MediaStorageAbort", "FileNotFoundException", "FileSystemException") } ->
            AppFailure(FailureCode.STORAGE_IO, "YOUTUBE_STORAGE_IO")
        names.any { it == "SecurityException" || it == "AccessDeniedException" } ->
            AppFailure(FailureCode.PERMISSION_DENIED, "YOUTUBE_PERMISSION_DENIED")
        "PermanentDownloadException" in names -> AppFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_REJECTED")
        names.any { it.contains("Timeout") || it in setOf("UnknownHostException", "ConnectException", "IOException", "RetryableDownloadException") || it.startsWith("SSL") } ->
            AppFailure(FailureCode.NETWORK_UNAVAILABLE, "YOUTUBE_NETWORK_UNAVAILABLE")
        this is IllegalArgumentException -> AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_SOURCE_REJECTED")
        else -> AppFailure(FailureCode.UNEXPECTED, if (download) "YOUTUBE_DOWNLOAD_FAILED" else "YOUTUBE_OPERATION_FAILED")
    }
    // Deliberately do not attach the original cause: native errors can contain signed URLs.
    throw VeyraException(failure)
}
