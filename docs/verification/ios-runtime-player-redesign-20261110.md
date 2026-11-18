# iPhone runtime repairs and expandable player — 2026-11-10

Latest continuation is documented in `redesign-native-ux-20261110.md`. The current
signed artifact is `iphone-framework-refresh`; installation remains pending. A
later Simulator linkage defect was corrected, so source snapshots alone must not
be taken as proof of the native host's code. Current Simulator captures use the
`redesign-refreshed-simulator-*` prefix. Older test outcomes below retain their
original scope and do not establish acceptance of the final build.

## Scope and provenance

Local development changes in `/Users/abdelrahman/Projects/veyra`, based on
`ef31659eb2600ed5f2f8c4b0dc2446555a798018`, with the earlier full-app redesign and
Android/kmpYT repairs preserved. No commit, push, PR, store upload or release.
The existing app was updated in place; its library and completed download were
preserved. Only the owner's connected iPhone was used for the physical checks.

The owner explicitly requested no agents after an OpenCode attempt failed with
`Invalid credential`. All subsequent implementation and verification was done
by the lead. No replacement agents were launched.

## Crash causes and repairs

1. **Startup abort**: Compose threw `IllegalStateException` because the iPhone
   minimum frame-duration plist flag was absent. Added
   `CADisableMinimumFrameDurationOnPhone=true` in the host Info.plist. Verified
   the processed device plist as well as the source.
2. **Library startup error**: Foundation's application-support path retained
   an OS-owned `/var` alias, while shared owned-file validation required a
   canonical path. `iosApplicationDirectory` now canonicalizes only the trusted
   OS base with Okio, then separately rejects symlinks in Veyra-owned children.
   Existing file-protection/cache policies and shared media safety checks stay
   enforced. Debug diagnostics expose only stages, exception types and bounded
   static failure details, never media URLs or listening data.
3. **Playback and download crashes**: Veyra's actual iPhone crash reports show
   `EXC_BAD_ACCESS / SIGBUS` at the stack guard, with deeply recursive native
   regex frames called by `YouTubeResponseExtractor.needsTransforms`. The
   repeated-alternation regex scanning `serverAbrStreamingUrl` overflowed the
   Kotlin/Native stack for long signed URLs. Both local kmpYT copies now use an
   iterative scan, decode the actual JSON string and inspect its real `n`
   parameter, retaining the original four-endpoint bound.

The original strict bootstrap and dependency pins are retained. The validated
Debug build uses the existing explicit local diagnostic path because kmpYT
still contains uncommitted repairs. It is not a distributable release candidate.

## Regression evidence

Evidence is retained under `.work/evidence/redesign/` unless noted otherwise.

- Two authored 32,768-character URL regressions fail on the previous regex with
  `StackOverflowError` and pass with the iterative scanner. See
  `ios-regex-regression-before.log`, the preserved before XML, and
  `ios-regex-regression-after.log` (12 focused tests passed).
- Full kmpYT JVM suite: **459 discovered, 456 passed, 3 skipped, 0 failures**,
  `ios-regex-library-full-runtime.log`; actual XML in
  `.work/kmpYT/kmpyt/build/test-results/jvmTest`. Existing Node and Chromium were
  used read-only, with task-owned profiles/temp files. Skips are optional
  FFmpeg/live checks. The earlier missing-runtime attempt is not the passing run.
- On the physical iPhone, the crash-repair build passed both actual playback
  and download tests: **2 tests, 0 failures**,
  `iphone-probe-playback-download-2-test.log`. The previously failed download
  reached Downloaded / 2.9 MiB, and the player reached Playing with advancing
  elapsed time. The existing recording was used; its media bytes are not test
  fixtures and are not committed.
- iOS native runtime checks executed on a task-owned iOS 27.0 simulator:
  **3 passed, 0 failed** in `ios-runtime-native-tests.log`: the actual OS-alias
  and owned-symlink directory test, plus both native audio-error classification
  tests. `ios-runtime-unit-link.log` records the binary build. Simulator data
  lives in `.work/ios-unit-simulators`; it was shut down after testing.
- Android unit tests before the final small UI refinements: **161 passed**,
  `player-sheet-compile-2.log` and actual reports. See final platform checks
  below for the final source state.

## Player behavior

`ExpandablePlayerScaffold` uses one Material3 sheet with compact and expanded
anchors. Home's Continue listening card and the mini-player dispatch the same
OpenPlayer action. Vertical dragging changes sheet pixels locally and publishes
only settled outcomes to the existing navigation owner. Closing returns to the
retained underlying screen. The coordinator still owns queue, playback and Back
semantics; no second player or playback session is created.

The full player has a drag handle, collapse button, centered title, queue action,
and pinned transport with shuffle/repeat and a visible repeat-one badge. Optional
audio provenance remains available through Audio details. Desktop rail layouts
reserve zero bottom space without a player, and wide expanded layouts keep their
queue inside the same sheet. Queue visibility disables sheet dismissal until the
queue has been closed.

Focused real Compose UI checks: **20 tests, 0 failures** in
`player-sheet-regressions-pass.log`, with copied actual XML reports prefixed
`player-sheet-TEST-`. They cover compact metadata, repeat controls, source
provenance disclosure, seek/queue behavior, retained underlying state and
accessibility, short-drag cancellation, English and Arabic vertical gestures,
and an empty rail with no reserved gap. Across 120 drag moves per language,
measured app-root recompositions were **0**, underlying body recompositions **2**,
and settled callbacks **2**. These are deterministic Desktop Debug test counts,
not iPhone frame timing or release performance measurements.

## Earlier gesture-tested development build

`iphone-player-sheet-build.log` records the complete Swift host build. Signing
was independently verified deep/strict, with the existing development identity
and provisioned physical device. Installation was in place:
`iphone-player-sheet-install.json`. The signature receipt's `installed=false`
field describes its pre-install signing stage; the separate successful install
receipt is authoritative for installation. The running app reached
`library_ready` in `iphone-player-sheet-console.log`.

- `Veyra` SHA256: `2d00b3d789a7dd3941da3dabbc00acda39cad1d676fb17e12126a30f7dbb3fdd`.
- `Veyra.debug.dylib` SHA256: `10514bdff108b63d4f3f8531f74245e24f4863597b34b67ee5535c55b2249ed8`.
- `__preview.dylib` SHA256: `afe812ed2b9383dbbda16521e186bb038928fba80c1209d8632a9e882015a5c3`.

Full signing details, Mach-O UUIDs and processed-plist hash for this earlier
build are preserved in `iphone-player-sheet-signature.json`; its source hashes
are in `iphone-player-sheet-source-hashes.json`. The generic `iphone-signature.json`
was subsequently refreshed by later signing and must not be treated as this
older tested build's receipt.

## Physical player acceptance

`iphone-probe-player-sheet-gestures-3-test.log`: **1 passed, 0 failed** on the
connected physical iPhone, about 41 seconds. Home's current-track card opens the
full player; close returns to Home; closing queue leaves the full player open;
a short drag cancels; three actual drag-up/drag-down cycles open and close the
sheet. Playback position advanced from 5 to 37 seconds during these gestures.
App-only screenshots are retained in `player-sheet-screenshots/` with its
attachment manifest. These show the earlier floating bottom navigation, before
the owner's subsequent edge-to-edge refinement request.

Xcode first failed before test execution with a runner trust error and then an
interrupted install connection. Direct runner launch and explicit install
succeeded. The third run above is the passing evidence. Precise failure text
logs are retained; broad failed-run device diagnostics were discarded. New
probe runs use `-collect-test-diagnostics never` and keep no automatic screen
recordings. Only explicit captures of the app in the foreground are retained.

A later download inspection on the same player build passed and showed the
current row already Downloaded; the menu offered Remove download. This later
check did **not** initiate a fresh transfer, so it must not be counted as another
new download. The earlier two-test playback/download run is the actual transfer
evidence.

## Owner refinements: keyboard and bottom layout

- Added a root pointer observer that dismisses the software keyboard for taps
  outside the currently focused control. It does not consume child events,
  interfere with switching/repositioning in text fields, or dismiss during a
  drag/scroll. Focus bounds and pointer progress do not enter Compose state.
- Search's keyboard Search action and submit action explicitly clear focus and
  hide the keyboard while retaining the existing search action.
- Removed the root's bottom safe-area exclusion. The compact player is docked
  directly to a full-width navigation surface, with no intervening margins.
  Navigation and full-player transport paint through the bottom safe area and
  keep controls above the home indicator. Top and horizontal safe-area and IME
  handling remain applied.
- The `iphone-keyboard-dock` build succeeded, was signed/verified and installed
  in place. Its distinct signature is preserved in
  `iphone-keyboard-dock-signature.json`. It reached `library_ready` in
  `iphone-keyboard-dock-console-2.log`. Final physical keyboard/geometry checks
  are still outstanding: the first attempt was blocked during runner install,
  and the second was interrupted by foreground-app changes. Neither is a pass.

## New reported unsupported-source error

The owner's current selection was observed displaying Unsupported source in
`iphone-probe-source-unsupported-inspect-test.log`. The Debug console then
captured **NSURLErrorDomain -1102**, with underlying **NSOSStatusErrorDomain
-12660**, and `UNSUPPORTED_SOURCE / native_audio_-1102`. This establishes the
native access/read error; it does not independently establish an HTTP 403 or a
specific server-side restriction.

Previously the iOS engine mapped every remote AVPlayer failure to unsupported
source. That misleading classification also skipped shared bounded link renewal.
The new `classifyIosAudioFailure` preserves access, network, HTTP status and
codec errors separately; local failures remain corrupt-media errors. The
existing controller can now renew the same recording/representation within its
existing two-renewal bound. No new first-result selection, unbounded retries,
credential workaround, or alternate-recording fallback was introduced. Debug
logs contain bounded error codes and source-kind booleans, never signed URLs or
NSError descriptions.

An additional authored regression exposed a separate resolver defect: one
unavailable optional public-client response poisoned actual selectable audio
from another response for the same video. It now retains that declared audio;
account/access/rate-limit restrictions still block. Red/green evidence:
`source-failure-provenance-before.log` and preserved before XML fail on the old
resolver; the three new tests pass after the fix. All **57 YouTube source tests
passed** in `source-failure-provenance-after.log`. These are authored tests,
not proof that the owner's exact live selection now plays.

The native classifier's two tests and the OS directory test actually executed
and passed in the project-owned simulator. iPhone AVPlayer/source renewal and
the exact selected video's eventual outcome remain pending physical validation.

## Latest signed build and connectivity blocker

`iphone-source-renewal-build.log`: complete iPhone Swift/Kotlin host build
succeeded. `iphone-source-renewal-sign.log`: existing development signature,
profile, entitlements, architecture and all nested code verified. Its receipt is
`iphone-source-renewal-signature.json`; source hashes are in
`iphone-source-renewal-source-hashes.json`. Signing-stage receipts intentionally
say `installed=false`; installation must be established independently.

Installation of this newest build failed with **CoreDeviceError 4016**, no
trusted device connectivity or power assertion available. A subsequent read-only
lock-state request failed with the same device-connectivity condition. Thus the
latest native error mapping/resolver correction is **built and signed, not
installed or physically accepted**. The owner was asked to reconnect/unlock the
phone and keep Veyra in the foreground. Preserve this distinction on resumption.

Final source UI/Android regression check passed:
`final-source-ui-android-checks.log`, **21 Desktop UI tests and 161 Android unit
tests, 0 failures/errors/skips**, verified from actual XML and copied under
`final-desktop-TEST-` and `final-android-TEST-` evidence prefixes. This final run
includes the shared resolver correction and the final keyboard/dock source.

## Remaining limits

Required next device actions: install the newest build, retry the exact current
selection, verify tap-outside and IME Search keyboard dismissal, confirm dock and
full-player backgrounds reach the physical bottom, repeat player gestures, and
capture fresh app-only screenshots. Optional app-offline restart playback also
remains unexecuted on this final source state. Do not substitute earlier captures
or earlier playing tracks for these checks.

No human listening confirmation, release/profile frame timing, new Android
physical-device gesture run, Windows/Linux execution, or store distribution is
claimed. The physical Android is disconnected. The broader full-app redesign
and platform release gates remain separate from these local repairs.

Latest signed source-fix artifact (installation blocked):

- `Veyra` SHA256: `aafa1317c8a52abbce3839365c5a934c76288b78bf344ceaeb85e7b960d92590`.
- `Veyra.debug.dylib` SHA256: `4f1df66e6a95d69ab3406c05fe18cda5220907768c7624a6c565488613934385`.
- `__preview.dylib` SHA256: `a7f215259da59f955425c16f0fc31b7b9225a515471c1bd8257f659d8554d5b8`.

## Resumption: current artifact review and isolated Simulator startup

The signed `iphone-source-renewal` artifact and all saved source hashes still
match their receipts. The physical iPhone again reports `unavailable` in
`devicectl list devices`; no new installation or selected-source playback
result is claimed.

The same source successfully built a complete iOS Simulator host in
`unsupported-source-simulator-build.log`, installed into the project-owned
`601CD290-99D7-435E-AA66-B4450B70B79A` Simulator and reached `library_ready`
in `unsupported-source-simulator-console.log`. The fresh
`unsupported-source-simulator-home.png` was visually inspected: the Home
navigation surface reaches the bottom edge. This empty Simulator has no
mini-player selection; the screenshot does not verify compact/full-player
geometry, keyboard behavior, physical-device rendering, or playback. The
Simulator's public discovery request displayed an item-not-found notice.

Xcode could not discover the separate Simulator set for the existing UI test
runner. No UI suite was executed, no passing live-source result is inferred,
and the temporary default-set alias was removed. The task-owned Simulator
was shut down after this check. There were no new production source edits.
Reviewed existing XML still reports 57 YouTube source tests, 21 Desktop UI
tests and 161 Android unit tests with no failures, errors, or skips. The
review receipt is `unsupported-source-resumption-review.json`.
