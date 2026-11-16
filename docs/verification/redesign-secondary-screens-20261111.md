# Secondary-screen redesign verification — 2026-11-11

Six focused Desktop tests passed: **6 tests, 0 failures, 0 errors, 0 skipped**.
The run checked Library navigation, podcast details, Settings and track details
using authored presentation data. No production source changed during this
verification, and no new installable artifact was built.

## Coverage and result

| Surface | Executed checks | Result |
| --- | --- | --- |
| Library overview | 320 × 760 ComposeScene, density 1; English/light at 100% text and Arabic/dark at 200% text; Favorites, Playlists, Downloads and Local Files | Every card scrolls fully into view with unclipped bounds, a target at least 48 dp, and exactly its expected `OpenLibrary(section)` action. No playback action emitted. |
| Podcast episode details | Existing authored narrow and wide viewport tests, English at 100% and Arabic at 200% text | Both tests passed. These render 320 × 760 and 960 × 720 Desktop scenes, not physical phone captures. |
| Settings | Existing narrow Arabic 200% text check | Controls remain inside a scrollable frame; passed. |
| Track details | Existing narrow Arabic large-text check | Wrapped actions and fixed Back remain reachable; passed. |

The preserved JUnit suites are:

- `dev.veyra.diagnostics.LibraryOverviewViewportProbeTest`: 2 tests.
- `dev.veyra.ui.podcasts.PodcastViewportCaptureTest`: 2 tests.
- `dev.veyra.ui.settings.SettingsScreenTest.arabicTwoHundredPercentControlsStayInsideANarrowScrollableFrame`: 1 test.
- `dev.veyra.ui.music.TrackDetailFlowsTest.narrowLargeArabicDetailsKeepWrappedActionsAndFixedBackReachable`: 1 test.

Current rendered Arabic Library, podcast action, and track action captures were
visually reviewed. The Library's final Local Files capture shows the complete
card after scrolling. Content extending below the initial viewport is expected
for this scrollable screen and is not evidence of an unreachable card. These
checks establish reachability and wrapping for their fixtures; they do not
establish every possible content length, locale/theme combination, or native
control behavior.

## Evidence and reproducibility

All paths are relative to the repository root. Preserved evidence directory:
`.work/evidence/redesign/library-overview-verification-20261111/`.

- `verification-summary.json`: actual JUnit totals and case names, preserved XML
  hashes, PNG dimensions/hashes, diagnostic input hashes and source verification.
- `junit/`: the four XML files copied from the completed Gradle run before another
  run could overwrite them.
- `current-source-inputs.json`: the ten original source input hashes. All ten
  still matched at evidence preservation.
- `library-*-geometry.txt` and `library-*.png`: actual Library bounds and captures.
- `rendered/podcasts/` and `rendered/music/`: copied current podcast and track
  captures from this successful run.
- Passing log:
  `.work/evidence/redesign/redesign-secondary-screen-verification-20261111-2.log`;
  `BUILD SUCCESSFUL in 40s`.

The new Library diagnostic source is opt-in through
`probe.init.gradle` in that evidence directory. Normal source sets do not include
it. The init script guards the included upstream build, which has no
`:composeApp`. The run used the scoped local-upstream diagnostic helper, the
existing offline dependencies and one heavy Gradle build. It did not change the
clean-source/bootstrap gates. An initial harness compilation error used
nonexistent `DpRect.width/height` properties; it was corrected to edge
subtraction before the successful test run. No assertions were removed.

## Remaining acceptance

These results do not establish physical iPhone streaming, a fresh download,
offline restart, UIKit native controls, VoiceOver/TalkBack, audible output,
Windows/Linux execution, or faster first playback. The source-selection repairs
and the currently installed iPhone artifact retain the separate status in
`source-selection-runtime-20261111.md` and
`iphone-source-install-20261111.md`.

Native player seek integration remains pending: the scoped OpenCode task failed
before implementation with `Invalid credential`. The 48 dp consumer-control
constraint and prepared change are documented in
`native-player-seek-audit-20261111.md`. No credential change, alternate worker,
provider switch or substantial direct implementation was attempted in this
verification. The full redesign goal remains incomplete.
