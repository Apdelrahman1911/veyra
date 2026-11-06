package dev.veyra.source.podcast

/**
 * Complete source title, not a display ellipsis or a shortened search query. XML has already
 * decoded its own entities; JSON titles are plain text. Do not strip punctuation or interpret
 * literal angle brackets as markup. A title beyond the accepted query bound is rejected.
 */
internal const val MAX_DISCOVERY_TITLE_LENGTH = 16_384

internal fun completeDiscoveryTitle(value: String): String {
    if (value.length > MAX_DISCOVERY_TITLE_LENGTH) malformed("discovery_title_limit")
    return value.trim()
}
