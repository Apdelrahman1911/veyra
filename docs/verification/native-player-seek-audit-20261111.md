# Native player control audit — 2026-11-11

This continuation compared the current UI with the actual reference image and
inspected nativeCmpkit integration. It performed a focused executable migration
experiment and rechecked the current production player's input behavior. **No
production implementation changed.** No physical phone, emulator or simulator was
controlled. The full redesign remains incomplete.

## Concrete native seek migration constraint

The full player's `PlayerSeek` still uses Material3 `Slider`; Settings uses
nativeCmpkit `AdaptiveSlider` with the consumer's UIKit `UISlider` actual. Common
icon actions, progress indicators, native settings switches and confirmation
dialogs already use the pinned toolkit surface. The nativeCmpkit checkout is clean
at the unchanged pin `c2eeec65a0bfd8d03f0b3a7e27c3f97b4542f0f6`.

A direct replacement cannot preserve the current player contract by reusing only
its outer `heightIn(min = 48.dp)`. An isolated Desktop Compose rendering at density
1 measured the actual merged progress/input node as follows:

| Rendered control | Actual input/progress height | 48 dp requirement |
| --- | --- | --- |
| Existing player slider shape, with its real 48 dp thumb | 48 dp | Met in this fixture |
| Pinned `AdaptiveSlider`, same outer minimum height | 44 dp | Not met |

The authored experiment's assertion therefore **fails**, as expected for the
proposed direct substitution. This is red migration evidence; it is not a failure
of the unchanged production player or a native-iOS measurement. Preserve the
pinned common API and the clean upstream source. The implementation should supply
a small consumer Material actual retaining the 48 dp thumb, then use AdaptiveSlider
in PlayerSeek with localized elapsed/duration native accessibility metadata.

The existing production regression was then executed without the opt-in probe.
**One test passed, zero failures/errors.** It covers English and Arabic, actual
48 dp unclipped target bounds, progress semantics, keyboard Right and Space, mouse
and touch taps at both vertical edges, and exactly one seek carrying the intended
entry and generation per gesture. This is a focused input regression, not a new
full-suite or native-iOS acceptance claim.

The migration also needs explicit draft/finish coverage: native position samples
must not steal the active thumb; drag preview must not emit native seeks; release
must commit once; recording/generation changes must prevent stale callbacks from
seeking the new recording. Transport geometry remains LTR within Arabic UI.
No frame-rate, startup-speed or recomposition improvement is claimed.

## Reference and full-scope audit

The reference was read directly from
`des/167bc28d2126c0caf23b3956f96716c3.webp`. The current actual Simulator dark Home
capture and Android full-player capture were also inspected. Home's violet top
glow, large headline, pill search, rounded cards and accent navigation follow the
reference's visual language. The application keeps its real music/podcast/library
features rather than inventing profile/follower content for the mockup.

| Requested area | Evidence currently available | Remaining acceptance |
| --- | --- | --- |
| Full shared-app redesign | Updated Home/Search, Library/Downloads, collection/track/playlist details, Podcasts, Settings and compact/full player source. Preserved 235-test UI/localization report in `redesign-native-ux-20261110.md`; real Home and Android player screenshots. | Do not extend those fixtures/captures to final native behavior on every screen/platform. Current Windows/Linux execution and physical accessibility checks are missing. |
| Simpler UX | Direct Home search and continuation; four primary destinations; secondary Settings sections; compact player opens/drag-expands; advanced audio disclosure. | Final iPhone journeys still need uninterrupted input testing. |
| nativeCmpkit components | Clean pinned source consumer; adaptive icon actions/progress/confirmation dialog; UIKit settings switch and text-size slider. | Player seek migration above, then native keyboard/VoiceOver and UIKit theme/direction checks. |
| Playback and downloads preserved | Source-selection regression, actual selected-recording Android streaming, fresh 23,626,255-byte download, cold-start app-offline playback and native seek. | Exact-recording iPhone playback/download/offline acceptance. Human audible output. No improved cold-start latency claim. |
| Existing data | Current iPhone build installed in place, library and selected recording observed; no data reset/uninstall. | Do not infer all persistence/upgrade paths from inspection alone. |
| English/Arabic | Existing localization/large-text UI tests, English/Arabic Simulator Home captures, focused current-player input test in both languages. | Native slider/switch direction, spoken values, VoiceOver/TalkBack and representative device large-text journeys. |
| Keyboard and bottom geometry | Actual Android outside/Search/IME dismissal and continuous compact/full-player/navigation bottom surfaces. | Equivalent final iPhone acceptance remains outstanding. |

Artwork placeholders in the reviewed Android player are not replaced by invented
artwork. Production rows use the owned metadata artwork cache; remote requests
are deliberately disabled in the row-level image loader. The capture alone does
not establish a cache defect or authorize an independent fetch route.

## Worker failure and authorization boundary

The requested OpenCode worker was started with the supported model
`opencode-go/deepseek-v4.1-flash`, variant `max`, shared existing checkout and strict
file ownership. Its authoritative terminal result is **Invalid credential**.
The task timeline contains no implementation/tool action, and before/after hashes
confirm no changes in its owned production/test files. No provider/model fallback,
credential modification or silent lead takeover was attempted.

The prepared task is
`.work/workers/native-player-seek-20261111/task.md`. The user must restore the
OpenCode connection or authorize Codex to perform this scoped implementation
directly. Independent focused verification above was completed before requesting
that choice. The failed worker is terminal, and the one-heavy-build limit was
respected.

## Evidence

Paths below are relative to `.work/evidence/redesign/`:

- `native-slider-audit-20261111/source-and-worker-state.json`: source hashes, clean pin and no worker edits.
- `native-slider-audit-20261111/kotlin/.../NativeCmpkitSliderProbeTest.kt` and `probe.init.gradle`: opt-in authored experiment only, not production source.
- `native-slider-audit-20261111-measurement.log`: first harness setup failure in the included kmpYT build. No test ran in that attempt.
- `native-slider-audit-20261111-measurement-2.log`, `native-slider-audit-20261111/pinned-adaptive-probe.xml` and `measurement-summary.json`: actual 48/44 dp measurement and the expected failing substitution assertion.
- `native-slider-audit-20261111-current-player.log`, `native-slider-audit-20261111/current-player-seek-regression.xml` and `current-player-verification.json`: successful focused current-production regression.

The opt-in probe source is not part of normal Gradle source sets. Both measured
runs used the existing explicitly authorized local diagnostic dependency route;
normal clean-source/bootstrap gates and pins remain unchanged.
