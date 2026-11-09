# Task B build, retention and five-platform preparation

Status: **run `42569788087` at `ceced9a` retained verified Linux and macOS arm64
packages; the macOS x64 lane is still building. Other completed lanes failed as
recorded below. The fresh unpacked Linux package passed silent native self-test.
Task B, installation, physical audio, signing and distribution remain incomplete.**
This note is the current-candidate Task B operating record. Older entries remain historical.

## Hosted retry findings and package execution — 2026-11-09

PR checks remain empty because verification is manual; inspect Actions run
`42569788087` directly. At this checkpoint macOS x64 reached installer generation;
its final result and the aggregate fresh-runner readback are pending.

| Lane | Verified result / remaining failure |
|---|---|
| Linux | Installer and inventory succeeded; GUI tests refused the required user namespace. Containment was preserved. |
| macOS arm64 | Desktop tests, installer and inventory succeeded. Shared tests: 1,205 methods, one failure in `cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback`. |
| Windows | The raw-backslash ZIP negative failed; dependency resolution returned HTTP 504. Native preparation separately failed without a retained compiler diagnosis; its cause remains unknown. |
| Android | Pinned Gradle distribution download returned HTTP 504 before compilation. Scoped retry `42570333742` repeated that failure; its diagnostic-only retention/readback succeeded. Further unchanged retries are held. |
| iOS x64 / arm64 | Gradle / Kotlin-Native distribution downloads respectively returned HTTP 504 before compilation. These are infrastructure failures, not new compiler diagnostics. |

The ZIP failure exposed both a fixture and scanner issue: Windows `ZipInfo` normalizes
backslashes. The fixture now writes the exact unsafe header name, and the scanner
validates `orig_filename` before using the normalized view. All 74 notice tests pass,
including an authored Windows-normalization discriminator. Shared JVM tests now log
full failure stacks; no cleanup behavior, assertions or timeouts changed.

The macOS shared failure took 0.475 seconds; its sanitized retained XML contains no
inner assertion. Two focused local attempts passed the unchanged method, including
one after the logging configuration change. These are equal-input dirty-worktree
runs (`cleanup-diagnostic-002` and `-003`), not a diagnosis or clean-source full-suite
acceptance. `cleanup-diagnostic-001` stopped on duplicate Gradle CLI flags before
running tests. The hosted failure remains unexplained; no timeout was inflated.

The lead downloaded and independently verified all assets from draft releases
Linux `438788311`, macOS arm64 `438793201` and Windows diagnostic `438785962`.
Exact tags resolve to `ceced9a7af226b22a2a96b82d8fa2451a6522729`; each packet's
source/pin/schema, asset sizes and hashes pass `scripts/build-retention.py verify`.
All three releases remain draft/prerelease/unpublished and all lane outcomes remain
failed. The macOS arm64 packet contains 1,689 tests total: one failure and one skip.

The new 112,972,962-byte Linux Debian package was extracted with safe tar filtering;
no package installation or maintainer script ran. `linux-package-selftest-003` passed
prepare/play/pause/seekForward/rate/seekBack/stop with authored MP3, mpv 0.41.0,
FFmpeg 8.0.1, `tlsVerify=yes`, silent `null` output, exit 0 and owned cleanup complete.
Attempt `-002` stopped before launcher execution because Python tarfile cannot read
the zstd payload directly; `-003` explicitly decompresses it with the installed zstd.
This proves unpacked-package native execution, not audible playback or installation.
Evidence: `.work/verification/task-b-hosted-fixes/retained-42569788087/`,
`linux-package-selftest-003/` and `lead-hosted-retry-receipt.json`.

## Focused correction results and current dispatch — 2026-11-09

Source corrections are committed at `ceced9a7af226b22a2a96b82d8fa2451a6522729` on
PR27. Hosted run `42569788087` uses that exact commit, `scope=all`,
`package_desktop=true`, `retain_builds=true`, and `retention_canary=false`. It retries
Android, Windows, Linux, both macOS architectures and both iOS simulator build lanes
on the existing standard public-repository runners. The run is pending, not acceptance.
The kmpYT pin is `6805d4db8077650992d342e5395b1f0d7c80e984`; schema 3 remains
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`.

Local evidence is under `.work/verification/task-b-hosted-fixes/`:

| Attempt | Source and result |
| --- | --- |
| `focused-batch-001` | Clean `daae24c`: all 43 selected shared methods passed in the log; desktop XML is 50 tests / 6 suites / 2 failures / 0 errors / 0 skips. All 37 proof guards, 3 native-audio, 2 viewport and 1 removal tests passed. The two failures were old crossfade transport-error assertions. The shared XML was overwritten by the desktop task's common output directory, so the 43 shared passes are log evidence, not retained shared XML. |
| `crossfade-focused-002` | `daae24c` plus the exact crossfade test changes later committed in `ceced9a`: 25 tests / 2 suites / 0 failures / errors / skips. This is an unchanged-input dirty-test run, not a clean committed-source run. |
| `native-report-focused-003` | Clean `ceced9a`: 11 tests / 2 suites / 0 failures / errors / skips; desktop production and test compilation executed. |
| `live-central-typed-002` | Clean `ceced9a`: actual discovery, raw-first acquisition, independent author check and playback preparation reached; provider access refused, process exit 1. No media transfer or offline phase. |

All four attempts have equal before/after input manifests (`inputs_equal=0` is the
comparison exit code) and independently rechecked attempt seals. The lead receipt is
`lead-receipt-ceced9a.json`. Project-lock snapshots and process scans are retained.
Some scans include the next queued gate's shell/Python processes; the live in-gate
scan also sees its outer managed-process keeper. These are not zero-match scans.
The live outer owner's final receipt reports `ownedCleanupComplete=true`; no Java
process remained in the recorded post-run scans. No historical evidence was resealed.

The crossfade corrections preserve the current reader-fence contract: failed retired
resource closure leaves `awaitRetiredReaders` unacknowledged and later stop/close
failing, while successful promotion or unrelated cancellation does not silence the
current deck or invent its playback error. Assertions now check the exact cleanup
failure, live-deck continuity, no duplicate close, and the later deletion/shutdown
veto. Production transport behavior was not changed to satisfy these tests.

The retained `e7cfb5c` Linux Debian package was safely extracted under
`linux-package-selftest-001` (503 entries, 288,298,773 extracted bytes), without
installation or maintainer scripts. Its launcher `--help` succeeded. Its native
self-test returned exit 1 with `invalid_verification_report` and host closure true.
Source inspection found a missing closing brace in `DesktopSelfTest`'s JSON return.
`ceced9a` corrects that brace; the native test now parses the complete report and
asserts all seven operations and the TLS setting, rather than searching substrings.
The older package remains a failed verification result; the current local test is
silent native decoding/control evidence, not a repaired-package or audible-output
claim. New package execution still requires the hosted retry's artifacts.

The typed live result records `acquireCalls=1`, `firstVideoAuthorChecks=1`,
`preparePlaybackCalls=1`, `prepareDownloadCalls=0`, `transferCalls=0`,
`transferBytes=0`, `approvedRawFirst=true`, no rejected-resource backlog and
`ownersClosedByHost=true`. The actual provider failure is
`PROVIDER_ACCESS_REQUIRED / YOUTUBE_ACCESS_REQUIRED`. No later-result substitution,
cookies, alternate source or reduced proof bounds were introduced. The online
materialization/offline-restart gate remains blocked. Linux hosted GUI namespace
permissions, physical devices/audio, installation, signing and release gates remain
open independently of this source refusal.

## Hosted retry and batched correction — 2026-11-09

The manual run used exact source `e7cfb5c9ec34d9e07587e116294d1675a2454a46`.
Its jobs ran even though PR27 reported no checks. Android assembled debug APK,
unsigned release APK and release AAB; Linux generated a 112,854,142-byte Debian
installer. The lead independently downloaded all ten assets across Android,
Linux and the Windows diagnostic lane and verified exact sizes, SHA-256 hashes,
commit/pin/schema identities and draft/prerelease/unpublished state. Draft release
IDs are Android `438758148`, Linux `438758003`, Windows `438757294`. All three
lane outcomes remain failed. Fresh-runner readback verified available lanes and
correctly failed when Apple lane releases were missing. Local evidence is
`.work/verification/task-b-hosted-fixes/hosted-retry-42563893628/`.

The same PR now batches the returned corrections:

- Explicit Native coroutine IO, okio `use` and common Volatile imports; replace
  JVM-only ForwardingSink with the equivalent common Sink delegation. No
  dispatcher, storage-error, schema or pin policy changes.
- Pass the scoped Git Bash executable directly to Gradle's native Exec task;
  use an ASCII JSON transport to test unchanged Arabic process arguments.
- Replace the invalid Objective-C `__block` capture by a synchronous pointer
  lifetime, and initialize Darwin native LC_NUMERIC to C before each libmpv
  owner, as required by libmpv client.h. Apple execution remains pending.
- Make optional empty retention arrays safe on macOS Bash 3. Preserve special
  archive-entry refusal while accepting only the observed exact `0xffff0010`
  empty Maven directory marker. Authored negatives still reject linked/special,
  encrypted, conflicting-file and nonempty sentinel entries.
- Close the reopened playback test controller; assert the accepted pre-activation
  removal failure; supply exact Resume fixture authority; share one native
  test-library path. Prior uncaught-test and duplicate-library warnings are
  evidence; exclusive causal attribution of those failures is not established.
- Allow 35 minutes for bounded desktop compile/tests: Intel macOS was stopped at
  20 minutes during cold compilation, before desktop tests began. Linux GUI
  namespace permissions remain a blocker; containment is unchanged.
- Adapt the opt-in listening proof to the accepted typed acquisition/execution
  ports and canonical durable Room identities. Author checks, actual raw-first
  admission, bounded owned playback/transfer, resource drain and offline
  zero-gateway requirements remain. See `central-proof-typed-integration.md`.

The 113 targeted Python checks (73 notices, 16 tooling, 14 retention, 10 workflow)
pass. Source-only notice `--check` remains 0 production components, 3 retained
texts and 1 open coverage issue; this is not binary legal closure. The combined
committed-source shared/desktop attempt is recorded above, including its two reds
and the narrow passing corrections. Hosted Apple/Windows verification remains pending;
earlier dirty proof runs are not final acceptance.

## Starting point and scope

- Worktree: `/root/.t3/worktrees/Veyra/t3-b705873a`
- Branch/PR: `t3/task-a-ui-recovery-integration`, PR #27
- Lead-accepted starting head: `9c8e97ce9a80ffa01f4751f0e6eb49759f9d183b`
  (Task A followup `3bf58fa` plus acceptance docs `9c8e97c`).
- Task A adjudication: `/root/projects/Veyra/.work/verification/resume-20261108/pr27-lead-adjudication.json`
- kmpYT pin: `c0a08090fe7dbd76e20216559b8184ad2cccde63`
- Room schema 3 SHA-256:
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`
- Repository is public. All runners/actions in `verify.yml` are standard free public-hosted
  images. No cache, artifact-storage, paid runner, package-manager or signing credential is added.
- The root checkout's three protected legal previews are untouched. Legal resources were
  regenerated only in this candidate.

## Retention route

`.github/workflows/verify.yml` has a manual `retention_canary` boolean. When true, the normal
desktop/Android/iOS matrix jobs are skipped and two canary jobs run:

1. **`retention-canary`** (`ubuntu-24.04`, job permission `contents: write`, no retained checkout
   credentials):
   - checks out the exact dispatched SHA, verifies a clean tree, pin and schema hash;
   - runs the authored retention-packet regressions;
   - creates four bounded non-audio assets: `payload.bin`, `identity.json`, `manifest.json`,
     `SHA256SUMS` (each <=16 KiB, total <=64 KiB);
   - refuses an existing release/tag, creates the unique immutable tag
     `veyra-draft-canary-<run_id>-<run_attempt>` at `github.sha`, then creates the draft
     prerelease with `--verify-tag` and never publishes it;
   - uploads the four assets directly with `gh release upload` (no clobber);
   - verifies draft/prerelease state, exact tag target and remote asset sizes/digests;
   - writes only a sanitized step summary. No release body contains credentials or signed URLs.
2. **`retention-readback`** (fresh `ubuntu-24.04`, `contents: write` visibility because GitHub
   hides draft releases from read-only tokens; the job only reads):
   - checks out the exact SHA and rechecks pin/schema;
   - lists/downloads the draft assets through the release-id asset API (not the published-only
     tag endpoint and not `gh release download`);
   - verifies the release id/state, exact tag target, exact asset set, sizes, `SHA256SUMS`,
     manifest, identity fields and independently recomputed SHA-256 hashes;
   - writes a sanitized readback receipt. It never uses `upload-artifact`, cache, or `--clobber`.

The tag is created before the draft release because GitHub does not materialize a tag for an
untagged draft. The release is addressed by release id for draft readback; `/releases/tags/...`
is only for published releases. Failed canary attempts and their tags/receipts are retained for
review; no tag is moved or overwritten.

### Canary evidence

- Workflow run: `42557930270` at exact head
  `5adf895b864b1c49878c8d10387c6ef0e8a61894`
- Tag/release: `veyra-draft-canary-42557930270-1`
- Release id: `438720474` (draft, prerelease, unpublished; `published_at=null`)
- Tag target: `5adf895b864b1c49878c8d10387c6ef0e8a61894` (immutable commit tag)
- Assets (each verified by the fresh-runner readback):
  - `identity.json`: 1,034 bytes, SHA-256 `665bdd21f8e7de6cdcf30c184afdb047bf5335c03b686e30e6e3c15694d067fb`
  - `manifest.json`: 418 bytes, SHA-256 `e2d1b37dfc51ce7c9b06bab3c2523134796c66c0a18ddacf3c336cb899b84158`
  - `payload.bin`: 259 bytes, SHA-256 `66f1faca321c85def0c2752f72146e8bed838dd23c074c9dafa4b4d82a0c1af3`
  - `SHA256SUMS`: 238 bytes, SHA-256 `1a852411d54d64362088e59595c2a9e495ba15def27224eb2ed150b607f009b2`
- Readback: the `contents: write`-visibility readback job downloaded all four assets through the
  release-id asset API, verified the exact tag target, draft/prerelease state and packet identity,
  and printed `"verified": true` for run `42557930270/1`.
- Local independent receipt:
  `.work/verification/task-b-build-retention-preparation/canary-final-42557930270/readback-receipt.json`;
  downloaded asset set and `SHA256SUMS` verified with the same script.
- Sanitized run log:
  `.work/verification/task-b-build-retention-preparation/canary-final-42557930270/run.log`
  SHA-256 `0dc6e4c5c536bc0d7cf872ad54b3f9c95b6fc4f2227a03602c901c61bca316f1`.
- The draft release is intentionally unpublished. It is not a release, package, signature or
  acceptance claim.
- Historical note: canary `42557930270` used `RUNNER_TEMP` for its transient packet/readback
  paths. Current workflow source now uses task-specific
  `$GITHUB_WORKSPACE/.work/verification/...` roots for canary, production lane packeting and
  readback. The historical run was not rerun for this destination-only workflow change.

Earlier canary attempts are preserved as failed evidence:

- `42557267886`: tag preflight false-positive because `gh api --jq` printed the JSON 404 body to
  stdout. Fixed to use command exit status.
- `42557355020`: GitHub created an untagged draft placeholder; no asset existed. The empty
  placeholder was removed after recording the failure.
- `42557488750`: tag creation and draft release creation succeeded, but the release lookup used the
  published-only `/releases/tags` endpoint before assets were uploaded. Draft release/tag retained;
  fixed to use release ids.
- `42557671375` and `42557816142`: tag/release/asset upload succeeded, but readback failed first on
  the published-only tag endpoint and then on a `contents: read` 403 for draft assets. Drafts/tags
  were retained; fixed to use release-id asset download with draft visibility.
- `42557816142`: release-id asset listing returned 403 to a `contents: read` token. The readback
  job now carries repository write visibility while remaining read-only in behavior; draft
  releases are not visible to read-only tokens. Fixed canary `42557930270` passed both jobs.

## Legal resources

`scripts/package-notices.py` regenerated the candidate source-only resources at the current pin.
The generated diff is limited to the current source/notice wording and hashes:

- `NOTICE.md`: kmpYT PR #1 is now described as merged at
  `45f7077880c37c20216d767b262bb5757b3010bf`; the final tested behavioral pin remains a gate.
- `THIRD_PARTY_NOTICES.txt`: warning corrected to state that license filenames and POM pointers are
  not proof of complete terms; explicit full-text review is required.
- `index.json`: matching hashes plus `sourceProjectArtifacts: []` in the current schema.
- `scripts/package-notices.py --check` passes with **0 production components, 3 retained texts,
  1 open coverage issue**. This is source-only and does not certify package completeness,
  corresponding source, native/helper closure, signing or redistribution readiness.

## Package provenance

`scripts/package-manifest.py` now:

- accepts only packages strictly newer than an explicit build marker; a stale pre-existing file
  cannot be presented as the current build;
- records exact source commit, kmpYT pin, schema3 hash, legal `index.json` hash, repository,
  workflow run id/attempt/ref, host image/system/architecture and task;
- records each package's path, bytes, SHA-256, architecture, unsigned/unverified status and
  `installationTested: false`;
- rejects missing/empty/oversized packages, escaping/symlinked paths, mismatched explicit source
  SHA and output outside the repository.

`scripts/package.sh` creates a project-owned marker before Gradle packaging and passes the exact
source SHA, repository and task to the manifest. Package output remains unsigned and uninstalled;
the manifest is provenance, not a signature or release acceptance.

## Exact five-platform commands and hosts

All commands run through the repository wrappers after `scripts/bootstrap.sh`; heavy local work
uses `vps-build` and the shared project lock. `scripts/ci.sh` runs the authored Python safety
tests, design checks and the mode-specific Gradle tasks.

| Target | Host | Prepared commands | Current limit |
| --- | --- | --- | --- |
| Linux x64 | local Linux x64 or `ubuntu-24.04` | `scripts/ci.sh desktop`; `scripts/ci.sh desktop --package` (DEB + package manifest); `scripts/gradle.sh -I scripts/runtime-inventory.init.gradle :composeApp:exportProductionInventory -Pveyra.inventory.platform=desktop-linux-x64`; `python3 -B scripts/package-notices.py --inventory .work/verification/runtime-inventory/desktop-linux-x64.json` | Local `fakeroot` absent; local offline package attempt stopped before native/package work on a missing Compose plugin probe. No DEB or physical audio. |
| Windows x64 | `windows-2022` | `scripts/ci.sh desktop`; `scripts/package.sh` (`packageMsi` + manifest); inventory `desktop-windows-x64` | No local Windows host; MSVC/GNU make/MSYS2 activation and MSI behavior unproven; unsigned. |
| macOS arm64 | `macos-15` arm64 | `DEVELOPER_DIR=/Applications/Xcode_26.0.1.app/Contents/Developer scripts/ci.sh desktop`; `scripts/package.sh` (`packageDmg`); inventory `desktop-macos-arm64`; `scripts/ci.sh ios`; `scripts/build-ios.sh simulator`; `scripts/build-ios.sh device` | No local macOS/Xcode; native helper/DMG and signing/notarization unproven. |
| macOS x64 | `macos-15-intel` x64 | Same commands with host x64; inventory `desktop-macos-x64`; iOS simulator target `IosX64`/`ios-simulator-x64` | Intel label/tool/runtime presence must be proven by an actual run; separate binary evidence required. |
| Android | `ubuntu-24.04` x64 or local Linux x64 | `scripts/ci.sh android` (JVM tests, debug APK, lint); prepared final commands `scripts/gradle.sh :composeApp:assembleRelease :composeApp:bundleRelease`; inventory `android-debug`/`android-release` | No release signing key; debug inventory is not release coverage; instrumentation compiled historically but **0 executed**; no local KVM/emulator. |
| iOS simulator arm64 | `macos-15` arm64 | `scripts/ci.sh ios`; `scripts/build-ios.sh simulator`; inventory `ios-simulator-arm64`; notices with `ios-arm64` + `ios-simulator-arm64` | Scheme has empty `<Testables/>`; compile only, no simulator launch/test execution; unsigned. |
| iOS simulator x64 | `macos-15-intel` x64 | Same with `ios-simulator-x64` / host `x86_64` | Intel host and x64 simulator runtime unverified; no launch/execution; unsigned. |
| iOS device arm64 | `macos-15` arm64 (canonical) | `scripts/ci.sh ios`; `scripts/build-ios.sh device`; inventory `ios-arm64` | Unsigned device build; no Apple team/profile/credentials, no installable IPA, no physical device. |

Exact platform commands are intentionally the existing reviewed wrappers, not a new parallel
build system. `scripts/build-ios.sh` emits `.work/verification/ios-*.json` receipts; desktop
packages land under `composeApp/build/compose/binaries/main/{deb,dmg,msi}`; Android debug APK lands
under `composeApp/build/outputs/apk/debug/`; package provenance lands at
`.work/verification/packages.json`. No artifact-storage action is assumed.

## Local host/toolchain preflight (2026-11-09)

- Linux `6.1.72` x86_64, 12 logical CPUs, ~27 GB free at inspection.
- OpenJDK 21.0.12; CMake 3.28.3; GNU Make 4.3; GCC 13.3; `dpkg-deb` 1.22.6.
- `fakeroot` **absent**; Xcode/`xcodebuild` **absent**.
- Android SDK `/opt/android-sdk`: platform 36 and build-tools 35.0.0 present.
- Local package attempt `final-linux-package-001` stopped in Gradle configuration because the
  offline cache lacks `org.jetbrains.compose:gradle-plugin-internal-jdk-version-probe:1.9.3`;
  it never reached native or package assembly. The attempt retains argv/input/exit/log/drain
  evidence. This is a local cache limitation, not a package result.
- `fakeroot` absence independently blocks the DEB route locally.

## Production retention extension (implemented; not dispatched)

The canary proves the free draft-Release route. The production extension is now implemented behind
the manual `retain_builds` input:

1. build/test steps remain without `GH_TOKEN`; only the final retention step receives
   `GH_TOKEN: ${{ github.token }}` (job permission `contents: write` is required for draft
   visibility/creation);
2. `scripts/build-retention.sh` creates one unique immutable tag/release per
   run/attempt/platform (`veyra-draft-<run_id>-<run_attempt>-<platform>`), uploads bounded actual
   package bytes and a support archive, and verifies remote names/sizes/digests;
3. `scripts/build-retention.py` collects only allowlisted package/application files, sanitized
   JUnit XML (system-out/system-err/properties removed; failure/error text and messages removed
   while retaining testcase/class/method/time, counts and failure/error type), generated
   legal/native inventory, package provenance and a failed-lane diagnostic summary into a bounded
   support archive plus manifest. Desktop packages come only from `packages.json` exact
   path/size/hash records tied to the same source/pin/schema, never from a package-tree scan. It
   rejects symlinks/aliased ancestors, escaping paths, oversized files, credential-like support
   text and duplicate asset names. Directory archives are streamed to disk after stat bounds.
4. desktop test and package generation are separate steps; a test failure/timeout still permits
   independent package generation and retention. Android gets an independent debug-APK rebuild and
   retains the APK at minimum. iOS retention records unsigned app/framework outputs with honest
   compile-only classification; device arm64 is retained once from the arm64 host;
5. a `retention-plan` job computes the exact expected lane set, and a fresh
   `retention-readback` job downloads every lane via the release-id asset API, verifies the exact
   tag commit/draft state/asset hashes and fails on a missing or malformed lane.

The lead-reviewed production command (dispatch recorded below once executed) is:

```sh
gh workflow run verify.yml \
  --ref t3/task-a-ui-recovery-integration \
  -f scope=all -f package_desktop=true -f retain_builds=true \
  -f retention_canary=false
```

No production matrix dispatch has occurred and no production package has been retained. Any desktop test failure or timeout fails the job truthfully while independent package generation
and failed-lane diagnostics remain retained; no release is published.

## Checks performed

- `python3 -B scripts/tests/test_retention_packet.py` — 7/7.
- `python3 -B scripts/tests/test_build_retention.py` — 13/13.
- `python3 -B scripts/tests/test_retention_workflow.py` — 10/10 static workflow invariants.
- `python3 -B scripts/tests/test_package_manifest.py` — 7/7.
- `python3 -B scripts/tests/test_tooling.py` — 10/10.
- Workflow YAML parsed successfully; final canary run `42557930270` passed the canary job and the
  independent readback job at exact head `5adf895`, with the draft release left unpublished.
- `python3 -B scripts/tests/test_package_notices.py` — 72/72; `test_verification.py` — 6/6.
- `python3 -B scripts/package-notices.py --check` — source-only pass with the stated open
  coverage issue.

## Limits and holds

- Historical crossfade helper hang is corrected in the selected diagnostic test run described below.
  A final clean hosted full-desktop run remains required.
- Previous same-source `DownloadRecoveryRoomTest` 15s timeout remains unexplained; later isolated
  and full-shared green runs do not diagnose it.
- No live source/authentication, copyrighted audio, physical audio/device, final-package native closure, signing,
  notarization, installability, package installation, workflow production matrix, release
  publication or distribution is claimed.
- No Task B or release acceptance is claimed. The canary draft is intentionally unpublished and
  must not be treated as a release.


## Lead review and local native/scroll verification (2026-11-09)

The lead independently verified canary run `42557930270`, its exact commit/tag/draft state,
and all four downloaded assets against their recorded sizes/digests. The seven cheap Python
suites were independently rerun: build retention **14**, workflow **10**, packet **7**,
package manifest **7**, notices **72**, tooling **10**, verification **6**, all green.
The 14th retention test rejects a compressed support entry exceeding its expanded size bound;
the existing application-directory test now also preserves an empty package file. The lead
reviewed the concrete workflow and retention scripts, including scoped credentials and paths.
Android's independent packaging step now also attempts unsigned release APK/AAB output; the
inventory in this lane is still explicitly `android-debug`, not release legal acceptance.
Hosted bootstrap requires clean exact `GITHUB_SHA`, approved pin and schema before building.

`gesture-native-003` ran through the resource gate with the shared project lock and bounded
JVM/test settings: Gradle exit **0**, **19 tests / 2 suites / 0 failures/errors/skips**:
`MusicSurfacesTest` **16**, `DesktopAudioEngineTest` **3**. The music test now uses one actual
swipe and asserts the complete progress bounds fit the pane and semantic value is **0.35**.
All title/provenance/phase/current-item oracles remain. Explicit scrolling had exposed the full
indicator while `performScrollTo` still looped; Material's expanded semantic bounds support
that helper-boundary diagnosis, but no native renderer deadlock is claimed.

The audio test exercised the actual bundled libmpv with authored MP3 and a **silent sink**:
prepare, play, pause, forward/back seek, speed, stop and TLS-verification configuration. It is
native execution evidence, not audible hardware, TLS network, live-source, package installation
or five-platform verification.

Evidence lives under `.work/verification/task-b-crossfade-diagnosis/gesture-native-003/`.
The selected test source matches retained `MusicSurfacesTest.gesture.kt` (SHA-256
`b6424855d8b1bffffae9b793eee414c3dca9a87f8a876787d2eeae5c3f61834a`);
only import ordering changed afterward. Concurrent workflow commits changed the broad input
manifest, so the evidence runner stopped at its comparison with exit **1** after Gradle passed.
This is diagnostic evidence, not a sealed clean-final-source run. XML/log/source are retained;
a later Java-executable/cwd/fd sample found **0** owned JVM processes. That later sample does
not establish an in-lock drain. The first sample incorrectly matched the shell text and is
retained separately as a false positive. Earlier bounded diagnostic hangs remain preserved.


## First production dispatch and Windows bootstrap correction

Run `42560795834` was dispatched at `2026-11-09T04:15:28Z` from exact source
`b1828cf3fcd2a2f01c4110a40787db293aa7932e`, scope all, desktop packages and retention enabled.
Windows stopped at bootstrap before compiling; the original guard had no failure diagnostic.
An authored local Git fixture reproduced a dirty markdown/JSON checkout when checkout used
`core.autocrlf=true` and validation used the scoped configuration with conversion disabled.
Adding `* text=auto eol=lf` preserves exact source bytes and makes that fixture clean. Every
tracked CRLF text file already has a deliberate `-text` exception, including retained notices
and `gradlew.bat`; those bytes remain unchanged. Bootstrap now reports source identity failures.
A `windows` dispatch scope permits a single-lane retry without repeating healthy platform lanes.
The other original jobs continue; this record does not claim their outcome or Windows success.

Windows retry `42561342038` at `035f518` stayed pending behind the whole original matrix's
branch-wide concurrency group. No job started. That pending run is superseded; retained runs
now use unique concurrency groups, so a scoped retry can proceed in its independent workspace
without cancelling an in-flight retention run. Standard non-retention runs keep scoped groups.


## Platform results and narrow corrections in progress

The first hosted matrix exposed previously unverified paths, so it is **not green**:

- Linux UI testing stopped before Gradle: `unshare` could not write `uid_map`. The repository
  guard explicitly refuses sudo/sysctl/AppArmor workarounds. This host capability gate remains
  blocked; no uncontained Xvfb fallback is authorized by that script.
- macOS arm64 executed the full shared JVM set: **1,205 tests, one failure**,
  `DownloadOwnershipRoomTest.changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates`
  timed out. The cause is **unexplained**. This is distinct from the earlier recovery timeout.
- iOS arm64 compiled far enough to diagnose unresolved `inet_pton`, `ntohs` and
  `NSDate.timeIntervalSince1970` in the old dependency pin. The isolated new immutable kmpYT
  pin `6805d4db8077650992d342e5395b1f0d7c80e984` is a direct descendant of `c0a0809` with exactly
  one iOS file changed: explicit exported imports and CoreFoundation byte swap. It reuses the
  narrow fix already authored upstream, without importing the broader checkpoint/runtime changes
  on upstream PR2. JVM/common source is byte-identical. The new commit is published on public
  branch `fix/veyra-ios-exported-bindings-20261109`; Apple compile success remains to be proven.
- Windows retry `42561590153` proved the line-ending/bootstrap fix, then Python selected the
  WSL shim rather than Git Bash. Independent package/inventory commands selected Java 8, and
  the Python asset list carried CRLF/native path syntax into Bash/gh. Those script repairs are
  batched with the retention executable-bit correction. The lead missed that executable bit
  during review; syntax/unit passes did not establish executable launchability.
- Desktop inventory called nonexistent Gradle `ArtifactResolutionResult.unresolvedComponents`.
  The lead inspected the installed Gradle 8.13 primary interface with `javap`: use `components`
  filtered by `UnresolvedComponentResult`, while preserving failed POM reporting.
- Linux native staging reached the existing-stage manifest check after FFmpeg preparation;
  a pristine output directory created before the Exec task is the source-supported explanation.
  The native fix will accept only an empty owned directory, preserving unknown/nonempty files.
  macOS native build failed with drained processes, but the original console omitted the actual
  compiler diagnostic. Bounded safe diagnostic capture is being added before a retry.

Original hosted job logs are preserved in `.work/verification/resume-20261108/task-b-*-1.log`
and `task-b-windows-2.log`. The original run and failed draft state are retained. These are
build findings; no existing test is weakened and no platform is claimed accepted.

### Batched hosted corrections — 2026-11-09

The Android job in run `42560795834` completed shared 713/713 and app 160/160
unit tests with no failures/errors/skips, then assembled debug APK, unsigned
release APK and release AAB. Its job remains failed: the subsequent Android
inventory selected no artifact type and hit AGP's ambiguous secondary variants;
retention then could not execute the nonexecutable script. The retry selects the
explicit AGP runtime class-JAR artifact view. This is a classpath inventory, not
binary/native/legal closure. The independent Gradle POM query now uses the actual
`components`/`UnresolvedComponentResult` API rather than `unresolvedComponents`.

Common scoped environment selection now checks an existing JDK21's version and
architecture for every Gradle/package/inventory child. Windows native subprocesses
receive the running Git Bash directory in PATH and its explicit native executable;
the directory is derived before Windows backslash conversion. Retention assets
use slash paths, strip list CR endings, and the retention script is executable.
Desktop Gradle uses `--continue` so independent Compose tests can run following a
shared-test failure without masking that failure.

Native staging now accepts only a pristine empty owned output directory before
staging; unknown nonempty stages remain refused. Native compiler failures retain
a bounded, filtered diagnostic at `.work/verification/native-build-diagnostic.json`
and print the same safe excerpt. The old Mac compiler failure remains unexplained.

The Mac ownership test now holds CLEANUP before its canonical lease claim, then
releases that barrier after the original old-finalizer settlement assertions.
The existing later allowance barrier, failures, history, claim, journal, deletion
and dependency assertions remain. This eliminates a source-reachable observation
race in the test; no retained stage trace establishes that it caused the original
Mac timeout. Production shared source is unchanged.

The dependency pin `6805d4db8077650992d342e5395b1f0d7c80e984` is a direct descendant
of `c0a08090`, changing only Apple's exported-binding imports and network-order
port conversion in one iOS file. JVM/common source and schema are unchanged.
Candidate legal metadata was regenerated; source-only legal coverage remains open.
All fixes stay in PR27 and the existing checkout. Hosted retry/native/package
success will be assessed separately from the cheap authored checks and local
focused ownership run.

Cheap checks for this batch: tooling 16/16, native bundle 25/25, build retention
14/14, workflow invariants 10/10, retention packet 7/7, package manifest 7/7,
package notices 72/72, verification summary 6/6; shell syntax and YAML parse pass.
These do not establish Windows/Apple execution or close the hosted Linux boundary.

Focused local validation at committed `800a591`: `task-b-hosted-fixes/ownership-001`
passed all 11 `DownloadOwnershipRoomTest` methods with no failures/errors/skips,
Gradle exit 0, byte-identical before/after manifests and zero final argv/cwd/fd
drain matches. Shared production/test Kotlin compilation executed. This supports
the test synchronization change; it does not retrospectively diagnose the Mac red.

The original Intel iOS logs also contain failed Kotlin compilation, despite GitHub
showing successful step conclusions for `continue-on-error` steps. Their actual
outcomes are failures and the final enforcement correctly fails the job. No iOS
compile success is claimed. The native inventory supports unpacked project KLIBs
by hashing each exact regular member (bounded traversal, no linked members) rather
than requiring every selected native artifact to be an archive file. Other kinds
of directory artifacts remain refused. This records selected link inputs and does
not establish final-package/native/legal closure.

The local inventory probe `android-inventory-001` stopped before inventory because
the task-owned bounds init required `VEYRA_TEST_EVIDENCE_DIR`; that omitted runner
variable was supplied in fresh `android-inventory-002`. The latter parsed and
executed the corrected inventory, selected the Android runtime class-JAR artifact
without the earlier variant ambiguity, then truthfully refused the missing local
`shared/.../bundleLibRuntimeToJarDebug/classes.jar`. This checkout had compiled
Android Kotlin but had not assembled that library JAR. No successful local
production inventory is claimed; the hosted retry runs assembly before inventory.
JDK release architecture matching accepts the native x86_64/amd64/x64 and
aarch64/arm64 spellings rather than treating equivalent spellings as a mismatch.
