# Source-selection repair and Android runtime verification — 2026-11-11

Local development work in `/Users/abdelrahman/Projects/veyra`. This continues the
full redesign and the owner's playback/download/keyboard/player requests. Existing
changes were preserved. No commit, push, distribution upload, uninstall or app-data
reset was performed. Dates in this report are local Africa/Cairo dates.

## Reproduced source-selection defect

The preserved Android acquisition receipt for the owner-reported recording was
`UNPLAYABLE / FIRST_RESULT_UNAVAILABLE`, with an unknown first entry and
`UNSUPPORTED` issue. The player surfaced the generic saved-data recovery message;
this observation does not establish damaged library data.

A separate anonymous request for the same unchanged complete title returned HTTP
200 and placed `didYouMeanRenderer` before the first `videoRenderer`. Only bounded
renderer names/paths and response size/status were retained, not the response body,
cookies, signed media URLs or audio. That separate payload is not represented as a
capture of the Android client's exact earlier response.

`PublicYouTubeBrowseService` previously counted this spelling-suggestion control as
an unknown content result. It now ignores that recognized control and its nested
navigation/content data. It does not follow the suggested query, change the
submitted title, skip actual unsupported media, or substitute a later recording.
The same fix and regression test are present in both `third_party/kmpYT` and the
authorized `.work/kmpYT` checkout; their file hashes match.

The authored regression fails before the fix and passes afterward. It verifies the
unchanged query, one request, exact first content video ID, ordinal zero, and no
selection from nested suggestion data. Existing first-result tests continue to
cover actual unavailable, unsupported and collection results.

The earlier iOS native access-error classification fix remains included. The
original phone error was NSURLErrorDomain -1102 with underlying OSStatus -12660;
this is not independent proof of HTTP 403. Android's source-selection success
below does not establish that the original phone's native error is resolved.

## Android runtime and test results

The preserved task-owned `VeyraRedesign` API 34 arm64 emulator ran against a private
ADB server on `127.0.0.1:5049`. The shared ADB server and unrelated devices were not
used for runtime actions. Installations used `-r` and retained data. Evidence below
is relative to `.work/evidence/redesign/`, with runtime evidence under
`android-runtime/`.

| Check | Actual result and evidence |
| --- | --- |
| Browsing and first-result contracts | **32 tests, zero failures/errors/skips**: `source-search-control-final-contracts.log`, `android-runtime/final-source-test-summary.json`, preserved XML. The initial single-test failure is retained under `spelling-control-before-*`. |
| Android startup/media-session/download ownership | **6 tests passed** in `android-runtime/clean-final-instrumentation.log`. Final test APK excludes the opt-in diagnostic probe. |
| Exact recording, live playback | Native Android playback reached PLAYING and 1,715 ms position after 21,741 ms including that advancement: `typed-owner-and-exact-play-instrumentation.log`, `safePlaybackProbe`. This individual live test passed within an earlier run whose three outdated download fixtures still failed; that whole earlier run is not counted as a passing suite. There is no comparable old-build speed benchmark or human listening confirmation. |
| Recording identity | A separate read-only acquisition check reports `BOUND`, `VIDEO`, `sameSelectedSourceId=true` in `current-owner-final-instrumentation.log`. Its individual diagnostic passed; the obsolete pause expectation still failed elsewhere in that intermediate run. |
| Fresh download | Actual app UI progressed 0 → 4.1 → 11.6 → 18.3 MiB → Downloaded / 22.5 MiB. The app-owned M4A is **23,626,255 bytes**. Evidence: `exact-recording-download-*.xml/png`, `exact-recording-downloaded-assets.json`. No audio was copied to evidence. |
| Offline cold start | App offline mode persisted through a COLD launch, the completed download remained available, and its actual Play action reached Playing with elapsed position **1 → 11 seconds**. Evidence: `offline-cold-start.xml`, `offline-download-retained-after-cold-start.xml`, `offline-native-player-early/later.xml`, `offline-native-player-results.json`. This is app-only offline policy, not a device radio/airplane-mode experiment. |
| Search keyboard | Actual IME state changed true → false for outside tap, pointer Search, and native keyboard Search. Receipts: `keyboard-button-outside-results.json`, `keyboard-ime-search-results.json`. Native keyboard letter taps also preserve `test`; rapid ADB text injection is not treated as native-typing evidence. |
| Player touch interactions | Mini-player tap, close tap, two actual drag-close/drag-open cycles, and a separate Home continue-listening-card tap passed. Evidence: `sheet-touch-results.json`, `home-card-touch-result.json`, associated UI dumps. These first cycles used paused playback. |
| Native local seek and gestures during playback | Touch seek reached **11:11**, and an actual drag-close/drag-open cycle kept Playing through **11:17**. `offline-native-seek.xml`, `offline-playing-drag-closed/opened.xml`. Playback was paused afterward and the original online setting was restored; `offline-check-restoration.json` and `online-setting-restored.xml` confirm the restoration. |
| Bottom geometry | Actual Home/mini-player and full-player screenshots were inspected. Navigation and transport surfaces continue through the system bottom area; the compact player meets navigation without the reported black gap. `home-with-mini-player.png`, `current-full-player-open.png`. This is Android emulator rendering, not final iPhone geometry acceptance. |

Installed app SHA256:
`ee6513f87ae6f85c85d1946caa2243256e4c2a9a71d5f542972b150648f97e89`.
Final clean test APK SHA256:
`938f6403611b08ca026f08fe249ed5fb037c7063821ff7badb495f9e01029870`.
Both installed hashes match their current artifacts in
`android-runtime/installed-final-hashes.json`. The original online setting was
restored and playback paused. The owned emulator and private ADB server were then
stopped with data preserved; their shutdown receipts and `runtime-final-summary.json`
are retained. The shared ADB server was left running.

### Download test-fixture corrections

The first six-test run passed startup/media-session tests but failed all three
owner-drain fixtures before their intended behavior. They used an OS cache alias
and the obsolete legacy enqueue route over current schema-3 Room. The fixture now
canonicalizes only the trusted OS cache base, captures and commits its authored
selection through the current acquisition owner, injects the same-owner physical
runtime dependencies, and reads actual physical jobs. Its fake transfer retains
and closes its authored resource during cancellation. It still uses real Room,
owned files and the actual Android gate/shared drain, with fake source, native ACK
and validation seams; these are not live scheduling/audio tests.

A deliberate failed native close now asserts the current public pause failure
`BACKGROUND_RESTRICTED / download_owner_drain_unconfirmed`. The original checks
for retained lease/attempt owners, failed drain, blocked reentry, once-only close
retry and later dependency release remain. Three test methods and all 49 previous
assertion calls were retained; one additional assertion was added. Intermediate
failures and the final six-test pass remain in evidence.

## Current iPhone artifact and remaining acceptance

**Installation update:** the same signed artifact was installed in place on the
physical iPhone later on 2026-11-11. Startup/inspection passed, but playback attempts
were interrupted by accessibility/foreground changes. No iPhone playback/download
acceptance is claimed. See `iphone-source-install-20261111.md`. The uninstalled
status below describes the earlier observation, before connectivity returned.

`iphone-search-control-20261111-build.log` records a complete arm64 Swift-host build,
including actual native link steps. The linked debug library contains the new
`didYouMeanRenderer` marker in Kotlin/Native's UTF-16 string data. It is development
signed and verified using the existing provisioned identity:
`iphone-search-control-20261111-signature.json`, `-source-hashes.json`,
`-linkage.json`, and `-sign.log`.

Signed debug-library SHA256:
`8f40e67d4436e2b098cde5af3ef3677be53f029740dca7a4afbc22a979abfadb`.
App: `.work/xcode/DerivedData-device-arm64/Build/Products/Debug-iphoneos/Veyra.app`.
This artifact supersedes `iphone-framework-refresh` at that output path. The
older artifact's receipts remain historical evidence, not hashes of the new app.

**Not installed on the physical iPhone.** The latest targeted CoreDevice receipt
still has `tunnelState=unavailable` and DDI services unavailable in
`iphone-search-control-20261111-device-state.json`. No install was attempted in
that state. Physical iPhone playback/download of the exact failing recording,
keyboard/bottom geometry, player gestures, native theme/RTL controls and VoiceOver
remain outstanding. Existing Simulator/desktop/older-phone evidence retains only
its original scope. Human audible output and current Windows/Linux execution are
not established by this run.

The original dependency pins and strict clean-source/bootstrap gates remain
unchanged. Builds used the already-authorized explicit local diagnostic route
while kmpYT repairs remain dirty. The full redesign goal remains incomplete.
