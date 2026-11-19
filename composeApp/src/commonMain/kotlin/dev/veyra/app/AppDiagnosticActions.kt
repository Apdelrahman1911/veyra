package dev.veyra.app

import dev.veyra.diagnostics.DiagnosticOperation
import dev.veyra.diagnostics.DiagnosticOperation.*
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.podcasts.PodcastAction

internal fun MusicUiAction.diagnosticOperation(): DiagnosticOperation = when (this) {
    is MusicUiAction.SearchChanged -> SEARCH_EDIT
    MusicUiAction.RunSearch, MusicUiAction.LoadMoreSearch -> SEARCH
    is MusicUiAction.Discover, MusicUiAction.LoadMoreDiscovery, MusicUiAction.ExploreMusic -> DISCOVER
    is MusicUiAction.OpenCollection, MusicUiAction.RefreshCollection, MusicUiAction.LoadMoreCollection -> COLLECTION
    is MusicUiAction.Play, is MusicUiAction.PlayCollection, is MusicUiAction.PlaySaved -> PLAY
    is MusicUiAction.PlayQueueEntry -> SWITCH
    MusicUiAction.TogglePlayPause -> TOGGLE_PLAYBACK
    MusicUiAction.Previous, MusicUiAction.Next, is MusicUiAction.Skip -> SKIP
    is MusicUiAction.Seek -> SEEK
    is MusicUiAction.Enqueue, is MusicUiAction.EnqueueCollection, is MusicUiAction.MoveQueueEntry,
    is MusicUiAction.RemoveQueueEntry, is MusicUiAction.ClearQueue, is MusicUiAction.UndoQueue, is MusicUiAction.ViewQueue -> QUEUE
    is MusicUiAction.SetShuffle -> SHUFFLE
    is MusicUiAction.SetRepeat -> REPEAT
    is MusicUiAction.SetSpeed -> SPEED
    is MusicUiAction.SetSleepMinutes, MusicUiAction.SleepAtEpisodeEnd, MusicUiAction.ClearSleepTimer -> SLEEP_TIMER
    is MusicUiAction.SetFavorites, is MusicUiAction.ToggleFavorite -> FAVORITES
    is MusicUiAction.CreatePlaylist, is MusicUiAction.AddAllToPlaylist, is MusicUiAction.RenamePlaylist,
    is MusicUiAction.DeletePlaylist, is MusicUiAction.AddToPlaylist, is MusicUiAction.RemovePlaylistEntry,
    is MusicUiAction.MovePlaylistEntry, MusicUiAction.UndoLibrary -> PLAYLIST
    is MusicUiAction.Download, is MusicUiAction.DownloadCollection -> DOWNLOAD
    is MusicUiAction.ValidateRetainedDownload -> DOWNLOAD_VALIDATE
    is MusicUiAction.PauseDownload -> DOWNLOAD_PAUSE
    is MusicUiAction.ResumeDownload -> DOWNLOAD_RESUME
    is MusicUiAction.CancelDownload -> DOWNLOAD_CANCEL
    is MusicUiAction.RemoveDownload -> DOWNLOAD_REMOVE
    is MusicUiAction.OpenSourcePage -> OPEN_EXTERNAL
    MusicUiAction.ImportAudio -> STORAGE_USAGE
    MusicUiAction.OpenPlayer, MusicUiAction.ClosePlayer, is MusicUiAction.OpenQueue -> PLAYER_UI
    MusicUiAction.ExplorePodcasts, is MusicUiAction.OpenSearch, is MusicUiAction.OpenLibrary, is MusicUiAction.OpenPlaylist,
    is MusicUiAction.OpenMedia, is MusicUiAction.OpenShow, MusicUiAction.NavigateBack, MusicUiAction.ManageStorage -> NAVIGATE
}

internal fun PodcastAction.diagnosticOperation(): DiagnosticOperation = when (this) {
    is PodcastAction.PlayEpisode, is PodcastAction.PlaySavedEpisode, is PodcastAction.StartOver -> PLAY
    is PodcastAction.ResumeEpisode -> RESUME
    is PodcastAction.SetPlayed -> HISTORY
    is PodcastAction.SetSaved -> FAVORITES
    is PodcastAction.PlayNext, is PodcastAction.AddToEnd, PodcastAction.OpenQueue -> QUEUE
    is PodcastAction.SetFollowed -> FOLLOW
    PodcastAction.RefreshShows, is PodcastAction.RefreshShow -> SHOW
    PodcastAction.OpenAddFeed, PodcastAction.DismissAddFeed, is PodcastAction.FeedUrlChanged, is PodcastAction.SubmitFeed -> FEED
    is PodcastAction.Download -> DOWNLOAD
    is PodcastAction.ValidateRetainedDownload -> DOWNLOAD_VALIDATE
    is PodcastAction.PauseDownload -> DOWNLOAD_PAUSE
    is PodcastAction.ResumeDownload -> DOWNLOAD_RESUME
    is PodcastAction.CancelDownload -> DOWNLOAD_CANCEL
    is PodcastAction.RemoveDownload -> DOWNLOAD_REMOVE
    is PodcastAction.OpenPublisher -> OPEN_EXTERNAL
    PodcastAction.FindPodcasts, is PodcastAction.OpenShow, is PodcastAction.OpenEpisode, PodcastAction.NavigateBack,
    is PodcastAction.SetShowListOptions -> NAVIGATE
}
