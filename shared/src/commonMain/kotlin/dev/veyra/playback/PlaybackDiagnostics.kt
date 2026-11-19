package dev.veyra.playback

import dev.veyra.diagnostics.DiagnosticOperation
import dev.veyra.diagnostics.DiagnosticOperation.*

internal fun PlaybackCommand.diagnosticOperation(): DiagnosticOperation = when (this) {
    is PlaybackCommand.Play, is PlaybackCommand.AdmitAcquisition -> PLAY
    is PlaybackCommand.PlayOccurrence, is PlaybackCommand.PlayEntry -> SWITCH
    is PlaybackCommand.ResumeOccurrence, PlaybackCommand.Resume -> RESUME
    PlaybackCommand.PlayPause -> TOGGLE_PLAYBACK
    PlaybackCommand.Pause -> PAUSE
    PlaybackCommand.Next, PlaybackCommand.Previous, PlaybackCommand.SkipBack, PlaybackCommand.SkipForward,
    is PlaybackCommand.SkipBy, is PlaybackCommand.SkipConfigured -> SKIP
    is PlaybackCommand.Seek -> SEEK
    is PlaybackCommand.Enqueue, is PlaybackCommand.Remove, is PlaybackCommand.Move, is PlaybackCommand.Clear,
    is PlaybackCommand.ClearUpcoming, is PlaybackCommand.UndoQueueEdit, is PlaybackCommand.SelectContext -> QUEUE
    is PlaybackCommand.SetShuffle -> SHUFFLE
    is PlaybackCommand.SetRepeat -> REPEAT
    is PlaybackCommand.SetRate -> SPEED
    is PlaybackCommand.SleepDuration, PlaybackCommand.SleepAtEndOfEpisode, is PlaybackCommand.SleepAtRecordingEnd,
    PlaybackCommand.CancelSleepTimer -> SLEEP_TIMER
    is PlaybackCommand.MarkPlayed, is PlaybackCommand.ResetProgress, PlaybackCommand.FlushProgress -> HISTORY
    PlaybackCommand.Stop -> CLOSE
}
