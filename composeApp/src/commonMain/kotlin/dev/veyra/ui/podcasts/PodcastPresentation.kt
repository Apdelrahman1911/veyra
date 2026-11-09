package dev.veyra.ui.podcasts

import dev.veyra.model.PodcastShow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** View preferences only. Neither filtering nor sorting mutates a listening queue. */
enum class PodcastEpisodeFilter {
    ALL,
    UNPLAYED,
    IN_PROGRESS,
    PLAYED,
    DOWNLOADED,
}

enum class PodcastEpisodeSort {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

data class PodcastListOptions(
    val query: String = "",
    val filter: PodcastEpisodeFilter = PodcastEpisodeFilter.ALL,
    val sort: PodcastEpisodeSort = PodcastEpisodeSort.NEWEST_FIRST,
)

/** Unknown/invalid duration must not acquire a fabricated completion percentage. */
internal fun podcastProgressFraction(positionMs: Long, durationMs: Long?): Float? {
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    return (positionMs.coerceIn(0L, duration).toDouble() / duration.toDouble()).toFloat()
}

internal fun podcastRemainingMs(positionMs: Long, durationMs: Long?): Long? {
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    return duration - positionMs.coerceIn(0L, duration)
}

/**
 * Search-only normalization: ignores case, tatweel and Arabic vowel marks.
 * It must never be used as an episode/feed identity or to rewrite publisher text.
 */
internal fun podcastMatchesQuery(query: String, vararg fields: String?): Boolean {
    fun normalized(value: String): String = value
        .filterNot { it == '\u0640' || it in '\u064B'..'\u0652' }
        .lowercase()

    val tokens = normalized(query).split(' ', '\t', '\n', '\r').filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val searchable = fields.filterNotNull().joinToString(" ") { normalized(it) }
    return tokens.all { token -> token in searchable }
}

/**
 * Presentation-only host extraction. Validation and redirect/network policy belong
 * to the real source adapter. Never display a feed's credentials, path or query.
 */
internal fun podcastPublicHost(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd < 1) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val authority = value.substring(schemeEnd + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    if (authority.isBlank() || authority.any(Char::isWhitespace)) return null
    return authority
}

/** Known feed/enclosure locators are not publisher pages, even when attribution repeats them. */
internal fun podcastPublisherUrl(state: PodcastEpisodeUiState): String? = podcastPublisherPage(
    candidates = sequenceOf(state.episode.attribution?.sourceUrl, state.show?.websiteUrl, state.episode.source.canonicalUrl),
    privateLocators = setOfNotNull(state.episode.source.feedUrl, state.episode.source.enclosureUrl, state.show?.feedUrl) +
        state.show?.feedAliases.orEmpty(),
)

internal fun podcastPublisherUrl(show: PodcastShow): String? = podcastPublisherPage(
    candidates = sequenceOf(show.websiteUrl, show.attribution?.sourceUrl),
    privateLocators = show.feedAliases + show.feedUrl,
)

private fun podcastPublisherPage(candidates: Sequence<String?>, privateLocators: Set<String>): String? {
    val excluded = privateLocators.mapTo(mutableSetOf(), ::podcastLocatorKey)
    return candidates.filterNotNull().map(String::trim).firstOrNull { candidate ->
        val authority = candidate.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        podcastPublicHost(candidate) != null && '@' !in authority && '\\' !in candidate &&
            candidate.none { it.isWhitespace() || it.code < 32 || it.code == 127 } && podcastLocatorKey(candidate) !in excluded
    }
}

/** A presentation guard, not network validation: fragments/outer whitespace do not make a feed public. */
private fun podcastLocatorKey(value: String): String {
    val withoutFragment = value.trim().substringBefore('#')
    val schemeEnd = withoutFragment.indexOf("://")
    if (schemeEnd < 1) return withoutFragment
    val authorityEnd = withoutFragment.indexOfAny(charArrayOf('/', '?'), startIndex = schemeEnd + 3)
        .takeIf { it >= 0 } ?: withoutFragment.length
    return withoutFragment.substring(0, authorityEnd).lowercase() + withoutFragment.substring(authorityEnd)
}

/** ISO calendar date is stable across targets; the surrounding label is localized. */
@OptIn(ExperimentalTime::class)
internal fun podcastPublishedDate(epochMs: Long?): String? = epochMs?.let {
    try {
        Instant.fromEpochMilliseconds(it).toString().substringBefore('T')
    } catch (_: IllegalArgumentException) {
        null
    }
}
