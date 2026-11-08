# PR #4 combined regression integration — review candidate

Status: **integration candidate on the accepted Task A parent; DRAFT, not acceptance.**
This branch composes six already-reviewed, non-overlapping regression-port candidates onto
`t3/typed-room-download-recovery` head `6d09ed5fa24f39e97a6d7b9689e9e30a008e1857`.
It is not a GitHub merge/approval of PRs #6/#8/#9/#10/#11/#13 and it does not complete Task A.
The three documented typed-production gaps from PR #8 remain deliberately red; this PR does not
edit production code or weaken their assertions.

## Accepted launch, base and ancestry

- Provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`. No independent
  runtime/backend/variant attestation is exposed by this harness; no substitution was made and no
  subagents were created.
- Isolated worktree: `/root/.t3/worktrees/Veyra/t3-085e3d56`.
- Integration base `feat/veyra-integration` `9b1b0479a2748012988d378d1f631cf5a50fff79` is an
  ancestor of the Task A parent; the local branch was fast-forwarded without rewrite to exact
  parent `6d09ed5fa24f39e97a6d7b9689e9e30a008e1857`.
- New branch: `t3/pr4-regression-integration` (local and remote). Parent PR #4 remains draft and
  unmodified.
- Before composition, `/root/projects/Veyra/.work/delegation/resume-20261108/inputs.json` was
  rehashed against `6d09ed5`: `head` matched, all **508** tracked regular-file SHA-256 values
  matched, and the one `third_party/kmpYT` record is the mode-160000 gitlink
  `c0a08090fe7dbd76e20216559b8184ad2cccde63`. No missing or extra path.
- kmpYT gitlink, `gradle/kmpyt.commit` and the actual submodule HEAD are all
  `c0a08090fe7dbd76e20216559b8184ad2cccde63`; submodule status is clean. Schema-3 export is
  SHA-256 `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` with 92 entities.
  No dependency, schema, migration, FK/RowWriter, build, workflow, pin or legal file changed.

## Composed source revisions

Every candidate was verified as a descendant of the original parent
`67594669c6cd51e63ca772b281d4fc56144cafdd`, inspected as a complete diff, and merged with an
ordinary non-force merge. Each branch head is docs-only relative to its last tested code source
(proof in the last column). Candidate commits and their evidence were not rewritten.

| Slice | PR | Candidate head | Tested source | Doc-only successor proof | Merge commit |
|---|---|---|---|---|---|
| scheduling | #6 | `8b12d2d68111e1861c145ca9d854677564201aa7` | `e7c41a48428dfb087529746801653ebd28f55e77` | `e7c41a4..8b12d2d`: +report only | `e6404669a16acb09f260db79a9de2167b0594f8f` |
| maintenance | #8 | `66fc49a8492048ef8e770e453e128beaabe52bce` | `f2f9091e8d31f8c277df4f2c3ec0cad9dbe6755f` | `f2f9091..66fc49a`: +report only | `184a1309e5e710a973ad16568e83d3f53fc65c36` |
| Room/prepared | #9 | `5b3571e82f3275df69015fac227e27d08b43548b` | `88f21fa119d7f65f31d456e52c355d38b436c940` | `88f21fa..5b3571e`: +report/+inventory corrections | `9be27e8eacb31450e2f2dab78a891a7a287626e8` |
| integrity/recovery | #13 | `e73158aa8fd52cfc4f8c72c88b37d4e9b90d073a` | `7d553607647c3a2ef5985d8f813697fb27129fdf` | `7d55360..e73158a`: +report only | `8e369d793ad338e3cbe93dbbad49a9626867038a` |
| desktop ownership | #10 | `fc3acbd6b6b904a49d365a0abf9355c3781405bb` | `1f2ab2a7036fd748dced3a918a0f39243f222c26` | `1f2ab2a..fc3acbd`: +report only | `c5cc227717fa064f14c9f8a2c99edd6923aba9cc` |
| desktop retained | #11 | `33626b20f5ba585e4807ddfbced9804b95e8286c` | `d30a77fafaa8cae67b325b3e3d3c937fff106867` | `d30a77f..33626b2`: +report only | `09cca32a4839d3c4a68141334edf9ced9d2fdbe9` |
| fixture repair | — | `53392cfe369267969c62d01ed6dc67e9a4b29d55` | same commit | — | ordinary commit |

The composed candidate delta over `6d09ed5` is **30 paths**: 18 shared test/support files,
5 desktop tests, and 7 candidate verification documents (six slice reports plus the corrected
second-correction mapping). This report is an additional docs-only path. The composed owned-file
manifest (30 SHA-256 values, excluding this report) is
`594413434ad0b96636dae33e2c89475d588adf50ec8e291ee5af8a3be9bb3bd5`. Production diff over the
parent is empty. `AppContainer.kt`, `AppContainerIntegrationTest.kt`,
`AppContainerSchema2Fixture.kt` and `pr4-app-composition-ports.md` are byte-identical to
`6d09ed5`; all **39** `AppContainerIntegrationTest` methods remain present.

## Original-method coverage

No candidate method was removed, renamed away, skipped, ignored or made conditional. The full
fresh shared suite declares and executes 97 suites / **1163** methods. The parent declared 96
suites / 1162 methods; the only net addition is the real-Room
`RoomAcquisitionRestoreTest.blockedValidVideoOutcomeSurvivesRealRoomCloseAndReopenWithoutBindingOrSourceWork`.
Every parent shared method remains declared; per-file parent→candidate `@Test` counts are equal
for every rewritten class (27 scheduling, 10 policy, 14 cleanup, 4 hash cancellation, 13
maintenance, 8 RoomLibraryStore, 5 RoomWriteVerification, 28 AcquisitionCoordinator, 9
YouTubePreparedDownloadGateway, 9 integrity, 11 ownership, 8 recovery, 3 member-retry, 5
retained-lifetime).

The committed candidate reports hold the full per-method tables:

- `docs/verification/pr4-download-scheduling-ports.md` — 15 ported legacy scheduling methods plus
  the 12 already-typed methods, with real Room retry/wake/lease/slot/cancel/cleanup assertions.
- `docs/verification/pr4-download-maintenance-ports.md` — 4 cleanup, 3 hash-cancellation and 8
  maintenance methods; EOF/source-close/hash cancellation, cleanup provenance/drain, manual vs
  scheduled validation and retry/backoff controls retained; three typed-production reds recorded.
- `docs/verification/pr4-room-regression-ports.md` and
  `docs/verification/pr4-second-correction-mapping.md` — real two-owner asset id/URI UNIQUE
  conflicts with exact failure and full rollback/reopen, saved-only catalogue edits/Undo owner
  continuity, migration compatibility, readback/revision/FK invariants, and prepared-download
  single-consumption/issuer/representation semantics.
- `docs/verification/pr4-download-integrity-recovery-ports.md` — 9 integrity, 5 ownership, 4
  recovery and 3 member-retry methods; checkpoint/validator/integrity/finalization/producer/
  generation/retention and cold-reopen assertions retained, including the immutable-history
  corruption probes.
- `docs/verification/pr4-desktop-download-ownership-tests.md` — real `DesktopAudioEngine` actor,
  `DesktopNativeReaderEpoch` and `DesktopAssetValidator` control flow around authored fake
  handles: native-before-resource ordering, held/failed destruction, sticky deletion veto,
  alias/reader counts, late/rejected ownership, cancellation, epochs, shutdown joins and validator
  reader release.
- `docs/verification/pr4-desktop-retained-lifetime-ports.md` — all five retained host methods with
  real app/host/process-lock lifetime, cancelled close callers, single consumption and explicit
  retries; DOWNLOAD resources stay forbidden and the typed local validator remains an authored
  non-audio fake.

Methods that already passed on `6759466` were preserved rather than reclassified as legacy-only.
Typed cases use production commands over the same `transactionOwner`; migration cases open the
real exported old schema through production migration; no schema-3 rows are fabricated by a
fixture. Authored material is limited to source answers, non-audio bytes and native/gate answers.

## Integration fixture repair (one file)

`shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadSchedulingRoomTest.kt` —
`failedCleanupBackoffPersistenceStillDefersLocallyAndRetainsAllDurableReferences` was the only
composition failure not among PR #8's three documented typed-production gaps.

Causal diagnosis: the candidate's fault accepted **any** Room transaction whose proposed graph had
a non-null failure and threw a raw `IOException`. In full-suite ordering the observed plan could
exist before the real cleanup attempt reached that transaction, so the candidate asserted a
transient `OwnedWork` registry count; under other timing the same raw exception was (correctly)
classified as an unknown typed-unconfirmed failure and latched the fail-closed admission. Both
outcomes were preserved as red attempts. The repair, confined to this owned test:

| Old candidate assertion | New assertion | Why it is not weaker |
|---|---|---|
| Fault throws raw `IOException` when any proposed row has `failure != null` | Fault throws `VeyraException(STORAGE_IO)` only when the proposed row has `STORAGE_IO`, `cleanupPending` and the published `now + 5 s` recheck, and counts the rollback | Targets the exact real Room error/backoff transaction and proves it executed; no synthetic graph row |
| `assertIs<OwnedWork>(idleDecision())` | `assertIs<Deferred>` with `nextLocalEpochMs == now + 5 s` and `hasDueWork == false`; the rollback counter proves the real cleanup owner ran | Restores the method's original Deferred safety claim; the in-memory finalizer can already be consumed while the durable cleanup reservation remains |
| `scheduler.close()` succeeds | `assertFailsWith<VeyraException>` with exact `STORAGE_IO`, then positive dependency release | Typed unconfirmed storage is surfaced, never swallowed as a green close |
| Cold host reads persisted `nextRetryEpochMs` and starts DURABLE | Advance the published 5 s and use the real foreground-resume path (`start(USER_SESSION)` + `resumePending()`) before the cold cleanup completes | A closed typed host leaves the removal `NEEDS_FOREGROUND`; the asserted durable reservation, owned bytes, exact once-only cleanup, no prepare/transfer/query and final delete all remain |

The repaired method passes focused and full-suite execution. This is a test-fixture correction,
not a production repair; it does not alter the three PR #8 gaps below.

## Fresh validation at frozen commit `53392cfe369267969c62d01ed6dc67e9a4b29d55`

All Gradle/KSP/test commands held the shared flock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock` through process
completion and evidence archival, used `scripts/gradle.sh --offline`, the literal
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
one Test fork with 384 MiB heap / 256 MiB metaspace / SerialGC / 2 CPUs, and an ignored init that
forces execution and fresh XML.

| Run | Command scope | Exit | Actual result |
|---|---|---:|---|
| `final-shared-compile-1` | `--rerun-tasks :shared:compileKotlinJvm :shared:compileTestKotlinJvm` | 0 | `BUILD SUCCESSFUL`; both compile tasks executed, no UP-TO-DATE |
| `final-shared-full-1` | `:shared:jvmTest` | 1 | **1163 tests / 97 suites / 3 failures / 0 errors / 0 skipped** |
| `final-desktop-compile-1` | `--rerun-tasks :composeApp:compileKotlinDesktop :composeApp:compileTestKotlinDesktop` | 1 | **kernel OOM killed the Gradle daemon**; no test claim; preserved |
| `final-desktop-compile-2` | same desktop compile without `--rerun-tasks` | 1 | **kernel OOM killed the Gradle daemon again**; no test claim; preserved |
| `final-desktop-compile-3` | `:composeApp:compileKotlinDesktop :composeApp:compileTestKotlinDesktop` | 0 | `BUILD SUCCESSFUL`; both desktop compile tasks executed |
| `final-desktop-focused-1` | `:composeApp:desktopTest` with the eight class selectors | 0 | **108 tests / 8 suites / 0 failures / 0 errors / 0 skipped** |

Shared run `final-shared-full-1`: log SHA-256
`ac6bb799a4f63fc7291cea1f846e7c0e021271242e28fb2deb9daeee2597a031`; before/after input
manifests are identical (`c594f80d1313b3ef72d64e4463c0296036eafac36ed402056655a0d1ebaa2cc9`),
status clean, positive owned-process drain. A source/XML inventory check reports 97 declared
classes / 1163 declared methods against 97 XML suites / 1163 executed methods, with no missing,
extra or duplicate method. The three reds are exactly the PR #8 documented typed-production gaps:

1. `DownloadMaintenanceRoomTest.cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback`
   — expired cleanup does not retry after foreground resume (`NEEDS_FOREGROUND`, maintenance
   `CLEANUP`, `cleanupPending=true`, `BACKGROUND_RESTRICTED download_executor_needs_foreground`).
2. `DownloadMaintenanceRoomTest.cancelledDispatcherReturnAfterRealMaintenanceCommitReleasesBeforeGateAcquisition`
   — cancellation after the real maintenance COMMIT still acquires/closes one gate (`closes=1`
   where the retained contract requires zero).
3. `DownloadMaintenanceRoomTest.deniedCompletedCheckKeepsDurableCompleteButPresentsNeedsForegroundUntilAnAllowedRecheck`
   — foreground resume leaves the durable COMPLETE job `QUEUED` and never rechecks it
   (`validations=0`).

The other 1160 methods pass, including all six candidate slices and the repaired scheduling
method. These reds are production defects outside this task's writable scope; they are preserved,
not relabelled.

### Preserved red / aborted attempts

| Attempt | Result | Cause |
|---|---|---|
| `shared-full-1` | 1163 / 4 failures | fourth was the race diagnosed above (candidate `OwnedWork` transient) |
| `shared-full-2` | 1163 / 4 failures | repaired fault predicate but no causal gate; rollback had not happened |
| `shared-full-3` | 1163 / 6 failures | fixture transition plus two unrelated order-sensitive intermittents (ownership/recovery); preserved, not accepted |
| `shared-full-4` | 1163 / 4 failures | typed close surfaced `STORAGE_IO`; cold retry still read a cleared backoff |
| `diag-failed-cleanup-single-*`, `diag-scheduling-class-*` | focused diagnostics | established single/class green versus full-suite timing; no production edit |
| `final-desktop-compile-1` | exit 1 | kernel OOM killed Gradle daemon 18723 (dmesg retained); owned Kotlin daemon terminated; no test or code claim |
| `final-desktop-compile-2` | exit 1 | kernel OOM killed Gradle daemon 31893 (dmesg retained) while other agents' JVMs were resident; no test or code claim |
| `final-desktop-compile-3` / `final-desktop-focused-1` | 0 | admitted through `/usr/local/bin/vps-build` after host memory recovered; both compile tasks executed and 108/108 focused tests passed |

No result XML was copied from an old run; every accepted run has a fresh unique XML directory,
log hash and before/after manifest under
`.work/verification/pr4-regression-integration/runs/`.

## Desktop focused verification

`final-desktop-compile-3` at `53392cf` executed both `:composeApp:compileKotlinDesktop` and
`:composeApp:compileTestKotlinDesktop`; log SHA-256
`38559494394c35490b1b11b70bfba18e28a6ad10fe8eae4908cb3f57f2a54a6d`, exit 0, positive drain.
`final-desktop-focused-1` then selected exactly the eight headless classes with separate
`--tests` arguments; the compile tasks were legitimately UP-TO-DATE against those same bytes:

| Suite | Executed |
|---|---:|
| `dev.veyra.desktop.audio.DesktopDownloadReaderEpochTest` | 6 |
| `dev.veyra.desktop.audio.DesktopDownloadReaderOwnershipTest` | 18 |
| `dev.veyra.desktop.audio.DesktopDownloadValidationOwnershipTest` | 10 |
| `dev.veyra.desktop.DesktopRetainedLifetimeTest` | 5 |
| `dev.veyra.app.AppContainerIntegrationTest` | 39 |
| `dev.veyra.desktop.DesktopLifetimeTest` | 7 |
| `dev.veyra.desktop.DesktopPlatformServicesOwnershipTest` | 18 |
| `dev.veyra.desktop.DesktopStartupJobsTest` | 5 |
| **Total** | **108 tests / 8 suites / 0 failures / 0 errors / 0 skipped** |

The focused run exited 0 with log SHA-256
`3b775e11616c27e9b3800f6dba58f9eb753507fcbfa0348878e23bc50f78bed4`, clean before/after input
manifests, positive owned-JVM drain and no other class selected. It ran through
`/usr/local/bin/vps-build` (job `d2826720142d`, normal 4 GiB / 2 CPU slots) with the existing Veyra
project lock passed as `--project-lock`; the evidence runner detected the gate-held lock and did
not re-acquire it. No GUI, native self-test or live-source class was selected or executed.
These are headless fake-deck/fake-resource tests; they do not prove actual native destruction,
process death or audio output.

## Limits and non-claims

- This is a test/regression integration only. No production, schema, migration, dependency, pin,
  workflow, build, legal, AppContainer or UI file changed.
- Room/SQLite transactions, files, OS path/slot leases and process locks are real. Source answers,
  bytes, native validation and execution allowances are authored non-audio fakes. No live source,
  authentication, codec, libmpv/native audio, physical device, emulator/simulator, Android/iOS
  execution, packaging, signing or distribution is claimed.
- The desktop ownership/retained tests are headless fake-handle tests; they do not prove actual
  native destruction, process death or audible output.
- Task A is not declared accepted; parent PR #4 remains draft and the three production gaps above
  remain open. Task B remains gated on an accepted Task A.
