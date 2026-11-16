# Native player seek integration — 2026-11-11

The full player now uses nativeCmpkit `AdaptiveSlider`. The user explicitly
instructed Codex to stop using subagents; this implementation was performed
directly in the existing checkout. The previous worker-authentication blocker no
longer determines the workflow.

## Behavior and implementation

- iOS uses the existing consumer UIKit `UISlider` actual. The player supplies its
  localized label, elapsed/duration value and English/Arabic speech language.
- Android/Desktop use a consumer Material actual with a real 48 dp thumb/input
  node. The upstream Material implementation measured 44 dp in the prior audit;
  outer minimum height alone did not enlarge the actual input node. The consumer
  actual forwards the pinned API, colors, semantics and callbacks and also
  applies to Settings. The pinned nativeCmpkit checkout is unchanged.
- Transport remains LTR inside Arabic UI. Drag previews remain local to the seek
  composable, ignore incoming native position samples, and commit once on release.
- Entry and generation key the actual control as well as its draft. A current
  owner guard rejects callbacks arriving after a native control was disposed.
  An old drag cannot seek a replacement entry or a new generation of the same entry.

## Executed verification

All **30 Desktop tests passed**: 18 `MusicSurfacesTest` cases and 12
`SettingsScreenTest` cases. Existing assertions for the real unclipped 48 dp
progress/input node, English/Arabic, semantics progress, keyboard and pointer
taps at both vertical edges remain intact. New actual-touch regressions check
preview versus release, incoming native samples during dragging, and entry or
generation replacement during an active drag.

`:composeApp:compileKotlinIosArm64`, `:composeApp:assembleDebug`, and
`:composeApp:assembleDebugAndroidTest` passed in one scoped offline Gradle run.
The compile establishes iOS source compatibility; no iPhone framework was linked
or installed by this change.

The Android app was installed in place on the preserved API 34 emulator, using
its existing data and private ADB server. Installed APK SHA256:
`8306a465f7446e296bafa95a724e12f423a096bd56da171f0d85ee13db187983`.
This initial UI-only artifact is superseded by the media transfer repair build;
see `media-performance-20261111.md` for the latest APK and measured timings.
The existing downloaded recording reached Playing. A real horizontal touch drag
changed the displayed position from around 11:18 to 17:09 of 24:19 while Playing;
playback was paused afterward. The expanded player screenshot was visually
reviewed. This is emulator native playback and input evidence, not audible output
or physical iPhone acceptance.

## Evidence and limits

Evidence directory:
`.work/evidence/redesign/native-seek-implementation-20261111/`.
It contains before/after source hashes, `desktop-junit/`, `desktop-results.json`,
installation receipt, runtime process records, UI XML, screenshots and the native
seek observation. Build logs are
`.work/evidence/redesign/native-seek-desktop-20261111.log` and
`.work/evidence/redesign/native-seek-platform-builds-20261111.log`.

The private ADB listener initially rejected a hostname-form listen address. Its
terminal failure was inspected, then the listener syntax was corrected while
retaining the already running emulator and its data. No shared ADB server or
unrelated device was changed.

Physical UIKit gestures and VoiceOver/TalkBack still need platform checks. No
startup-speed or download-speed improvement is claimed by the UI change. The
user requested measured emulator investigation of those delays next. The full
redesign remains incomplete.
