# iPhone workflow checklist

Use media you are authorized to play and download. Keep a short track, a long episode, and a known working public RSS feed handy. Use an existing Veyra installation for restart and state-restoration checks, and use a development build with the same bundle identifier to retain its data. See the [download test and installation instructions](iphone-download-finalization.md).

For each case, record **Pass**, **Fail**, or **Skipped**, the time with time zone, and what you saw or heard. Include a screen label and elapsed wait for failures. Record whether audio actually came out of the expected speaker or headset. The app can measure native playback state without being able to hear its own output. Avoid putting titles, personal feed URLs, or account details in your notes.

| Case | Exercise | Expected observation |
| --- | --- | --- |
| 01 | Cold launch; open each tab; background, return, and relaunch. | The library loads, the selected screen restores where supported, and earlier downloads and settings remain visible. |
| 02 | Browse music, open a collection and track, and use Back. Try scrolling to another page. | Loading settles into content, an empty state, or an actionable error. Back returns to the expected screen. |
| 03 | Search for music and podcasts separately and together. Change the query quickly, then check library scope and an empty result. | The newest request owns the screen. Late results from a previous request never replace it. Search scope matches the displayed results. |
| 04 | Play a short online track. Time the first audible sound. Pause, resume, seek, then skip forward and back. Repeat startup for a long episode and seek near its end. | The player reflects the audible track and state. Seeking settles near the chosen point. A failed startup offers a recovery path. |
| 05 | Change the active track repeatedly, then disconnect while a new track is preparing, wait for an error, reconnect and retry. Repeat with Offline only enabled. | The final track choice owns playback. Old loads never start unexpectedly. Policy or network errors are visible and recovery works. |
| 06 | Add tracks to the front and end of the queue. Reorder, remove, undo, clear upcoming entries, and try shuffle/repeat. | Queue order and current item agree with the action. Play from the expected occurrence after editing. |
| 07 | Add and remove a favorite. Create and rename a playlist, add and reorder entries, undo an edit, and check playback history. Relaunch. | Saved changes and eligible undo operations match the library after relaunch. History shows completed user playback activity where applicable. |
| 08 | Follow a podcast, reopen its show, refresh episodes, save one, change the playback rate and skip intervals, then use Resume. Exercise the sleep timer and unfollow. | The preferences apply at the expected scope. Resume returns to the saved episode position. The timer visibly pauses playback when due. |
| 09 | Add a known public RSS feed. Try an invalid feed separately, then retry with the working feed. | The valid show appears and can be followed. The invalid URL yields a recoverable message. |
| 10 | Run the short, long, interrupted, duplicate, cancelled, restarted and offline download cases. | Finalized audio plays offline after relaunch. Interrupted work resumes safely or offers an explicit retry. [Download cases](iphone-download-finalization.md#exercise-the-transfer-and-recovery-paths) |
| 11 | Play audible audio, lock the iPhone, wait 30 seconds, then use play/pause, skip or seek from the lock screen and Control Center. Return to the app. | Playback continues where allowed. Now Playing and the app agree on state. Commands affect the active track. |
| 12 | Connect a Bluetooth or wired headset, use its media buttons, and disconnect it while playing. If practical, test an incoming call or other system interruption. | Controls act on the current track. Audio changes route safely, pauses when appropriate, and resumes according to system policy and the user's last command. |
| 13 | Change app language to Arabic and then back. Change text scale or theme, open Downloads and the player, and relaunch. | The app uses the selected language and layout direction. Controls remain visible and preferences survive restart. |

Mark hardware and external-service cases **Skipped** when you cannot exercise them. If a recovery step requires changing networks, closing the app, or relaunching, write down when you did it so the resulting diagnostics can be interpreted against your observation.
