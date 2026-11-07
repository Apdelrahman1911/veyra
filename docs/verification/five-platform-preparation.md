# Five-platform final builds, native verification, and distribution — Task B preparation

**Status: preparation only. No Gradle invocation, native build, package build, device/emulator/simulator operation, workflow dispatch, live-source check, or release publication was performed for this document.**

- **Prepared:** 2026-11-07 UTC
- **Repository:** <https://github.com/Apdelrahman1911/veyra> (public)
- **Integration branch and PR base:** `feat/veyra-integration`
- **Preparation branch:** `t3/five-platform-preparation-1`
- **Verified starting commit:** `ec97925883f7a26765b7e3409ba5a81e358082b9` — “Checkpoint durable download recovery and platform lifecycle safeguards”
- **Verified remote tip at preparation:** `ec97925883f7a26765b7e3409ba5a81e358082b9`
- **Pinned kmpYT revision:** `c0a08090fe7dbd76e20216559b8184ad2cccde63`
- **Current Room schema in this base:** version 2 (`shared/schemas/dev.veyra.data.room.VeyraDatabase/2.json`)
- **Default `main`:** `304d4dcde725899d2f50c72497c624d8f416a54c` — workflow-bootstrap ancestry only; do not target this work at `main`.

This runbook is the source-verified execution plan for **Task B**. Task B may start only after the lead supplies the precise reviewed, merged and validated **Task A** integration SHA. The first Room-foundations PR is not that gate: typed schema-3/download-recovery integration and regressions remain pending beyond it. Do not treat `ec97925` as the final revision and do not package it as one.

---

## 1. Authority, scope, and non-claims

### 1.1 Authorized scope

Only two bodies of work are authorized:

- **(A)** newer Room/download-recovery integration and testing, owned by the isolated Room engineer.
- **(B)** final five-platform builds, native verification, and distribution, prepared here and executed later from the accepted Task A SHA.

This preparation is documentation-only. Its exclusive tracked edits are:

- `docs/verification/five-platform-preparation.md` (this file)
- `docs/verification/five-platform-execution-checklist.json`

No production, tooling, workflow, source, test, schema, or generated-resource change is made here. Any required implementation/tooling/workflow change is proposed with exact files, evidence, a minimal diff plan, and tests, then returned to the lead for separate authorization. Use only the supplied checkout; do not create another checkout/worktree or switch/reset the lead’s tree. Task B execution may use its own isolated build workspace only when the lead authorizes it.

### 1.2 Explicit exclusions and preserved holds

The following remain outside this assignment and must not be quietly implemented, satisfied, or declared met here:

- Streaming/offline source authentication, cookies/login fixes, live central-source playback, and real-source release proof.
- Physical-device audible crossfade verification and any physical-audio claim.
- Catalogue/discovery features, new UI, or unrelated redesign.
- Room implementation itself; the Room engineer owns its source, tests, and `docs/verification/room-foundations.md`. Do not edit those, read the Room agent’s mutable outputs as final evidence, or send it conflicting Task B work; the lead’s accepted Task A SHA/evidence is the gate.
- Purchases, billing changes, paid runners/services/storage, package-manager or system installation, global configuration/cache changes, or access to unrelated workloads.
- Release publication, tags, workflow permission changes, or branch deletion during preparation.

The existing real-source and physical-audio release holds remain visible and unmet. Task B must not relax them. `CentralListeningProof` and its README are source/authentication-specific; do not run `--central-proof online` or `offline` as part of Task B, and do not count the authored central-proof fixtures as live media proof.

### 1.3 What this preparation establishes — and does not

**Establishes:** the current source/configuration state at `ec97925`; exact commands and tool prerequisites already present in the repository; artifact contracts; inventory/notice/legal order; known gaps; platform ownership proposal; evidence fields; and the checklist for later execution.

**Does not establish:** a successful build, a package, a native execution, a simulator/emulator launch, an installation, a signed artifact, a distribution channel, a legal closure, a live-source result, or physical audio. The word “verified” in this document means “checked against the actual repository source/configuration,” not “executed.”

### 1.4 Launch configuration and attestation

This preparation ran as **`opencode-go/deepseek-v4.1-flash`** (DeepSeek 4.1 Flash), as displayed by the harness. The lead requested **variant `max`**. The harness exposes the model/provider identity in this session but provides **no independent runtime/back-end or variant attestation**. I therefore record the accepted launch configuration and the attestation limit, and I do not claim a stronger runtime identity. The older `AGENTS.md` GPT-6 Astra allocation is superseded by the explicit owner preference for this task. No subagents were created; this work was done directly in the assigned checkout.

---

## 2. Source-verified present state

### 2.1 Git, repository, and workflow facts

| Fact | Observed value | Source / command |
|---|---|---|
| Worktree | `/root/.t3/worktrees/Veyra/t3-61a2201d` | `pwd`, `git rev-parse --show-toplevel` |
| Branch | `t3/five-platform-preparation-1` | `git branch --show-current` |
| HEAD | `ec97925883f7a26765b7e3409ba5a81e358082b9` | `git rev-parse HEAD` |
| Working tree | clean; 0 tracked/untracked changes before authoring | `git status --porcelain=v1 -uall` |
| Tracked paths | 346 | `git ls-tree -r --name-only HEAD \| wc -l` |
| Checkpoint scale note | The checkpoint is described in the lead brief as a 104-file change set; the tree itself has 346 tracked paths. Do not confuse changed-file count with repository size. | lead brief + local tree count |
| Remote tip | `ec97925883f7a26765b7e3409ba5a81e358082b9` | `git ls-remote … refs/heads/feat/veyra-integration` |
| Default `main` | `304d4dcde725899d2f50c72497c624d8f416a54c` | `git ls-remote … refs/heads/main` |
| Submodule gitlink | `160000 commit c0a08090fe7dbd76e20216559b8184ad2cccde63 third_party/kmpYT` | `git ls-tree HEAD third_party/kmpYT` |
| Recorded pin | `c0a08090fe7dbd76e20216559b8184ad2cccde63` | `gradle/kmpyt.commit` |
| Repository visibility | PUBLIC; default branch `main` | `gh repo view Apdelrahman1911/veyra --json …` |
| Workflow | id `409222980`, active; `.github/workflows/verify.yml` | `gh api repos/Apdelrahman1911/veyra/actions/workflows` |
| Workflow runs | 0 at inspection | `gh run list --workflow verify.yml --limit 10` returned `[]`; runs API `total_count` 0 |
| Open pull requests | 0 at inspection | `gh pr list --state open` |

The submodule checkout is present in this isolated worktree at the pinned revision. No submodule initialization/download was needed for this preparation; the directory content and `git submodule status` were inspected read-only. The parent `docs/STATE.md` records earlier HEADs (`fa8d87c`, etc.); those are historical and do not override `ec97925`.

### 2.2 Inspected paths

The following were read (not executed) for this preparation. Optional paths that do not exist are identified explicitly.

**Project state and agreements**

- `AGENTS.md`
- `docs/STATE.md`
- `docs/PLAN.md`
- `docs/DECISIONS.md`
- `README.md`
- `NOTICE.md`
- `docs/verification/boundary.md`
- `docs/verification/licensing.md`
- `docs/verification/package-notices.md`
- `docs/verification/android-stage0.md`
- `docs/research/verification-ci.md`
- `docs/research/desktop-runtime.md`
- `docs/research/mobile-audio.md`

**CI, environment, and packaging**

- `.github/workflows/verify.yml`
- `scripts/ci.sh`, `scripts/env.sh`, `scripts/dev.sh`, `scripts/gradle.sh`
- `scripts/bootstrap.sh`, `scripts/checkout-submodule.sh`
- `scripts/package.sh`, `scripts/package-manifest.py`
- `scripts/build-ios.sh`, `scripts/xcode-framework.sh`
- `scripts/runtime-inventory.init.gradle`
- `scripts/package-notices.py`
- `scripts/verification-summary.py`
- `scripts/with-display.sh`, `scripts/linux-namespace.sh`
- `scripts/android-instrumentation.sh`
- `scripts/tests/test_tooling.py`, `test_verification.py`, `test_package_notices.py`, `test_design_resources.py`, `test_android_preflight.py`
- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- `gradle/kmpyt.commit`, `.gitmodules`, `.gitignore`, `.gitattributes`
- `build.gradle.kts`, `shared/build.gradle.kts`, `composeApp/build.gradle.kts`

**Native runtime and helpers**

- `native/README.md`, `native/CMakeLists.txt`, `native/include/veyra_session.h`
- `native/bundle.py`, `native/test_bundle.py`
- `native/owned_process.py`, `native/test_owned_process.py`
- `native/ffmpeg/README.md`, `native/ffmpeg/build.py`, `native/ffmpeg/test_build.py`
- `native/runtime/generate_helper_inventories.py`
- `native/licenses/ca-bundle-provenance.md`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopNativeRuntime.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopMediaRuntime.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopHelperManifest.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopVerification.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/session/DesktopMediaSession.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/session/LinuxMprisSession.kt`
- `composeApp/src/desktopMain/kotlin/dev/veyra/desktop/Main.kt`
- `composeApp/src/desktopMain/resources/dev/veyra/desktop/helpers/*.json`
- `composeApp/src/desktopMain/resources/dev/veyra/desktop/runtime/ca-bundle.crt`

**Mobile/iOS hosts and tests**

- `iosApp/Veyra.xcodeproj/project.pbxproj`
- `iosApp/Veyra.xcodeproj/xcshareddata/xcschemes/Veyra.xcscheme`
- `iosApp/iosApp/Info.plist`, `VeyraApp.swift`, `ContentView.swift`, `AppDelegate.swift`
- `composeApp/src/androidMain/AndroidManifest.xml`
- `composeApp/src/androidMain/kotlin/dev/veyra/app/{MainActivity,VeyraApplication,AndroidInitialization}.kt`
- `composeApp/src/iosMain/kotlin/dev/veyra/app/{MainViewController,IosAppLifecycle,IosInitialization}.kt`
- `composeApp/src/androidInstrumentedTest/**` (14 files; 36 methods per historical evidence)
- `composeApp/src/iosTest/**` (5 files)
- `shared/src/iosTest` — **does not exist**; this is a real path absence, not an omitted listing.
- `composeApp/src/desktopTest/kotlin/dev/veyra/desktop/{DesktopAudioEngineTest,DesktopCrossfadeTest,DesktopMediaSessionTest,DesktopMediaSessionFactoryTest,DesktopMediaRuntimeTest,LinuxMprisSessionTest,DesktopVerificationTest}.kt`
- `composeApp/src/desktopTest/resources/dev/veyra/desktop/tone.mp3` and its `README.md`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/VeyraDatabase.kt`
- `shared/schemas/dev.veyra.data.room.VeyraDatabase/{1,2}.json`

### 2.3 Critical-input fingerprint reference

The lead supplied a read-only 43-file manifest:

- Path: `/root/projects/Veyra/.work/delegation/room-download-20261107/five-platform-preparation-inputs.json`
- SHA-256: `e726c4d65546ac88384dd369816cbea0841cf789e6e7d074d87aa8f5bfd01795`
- Listed files: 43; **43/43 byte hashes matched** the files in this worktree; 0 missing.

The most critical hash prefixes verified independently in this worktree (full values are in the lead manifest):

| Path | SHA-256 prefix |
|---|---|
| `.github/workflows/verify.yml` | `bcdcedf27f23…` |
| `scripts/ci.sh` | `80794592532b…` |
| `scripts/package.sh` | `ed7395edce16…` |
| `scripts/package-manifest.py` | `96e3c16313b1…` |
| `scripts/build-ios.sh` | `e4d50c6ac9fd…` |
| `scripts/runtime-inventory.init.gradle` | `883024b6bb12…` |
| `scripts/package-notices.py` | `62e1e9ebc2d0…` |
| `scripts/verification-summary.py` | `74dbd6cafdc1…` |
| `gradle/libs.versions.toml` | `8017c2c9d806…` |
| `gradle/kmpyt.commit` | `388d9fc47d6b…` |
| `settings.gradle.kts` | `6877c4f3a677…` |
| `gradle.properties` | `fdfb13bfed99…` |
| `shared/build.gradle.kts` | `7c3a38611e6a…` |
| `composeApp/build.gradle.kts` | `024897056ede…` |
| `iosApp/Veyra.xcodeproj/project.pbxproj` | `b405542fc318…` |
| `iosApp/Veyra.xcodeproj/xcshareddata/xcschemes/Veyra.xcscheme` | `fdb7c875ab20…` |
| `iosApp/iosApp/Info.plist` | `8fa78e2e9b35…` |
| `composeApp/src/androidMain/AndroidManifest.xml` | `7198ec7546c8…` |
| `native/README.md` | `5a0b5d24045f…` |
| `native/bundle.py` | `708b075bfae1…` |
| `native/ffmpeg/build.py` | `9e5ef49a4249…` |
| `native/include/veyra_session.h` | `1bfd410117d9…` |

**This table is an inspection aid, not the authority.** The authority is the lead manifest above; rehash the final Task A SHA inputs afresh. Do not reuse these hashes as evidence for a later revision.

### 2.4 Current source-only legal-resource blocker (important)

Running the existing bounded offline check in this clean checkout produced:

```text
$ scripts/dev.sh python3 -B scripts/package-notices.py --check
Notice verification failed: Generated legal resources are stale/missing: NOTICE.md, THIRD_PARTY_NOTICES.txt, index.json
[exit 1]
```

Cause: root `NOTICE.md` at `ec97925` contains the newer kmpYT merge/pin text, but the committed generated resources under `composeApp/src/commonMain/composeResources/files/legal/` still contain the older text and older index/schema. The parent worktree `/root/projects/Veyra` intentionally carries three unstaged generated replacements (not copied, altered, deleted, stashed, or committed here):

| Generated file | Committed at `ec97925` | Parent unstaged (regenerated) |
|---|---|---|
| `NOTICE.md` | `2227f968a4f241c6dd0add307158427d5f7f3367a42320b28d46dd991294e85a` | `7ca2f79a699cbb9821efe4bb244638b1b7e2d3bf971fb026e2ee1e1ef01774b3` |
| `THIRD_PARTY_NOTICES.txt` | `9167d699582c1cbdb1d941c8b032129c282b35ffe38bba00f7889e58bf257434` | `bbd4fdff5cb423ae29a8731b173cc52e84db48d22308f995645ee6223d19d025` |
| `index.json` | `dd2c1945a797e0ff78db2ebac3fad38342c0b5323d769dabe941cbbae2f5cdcd` | `160dd9fbc4978b5346cdc2ba4f1f3d0374b0fb71732a5f757a077bf1af89612a` |

Consequence: a **clean checkout of `ec97925` fails `scripts/ci.sh` before any build**, because `ci.sh` runs `scripts/package-notices.py --check` unconditionally. The final Task A SHA must either (a) include an explicitly reviewed regeneration commit for these three deterministic files, or (b) Task B must regenerate them in the disposable build workspace before invoking `ci.sh`, and record the diff/hashes. Option (a) is required for clean-checkout reproducibility; option (b) is acceptable only as a build-workspace step and must not be committed silently. This is a **P0 gate**, not a warning.

Do not copy the parent’s unstaged files into this preparation branch. They are evidence of the intended regeneration, not a source commit.

Minimal future repair (separate reviewed commit, not this preparation): run `scripts/dev.sh python3 -B scripts/package-notices.py` in a clean final checkout, run `--check`, inspect the three-file diff, and stage only those generated files. If the final Task A SHA changes the source notice text, the expected hashes change; re-record them. This is a deterministic source-derived regeneration, not a silent generated-file sweep.

### 2.5 Host inventory observed this turn (read-only)

| Item | Observation |
|---|---|
| Host OS/arch | Linux `x86_64`, 12 logical processors |
| Filesystem free | 48,376,393,728 bytes (~45.05 GiB) at 05:36 UTC on the worktree volume; `/tmp` tmpfs 93% used |
| Memory | `MemTotal` 32,878,632 kB; `MemAvailable` 8,402,800 kB; swap 8,388,604 kB |
| `/dev/kvm` | absent |
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64` (also JDK 17 present) |
| Android SDK | `/opt/android-sdk`; `platforms/android-36` present; `build-tools/35.0.0` present; emulator executable present |
| Local command paths | `python3`, `java`, `cmake`, `make`, `cc`, `bash`, `dpkg-deb`, `Xvfb`, `xauth`, `unshare`, `gh`, `strace`, `file`, `readelf`, `ldd`, `unzip` |
| Local command absences | `fakeroot`, `rpm`, `xcodebuild`, `xcrun`, `cl` (MSVC) |
| Workflow runs | none |

The lead’s earlier measurement (4,133,810,176 bytes free) and this measurement differ; disk state is volatile. Re-inventory immediately before any execution and never infer capacity from this table. The parent `/root/projects/Veyra` and this worktree share the same overlay filesystem in the current environment, but the free-space value can change with unrelated workloads. Do not kill or clean unrelated workloads.

### 2.6 Historical evidence that must not be promoted

These are **real past executions on earlier inputs**, preserved in the parent (read-only). They are not final-revision, clean-revision, native-package, or physical-audio proof:

| Evidence | Historical result | Permitted use |
|---|---|---|
| `.work/verification/retained-download-tests-proposal/runs/shared129-first/` | 129/129 targeted shared tests; 11 suites; 0 failures/errors/skips | Historical Room/file/fake-transfer contract only |
| `.work/verification/retained-download-desktop43-second-proposal/runs/desktop43-second/` | 43/43 targeted desktop tests; 3 suites | Historical authored AppContainer/retained-lifetime fixtures |
| `.work/verification/lifecycle48-first/` | 48/48 headless lifecycle tests | Historical controller/lifecycle contracts |
| `.work/verification/gui13-runs/gui13-reviewed-ui-first/` | 13/13 GUI tests; screenshots reviewed | Historical en/ar/UI fixture evidence |
| `.work/mobile-evidence/android-instrumentation-assemble-second/` | 11 instrumented classes / 36 methods compiled; 0 executed | Historical compile/APK artifact only |
| `.work/android/runs/preflight-_30xwl0l/` + `.work/verification/android-stage0-fifth/` | fifth no-guest preflight failed acceleration check exit 8; `/dev/kvm` missing | Historical containment attempt; no guest |
| `.work/verification/windows-known-folder-root-review/observed-run-42252956537/root-result.json` | kmpYT public run 42252956537 succeeded diagnostically; `LOCALAPPDATA` lookup failed `80070003`; browser not launched | Historical Windows diagnostic only |
| `.work/native-ffmpeg/output/linux-x64/` (parent) | Linux FFmpeg output/provenance exists | Reusable only after `--verify-output` at the final SHA; not final package proof |

Never count stale/private XML, method authoring counts, compile-only code, screenshots, a null sink, or a diagnostic run as fresh Task B success.

---

## 3. Current architecture and pinned inputs

`:shared` owns models, Room persistence, source/download/playback contracts and implementations. `:composeApp` owns the shared Compose UI and platform host adapters. `iosApp` is the full Swift/Xcode host. Settings use Google Maven/Central plus `includeBuild(third_party/kmpYT)` with dependency substitution; MavenLocal, dynamic versions, and a second acquisition engine are not permitted. Test-only JUnit must not enter production packages.

| Input | Pinned value | Source |
|---|---|---|
| Kotlin | `2.2.21` | `gradle/libs.versions.toml` |
| Compose Multiplatform | `1.9.3` | same |
| AGP | `8.13.2` (outside KGP 2.2.21’s published tested range; actual build gate) | same + `docs/research/verification-ci.md` |
| Gradle wrapper | `8.13`; SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` | `gradle/wrapper/gradle-wrapper.properties` |
| JVM target | 17; JDK 21 for builds | module build files + `scripts/ci.sh` |
| Room / SQLite / KSP | `2.8.5` / `2.6.2` / `2.2.21-2.0.4` | version catalog |
| kmpYT | `c0a08090fe7dbd76e20216559b8184ad2cccde63` | gitlink + `gradle/kmpyt.commit` |
| Current Room schema | 2 | `VeyraDatabase.kt` + exported schema JSON |
| Media runtime | `org.openani.mediamp:mediamp-mpv-runtime-<os>-<arch>:0.5.3`, `transitive=false` | `composeApp/build.gradle.kts` |
| FFmpeg | 8.0.1, commit `894da5ca7d742e4429ffb2af534fcda0103ef593`, archive SHA-256 `679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1` | `native/ffmpeg/build.py` |
| CA bundle | SHA-256 `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285` | `DesktopNativeRuntime.kt` + provenance record |
| Android app | `applicationId`/`namespace` `dev.veyra.app`; compile/target 36; min 23; versionCode 1; versionName 1.0.0; release minify false; **no release signing config** | `composeApp/build.gradle.kts`, manifest |
| Desktop package | packageName `Veyra`; version `1.0.0`; vendor `Veyra contributors`; Debian name `veyra`; group `AudioVideo`; maintainer `Apdelrahman1911@users.noreply.github.com`; Windows `upgradeUuid aebfc901-b3a1-4c57-b20e-4bbdf1968e08`; menu shortcut; macOS bundle `dev.veyra.app`; `includeAllModules=true` | `composeApp/build.gradle.kts` |
| iOS host | bundle `dev.veyra.app`; version `1.0.0`; build 1; deployment target 16.0; `en`/`ar` localizations; background mode `audio`; exact numeric-loopback ATS exception only | `project.pbxproj`, `Info.plist` |
| iOS scheme | `Veyra`; **empty `<Testables/>`** | `Veyra.xcscheme` |
| Kotlin Apple frameworks | static; `baseName = "Veyra"`; bundleId `dev.veyra.app.shared` | `composeApp/build.gradle.kts` |

Room schema 3 is **not** in this base. The final Task A SHA must add the typed schema-3 state and its migration/exported schema. Task B must re-record:

- `shared/src/commonMain/kotlin/dev/veyra/data/room/VeyraDatabase.kt` version constant and `MIGRATION_2_3` (or equivalent) source hash.
- `shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json` presence, bytes, SHA-256.
- The accepted Task A migration/regression test counts and their immutable evidence paths.
- Whether the checked-in legal resources were regenerated after the final source changes.

Parent `.work`, historical evidence, private staging and caches are read-only inputs. Do not copy or integrate the parent private staging (`/root/projects/Veyra/.work/veyra-acquisition/.work/d010-staging/`). It is undelivered, contains failed private-suite evidence, and is not Task A. Preserve all existing work; do not clean, stash, reset, or delete it.

---

## 4. Five-platform matrix

“Configured” means the workflow/build file names the runner or task. “Availability” means actual remote tool presence, which is **not proven** by this preparation. Runner-image documentation was checked read-only at the revisions noted in §4.1; documentation is not a successful run.

### 4.1 Runner-image documentation checks (read-only)

| Label | Official `actions/runner-images` document revision | Date | Relevant documented tools | Limit |
|---|---|---|---|---|
| `ubuntu-24.04` | `dbf2f009b5e1dcce57cf578ff4fdc1391a4d6d1b` | 2026-11-06T12:03:54Z | JDK 21 (21.0.13+9), Android SDK platform 36, build-tools 35.0.0, `fakeroot`, `rpm`, Xvfb, CMake, `dpkg` | Docs are not a run; exact image rollout may differ |
| `windows-2022` | `0306528a7158121026b85c19559754b9415c1209` | 2026-11-06T12:04:26Z | Visual Studio 2022 17.14, CMake 3.31.6, WiX 3.14.1, MSYS2 preinstalled at `C:\msys64` (not on PATH), JDK 21 | GNU `make` not listed; MSVC environment activation not proven |
| `macos-15` (arm64) | `4da2307112a0fad2fd2ecaca8ba7d08683b8c581` | 2026-10-30T14:53:57Z | Xcode 26.0.1 at `/Applications/Xcode_26.0.1.app`, iOS 26.0 simulator runtime, JDK 21 arm64 | Docs are not a run; image can change |
| `macos-15-intel` (x64) | `07cc2190cd4d100d9f69478219017720c02c40f6` | 2026-08-28T08:09:32Z | Xcode 26.0.1, JDK 21 x64 | Older document; actual label availability/tools must be proven by a run |

### 4.2 Matrix

| ID | Target | Configured host | Tool prerequisites (existing only) | Existing build/test entrypoint | Existing execution level | Produced artifact type and path contract | Signing / installability | Inventory/legal target | Retrieval | Primary blockers |
|---|---|---|---|---|---|---|---|---|---|---|
| **AND** | Android (device/emulator; app builds on Linux x64) | `ubuntu-24.04` x64; local Linux x64 | JDK 21, SDK 36, build-tools 35.0.0, ADB/emulator for instrumentation, KVM for acceleration (not local) | `scripts/ci.sh android`; tasks `:shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:lintDebug` | JVM unit + APK + lint configured; **instrumentation not executed**; 36 methods historically compiled | Debug APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk` (historical). Release/APK/AAB paths are conventions under `composeApp/build/outputs/{apk,bundle}/release/`; capture exact file | Debug-signed installable APK; release unsigned; no production key | `android-debug`, `android-release` | Local `.work` or future public Release; current workflow uploads nothing | No KVM locally; no reviewed instrumentation runner; no release signing key; lint incomplete; final SHA not supplied |
| **IOS-SIM** | iOS Simulator arm64 (and x64 on Intel) | `macos-15` arm64; `macos-15-intel` x64 | Xcode 26.0.1, JDK 21, `xcodebuild`, `xcrun`, `plutil`, `lipo` | `scripts/ci.sh ios`; `scripts/build-ios.sh simulator` | Compile only: Kotlin test sources + debug framework + full Swift host; **no simulator launch/test execution** | `iosApp` build output under `.work/xcode/DerivedData-simulator-<arch>/Build/Products/<Config>-iphonesimulator/Veyra.app`; result bundle `.work/xcode/results/…xcresult`; receipt `.work/verification/ios-simulator-<arch>.json` | Unsigned; **simulator-only**, not device-installable | `ios-simulator-arm64`, `ios-simulator-x64` (Intel) | Future public Release; current workflow uploads nothing | Empty scheme `<Testables/>`; no simulator launch; no test execution; Xcode path/tool presence must be proven |
| **IOS-DEV** | iOS device arm64 | `macos-15` arm64 | Xcode 26.0.1, Apple signing identity/team/profile for installable IPA (none seen) | `scripts/build-ios.sh device` | Compile/link unsigned device host; **no signing/install** | `.work/xcode/DerivedData-device-arm64/Build/Products/<Config>-iphoneos/Veyra.app`; receipt `.work/verification/ios-device-arm64.json` | **Unsigned device build; NOT an installable IPA**; no production signing | `ios-arm64` | Future public Release only if an installable/signed artifact exists | No Apple team/profile/credentials; no device hardware; export/notarization not configured |
| **WIN** | Windows 10+ x64 | `windows-2022` x64 | JDK 21, VS 2022 x64 (`cl`, `lib`, `dumpbin`), CMake ≥3.24, GNU make + bash (MSYS2), WiX 3, jpackage | `scripts/ci.sh desktop`; `native/bundle.py --target windows-x64`; `scripts/package.sh` → `:composeApp:packageMsi` | JVM desktop tests configured; native helper/FFmpeg **not matching-host built in checkpoint**; MSI not built | MSI under `composeApp/build/compose/binaries/main/msi/`; app image under `.../app/Veyra/`; native resources staged at `.work/desktop-native/resources/windows-x64/dev/veyra/desktop/native/windows-x64/` | Unsigned MSI; no Authenticode; SmartScreen/trust unresolved; installer transaction external | `desktop-windows-x64` | Future public Release; current workflow uploads nothing | No local Windows host; `make`/MSVC env unproven; scoped `LOCALAPPDATA`/Chromium startup risk; unsigned; installer test boundary |
| **MAC-ARM** | macOS 13+ arm64 | `macos-15` arm64 | Xcode 26.0.1/CLT, JDK 21, CMake ≥3.24, `clang`, `make`, `bash`, `hdiutil`, `codesign` | `scripts/ci.sh desktop`; `native/bundle.py --target macos-arm64`; `scripts/package.sh` → `:composeApp:packageDmg` | JVM desktop tests configured; native ObjC++ helper/FFmpeg not matching-host built in checkpoint; DMG not built | DMG under `composeApp/build/compose/binaries/main/dmg/`; app `.../app/Veyra.app`; session helper `libveyra-session.dylib` staged under native resources | Unsigned; no Developer ID/notarization; Gatekeeper blocks downloaded unsigned copy | `desktop-macos-arm64` | Future public Release; current workflow uploads nothing | No local macOS host; nested signing/notarization absent; session helper compile unproven |
| **MAC-X64** | macOS 13+ x64 | `macos-15-intel` x64 | Same as MAC-ARM but x64 | Same entrypoint with `--target macos-x64` | Same as MAC-ARM; **separate x64 execution required** | Same DMG/app paths, x64 | Unsigned; no notarization | `desktop-macos-x64` | Future public Release; current workflow uploads nothing | Intel runner availability/tool presence; no local host; unsigned |
| **LIN** | Ubuntu 24.04+ x64 | `ubuntu-24.04` x64; local Linux x64 | JDK 21, CMake ≥3.24, `cc`, GNU `make`, `bash`, `dpkg-deb`, `fakeroot` (runner), Xvfb/`xauth` for display tests, libmpv system dependencies | `scripts/ci.sh desktop`; `native/bundle.py --target linux-x64`; `scripts/package.sh` → `:composeApp:packageDeb` | JVM/desktop tests configured with project-owned Xvfb; Linux FFmpeg output historically exists; **DEB not built**; no physical audio | DEB under `composeApp/build/compose/binaries/main/deb/`; app image under `.../app/` | Unsigned DEB; no repository signature; `dpkg-deb -x` extraction ≠ system installation | `desktop-linux-x64` | Local `.work` or future public Release | `fakeroot` absent locally; no physical audio; package/launch not run; final SHA not supplied |

**macOS x64 and arm64 are separate rows and separate binary/runtime evidence.** Do not infer x64 from arm64 or vice versa. Linux arm64 is outside this assignment.

### 4.3 CI entrypoints and actual execution levels

The workflow is manual `workflow_dispatch` only (inputs `scope` = `all|desktop|android|ios`, boolean `package_desktop` default false), with `permissions: contents: read`, per-ref concurrency, and **no cache, upload, publish, scheduled trigger, or `pull_request_target`** action. Checkout is pinned to `actions/checkout` SHA `93cb6efe18208431cddfb8368fd83d5badbf9bfd`, `persist-credentials: false`, `set-safe-directory: false`, followed by `scripts/checkout-submodule.sh`.

`scripts/ci.sh` is the only top-level CI entrypoint. It first selects an existing architecture-matching JDK 21 (`JAVA_HOME_21_ARM64`/`JAVA_HOME_21_X64` where present, else `JAVA_HOME`), validates the expected OS/architecture, runs `scripts/bootstrap.sh`, writes metadata-only environment JSON, runs the Python tooling/verification/package-notice/Android-preflight tests, the source-only notice `--check`, and the design-resource checks. Then it dispatches:

| Mode | Actual commands after the common checks | What it can prove | What it cannot prove |
|---|---|---|---|
| `desktop` | Linux: `scripts/with-display.sh scripts/gradle.sh :shared:jvmTest :composeApp:desktopTest`; other hosts: `scripts/gradle.sh :shared:jvmTest :composeApp:desktopTest`; `verification-summary.py --phase desktop --require-tests shared composeApp`; optional `scripts/package.sh` | JVM/desktop tests executed (fresh XML), optional current-OS package | No native helper build/launch, package inspection, installation, signing, or physical audio |
| `android` | SDK 36/build-tools 35.0.0 check; `:shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:lintDebug`; summary `--require-tests` | JVM Android tests, debug APK, lint | No instrumentation, install, emulator/device, background/OS, or physical audio |
| `ios` | Darwin/Xcode check; `:shared:compileTestKotlinIosSimulatorArm64` (or `IosX64`), `:composeApp:compileTestKotlinIosSimulatorArm64`, `:composeApp:linkDebugFrameworkIosArm64`; `build-ios.sh simulator`; `build-ios.sh device`; summary (zero iOS tests by design) | Kotlin test-source compile, framework link, full Swift host compile | No native test execution, simulator/device launch/install, signing, IPA, or physical audio |

Configured matrix controls: desktop `max-parallel: 2`, `timeout-minutes: 60`; iOS `max-parallel: 1`, `timeout-minutes: 90`; all jobs use `fail-fast: false`. Concurrency is per-ref with `cancel-in-progress: true`. These are settings, not evidence of available capacity.

`ci.sh` does **not** run native Python tests, `native/bundle.py`, FFmpeg, the session helper, runtime inventories, package notices with production inventories, package content inspection, installer transactions, `adb install`, `am instrument`, `simctl`, or release publication. Those are Task B steps in §6–§7.

---

## 5. Verification levels and evidence rules

### 5.1 Distinct levels

Use these labels in the checklist and evidence. They are not interchangeable. The checklist also uses `not-run` and `configured` as pre-execution placeholders: `not-run` means no execution/level established; `configured` means an entrypoint/runner is named but no execution is proven.

1. **inspected** — source/configuration/documentation read; no execution claim.
2. **configured** — a workflow/runner/task is named, but no run is proven.
3. **compiled** — a specified compile/link task completed; a framework is not an app.
4. **automated-logic-tested** — named unit/integration tests executed with exact counts and results.
5. **native-executed** — native decoder/engine/helper actually executed on the stated host/guest; null/silent output is labeled.
6. **packaged** — a platform distributable was produced and hashed; not installation or launch.
7. **runtime-smoke-tested** — the packaged app actually started and performed named assertions on the stated host/guest.
8. **installed** — the OS installer transaction completed and the app launched in a clean environment.
9. **physical-device-tested** — exact hardware/route/audio behavior observed; emulator/simulator/null sink cannot be promoted here.

### 5.2 Rules

- A **compile-only** native source is not a native execution.
- An **empty or skipped** test set is not a pass. `verification-summary.py` treats zero discovered tests as zero; `--require-tests` fails only when tests ≤ skipped for that module.
- Test XML must be read from the **exact task result directory** produced by this run, not recursively from prior outputs. Record suite/test/failure/error/skip counts and parse failures.
- The current `verification-summary.py` does **not** bind XML to a command, timestamp, or input hash. Task B must bind them externally: record command, start/end UTC, exit code, log hash, `git status`, input manifest, and preserve a copy of the result directory.
- `package-manifest.py` hashes files found under `composeApp/build/compose/binaries/main`; pre-existing packages can be picked up. Record pre-existing package paths/hashes before the build and accept only newly produced paths, or use the proposed provenance improvement in §9.
- `index.json` from `package-notices.py` always records `binaryRedistributionReady: false`; no flag flip or successful script exit certifies redistribution.
- A null/silent libmpv run proves decode/transport/control, not audibility. A simulator `.app` is not a device app. An unsigned device `.app` is not an IPA.
- `scripts/build-ios.sh` explicitly writes `launched=false`, `installed=false`, `productionSigned=false`, and labels the simulator artifact simulator-only / device artifact not an installable IPA. Preserve those fields.
- Never place credentials, cookies, signed URLs, private captures, or downloaded copyrighted media in logs, evidence, commits, or releases.

### 5.3 Evidence directory and record

Create one ignored run directory per execution:

```text
.work/verification/task-b/<run-id>/
  inputs-before.json          # HEAD, status, pin, schema hashes, host/tool versions
  commands.jsonl              # exact argv, cwd, start/end, exit
  gradle.log
  test-results/               # copied exact JUnit XML for this run
  artifacts/
    <artifact files or copies>
    MANIFEST.sha256
  legal/                      # generated legal files/hashes for this target
  native/                     # FFmpeg/session-helper manifests, ldd/otool/dumpbin output
  packages/
    package-manifest.json     # copy of .work/verification/packages.json
  review.json                 # independent review result
  inputs-after.json
```

Every record must include:

- `runId`, `taskId`, `commit`, `kmpytPin`, `dirtyPaths` (empty for a clean run).
- `os`, `architecture`, `runnerLabel`, `runnerImage`, `toolVersions` (`java -version`, `xcodebuild -version`, `cmake --version`, Android SDK/build-tools, WiX, etc.).
- `command` (argv array; no secrets), `startedAtUtc`, `endedAtUtc`, `exitCode`.
- `testLevel`, `tests {suites,tests,failures,errors,skipped}`, exact `junitPaths`.
- `logPath`, `logSha256`.
- `artifactPath`, `artifactBytes`, `artifactSha256`.
- `generatedLegalHashes`, `inventoryPaths`, `correspondingSourcePaths`.
- `signingStatus`, `installability`, `launchLevel`.
- `reviewer`, `reviewStatus`, `notes`.

The checklist JSON carries this schema and an empty `runs` array. Do not invent timestamps, SHAs, counts, or evidence paths to fill it.

---

## 6. Ordered Task B execution plan

The order below resolves two real dependencies:

1. **Inventory needs local production artifacts.** `scripts/runtime-inventory.init.gradle` refuses to invent missing project artifacts. A prerequisite build/package must exist first.
2. **Notices are packaged resources.** The target-specific legal files must be generated **before** the final package build. Generating them after the package would leave the package with stale notices and invalidate its byte hashes.

Therefore the sequence is **pre-build → inventory → notices → final rebuild → package verification**, not “build once and patch the package.”

Changing a dependency, source pin, native helper, FFmpeg profile, or notice text after the final build invalidates the exact inventory/package hashes and requires the affected steps again. A source text snapshot is not a package.

### 6.1 Gate 0 — accept the final Task A SHA and freeze inputs

Do not start until the lead provides the exact reviewed, merged, validated Task A SHA and the accepted Room/schema-3 evidence.

```sh
git fetch origin feat/veyra-integration
git checkout --detach <FINAL_TASK_A_SHA>
test "$(git rev-parse HEAD)" = "<FINAL_TASK_A_SHA>"

git status --porcelain=v1 -uall
git submodule status -- third_party/kmpYT
actual_pin="$(git -C third_party/kmpYT rev-parse HEAD)"
test "$actual_pin" = "$(tr -d '\r\n' < gradle/kmpyt.commit)"

grep -n "const val VERSION" shared/src/commonMain/kotlin/dev/veyra/data/room/VeyraDatabase.kt
find shared/schemas/dev.veyra.data.room.VeyraDatabase -maxdepth 1 -type f -name '*.json' -print
sha256sum shared/schemas/dev.veyra.data.room.VeyraDatabase/*.json
```

Required result:

- Worktree clean; no untracked production files.
- kmpYT HEAD, gitlink, and `gradle/kmpyt.commit` all equal.
- Room version and exported schema reflect the accepted Task A state (expected version 3; verify, do not assume).
- The accepted Task A migration/regression evidence is recorded with exact paths/hashes.
- The reviewed source-only legal baseline is reconciled (`scripts/package-notices.py --check` passes), or the build-workspace regeneration is explicitly authorized and recorded. See §2.4.
- No source edits occur during Task B except a separately reviewed tooling/legal regeneration PR. Platform lanes do not edit each other or Room.

If any check fails, stop and return the exact mismatch to the lead. Do not “fix” Room or generated legal files inside a platform lane.

### 6.2 Gate 1 — host/tool preflight and one heavy-build slot

Run a minimal read-only preflight on each intended host. Do not install missing tools.

```sh
uname -s; uname -m; nproc
df -B1 .
awk '/MemTotal|MemAvailable|SwapTotal/{print}'
java -version
command -v cmake make cc bash dpkg-deb Xvfb xauth unshare gh strace
```

On Linux, also check the read-only SDK:

```sh
test -f "${ANDROID_HOME:-/opt/android-sdk}/platforms/android-36/android.jar"
test -d "${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0"
test -e /dev/kvm && echo "kvm-present" || echo "kvm-absent"
```

Do **not** run `adb devices`, connect to port 5037, start an emulator, or query unrelated ADB state during preflight. The host’s port 5037 belongs to an unrelated context.

On macOS, record `xcodebuild -version`, `xcrun --sdk iphonesimulator --show-sdk-version`, `cmake --version`, and the exact `DEVELOPER_DIR`. On Windows, record the Visual Studio environment activation, `cl`, `lib`, `dumpbin`, `cmake`, MSYS2 `make`/`bash`, WiX, and JDK 21. If any required tool is absent, stop and report it; do not use `apt`, Homebrew, Chocolatey, `pacman`, `sdkmanager`, or another global installer.

Resource rules:

- At most **one local heavy build** at a time.
- Keep `VEYRA_GRADLE_WORKERS=1`; `scripts/gradle.sh` already uses `--no-daemon --max-workers=1`, `-Pandroid.builder.sdkDownload=false`, and `-Porg.gradle.java.installations.auto-download=false`.
- Heavy local override (one literal Gradle JVM argument):

  ```sh
  scripts/gradle.sh \
    '-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
    <tasks>
  ```

- `composeApp` test workers are bounded by `composeApp/build.gradle.kts` (`maxHeapSize=384m`, `MaxMetaspaceSize=256m`, SerialGC, 2 CPUs, 15-minute timeout). **`shared` test workers are not explicitly bounded in this base**; do not assume the historical private 384m setting is part of the repository. Use a reviewed test init script or add the same block to `shared/build.gradle.kts` in a separate tooling PR before a heavy shared run.
- Do not retry an unchanged failed benchmark/test with relaxed thresholds. Preserve logs and obtain review.
- Disk is an operational risk. Do not clean global caches or other workloads. Remove only task-owned package/app-image output after evidence is archived.

### 6.3 Gate 2 — existing source-only regressions

Run the repository’s existing bounded offline checks before any heavy build. These are not a release gate but catch stale source/legal/tooling state early.

```sh
scripts/dev.sh python3 -B scripts/tests/test_tooling.py
scripts/dev.sh python3 -B scripts/tests/test_verification.py
scripts/dev.sh python3 -B scripts/tests/test_package_notices.py
scripts/dev.sh python3 -B scripts/tests/test_design_resources.py
scripts/dev.sh python3 -B scripts/verify-design-resources.py
scripts/dev.sh python3 -B scripts/package-notices.py --check
scripts/dev.sh python3 -B scripts/tests/test_android_preflight.py
```

`scripts/ci.sh` runs the listed tooling, notice, design and Android-preflight checks automatically. Running them separately gives focused exit codes. Historical counts were 10 tooling, 6 verification, 72 package-notice, 49 design, 85 Android-preflight methods; the checkpoint separately recorded 72 package-notice and 10 tooling passes on earlier inputs. Those do **not** establish real runtime inventories, native closure, or the final SHA. Count the actual output for the final SHA; do not reuse historical numbers.

Native Python tests are **not** part of `ci.sh`; run them in the matching-host lanes:

```sh
scripts/dev.sh python3 -B native/test_bundle.py
scripts/dev.sh python3 -B native/test_owned_process.py
scripts/dev.sh python3 -B native/ffmpeg/test_build.py
```

These are authored archive/scope/receipt/process tests; they do not compile or execute the native production helper/FFmpeg.

### 6.4 Gate 3 — prerequisite build on each target

This is the first build, not the final package. It produces the local artifacts that the inventory task requires. Do not treat its package bytes as final.

Platform commands are in §7. In general:

- Linux: `scripts/ci.sh desktop` then `native/bundle.py --target linux-x64` then `scripts/package.sh` once.
- Windows: `scripts/ci.sh desktop` then `native/bundle.py --target windows-x64` then `scripts/package.sh` once.
- macOS arm64/x64: same with the matching target.
- Android: `scripts/ci.sh android`; optionally `:composeApp:assembleRelease`.
- iOS: `scripts/ci.sh ios`, then `scripts/build-ios.sh simulator` and `device` (or the release configuration).

Record exact commands, logs, exit codes, and **pre-existing package paths** before packaging.

### 6.5 Gate 4 — export exact production inventories

Run only on the matching host, through `scripts/gradle.sh`, after the prerequisite artifacts exist:

```sh
scripts/gradle.sh -I scripts/runtime-inventory.init.gradle \
  :composeApp:exportProductionInventory \
  -Pveyra.inventory.platform=desktop-linux-x64
```

Repeat with exactly one target key:

| Target key | Matching host constraint |
|---|---|
| `desktop-linux-x64` | Linux x64 |
| `desktop-windows-x64` | Windows x64 |
| `desktop-macos-x64` | macOS x64 |
| `desktop-macos-arm64` | macOS arm64 |
| `android-debug` | host with Android project artifacts |
| `android-release` | host with Android project artifacts |
| `ios-arm64` | macOS |
| `ios-simulator-arm64` | macOS arm64 |
| `ios-simulator-x64` | macOS x64 |

Expected output: `.work/verification/runtime-inventory/<platform>.json`.

Copy each inventory to the run evidence directory and hash it before any regeneration. The inventory records exact coordinates, variants, bytes, SHA-256s, POMs, critical build inputs, and the kmpYT pin. It explicitly sets `finalPackageInventory:false`, `nativeClosureVerified:false`, `correspondingSourceVerified:false`, `physicalAudioVerified:false`. **Do not edit these claims.**

If the target is a desktop architecture other than the host, or the host does not match, the exporter must fail. Do not cross-label. Do not substitute a test configuration or another platform’s inventory.

### 6.6 Gate 5 — generate target-specific notices in the disposable workspace

The checked-in legal directory is a **source-only preview**. For a final package, generate notices from the exact target inventory(ies), with reviewed complete-text supplements where needed.

Single target:

```sh
scripts/dev.sh python3 -B scripts/package-notices.py \
  --inventory .work/verification/runtime-inventory/desktop-linux-x64.json \
  --output composeApp/src/commonMain/composeResources/files/legal \
  --require-notices-complete
```

Same-host combined targets (only when all referenced artifacts actually exist on that host):

```sh
scripts/dev.sh python3 -B scripts/package-notices.py \
  --inventory .work/verification/runtime-inventory/android-debug.json \
  --inventory .work/verification/runtime-inventory/android-release.json \
  --output composeApp/src/commonMain/composeResources/files/legal \
  --require-notices-complete
```

Rules:

- Do **not** combine inventories whose artifact paths are absent on the generating host; `package-notices.py` verifies every recorded file.
- The common legal resource directory is shared by all targets. Do not generate notices for target A and then build target B from that working tree. Use a separate runner/worktree per target, or regenerate immediately before each target’s final build.
- Record `git status` and `git diff --stat` after generation. The tracked legal directory will be dirty. Copy the generated files and their hashes to the run evidence directory.
- Do **not** commit the generated files in Task B. After all builds/checks in that workspace are complete, restore the reviewed baseline with `git restore --source=HEAD -- composeApp/src/commonMain/composeResources/files/legal` (or leave the dirty state recorded if the lead explicitly prefers). Never rebuild after restoring without regenerating.
- `--check` with the same inventory(ies) must pass immediately after generation:

  ```sh
  scripts/dev.sh python3 -B scripts/package-notices.py \
    --inventory .work/verification/runtime-inventory/desktop-linux-x64.json \
    --check
  ```

- `--require-notices-complete` returns nonzero on open notice-gap issues. It never certifies binary/source closure. `index.json` remains `binaryRedistributionReady:false`.
- Package-generated legal files may change package bytes. That is why the final build follows generation.

### 6.7 Gate 6 — final rebuild with exact notices

Re-run the **final** package build after notice generation. The exact commands are in the platform briefs. For desktop, `prepareDesktopNativeResources` is a prerequisite and re-verifies receipts; for Android/iOS, the common Compose resources are embedded in the APK/AAB/framework.

Record:

- `git rev-parse HEAD`, pin, and `git status` (the legal directory will be dirty by design).
- The generated legal file hashes.
- Build start/end, command, exit, log hash.
- New artifact path/bytes/SHA-256.
- Whether any prior package path was reused. It must not be accepted unless its bytes match a current build and its source/dependency inputs are unchanged.
- Whether the packaged legal files exactly match the generated hashes. If they do not, the resource pipeline reused stale output: stop, record it, clean only the task-owned build/package outputs, and rebuild; do not claim the package contains the generated notices until the files match.

### 6.8 Gate 7 — native/package inspection and runtime smoke

Run the platform-specific inspection and smoke commands in §7. Verify the **actual package**, not only the build directory:

- The packaged app contains the exact generated legal files.
- The bundled JDK/runtime is present (desktop).
- The packaged libmpv runtime JAR and its extracted native library/CA bundle are present/usable.
- The FFmpeg executable + `.sha256` sidecar + provenance/licenses are present (desktop; session helper where applicable).
- The media-session helper is present on Windows/macOS and absent on Linux (Linux uses dbus-java).
- No test-only JUnit/EPL artifacts or test resources are in the production package.
- The app launches with the bundled runtime, not a developer JDK/mpv/ffmpeg from `PATH`.
- Signing status and installability are recorded honestly.
- If signing/notarization occurs, hash the **signed** artifact, not the unsigned intermediate. Signing changes bytes and can invalidate a previously recorded package hash/inventory; regenerate the final package manifest after signing.

After the final rebuild, re-run the integrated regression matrix against the **same final SHA/pin** (`:shared:jvmTest`, `:composeApp:desktopTest` on the matching host, Android JVM tests, and the native test tasks that are actually configured). Tests do not change package bytes, but a package built from one SHA and tests run against another are not a matched result. If the regression run exposes a source defect, stop: any source fix creates a new SHA and invalidates the affected inventories/notices/packages.

### 6.9 Gate 8 — independent review, retention, and publication decision

The lead independently inspects the complete docs/diff/source references and evidence. Exactly one platform lane owns each result packet. The lead resolves disagreements with evidence.

Retention:

- Copy artifacts/logs/XML/manifests into the ignored evidence directory; hash the manifest.
- Ephemeral CI paths are **not** delivered distribution. The current workflow has no artifact-upload/cache action.
- Publication is a separate gated action. Do not create tags/releases or change workflow permissions during Task B preparation or execution unless the owner separately authorizes it (§10).

---

## 7. Per-platform implementation-ready briefs

Each brief identifies existing entrypoints versus candidate commands. Candidate commands require future task/tool confirmation and, where noted, a separate reviewed implementation. Do not pretend a helper script or test target exists because a command name looks plausible.

| Lane | Exclusive platform ownership | Must not edit |
|---|---|---|
| Lead / integration | Final Task A acceptance, cross-lane review, release/owner decisions, integrated evidence | Room source/tests (delegated); platform lane files without coordination |
| Android | `composeApp/src/androidMain/**`, `composeApp/src/androidInstrumentedTest/**`, `AndroidManifest.xml`, Android Gradle block, Android job | `shared/` Room/source, iOS/desktop lanes |
| iOS | `iosApp/**`, `composeApp/src/iosMain/**`, `composeApp/src/iosTest/**`, iOS targets, `build-ios.sh`, iOS job | `shared/` Room/source, desktop/macOS-native lane |
| Windows | `native/windows/**`, Windows branches in native build files, desktop Windows host adapters, Windows job | iOS/macOS/Linux native sources, Room |
| macOS (arm64/x64) | `native/macos/**`, macOS branches, desktop macOS host adapters, macOS jobs | iOS/Windows/Linux native sources, Room |
| Linux | `native/ffmpeg/**`, `scripts/with-display.sh`, `scripts/linux-namespace.sh`, desktop Linux adapters/job | iOS/Windows/macOS native sources, Room |
| Verification/legal/CI | `scripts/package-notices.py`, `package-manifest.py`, `runtime-inventory.init.gradle`, workflow, evidence manifests | Platform production source unless separately assigned |
| Room engineer | Room source/tests and `docs/verification/room-foundations.md` | Everything in Task B |

### 7.1 Android

**Ownership for later execution:** Android platform lane. Exclusive files if a fix is required: `composeApp/src/androidMain/**`, `composeApp/src/androidInstrumentedTest/**`, `composeApp/src/androidMain/AndroidManifest.xml`, the Android block in `composeApp/build.gradle.kts`, and the `android` job in `.github/workflows/verify.yml`. Do not edit `shared/` Room/source or iOS/desktop lanes.

**Existing entrypoint**

```sh
scripts/ci.sh android
```

This selects an existing architecture-matching JDK 21, validates OS/arch, bootstraps the pinned submodule/wrapper, runs metadata-only reporting, the Python tooling/notice/design/preflight tests, checks the read-only SDK 36/build-tools 35.0.0, then runs:

```sh
scripts/gradle.sh \
  :shared:testDebugUnitTest \
  :composeApp:testDebugUnitTest \
  :composeApp:assembleDebug \
  :composeApp:lintDebug
```

and then:

```sh
scripts/dev.sh python3 -B scripts/verification-summary.py --phase android --require-tests shared composeApp
```

**What this proves:** JVM Android tests ran (if fresh XML has executed methods), the debug APK assembled, lint ran. **It does not prove instrumentation, installation, emulator boot, native playback, background execution, or physical audio.** Historical compile evidence (11 classes/36 methods, 0 executed) is not a substitute.

**Historical artifact contract (from the checkpoint, not current):**

- `composeApp/build/outputs/apk/debug/composeApp-debug.apk` — 23,921,216 bytes; SHA-256 `5ac89f2c2e787f2f1da5aeb45430cc90f1a83185e49d38f72026616cfea27bd4`.
- `composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk` — 486,356 bytes; SHA-256 `cabce892a1db51412f615418b67f60a188dc360646617e137f193cca37cbb377`.
- Both historical APKs share the same debug certificate; these hashes must not be reused as final-SHA evidence.

**Final artifact candidates (verify the actual filename; do not assume):**

- `composeApp/build/outputs/apk/debug/` — debug APK, debug-signed.
- `composeApp/build/outputs/apk/release/` — release APK; **unsigned** with no signing config.
- `composeApp/build/outputs/bundle/release/` — AAB if `:composeApp:bundleRelease` is used; signing/upload key not configured.

**Tests/native execution**

- JVM unit: existing.
- Instrumentation: 14 source files; historical 36 methods compiled (an earlier assembled count was 81 methods across app/test classes), **0 executed**. Do not claim either compiled count executed without actual results. No reviewed runner is authorized in this preparation. The existing `scripts/android-instrumentation.sh --run` deliberately exits 78.
- Emulator: local `/dev/kvm` is absent; the fifth no-guest preflight failed acceleration check exit 8 and never booted a guest, generated a private ADB key, or ran instrumentation. Do not run a sixth preflight or a guest without separate authorization.
- A future standard-runner emulator path must be designed and reviewed. A candidate job would use `ubuntu-24.04`, check `/dev/kvm`, use a project-owned AVD/ADB port/namespace, install the freshly built app/test APKs, and run `androidx.test.runner.AndroidJUnitRunner`. Do not claim this path exists or works until a reviewed implementation runs. If `/dev/kvm` is absent on the runner, do not silently fall back to an unprepared TCG guest; report blocked.
- Physical device: external; no device/hardware evidence here.

**APK inspection (existing tools)**

```sh
sdk="${ANDROID_HOME:-/opt/android-sdk}"
"$sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs <apk>
"$sdk/build-tools/35.0.0/aapt2" dump badging <apk>
unzip -l <apk>
sha256sum <apk>
```

Record the signer certificate, `applicationId`, `versionCode`, `versionName`, min/target SDK, native ABIs, and whether v1/v2/v3 signatures verify. The historical API23 v1/META-INF warnings are a known packaging observation, not a reason to delete legal entries.

**Signing/installability**

- Debug APK: installable on a compatible device/emulator with debug trust; not production.
- Release APK: unsigned; not installable as a production artifact unless signed later. AAB: not store-ready without an upload key and Play configuration.
- No production key/alias/password is in the repository or provided. This is an owner decision if store-ready Android distribution is required.

**Failure/edge cases**

- `lintDebug` historically incomplete; a lint failure is a real result, not a reason to remove lint.
- `adb` port 5037 on the local host belongs to an unrelated context; never query/reuse/kill it. Use only an ephemeral, explicitly owned CI ADB.
- API-level behavior (foreground service restrictions, notification permission, WorkManager quotas) needs an actual guest/device, not JVM fakes.
- Do not add a second downloader or bypass kmpYT to make an instrumentation test pass.
- Cleanup: shut down only owned emulator/ADB processes; preserve APK/test XML/logs; do not remove global SDK/caches.

### 7.2 iOS

**Ownership for later execution:** iOS lane. Exclusive files if a fix is required: `iosApp/**`, `composeApp/src/iosMain/**`, `composeApp/src/iosTest/**`, iOS targets in `composeApp/build.gradle.kts`, `scripts/build-ios.sh`, `scripts/xcode-framework.sh`, and the `ios` workflow job. Do not edit `shared/` Room/source.

**Existing entrypoint**

```sh
DEVELOPER_DIR=/Applications/Xcode_26.0.1.app/Contents/Developer scripts/ci.sh ios
```

`ci.sh ios` requires Darwin, checks the pinned Xcode path, selects `IosSimulatorArm64` on arm64 or `IosX64` on Intel, then runs:

```sh
scripts/gradle.sh \
  ":shared:compileTestKotlin$simulator" \
  ":composeApp:compileTestKotlin$simulator" \
  :composeApp:linkDebugFrameworkIosArm64
scripts/build-ios.sh simulator
scripts/build-ios.sh device
```

`build-ios.sh` builds the **full Swift host** with `xcodebuild`, repository-local DerivedData/SourcePackages, `CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`, and writes `.work/verification/ios-<platform>-<arch>.json` plus a build log. It checks the built executable architecture. The receipt explicitly says `launched=false`, `installed=false`, `productionSigned=false`, `physicalAudioVerified=false`.

**What this proves:** Kotlin test sources compiled, debug framework linked, Swift host compiled for simulator/device. **It does not prove any test ran, the app launched, simulator installation, device installation, signing, or native audio.**

**Artifact paths**

- Simulator: `.work/xcode/DerivedData-simulator-<arch>/Build/Products/<Config>-iphonesimulator/Veyra.app`
- Device: `.work/xcode/DerivedData-device-arm64/Build/Products/<Config>-iphoneos/Veyra.app`
- Result bundle: `.work/xcode/results/<platform>-<arch>-<uuid>.xcresult`
- Receipts: `.work/verification/ios-simulator-<arch>.json`, `.work/verification/ios-device-arm64.json`

`build-ios.sh` defaults to `Debug`. For a final distribution build, set `VEYRA_IOS_CONFIGURATION=Release` and record the exact configuration. Debug-vs-Release is a bytes/optimization difference, not a signing claim.

**Tests/native execution — current gap**

- Scheme `Veyra.xcscheme` has `<Testables/>` empty. `xcodebuild test` has no test target/testable; it cannot certify app tests.
- `composeApp/src/iosTest` contains 5 test files; `shared/src/iosTest` does not exist. CI only compiles the simulator test sources; it does not run them.
- Candidate (not verified, task existence must be confirmed before execution):

  ```sh
  scripts/gradle.sh :shared:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test
  ```

  This would run Kotlin/Native tests in a simulator environment if the tasks exist and the host supports it. Do not claim the task names/behavior until a matching-host run proves them.
- Candidate simulator smoke (not an existing script; requires review):

  ```sh
  xcrun simctl list devices available
  xcrun simctl boot <owned-or-existing-udid>
  xcrun simctl install <udid> <built-Veyra.app>
  xcrun simctl launch <udid> dev.veyra.app
  xcrun simctl io <udid> screenshot <owned-dir>/launch.png
  xcrun simctl terminate <udid> dev.veyra.app
  xcrun simctl shutdown <udid>
  ```

  CoreSimulator service state is host-managed; `scripts/env.sh` scopes homes/caches but does not prove all simulator writes are contained. Review before execution. Do not use this to claim device or physical behavior.

**Inspection**

```sh
plutil -p <app>/Info.plist
lipo -archs <app>/<CFBundleExecutable>
codesign -dv --verbose=4 <app> 2>&1 || true
```

Record the actual `CFBundleIdentifier`, version/build, deployment target, supported orientations, background modes, ATS keys, architectures, and signing state.

**Signing/installability**

- Simulator `.app`: simulator-only; not device-installable.
- Device `.app`: unsigned; **not an installable IPA**.
- No `DEVELOPMENT_TEAM`, signing certificate, provisioning profile, App Store Connect team, or export options are present. Creating an installable IPA/TestFlight/App Store build requires an owner-provided authorized Apple team/certificate/profile and a reviewed export/signing step. Do not purchase, enroll, or guess credentials.
- If no identity exists, Task B may deliver a simulator app plus an unsigned device compile only, with the limitation explicit. That does **not** satisfy an installable iOS distribution gate.

**Failure/edge cases**

- Pinned Xcode path absent: fail; do not select another Xcode implicitly.
- Xcode toolchain/KGP compatibility: Kotlin 2.2.21’s published table includes Xcode 26.0; the pinned 26.0.1 is the nearest patch. Actual host build is the evidence.
- Empty Testables must not be reported as “tests pass.”
- A Swift host compile is not a framework-only success; preserve the distinction.
- Cleanup: shut down owned simulator, terminate owned app; preserve `.work` evidence; do not delete parent Xcode caches or other users’ derived data.

### 7.3 Windows x64

**Ownership for later execution:** Windows lane. Exclusive files if a fix is required: `native/windows/**`, Windows branches in `native/CMakeLists.txt`/`native/bundle.py`/`native/ffmpeg/build.py`, Windows-specific desktop host adapters, and the Windows matrix entry. Do not edit iOS/macOS/Linux native sources or Room.

**Prerequisites to verify before building**

- Existing JDK 21 (`JAVA_HOME_21_X64` on the runner).
- Visual Studio 2022 x64 developer environment: `cl.exe`, `lib.exe`, `dumpbin.exe`.
- CMake ≥3.24.
- A POSIX `bash` and GNU `make` available to `native/ffmpeg/build.py`; the official image documents MSYS2 at `C:\msys64` but **not** GNU make on PATH. Verify `C:\msys64\usr\bin\make.exe` (or another existing make) and a working MSVC+MSYS invocation before claiming the FFmpeg build path.
- WiX Toolset 3.x for `packageMsi`; the image documents WiX 3.14.1.
- No system installation if a tool is missing. If `make` is absent, stop and return the exact blocker; a repository-local portable toolchain acquisition would be a separately reviewed decision, not an ad-hoc `choco`/`pacman` install.

**Existing entrypoint**

```sh
scripts/ci.sh desktop
```

On Windows this runs `:shared:jvmTest :composeApp:desktopTest` (no Xvfb), then the summary. It does not build native helpers or a package.

Native preparation:

```sh
scripts/dev.sh python3 -B native/bundle.py --target windows-x64
```

This runs the pinned FFmpeg build (`native/ffmpeg/build.py --jobs 1`) and the CMake/C++ session helper, then stages resources under:

```text
.work/desktop-native/resources/windows-x64/dev/veyra/desktop/native/windows-x64/
```

Package:

```sh
scripts/package.sh
```

which runs `:composeApp:packageMsi` (via the Compose Gradle task) and `scripts/package-manifest.py`. The manifest scans `composeApp/build/compose/binaries/main` for `.msi`.

**Artifact contract**

- MSI: `composeApp/build/compose/binaries/main/msi/` (discover exact filename; record bytes/SHA-256).
- App image: `composeApp/build/compose/binaries/main/app/Veyra/` (expected Compose layout; verify `Veyra.exe` and bundled runtime).
- Native helper: `.work/native-session/windows-x64/output/veyra-session.dll` (historical contract; verify after build).
- FFmpeg: `.work/native-ffmpeg/output/windows-x64/ffmpeg.exe`, `.sha256`, `provenance.json`, `licenses/`, `corresponding-source/`.

**Inspection**

Use existing Windows tools; candidate commands:

```powershell
Get-FileHash <msi> -Algorithm SHA256
Get-AuthenticodeSignature <msi>
7z l <msi>
7z l <app-image>\Veyra.exe   # or inspect the app image directory
dumpbin /headers <app-image>\...\veyra-session.dll
dumpbin /headers <ffmpeg.exe>
```

Check the PE architecture, imports, embedded runtime/JARs, legal files, FFmpeg/session-helper presence, and absence of test JUnit/EPL content. `7z` and `dumpbin` availability must be confirmed; if absent, record the inspection as blocked rather than guessing.

**Runtime smoke**

Candidate direct packaged launch (from the app image, with external Java/mpv/ffmpeg hidden from PATH):

```powershell
<app-image>\Veyra.exe --self-test <repo>\composeApp\src\desktopTest\resources\dev\veyra\desktop\tone.mp3 `
  --data-dir <owned-data-dir> --silent-audio `
  --verification-result <owned-data-dir>\verification\results\self-test.json
```

The `tone.mp3` fixture is authored CC0 and synthetic; this proves native decode/transport/cleanup with silent output, not audible playback. `--silent-audio` is required if the runner has no audio device. The CLI enforces that the result is a JSON file directly under `<data-dir>/verification/results/`.

**Windows-specific risk: scoped `LOCALAPPDATA`/Chromium**

The completed kmpYT diagnostic run [42252956537](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42252956537) at `69878e6bae664944bc12873998c2ff49f268cccb` succeeded diagnostically but found normal `LocalAppData` lookup failed `80070003`; `DONT_VERIFY` found the repository-scoped home/AppData/Local path missing. Chromium was not launched. This did not establish the sole startup-timeout cause and does not authorize a fix. For Veyra, the app’s explicit data/temp paths and kmpYT’s helper runtime may still encounter this. Treat any Chromium/Node startup failure during Windows smoke as a named risk; do not mark source/helper readiness from the diagnostic. A minimal future fix (creating the scoped `AppData\Local` directory or a documented helper home) must be separately reviewed and tested; do not add it ad hoc in Task B.

**Signing/installability**

- MSI is unsigned; no Authenticode. SmartScreen/trust prompts remain.
- Do not run `msiexec /i` on the shared host; it changes registry/system locations. A disposable, separately authorized Windows VM/runner is the proper installation environment. Administrative extraction (`msiexec /a`) may still use the Windows Installer service; treat it as a candidate requiring review.
- The app image/direct exe smoke is not installer installation.

**Failure/edge cases**

- Missing MSVC/make/WiX: block; do not install.
- The Windows runner may not provide an interactive desktop for UI smoke; `--self-test` is the lower-level fallback, and a failure to render is not a decode failure.
- The Windows Job/process supervisor has authored fake Job-policy tests; they do not replace a real native build/run.
- Cleanup: terminate owned app/helper processes; preserve logs; do not remove MSI/WiX caches or unrelated runner state.

### 7.4 macOS desktop — arm64 and x64 (separate rows)

**Ownership for later execution:** macOS lane, split into arm64 and x64 evidence packets. Exclusive files if a fix is required: `native/macos/**`, macOS branches in `native/CMakeLists.txt`/`native/bundle.py`/`native/ffmpeg/build.py`, macOS-specific desktop host adapters, and the macOS matrix entries. Do not share an artifact between architectures.

**Prerequisites**

- `macos-15` arm64 or `macos-15-intel` x64.
- `DEVELOPER_DIR=/Applications/Xcode_26.0.1.app/Contents/Developer`; verify the directory and `xcodebuild -version`.
- JDK 21; CMake ≥3.24; `clang`, `make`, `bash`; `hdiutil`, `codesign`, `lipo`, `otool`, `plutil`.
- For x64, the Intel runner must actually be available; the workflow configures it, but availability/tool presence is not proven here.

**Existing entrypoint**

```sh
scripts/ci.sh desktop
```

On macOS this runs `:shared:jvmTest :composeApp:desktopTest` without Xvfb, then the summary.

Native preparation:

```sh
scripts/dev.sh python3 -B native/bundle.py --target macos-arm64
# or: --target macos-x64
```

This runs the pinned FFmpeg build and the CMake/ObjC++ session helper with explicit architecture and deployment target 13.0. Staged resources:

```text
.work/desktop-native/resources/macos-arm64/dev/veyra/desktop/native/macos-arm64/
.work/desktop-native/resources/macos-x64/dev/veyra/desktop/native/macos-x64/
```

Package:

```sh
scripts/package.sh
```

which runs `:composeApp:packageDmg` and the manifest.

**Artifact contract**

- DMG: `composeApp/build/compose/binaries/main/dmg/` (discover exact filename).
- App image: `composeApp/build/compose/binaries/main/app/Veyra.app`.
- Session helper: `.work/native-session/macos-arm64/output/libveyra-session.dylib` or the x64 path.
- FFmpeg: `.work/native-ffmpeg/output/macos-arm64/` or `macos-x64/`.

**Inspection**

```sh
hdiutil attach -readonly -nobrowse <dmg>
lipo -archs <mounted>/Veyra.app/Contents/MacOS/Veyra
otool -L <mounted>/Veyra.app/Contents/MacOS/Veyra
find <mounted>/Veyra.app -name 'libveyra-session.dylib' -o -name 'libmpv*.dylib'
codesign -dv --verbose=4 <mounted>/Veyra.app 2>&1 || true
plutil -p <mounted>/Veyra.app/Contents/Info.plist
hdiutil detach <mounted>
```

`hdiutil attach` is a candidate inspection command; it mounts the image (read-only) and may interact with host services. Review before running. Do not claim Gatekeeper acceptance.

**Runtime smoke**

Candidate direct launch from the app image, with external Java/mpv/ffmpeg hidden:

```sh
<app>/Contents/MacOS/Veyra --self-test <repo>/composeApp/src/desktopTest/resources/dev/veyra/desktop/tone.mp3 \
  --data-dir <owned-data-dir> --silent-audio \
  --verification-result <owned-data-dir>/verification/results/self-test.json
```

A GUI `--ui-smoke` is a separate display-session test; the macOS runner may have an Aqua session, but do not assume it. Native session/Now Playing behavior requires an actual desktop session and is not proven by self-test.

**Signing/notarization**

- No Developer ID identity, team, or notarization credentials are present in the repository/configuration. DMG/app will be unsigned or at best ad-hoc; Gatekeeper will block a downloaded unsigned copy.
- If an owner provides an identity, the correct order is: sign the nested `libveyra-session.dylib` and any bundled native executable/libraries, then sign the app bundle, then build/sign the DMG, then notarize/staple. The current `bundle.py` does not sign; this needs a separately reviewed implementation. Never disable library validation or Gatekeeper to make it pass.
- arm64 and x64 signing/notarization are separate artifacts and evidence.

**Failure/edge cases**

- Session helper ObjC++/ARC compile is unproven at the checkpoint; a failure is a real native-source result, not a reason to skip the helper.
- `hdiutil`/DMG layout and bundle metadata must be inspected from the actual DMG, not the build directory.
- Cleanup: detach owned mounts, terminate owned app, preserve DerivedData/evidence; do not remove global Xcode caches.

### 7.5 Linux x64

**Ownership for later execution:** Linux lane. Exclusive files if a fix is required: `native/ffmpeg/**`, Linux/desktop host adapters, `scripts/with-display.sh`, `scripts/linux-namespace.sh`, and the Linux matrix entry. Do not edit Windows/macOS/iOS native sources or Room.

**Prerequisites**

- Existing JDK 21, CMake ≥3.24, `cc`, GNU `make`, `bash`, `dpkg-deb`, `fakeroot` (runner), Xvfb/`xauth` for display tests, D-Bus, and the system libmpv closure.
- Local host lacks `fakeroot`; `dpkg-deb` exists. Do not install `fakeroot`. If `packageDeb` requires it, run packaging on the documented `ubuntu-24.04` runner or return the blocker.
- Local `/dev/kvm` is irrelevant to Linux desktop packaging but blocks Android acceleration.

**Existing entrypoint**

```sh
scripts/ci.sh desktop
```

On Linux this uses `scripts/with-display.sh` with the reviewed private mount/namespace/cookie guard:

```sh
scripts/with-display.sh scripts/gradle.sh :shared:jvmTest :composeApp:desktopTest
```

Then the summary and optional `scripts/package.sh`.

Native preparation:

```sh
scripts/dev.sh python3 -B native/bundle.py --target linux-x64
```

This verifies/rebuilds the pinned FFmpeg output and stages resources under:

```text
.work/desktop-native/resources/linux-x64/dev/veyra/desktop/native/linux-x64/
```

Linux has no C session helper; MPRIS uses the packaged dbus-java adapter and the session bus.

Package:

```sh
scripts/package.sh
```

which runs `:composeApp:packageDeb` and the manifest.

**Artifact contract**

- DEB: `composeApp/build/compose/binaries/main/deb/` (discover exact filename).
- App image: `composeApp/build/compose/binaries/main/app/`.
- FFmpeg: `.work/native-ffmpeg/output/linux-x64/ffmpeg`, `.sha256`, `provenance.json`, `licenses/`, `corresponding-source/`.

**Inspection**

```sh
dpkg-deb -I <deb>
dpkg-deb -c <deb>
dpkg-deb -x <deb> <owned-extract-dir>
file <owned-extract-dir>/.../bin/Veyra
readelf -h <binary>
readelf -d <binary>
ldd <binary>          # only on trusted local binaries; records system dependencies
sha256sum <deb>
```

Check bundled JDK/runtime, libmpv JAR/native libraries, FFmpeg/sidecar, legal files, DEB metadata (`Package`, `Version`, `Architecture`, `Depends`, `Maintainer`, `Section`), and absence of test JUnit/EPL. `dpkg-deb -x` is extraction, not installation.

**Runtime smoke**

Candidate direct app-image launch under the guarded display if UI is needed:

```sh
scripts/with-display.sh <app-image>/bin/Veyra --ui-smoke \
  --data-dir <owned-data-dir> \
  --verification-result <owned-data-dir>/verification/results/ui-smoke.json
```

Lower-level native decode:

```sh
<app-image>/bin/Veyra --self-test <repo>/composeApp/src/desktopTest/resources/dev/veyra/desktop/tone.mp3 \
  --data-dir <owned-data-dir> --silent-audio \
  --verification-result <owned-data-dir>/verification/results/self-test.json
```

The self-test uses the pinned libmpv decoder, sets `tls-verify=yes`, and reports the actual audio output; with `--silent-audio` it uses the null sink. Physical audio is unavailable on this host (no sound card) and remains a separate gate. MPRIS/D-Bus should be tested with a private/owned bus; no session bus is a supported unavailable capability, not a crash.

**Signing/installability**

- DEB is unsigned; no repository metadata/signature.
- `dpkg-deb -x` extraction ≠ `dpkg -i` installation. A real installation changes system paths and requires an external authorized environment.
- Do not run privileged installers on the shared host.

**Failure/edge cases**

- Xvfb without the guard is not permitted. There is no direct `xvfb-run` fallback.
- The Xvfb mount guard is a reviewed helper for this host; it is not proof of GitHub-runner namespace support. If the runner cannot create the namespace/mounts, stop; do not silently use an unguarded display.
- No sound card: null-sink success is not physical audio.
- Cleanup: terminate owned Xvfb/app processes, detach/remove only owned extraction dirs, preserve logs/XML; do not remove global caches.

### 7.6 Cross-platform UI/Arabic/RTL checks

The required English/Arabic/RTL compatibility is checked through existing UI behavior, not redesign.

- Static resources: `scripts/tests/test_design_resources.py` and `scripts/verify-design-resources.py` (run by `ci.sh`).
- Desktop: `:composeApp:desktopTest` includes narrow Arabic large-text, compact navigation label clipping, RTL mirroring, keyboard/focus, and player transport tests. Screenshots are written under `composeApp/build/test-evidence/music/core-shell/...`; preserve and review the fresh PNGs. A test pass does not prove every visual/OS state.
- Android: candidate emulator/device en/ar launch and screenshot; needs the instrumentation/runtime guest path. Not executed here.
- iOS: candidate simulator en/ar launch and screenshot; needs the simulator path. Not executed here.
- No source/UI redesign is authorized to make a check pass. If a real layout defect appears, report it to the lead; do not weaken assertions.

---

## 8. Native runtime, package content, and legal contracts

### 8.1 Desktop native resources

`prepareDesktopNativeResources` is a prerequisite of production run/distributable/native-installer tasks. It runs:

```sh
scripts/dev.sh python3 -B native/bundle.py --target <matching-target>
```

and verifies existing receipts even when Gradle considers outputs up to date (`outputs.upToDateWhen { false }`). The staging contract is:

```text
.work/desktop-native/resources/<target>/dev/veyra/desktop/native/<target>/...
```

`native/bundle.py` requires the target to match the host, runs the pinned FFmpeg build, builds the Windows/macOS session helper, checks architecture/magic, and writes `veyra-native-bundle.json` with exact hashes. It refuses a system/PATH mpv/FFmpeg fallback.

**Important:** the package must carry these exact resources, not merely have them in `.work`. Inspect the actual app image/JAR/package.

### 8.2 libmpv runtime

Desktop uses raw libmpv through JNA and the per-host `org.openani.mediamp:mediamp-mpv-runtime-<os>-<arch>:0.5.3` artifact with `transitive=false`. `DesktopNativeRuntime` pins the whole JAR SHA-256:

| Target | JAR SHA-256 |
|---|---|
| `linux-x64` | `d1169c752a587f8d96311e773cda057ca8563fc5c8cb5088f299d61c95d0a15a` |
| `windows-x64` | `228642a9a2f22ab4e8bf8096d935e1bbc2493310d8a47a4a321aef9378cb6c26` |
| `windows-arm64` | `bc6222e61b13b890665c3a019d9058a10cfb7f7a815a20905536b13b6b340229` |
| `macos-x64` | `7c9bda187618d851cc258378126d1afdb48202561b476e9aaaab45947406edf0` |
| `macos-arm64` | `1ed2def130d751d5c14add0995726670de6426bc4cf868353e963a5c363a656f` |

The app extracts the native library into its private data directory and verifies the whole JAR and each extracted file. The package inspection must verify the runtime JAR is present in the app image/dependency set and the native library is loadable. Linux is GPLv3; Windows/macOS are LGPL2.1+ native builds; corresponding source/notices are separate gates.

### 8.3 FFmpeg

The bundled FFmpeg is a **separate local-remux-only production profile**, not the broader kmpYT CI decoder. Pinned source: official FFmpeg 8.0.1, commit `894da5ca7d742e4429ffb2af534fcda0103ef593`, archive SHA-256 `679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1`, 16,408,559 bytes. Output contract:

```text
.work/native-ffmpeg/output/<platform>/
  ffmpeg[.exe]
  ffmpeg[.exe].sha256
  provenance.json
  licenses/
  corresponding-source/
```

`<platform>` is `linux-x64`, `windows-x64`, `macos-x64`, or `macos-arm64`. The app package includes the executable, sidecar, provenance, and licenses via `native/bundle.py`; the **corresponding-source archive and build materials stay in `.work` and must be included in the separate source-distribution asset set**. Do not claim the app package alone satisfies corresponding-source obligations.

`--verify-output` is an offline, nonexecuting integrity/reuse check. Reusing prior output is allowed only after exact source/profile/recipe/README/owned-process/binary/sidecar/retained-file/official-license verification at the final SHA. An existing receipt is not final package verification.

### 8.4 Media-session helper

Windows/macOS use the C ABI 2 helper in `native/include/veyra_session.h`:

- Windows: real Compose HWND SMTC worker; `veyra-session.dll`.
- macOS: MediaPlayer/CoreAudio; `libveyra-session.dylib`.
- Linux: no C helper; dbus-java MPRIS.

`close` return `1` means cleanup/drain succeeded and the handle is consumed; `0` means incomplete, sticky, and the caller retains handle/callback/library. The library remains loaded for process lifetime. Do not weaken this to make a package/smoke pass. Actual matching-host compile/command/route/cleanup evidence is required; source review and fake Job/process tests are not enough.

### 8.5 Legal/inventory closure

Veyra’s GPL-3.0-or-later choice, the kmpYT MIT permission/official grant, IBM Plex Sans Arabic OFL, and the Mozilla CA bundle MPL/provenance are already resolved. This runbook does not reopen those permission questions; it only tracks exact inventories, notices, corresponding sources, and distribution gates.

Three distinct things:

1. **Source-only `--check`** — retained source/asset bytes; not a production inventory or binary certificate.
2. **Resolved production inventory** — exact Maven/runtime components for one target; still not native/JDK/helper/package closure.
3. **Final package closure** — actual packaged JDK, libmpv/native libraries, FFmpeg, session helper, Node/Chromium delivery, fonts/CA, legal files, corresponding sources, signing/distribution. No current tool certifies this end-to-end.

`index.json` always has `binaryRedistributionReady:false`. `--require-notices-complete` is a notice-gap gate, not legal approval. Complete-text supplements require exact hashes, role, provenance, and explicit `completeTextVerified`; a filename/POM URL is not a license.

**Provisioned Node/Chromium helpers** are downloaded on demand, not Maven components. Their manifests/installed-file selections/source mappings are separate. The current Chromium snapshot contains only a 401-byte sample credits page (SHA-256 `c2a338c4f1f5f5e19d3020a25f8dc47e394ba4fed1ec2d8dc5b2a915170e02bc`), not complete third-party terms. `package-notices.py --helper-inventory` can record reviewed helper inventories, but no complete enrollment exists; the generator’s fake-fixture tests are not real upstream evidence. Do not declare Chromium notice/source closure or fabricate a receipt. This is a release/legal decision, not a code shortcut.

**Packaged JDK**: jpackage includes a runtime. Inventory the actual app image’s JDK/runtime files and notices; do not infer from the build JDK. **Test-only JUnit EPL** must not ship; `package-notices.py` rejects test components in production inventories.

---

## 9. Known gaps, blockers, and minimal future implementation proposals

Priorities: **P0** blocks a truthful final build/native verification/distribution; **P1** is a material evidence/reproducibility improvement; **P2** is operational polish.

| ID | Priority | Gap / evidence | Minimal future change (files) | Tests / acceptance |
|---|---|---|---|---|
| G-01 | P0 | Clean `ec97925` fails `package-notices.py --check`; three generated legal files stale (see §2.4) | Explicit reviewed regeneration of `composeApp/src/commonMain/composeResources/files/legal/{NOTICE.md,THIRD_PARTY_NOTICES.txt,index.json}` from current source, or a lead-approved Task A commit containing it. No production code change. | `package-notices.py --check` exit 0; `test_package_notices.py` passes; diff limited to generated legal files; clean checkout reproduces |
| G-02 | P0 | Final Task A SHA/schema-3 not supplied | None in this branch; lead supplies exact SHA + schema/migration evidence | `VeyraDatabase.VERSION==3`, `3.json` hash, migration tests accepted; no schema-2 packaging |
| G-03 | P0 | Android instrumentation not executed; `--run` exits 78; no reviewed runner; local no KVM | New reviewed `scripts/android-instrumented-tests.sh` (or equivalent) + workflow job using a disposable emulator/guest; reuse/adapt Stage 0 containment, private ADB port, owned AVD, no host ADB 5037. Do not install KVM/SDK. | Actual 36-method suite executed with exact XML/results; `apksigner`/`aapt2`; no guest claim from compile; owner/lead authorization recorded |
| G-04 | P0 | iOS scheme Testables empty; no simulator test/launch | Option A: add/confirm Gradle `iosSimulatorArm64Test` execution. Option B: add a minimal reviewed simulator smoke script (`scripts/ios-simulator-smoke.sh`) using `simctl`; do not add a full XCTest target unless needed. Files: `scripts/`, `.github/workflows/verify.yml`, scheme only if a test target is added. | Kotlin tests executed (counts) and/or simulator app installed/launched with owned screenshots; `launched=true` evidence; no device/IPA claim |
| G-05 | P0 | iOS installable IPA/signing absent | Owner provides authorized Apple team/cert/profile; add reviewed signing/export step (project settings + script/workflow), no portal mutation. | Signed/exported IPA verified, or explicit owner decision to ship simulator-only/unsigned and keep distribution gate incomplete |
| G-06 | P0 | Android release signing/store artifacts absent | Owner provides release keystore/alias/passwords via approved secret mechanism; add signing config only after review; never commit keys. | `apksigner` production cert; release APK/AAB verified; or explicit debug-only/unsigned decision |
| G-07 | P0 | Windows native toolchain (GNU make/MSVC env) unproven; FFmpeg/session helper not built | Preflight first. If a tool is missing, stop. A repository-local portable tool acquisition needs separate review; no `choco`/`pacman`. | `native/bundle.py --target windows-x64` exit 0; `dumpbin` architecture; `ffmpeg --verify-output`; MSI built |
| G-08 | P0 | Windows scoped `LOCALAPPDATA`/Chromium known-folder risk; diagnostic did not launch browser | Minimal reviewed fix creating the scoped `AppData\Local`/helper home (likely `scripts/env.sh` or desktop runtime), with a focused test; do not claim the old diagnostic was the sole cause. | Browser/helper startup test in scoped Windows environment; no live source required for packaging; failure classified |
| G-09 | P0 | Windows Authenticode and macOS Developer ID/notarization absent; unsigned downloads blocked | Owner decision/provisioning. If provided, add nested-signing order (helper/FFmpeg then app then DMG), notarize/staple; never disable Gatekeeper. | `codesign --verify --deep --strict`, `spctl`/notary acceptance, or explicit unsigned limitation |
| G-10 | P0 | Final artifact retention/distribution absent; workflow has no upload/cache; ephemeral runner paths vanish | Add a separately authorized release job with `contents:write` on a trusted ref that uploads to a public GitHub Release (no Actions artifact storage), or an owner-approved alternative. Do not change permissions in Task B without authorization. | Release assets uploaded, downloaded independently, SHA-256 matched; source/notices included; no secrets/media |
| G-11 | P0 | Chromium/Node helper legal closure incomplete; sample credits only | Legal/release decision; if helpers are delivered, build exact helper inventories + original source/terms + complete-set review. No fake receipts. | `--helper-inventory` accepted with reviewed evidence; or explicit non-redistribution/on-demand limitation documented |
| G-12 | P0 | Physical audio/crossfade and live-source release holds remain | Separate owner/hardware work, outside Task B. Do not relax. | Physical evidence on each supported platform; not mockable |
| G-13 | P1 | `package-manifest.py` scans pre-existing packages; no source/commit binding | Add `--expected`/`--build-started-at`/`--source-sha` and record commit/pin/task; or operator binds pre/post scans. Files: `scripts/package-manifest.py`, new focused test; add to `ci.sh` only if reviewed. | Stale package rejected; new artifact bound to commit/command; test covers pre-existing file |
| G-14 | P1 | `verification-summary.py` does not bind XML to inputs/time; iOS always zero | Add optional run marker/timestamp/expected task filter; do not change counts semantics. For iOS, keep zero and require separate native evidence. | Stale XML rejected; exact run XML accepted; tests in `test_verification.py` |
| G-15 | P1 | `shared` test workers not bounded in repo | Add `tasks.withType<Test>` bounds in `shared/build.gradle.kts` mirroring composeApp (384m/256m/SerialGC/2CPU), or a reviewed init script. | Shared suite runs within bounds; no OOM; counts recorded |
| G-16 | P1 | `scripts/package.sh` uses `packageDeb/Dmg/Msi`, not `packageRelease*`; final config intent undocumented | Decide and document the final task variant; if changing, update `scripts/package.sh` + tests. Do not silently switch. | Artifact reflects intended release variant; hashes/inventory refreshed |
| G-17 | P1 | Installer transactions not tested (dpkg/msiexec/dmg/Gatekeeper) | Add a separately authorized clean VM/device harness or document external verification. Do not run privileged installers on shared host. | Installer install/launch/rollback evidence in disposable environment |
| G-18 | P1 | Android targetSdk36 native packaging/page-size and v1/META-INF warnings not fully audited | Add APK/ABI/page-size inspection and document warnings; do not delete legal entries. | APK loads on target API; warnings classified; no silent removal |
| G-19 | P2 | Cross-platform en/ar/RTL screenshots absent for Android/iOS final build | Add simulator/emulator screenshot steps after G-03/G-04; review existing UI behavior, no redesign. | en/ar light/dark/large-text screenshots reviewed; no assertion weakening |

### 9.1 Genuine owner decisions only

Ask only where code/evidence cannot decide:

1. **Apple signing identity/team/profile** for an installable iOS IPA/TestFlight/App Store build — or explicit acceptance of simulator-only/unsigned device artifacts with the distribution gate left incomplete.
2. **Android production signing key/alias/passwords** for a store-ready APK/AAB — or explicit debug-only/unsigned limitation.
3. **Windows Authenticode and macOS Developer ID/notarization credentials** — or explicit unsigned/SmartScreen/Gatekeeper limitation.
4. **Authorization to publish** a tag/public GitHub Release and add a trusted `contents:write` release job (zero-purchase public Release assets), or an owner-approved retrieval alternative.
5. **External clean installation environment** (Windows VM, macOS machine, Ubuntu/device) for actual installer/device verification, or explicit acceptance that only extraction/launch smoke is available.
6. **Helper/legal strategy** for on-demand Node/Chromium delivery (enroll reviewed notices/source or document why they are not redistributed) and corresponding-source delivery for libmpv/FFmpeg/JDK.
7. **Physical-audio/live-source holds** — separate hardware/source work; not Task B.

Do not ask broad preference questions that the source/commands already answer.

---

## 10. Artifact retention and distribution

Current state: `.github/workflows/verify.yml` has `permissions: contents: read` and **no** artifact-upload/cache/publish action. JSON/logs/packages remain on the ephemeral runner. A path printed by a job is not a delivered distribution.

Zero-purchase documented option for a later authorized action:

- Publish final assets directly from a trusted build job to a **public GitHub Release** using the workflow’s scoped `GITHUB_TOKEN` (`contents: write` only in that job/event). Do not use `pull_request_target` or run untrusted code with publication credentials.
- Release assets must be below GitHub’s per-asset limit (research recorded 2 GiB) and within the 1,000-asset limit; no total-size/bandwidth assumption beyond the public-release documentation. Do not use Actions artifact storage, caches, cloud uploads, or paid storage.
- Include: per-platform package, SHA-256 manifest, legal notice bundle, FFmpeg/libmpv/JDK corresponding-source/build materials where applicable, and a verification summary with exact commits/checks. Exclude copyrighted media, cookies, signed URLs, private captures, and listening data.
- Publication is a separate gated action. Do not create a tag/release or change workflow permissions in Task B without owner authorization.

If no publication/retrieval path is authorized, Task B can still produce and verify local Linux/Android artifacts in `.work`, but Windows/macOS/iOS artifacts cannot be retained across ephemeral runners. That is a distribution blocker, not a packaging success.

---

## 11. Resource coordination and failure handling

- **One local heavy build slot.** Remote jobs may run in parallel only within the configured matrix limits (`desktop max-parallel: 2`, `ios max-parallel: 1`). Do not run local Gradle/native builds concurrently with a shared heavy workload.
- `scripts/gradle.sh` is the only Gradle entrypoint; it uses `--no-daemon --max-workers=1`, disables SDK/JDK auto-download, and scopes homes/caches/temp under `.work`.
- `scripts/dev.sh` is required for Python/native/build subprocesses. Do not source `scripts/env.sh` into the shell used for authenticated `gh`; it would change `HOME`/Git configuration.
- Current measured memory/disk are volatile. Re-inventory before execution. Do not kill unrelated processes or delete caches/evidence.
- Compose test workers: 384m heap/256m metaspace/SerialGC/2CPU/15min. Shared workers: not bounded in repo (G-15). Kotlin/Native/Xcode linking has its own memory/time cost; serialize.
- A killed daemon, SIGTERM, kernel OOM, empty test set, skipped task, stale XML/package, compile-only native source, or authored fixture is **not** a pass. Preserve the failed evidence; do not overwrite it with a retry.
- No unchanged failed benchmark/test retry with relaxed thresholds. Any retry requires a reviewed cause and the same assertions.
- Cleanup is selective: inspect task-owned outputs, preserve latest useful evidence, remove only owned build/package/extraction directories. Never delete global caches or parent evidence.

---

## 12. Validation of this preparation

Performed for this documentation-only PR:

- JSON parseability and internal consistency of `docs/verification/five-platform-execution-checklist.json` (schema fields, unique task IDs, dependency references, no dangling refs).
- Every repository path/link/task/CLI name referenced here checked against the actual source, or explicitly labeled candidate/proposed/absent.
- `git diff --check` for whitespace/errors.
- `git status` and complete diff inspection before commit.
- Submodule gitlink unchanged.
- No generated legal files, build outputs, secrets, or unrelated changes staged.
- Offline source-only notice check was run read-only and **failed** at `ec97925` for the stale generated legal resources recorded in §2.4; this is a real blocker finding, not a test of the docs.
- No Gradle/native/package/device/workflow operation was run.

Acceptance of this preparation is documentation review, not Task B completion. Task B remains unexecuted until the lead supplies the final Task A SHA and separately authorizes the build/native/distribution work.

---

## Appendix A — Existing command catalog (source-verified)

| Purpose | Command | Exists in repo? | Notes |
|---|---|---|---|
| Bootstrap/pin check | `scripts/bootstrap.sh` | yes | no fetch/install |
| Scoped submodule init | `scripts/checkout-submodule.sh` | yes | public kmpYT only |
| Scoped Gradle | `scripts/gradle.sh <tasks>` | yes | `--no-daemon --max-workers=1` |
| Scoped command | `scripts/dev.sh <command>` | yes | project-owned homes/caches/temp |
| CI desktop | `scripts/ci.sh desktop [--package]` | yes | Linux uses `with-display.sh`; other hosts direct |
| CI Android | `scripts/ci.sh android` | yes | JVM tests + debug APK + lint |
| CI iOS | `scripts/ci.sh ios` | yes | compile only; no simulator/device run |
| Package current OS | `scripts/package.sh` | yes | Deb/Dmg/Msi only, current host |
| Package manifest | `scripts/package-manifest.py` | yes | scans output root; stale-file caveat |
| iOS build | `scripts/build-ios.sh simulator\|device` | yes | unsigned; `launched=false` |
| Xcode framework build | `scripts/xcode-framework.sh` | yes | called by Xcode build phase |
| Inventory export | `scripts/gradle.sh -I scripts/runtime-inventory.init.gradle :composeApp:exportProductionInventory -Pveyra.inventory.platform=<key>` | yes | matching host; local artifacts first |
| Notices | `scripts/package-notices.py …` | yes | `--check`, `--require-notices-complete`, `--helper-inventory` |
| Summary | `scripts/verification-summary.py --phase …` | yes | XML counts; no input binding |
| Display tests | `scripts/with-display.sh <command>` | yes | Linux guard; no direct Xvfb fallback |
| Desktop native resources | `scripts/dev.sh python3 -B native/bundle.py --target <host>` | yes | FFmpeg + session helper |
| FFmpeg verify | `scripts/dev.sh python3 -B native/ffmpeg/build.py --verify-output` | yes | offline/nonexecuting |
| Native Python tests | `native/test_bundle.py`, `native/test_owned_process.py`, `native/ffmpeg/test_build.py` | yes | not in `ci.sh` |
| Android Stage 0 | `scripts/android-instrumentation.sh --preflight` | yes | no guest; `--run` exits 78 |
| Android instrumentation | candidate `adb install`/`am instrument` or reviewed runner | **no authorized script** | do not claim |

## Appendix B — Candidate commands requiring future confirmation

These are **not existing repository entrypoints** and must not be reported as executed:

- `:composeApp:assembleRelease`, `:composeApp:bundleRelease` — task names are conventional for AGP; confirm at final SHA.
- `:shared:iosSimulatorArm64Test`, `:composeApp:iosSimulatorArm64Test` — KMP task existence/simulator behavior unconfirmed; use `:iosX64Test` on Intel.
- `xcrun simctl` install/launch/screenshot — no reviewed script; CoreSimulator containment review required.
- Windows `dumpbin`/`7z`/`Get-AuthenticodeSignature` inspection — tools documented on the image but not locally verified; confirm on the runner.
- `hdiutil attach`/`codesign`/`spctl` inspection — macOS-only; `spctl` will reject unsigned artifacts.
- `msiexec /a`, `dpkg -i`, `/Applications` copy, Gatekeeper launch — installation-level actions requiring an external authorized environment.
- Public GitHub Release upload — separately authorized workflow/release action.

## Appendix C — Checklist JSON schema summary

The companion file `docs/verification/five-platform-execution-checklist.json` contains:

- `schemaVersion`, `kind`, `document`, `preparedFrom` (commit/branch/pin), `preparationExecution`, `preparedAtUtc`, `readyToExecute`, `dependencyGate`.
- `statusVocabulary` with definitions for `inspected`, `configured`, `blocked`, `not-run`, `executed-passed`, `executed-failed`, `excluded`.
- `verificationLevels` (1–9 as in §5.1).
- `platforms` with runner labels, architectures, and artifact contracts.
- `tasks` with unique IDs, `platform`, `title`, `category`, `inScope`, `status`, `verificationLevel`, `dependsOn`, `existingEntrypoint`, `commands`, `candidateCommands`, `artifacts`, `blockers`, `acceptance`, `evidenceRunIds`.
- `evidenceRecordSchema` and `runs: []` to be populated later with exact commit/pin/os/arch/runner/image/tool versions/command/timestamps/exit/test counts/log/XML/artifact bytes/SHA-256/generated-legal hashes/corresponding-source paths/signing/installability/reviewer.
- `ownerDecisions` and `proposedChanges` as concise references to §9.

Statuses are initialized honestly: no build/package/native task is marked executed; no timestamp/SHA/count/path is invented. A task with a missing prerequisite is `blocked`; a ready-but-not-attempted task is `not-run`; a source-only observation is `inspected`; a configured but unproven entrypoint is `configured`; out-of-scope holds are `excluded`.

**Final dependency gate:** Task B execution starts only from the lead’s precise reviewed/merged/validated Task A integration SHA, with schema-3/download-recovery evidence accepted. Preparation of this runbook does not complete Task B or any platform release gate.
