# Three reported iPhone issues — root repairs and physical verification

Date: 2026-11-11. Existing dirty Veyra checkout, base
`ef31659eb2600ed5f2f8c4b0dc2446555a798018`. The owner authorized exactly three
parallel Sol/max repair tasks; all finished and their actual source changes were
reviewed. No additional workers, checkouts, commits, pushes, dependency-pin changes,
app-data reset or external notifications were performed in this continuation.

**Status: root repairs installed and tested; important limits remain.** The final
v9 development-signed Debug app is installed in place as `dev.veyra.app`, preserving
the owner's data. The exact example downloads and plays locally after a cold app
restart; original-track provenance and controlled online native progress are also
verified. Cold online preparation and a separate first-result selection/message
failure remain open. This is not universal playback or whole-project acceptance.

## 1. Original audio rather than an English auto-dub

The supplied example is YouTube video `1WdCxUiIC7I`. The previously captured,
whitelisted metadata identifies `en-US.10` as default and auto-dubbed, while
`ar.4` is labeled “Arabic original” and is not the default. Veyra had followed
the default; that is not proof of the recording's original language.

The library now retains source auto-dub metadata and models original, auto-dubbed,
unknown and conflicting provenance. Automatic compatible selection prefers
declared originals, independent of the application's language. Explicit selectors
and unsupported-original failures retain their contracts; unknown metadata falls
back to the existing policy. Renewal and SABR recovery cannot silently substitute
an audio track with changed provenance.

The lead also removed Veyra's separate default-only initial candidate filter in
`shared/src/commonMain/kotlin/dev/veyra/source/youtube/YouTubeAudioSelection.kt`.
Required selection keys remain exact: existing retained dub identities/files are
not silently rewritten. Seven new app regressions cover playback/download,
non-Arabic originals, fallback, unsupported originals and exact renewal.

Physical inspection confirms `original=true arabic=true`, using the actual
`audioTrackId == "ar.4"` rather than optional `audioLanguage` metadata. The original
track decoded and advanced through the native player. These tests were muted:
track provenance and native progress are verified, not a human hearing Arabic.
Retained explicit/old selections remain distinct from automatic selection.

## 2. Slow preparation before playback or download

The common YouTube player-program compiler now avoids repeated per-token
collection/string/subsequence allocation and redundant large source copies.
Seven new regressions cover lexical safety, cache reuse/coalescing, cancellation,
replacement/close and failure recovery. The change is mirrored in consumed and
upstream library trees.

Offline JVM measurements support this allocation optimization, not a physical
speedup percentage. Cache policy, lifecycle, network isolation and source authority
are unchanged; safe finite Debug-only native/source timing diagnostics were added.

Physical controls found a second issue: the example's progressive stream remained
Preparing and eventually reported AVFoundation `-11800`, underlying OSStatus
`-12190`. The canonical meaning of `-12190` is not established here. Independent
requests to the selected progressive counterpart actually returned HTTP 403.
One exact-known-length range control returned 206 with MP4 bytes while an open
request failed; other runs rejected even full ranges. Neither a universal range
cure nor a User-Agent or URL-normalization fix is claimed.

The source-declared SABR representation of the **same `ar.4` original** reached
native readiness and advancing playback. Automatic iOS playback now prefers it
when the actual existing SABR bridge is configured. Original-language and native
AAC compatibility remain first; required keys remain exact. Download and other
platform ordering are unchanged. This uses existing kmpYT transport/owned-loopback
support, not another recording, credentials, fabricated tokens or access bypass.

Controlled physical timing from `repair-sabr-progress-console.log`:

| Stage | Cold run | Second run |
| --- | --- | --- |
| Source inspection | 12.058 s | 6.504 s |
| Native readiness after asset open | 8.387 s | 3.942 s |
| Inspection start to advancing native playback | 21.780 s | 11.677 s |

The player program changed between these runs, from 3,012,954 to 3,016,486
characters, and was fetched again. Despite historical probe labels saying WARM,
the second run is **not** evidence of a same-version warm-cache hit. Cold online
preparation remains substantial. Controlled native streaming is also distinct
from a fresh online app-UI journey; later exact-example UI plays used its local file.

## 3. Generic download-state failure

The generic message alone did not establish a cause. The worker's bounded,
redacted diagnostics were retained rather than substituting a guessed repair:

- A bounded 32-event process-local tail records typed stages/outcomes, finite
  authored reason codes, guard kind and redacted ownership/claim comparisons.
- Last-committed and cached observations are labeled separately and never become
  fresh Room authority or proof that a native/file owner drained.
- Debug-only iOS observers emit `VEYRA_DOWNLOAD_DIAGNOSTIC` and sanitized
  `VEYRA_APP_FAILURE` entries, including a dropped count for sequence gaps.
- Arbitrary messages/details, exceptions, URLs, titles, stable entity/owner/track
  IDs, paths, validators, hashes and media bytes are not retained in that tail.
- Admission mutexes, fences, cancellation, stale CAS, same-track preparation,
  single settlement, validation and atomic publication remain enforced.

Subsequent direct diagnosis and failing-before/passing-after regressions established
two root repairs:

1. In-place iOS updates can relocate the data container while retained media rows
   still hold its old absolute path. `DownloadFiles.ownedPath` maps a valid historical
   container UUID to the current trusted media root only within the same namespace
   and fixed Veyra suffix, including `/var` versus `/private/var`. Traversal,
   unrelated roots, file URLs and owned symlinks remain rejected. Native validation
   and local playback use the resolved path; historical/current aliases acquire the
   same real file lease. Exact size/hash and decoded validation remain required.
2. Final hashing can overlap a lease heartbeat. An asset completion timestamp made
   before hashing can then be older than the committed heartbeat. The runtime now
   creates completion time inside the existing publication mutation lock and uses
   that same current timestamp for Room publication. Ownership, CAS, leases and
   materialization evidence are not weakened.

Six authored relocation tests and one real-Room heartbeat race initially ran as
seven tests with **four failures**, then passed after these repairs. The historical
Room command/repository/test files remain unchanged. No schema migration, extra
retry loop or success substitution was introduced.

The previously stuck retained MARWAN PABLO download recovered to Downloaded on an
explicit retry. The exact `1WdCxUiIC7I` example completed in the app at about
22.5 MiB, decoded and played from its saved file. Other historical failed rows were
not deleted or silently declared valid; some still need their explicit local-file
validation journey.

On **final v9**, a cold app restart with Veyra offline mode enabled retained this
exact Downloaded file and native playback advanced. Tap-to-Playing was **2.765 s**;
the 22.453 s test passed, paused playback and restored online mode. A separate
4.457 s final-v9 check confirms the formerly failed retained row remains complete.
This is app offline policy/local playback, **not OS-level native network denial**.

## Separate fresh-source failure retained

An additional v8 UI check selected `cYniJJRrzj8` and failed **before** WATCH/source
or native preparation. A private read-only Room snapshot identifies selection
`FIRST_RESULT_UNAVAILABLE`, with an UNSUPPORTED immutable first receipt. It is not
a demonstrated SABR regression. D005 still forbids silently choosing a later result.
The UI's saved-data-recovery message is misleading; this source-selection/message
gap remains open. Do not repeat arbitrary recordings until one succeeds or describe
this check as a fresh online app pass. Private database/WAL captures stay ignored.

## Executed verification

Evidence is under `.work/evidence/three-ios-repairs-20261111/`.

| Check | Actual result and limit |
| --- | --- |
| Final shared download/playback/YouTube regressions | **691 passed**, zero failures/errors/skips, 69 suites; preserved XML independently recounted |
| Focused original/path/publication/transport regressions | **26 passed**, zero failures/errors/skips, four suites; not an additional count on top of the broader run |
| Focused integrated audio/preparation library classes | **121 passed**, zero failures/errors/skips; 155 recorded library inputs unchanged |
| Full library JVM suite | **Not green**: 489 tests, one desktop Chromium document-navigation timeout and three skips after supplying installed runtime paths |
| Desktop runtime isolation | Timeout reproduced in the three-case runtime class, including direct-runtime tests; no timeout/test/runtime patch or exclusion was introduced |
| Library mirror comparison | All 150 recorded Kotlin sources byte-match `.work/kmpYT` |
| Combined source compilation | Android app main, iOS Simulator app main/shared test sources and iPhone arm64 app/shared main compile successfully |
| Full device host | Fresh Kotlin framework link and complete Swift host build succeed |
| Local development signing | Existing identity/profile, certificate expiry/purpose/key pairing, native trust, nested signatures, entitlements, bundle ID and arm64 verified |
| Stable source tree | All **796** final source/build inputs match the prebuild manifest; signed native hashes match; temporary live-probe launch marker absent |
| Physical iPhone installation/runtime | Final v9 installed in place; exact-example cold-restart offline/local playback and retained-download completion checks pass |

The earlier 137-test integration run followed a focused 11-test run with one authored assertion
failure: preparation correctly remains `RESOLVING` before representation selection,
not `RUNNING`. The lead corrected that assertion rather than changing production
transitions. Failed log/XML and successful combined XML are preserved separately.
Native test-source compilation is not execution. No latest Android runtime or full
Simulator-host runtime result is implied. The physical results are the separately
identified device checks, not inferred from compilation or JVM tests.

The first combined compilation lacked an SDK environment; a scoped rerun found
three uncached Simulator-test libraries in offline mode. Supplying the installed
read-only Android SDK and resolving those libraries into project caches succeeded.
No SDK/global configuration was installed or changed.

Signing preflights initially assumed ASCII storage for a Kotlin diagnostic
literal, which is UTF-16LE in the actual binary, and encountered native Security
trust/profile failures under the artificial tool HOME. The final check verified
the actual linked encodings and used the existing native read-only Security
context. Signing retained repository-owned temp/cache locations, the existing
login keychain/profile and original app identity; no trust/keychain configuration
was changed or identity substituted. The failed preflights remain in their logs.

## Current artifact and receipts

- App: `.work/xcode/DerivedData-device-arm64/Build/Products/Debug-iphoneos/Veyra.app`
- Build: `repair-final-iphone-build.log`, with successful complete Kotlin/Swift link
- Signature: `repair-iphone-signature.json` and `repair-final-iphone-sign.log`
- In-place installation: `repair-final-iphone-install.json` and adjacent log
- Main signed debug-library SHA256:
  `6d0b665aeabd3b302bda7ab7522cf54abad82e93db46fadd0a655402a0d8a8d5`
- Main signed debug-library UUID: `B9EC6E27-43F2-378D-B5DF-2ED012220057`
- Source/artifact/device check: `repair-source-inputs.json`,
  `repair-final-source-inputs-prebuild.json`, `repair-final-device-verification.json`
- Current shared XML: `repair-final-shared-results/` and
  `repair-final-shared-summary.json`; focused XML: `repair-sabr-focused-results/`
- Physical controls: `repair-sabr-progress-console.log`; final UI logs under
  `.work/evidence/redesign/iphone-probe-repair-final-offline-cold-v9-20261111-test.log`
  and `iphone-probe-repair-final-stored-v9-20261111-test.log`
- Earlier integration/library failures, XML, signing receipts and unavailable-device
  checks are preserved. Versioned v1–v8 manifests/signatures remain historical.

This is a local diagnostic Debug app, not an uploaded/distributed release. The
signature receipt's `installed:false` and `physicalMediaVerified:false` describe
signing-only acceptance; installation and device tests are proved separately, not
by rewriting that receipt. The temporary launch-flag live probe was removed from
production source and the final binary; its source remains ignored evidence.
Safe finite Debug-only diagnostics remain. Phone data is preserved, online mode is
restored and playback is paused. No persistent task-owned console remains active.

## Resume and acceptance

1. Investigate the distinct first-result UNSUPPORTED classification and misleading
   recovery message without bypassing immutable first-result selection.
2. Continue cold source-preparation work with same-version measurements. Do not
   describe the second, different-program run as warm-cache proof or instant start.
3. Obtain human audible-original confirmation and a fresh online app-UI journey
   without deleting the owner's retained download to force a network request.
4. Execute the separate native-player OS-network-denial/restart release gate;
   app offline mode alone does not satisfy it. Preserve native ownership/integrity.
5. Resolve or separately classify the retained full-library Chromium timeout and
   complete the remaining native/platform gates. Keep the full redesign/nativeCmpkit,
   English/Arabic/RTL and Android/iOS/Windows/macOS/Linux scope open.
