# Task B build, retention and five-platform preparation

Status: **retention source reviewed; production dispatch authorized and next. Canary verified;
no production package, release publication, signing or Task B acceptance is claimed.** This note is
the current-candidate Task B operating record. The older
`docs/verification/five-platform-preparation.md` remains historical source research; its Task A
dependency-gate text is superseded by the lead acceptance at `9c8e97c`.

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
