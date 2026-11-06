package dev.veyra.ui.music

import dev.veyra.model.AppSnapshot
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState

/** Numeric media time, deliberately independent of the surrounding text direction. */
fun formatMediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    val remainingSeconds = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$remainingSeconds" else {
        "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$remainingSeconds"
    }
}

/** A missing duration must never be rendered as a known zero-length recording. */
fun seekFraction(positionMs: Long, durationMs: Long?): Float =
    if (durationMs == null || durationMs <= 0L) 0f
    else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

fun seekPosition(fraction: Float, durationMs: Long): Long {
    if (!fraction.isFinite() || durationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f).toDouble() * durationMs.toDouble())
        .toLong().coerceIn(0L, durationMs)
}

/** Preserve user-authored metadata; isolate only at the presentation boundary. */
fun isolateMetadata(value: String): String = dev.veyra.ui.design.isolateMetadata(value)

/** Blank playlist titles never escape the editor; normalization is not persisted to media titles. */
fun validatedPlaylistName(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

/** A reorder must identify an occurrence, not all copies of the same recording. */
fun <T> moveOccurrence(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

/** Pending clear deliberately excludes the current transport occurrence. */
fun pendingOccurrenceIds(orderedIds: List<String>, currentId: String?): List<String> {
    if (currentId == null) return orderedIds
    val index = orderedIds.indexOf(currentId)
    return if (index < 0) orderedIds else orderedIds.drop(index + 1)
}

/** Cached online search metadata alone is not library membership. */
fun libraryMedia(snapshot: AppSnapshot): List<MediaItem> {
    val referenced = buildSet {
        addAll(snapshot.favoriteIds)
        addAll(snapshot.savedEpisodeIds)
        snapshot.playlists.forEach { playlist -> addAll(playlist.entries.map { it.mediaId }) }
        addAll(snapshot.downloads.values.map { it.mediaId })
        snapshot.followedShowIds.forEach { id -> addAll(snapshot.shows[id]?.episodeIds.orEmpty()) }
    }
    return snapshot.media.values.filter { it.id in referenced || it.source.provider == SourceProvider.LOCAL }
}

/** Retrieval-only normalization: never changes displayed metadata or source identity. */
fun mediaSearchKey(value: String): String = value.lowercase().filterNot {
    it in '\u064b'..'\u065f' || it in '\u0610'..'\u061a' || it == '\u0670' || it == '\u0640'
}

fun matchesMediaQuery(item: MediaItem, query: String): Boolean {
    val words = mediaSearchKey(query.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val haystack = mediaSearchKey(listOf(item.title, item.creator, item.album.orEmpty(), item.artist.orEmpty()).joinToString(" "))
    return words.all { it in haystack }
}

fun matchesSearchKind(item: MediaItem, kind: SearchKind): Boolean = when (kind) {
    SearchKind.ALL -> true
    SearchKind.MUSIC -> item.kind == MediaKind.MUSIC
    SearchKind.PODCASTS -> item.kind == MediaKind.EPISODE
}

/** A pending play can still be paused during resolution/buffering; failure instead offers retry. */
internal fun showsPause(playback: PlaybackState): Boolean = playback.desiredPlaying &&
    playback.phase !in setOf(PlaybackPhase.IDLE, PlaybackPhase.ERROR, PlaybackPhase.ENDED)

internal fun nextRepeatMode(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}

/** Matches the coordinator's one-day bound and accepts Arabic/other Unicode decimal digits. */
internal fun validatedTimerMinutes(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val normalized = buildString {
        for (character in trimmed) append(character.digitToIntOrNull() ?: return null)
    }
    return normalized.toIntOrNull()?.takeIf { it in 1..1_440 }
}

internal fun availableSpeeds(min: Float, max: Float): List<Float> =
    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).filter { it >= min && it <= max }
