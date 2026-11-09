package dev.veyra.data

import dev.veyra.model.MediaItem
import dev.veyra.model.PodcastShow

/** Refresh metadata is not an instruction to discard an independently cached offline artwork file. */
internal fun MediaItem.withRetainedLocalArtwork(previous: MediaItem?): MediaItem =
    if (localArtworkPath != null || previous?.localArtworkPath == null) this
    else copy(localArtworkPath = previous.localArtworkPath)

internal fun PodcastShow.withRetainedLocalArtwork(previous: PodcastShow?): PodcastShow =
    if (localArtworkPath != null || previous?.localArtworkPath == null) this
    else copy(localArtworkPath = previous.localArtworkPath)
