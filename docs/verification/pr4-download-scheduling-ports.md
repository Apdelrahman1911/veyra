# PR #4 scheduling Room ports and native-drain diagnosis

Status: **scoped slice implemented and verified; PR kept draft for lead review/integration.**
This slice does not claim a green full shared suite, native/iOS/platform execution, playable audio,
live-source access or release readiness. The parent PR #4 remains draft; the lead owns the combined
shared-suite run and final integration.

## Accepted launch and starting state

- Provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`. No independent
  runtime/backend attestation is exposed by this harness; the accepted launch configuration is the
  only record.
- Supplied isolated worktree `/root/.t3/worktrees/Veyra/t3-d731f1ef`; parent integration
  `feat/veyra-integration` `9b1b0479a2748012988d378d1f631cf5a50fff79`; parent PR #4 branch
  `t3/typed-room-download-recovery` at `67594669c6cd51e63ca772b281d4fc56144cafdd`.
- Starting branch was a clean fast-forward of that parent; remote
  `t3/pr4-download-scheduling-ports` head after the import commit was `ba073b6…`, then the port
  commit `e7c41a4…`.
- Read `AGENTS.md`, `docs/STATE.md`, `docs/PLAN.md`, `docs/DECISIONS.md`,
  `docs/verification/room-download-recovery.md` and
  `docs/verification/pr4-second-correction-mapping.md` before editing.

## Preserved packet verification (fail-closed)

The lead packet `/root/projects/Veyra/.work/delegation/room-download-20261107/shared-ports-resplit-20261107/`
was verified before applying anything:

| Artifact | SHA-256 |
|---|---|
| `snapshot-receipt.json` | `a584e34427b1201457dd8b8edd7904921044bffc0116c4da6b785dcf7a43dacf` |
| `scheduling-candidate.patch` | `4e6de598e3e08eb0080bae24739d64976c10472d6eada5b3f03258fcc2e37804` |
| `scheduling-brief.md` | `9ebb3a375bc4cb95a5cdd064bdc599c7e94a6708827aaf65cceec9d652f7f9d8` |
| `DownloadSchedulingRoomTest.kt.snapshot` | `2a23978fd8bd2006af76e0641dc6dac70ffcfe9d175a102ebfb63bab770e45d2` |
| `DownloadAcquisitionRoomTestSupport.kt.snapshot` | `c7a2fabfe80ad28d674c685802c9bd1f8cd77add7a2c34972bf46cf92330de93` |

- 507 records checked: **505 base records** (504 tracked regular files plus the
  `third_party/kmpYT` gitlink `c0a08090fe7dbd76e20216559b8184ad2cccde63`) matched clean
  `6759466`; the remaining **2 records** are the candidate postimages and matched both frozen
  `.snapshot` files and the one imported patch application.
- The gitlink was handled as mode `160000`; the submodule HEAD and cleanliness were recorded from
  the real checkout, never by recursively copying private/untracked upstream files.
- `scheduling-candidate.patch` was applied **once** to the isolated checkout. No previous
  `t3-98140935` tree was used as a moving source.
- Import commit `ba073b6211b37d03783e767b457de7c3117576c5` contains the two exact postimages.
  The candidate was a starting point only and did not compile: `UpdateStandaloneDownloadIntent`
  names `retainCompletedAsset`, not `retain`. That was corrected before any class result was
  claimed.

## Changed paths (exclusive scope)

| Path | Reason |
|---|---|
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadSchedulingRoomTest.kt` | Ported all 15 legacy methods to typed canonical setup/commands; corrected candidate setup, retry deadlines, drain seam and assertions. |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadAcquisitionRoomTestSupport.kt` | Added real-file-system plumbing, live canonical admission token, real pending/failing transfer + foreign claim helpers, bounded failure diagnostics, read-fault/after-read/commit seams and safe release of authored held claims. |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadPolicyRoomTest.kt` | EX34 only: restored the published 15 s bounded fake-clock completion bound from the candidate’s 20 s. |
| `docs/verification/pr4-download-scheduling-ports.md` | This report. |

No production, schema, pin, dependency, workflow, build, legal or AppContainer file changed.
`third_party/kmpYT` and `gradle/kmpyt.commit` remain
`c0a08090fe7dbd76e20216559b8184ad2cccde63`; schema 3 remains
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 tables, identity
`a51a13de7a6c06c5b3ff5f9890c7ddd2`). The submodule had no local changes.

## Method ports — original → actual setup/command → retained assertions

The original bodies are in `6759466:shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadSchedulingRoomTest.kt`.
Every original method name remains; no assertion was deleted, skipped or ignored. The table
records the typed seam that replaces each forbidden legacy snapshot seed.

| Original method | New setup / actual command seam | Retained assertions (and typed additions) |
|---|---|---|
| `schedulingOnlyBootstrapReadsIntentWithoutAnyMediaFileSourceValidatorOrGateWork` | A real completed typed job, one actual failing transfer producing a 24 h network retry, and `prepareFinalizationSchema3` history whose local recheck fails with a typed `STORAGE_IO`; setup host closed. A genuinely separate cold host runs `initializeScheduling()` + `schedulingPlan()`. | Plan has published local 5 s and network 86 400 000 ms deadlines, no due work and `Deferred`; historical canonical row is `COMPLETE` while presentation is truthfully `VERIFYING`; cold host has zero validator calls, gate acquires, coverage checks, selector requests, prepare/transfer calls and file callback accesses; setup’s only recheck/allowance are asserted separately; both legacy gateways unused. The legacy cleanup-pending seed is now covered by the dedicated failed-cleanup method rather than a fabricated legacy row. |
| `durableActivationDoesNotScanHistoricalAssetsAndUserUpgradeIsMonotonic` | Real complete typed history, then a cold host with a held real validator. `start(DURABLE_WORK)` followed by `start(USER_SESSION)` and a second DURABLE start. | DURABLE is `Deferred` and reads **zero historical source bytes**, zero validations, gate acquires and coverage checks; history presents `VERIFYING`. USER_SESSION enters the real held validator, is `OwnedWork`, DURABLE does not downgrade it; release completes, one validation, at least one allowance, and a later historical source read. The metadata-only retired-staging directory listing is the existing reconciler’s sweep, not an asset integrity read; it is deliberately not counted as a source scan. |
| `cancellingIndependentBackgroundDrainCannotSkipJoiningPendingNativeAcquisition` | A real pending `DownloadExecutionGate.acquire` before prepare/transfer, released by an authored native answer only after the cancelled drain caller is proven still owed. | Drain accepts, caller is cancelled, `join` still does not complete (`withTimeoutOrNull(100)`), caller-finished stays false, host is `OwnedWork`; the native allowance close is observed before the drain joins; drain completes cancelled exactly once, job becomes `NEEDS_FOREGROUND` with no lease, host later `Deferred`; exactly one gate acquire, no prepare/transfer/re-query. See the hang diagnosis below for why the original cancellation-of-acquire shape was replaced. |
| `unknownOrCancelledCheckpointStillJoinsOwnersButNeverAcknowledgesOrSilentlyRetriesForegroundResume` | Real transfer completed, then the executor’s post-COMMIT `finishPhysicalDownloadAttempt` hook throws an authored `CancellationException` and `AssertionError`; real gate close barrier. | Both natures join native/allowance ownership before the drain returns, never acknowledge, and return the same unknown throwable class; no finish attempt is invented before close; after close the allowance/native flags are true, lease is gone, slot is free, the first sticky `BACKGROUND_RESTRICTED` failure is visible on the plan and every `resumePending`/drain/idle retry rejects with that exact same failure; exactly one transfer. The typed path has one real native allowance owner rather than a separate fabricated `PlaybackResource`. |
| `backgroundDrainJoinsAlreadyClosingOwnersWithoutReopeningOrCheckpointingClosedCoordinator` | Real transfer held, real gate-close barrier, real commit counter; `close()` races a second independent drain. | `schedulingPlan()` throws after close; the drain stays incomplete while close waits, no extra commit occurs before release; after release the drain returns a null checkpoint failure, close completes, the job lease is gone, `closeStore` sees positive dependency release, a post-close drain still returns null and pause is accepted. |
| `unrelatedPlaybackProgressAndPresentationChangesDoNotStarveIdleRetirement` | Real 24 h network-retry job; three separate real Room writers update download presentation rows and insert/update a real `media_progress` row for an unrelated catalogue media, plus legal settings/navigation updates. | Candidate plan is unchanged through all unrelated rows; revision advances; the same latest ticket still retires. Typed scheduling intentionally zeroes download progress/bytes from its scheduling inputs, so those edits cannot starve retirement. |
| `anotherRoomConnectionCanInvalidateTicketEvenWhenItsInvalidationHintIsUnavailable` | Writer connection produces a 24 h retry through a real failing transfer; a separate reader connection suppresses invalidation hints; the reader plans. A second real failing transfer on a different canonical recording publishes a 2 s retry. | Reader remains deliberately stale at the original deadline; `confirmIdle` rejects the old ticket; a fresh authoritative Room read publishes the earlier network deadline and a different plan key. No row is fabricated. |
| `enqueueResumeCancelAndPolicyChangesDuringWakeAckInvalidateOldTicket` | Five independent real fixtures, one for each mutation (enqueue, resume, cancel, offline-policy change, pause). Each has three real 24 h retries and a live wake ticket. | During a held ACK each single mutation makes `confirmIdle` false and never invokes the retire callback. No shared manufactured reset is used between cases. |
| `connectedDueWorkAndHostEpochChangesInvalidateDeferredTickets` | Accepted durable queue intent under a disconnected network; real network-state flow and host pause/resume. | Disconnected queue is `Deferred`; connecting makes the old ticket unable to retire; disconnecting restores a new `Deferred`; pause/resume generation change invalidates that ticket. |
| `dueCrossingInsideFreshReadCannotConfirmEarlierFutureTicket` | Real 2 s network retry produced by an actual failing transfer; separate reader with suppressed hints; a real Room read barrier. | Fresh read is held, the injected clock crosses the published deadline, the old ticket cannot confirm/retire after the read is released. |
| `secondTimeCheckRejectsCrossingAfterTheFreshPlanWasBuilt` | Real 2 s retry; a crossing clock that returns the published deadline from the second armed read; new host plans first. | Planned deadline equals the original 2 s; after arming, `confirmIdle` rejects without invoking retire and exactly two clock reads occurred after arming. |
| `refusedPhysicalTransferSlotHasBoundedLocalBackoffWithoutAnyGateOrSourceClaim` | Real queued job, parallelism 1, a real OS-held transfer slot in a separate probe namespace. | Six bounded local deadlines (5/10/20/40/60/60 s), no due work/network ticket, `Deferred`, zero gate/attempt/prepare/transfer/selector work; the held slot is never released by failed contenders; after release and exact clock advance the real completion runs with one gate and one transfer. |
| `rolledBackAcquisitionClaimReleasesFilesAndDefersInsteadOfKeepingAnEmptyOwnerAlive` | Real canonical acquisition claim whose Room commit is rolled back by a transform fault when the active attempt appears. | The original queued job object is unchanged, no attempt for the job, real slot is free, no gate/selector/prepare/transfer work, a local 5 s recheck defers and the latest ticket can retire; after clearing the fault and advancing exactly 5 s the real transfer completes once. |
| `failedExpiredLeaseRecoveryIsDeferredWithoutForgingAcknowledgementOrStartingSource` | Real authored foreign claim (canonical claim/select/heartbeat) under real slot+path locks, released as process loss; lease expires; a recovery commit fault then rolls back. | Original lease owner remains visible, the exact job is unchanged, its attempt remains unfinished, no gate/selector/prepare/transfer work, host defers locally; after clearing and advancing exactly 5 s the real transfer completes once. |
| `failedCleanupBackoffPersistenceStillDefersLocallyAndRetainsAllDurableReferences` | Real completed typed asset; canonical removable intent via `updateStandaloneDownloadIntent` + `reconcilePhysicalDownloadIntent`; the first actual file delete fails, then the error/backoff persistence commit is rolled back. | One real deletion attempt, bounded 5 s local plan, `OwnedWork` because the REAL failed cleanup owner is retained (not legacy idle), cleanup/remove flags, asset and file remain. Explicit close consumes that exact owner without inventing success; the retained reservation and file still exist. A separate cold host retries after the persisted deadline and only its actual deletion clears cleanup and the asset, with no new prepare/transfer/selector work. |

The twelve methods that were already typed and green at `6759466` remain in the class and all pass:
`futureTwentyFourHourRetryRemainsPendingRetiresAndReopensAtItsOriginalDeadline`,
`reconnectDoesNotBypassTwoSecondRetryAndUnknownBlocksOnlyNetworkClaims`,
`unactivatedCancelAndRemovePersistIntentButCannotStartMaintenanceBeforeCoverageActivation`,
`acquisitionIsOwnedBeforeGateAckThroughActualResourceAndAllowanceClose`,
`maintenanceIsOwnedBeforeGateAckAndAfterRoomCompletionUntilNativeCloseReturns`,
`independentBackgroundDrainWaitsForResourceAllowanceAndRoomFinishEvenAfterNativeLeasesReachZero`,
`failedBackgroundCheckpointAcknowledgesOnlyAfterAcquisitionAndMaintenanceOwnersDrain`,
`resumeDuringSuspendedFailingCheckpointIsRejectedWithoutAdmittingAnUncapturedOwnerOrReplayingLater`,
`ticketsAreExactLatestInstancesAndEveryConfirmationAttemptIsSingleUse`,
`freshReadFailureThrowsInsteadOfCertifyingCachedIdleAndConsumesTicket`,
`refusedFinalizationFileLeaseDefersLocallyBeforeValidationOrNativeAcquisition`,
`rolledBackMaintenanceClaimKeepsIntentAndHasLocalCooldownBeforeRetry`.

## Native-drain hang: evidence and resolution

The lead’s run008 (`t3-98140935/.work/verification/pr4-shared-regression-ports/run-008-scheduling-class`)
is not a green class or a complete XML result. It started 14:47 UTC, last log progress was 14:50,
worker 23720 was dumped at 15:57 and terminated at 15:58 with exit143; its XML set is empty. Its
main thread was parked in `runBlocking`/`executorSchema3Case` →
`DownloadSchedulingRoomTest.cancellingIndependentBackgroundDrainCannotSkipJoiningPendingNativeAcquisition`.
Six earlier partial test events are retained and not misreported as a final XML result.

A reproduced test-seam deadlock was captured live from this worktree at 2026-11-07 20:02:59 UTC:
`.work/verification/pr4-download-scheduling-ports/hang-dump-023/worker-thread-dump.txt`
(SHA-256 `b4ac68f348c93d4f851b4a33580960d9795b86d47a1d4ffc483a1f3bde9eac07`). The `Test worker`
was parked in `runBlocking` for 2075 s, and the dispatcher workers were still consuming CPU. The
cause of that hang is test-specific: the old test gate called `awaitCancellation()` inside a real
`DownloadExecutionGate.acquire`, while production invokes gate acquisition under
`withContext(NonCancellable)` (`DownloadPhysicalRuntime.kt:1132`). The active executor job therefore
cannot deliver cancellation into `awaitCancellation`; the test’s `finally`/barrier never fires, the
body timeout cannot unwind the `coroutineScope`, and `coroutineScope` waits forever for the
`NonCancellable` `drainForBackground` caller. This is not evidence of a production ownership or
drain defect; the test must supply the real native answer instead of asking cancellation to end a
non-cancellable native acquisition.

The final method therefore holds a real pending gate acquisition and releases it explicitly via
`nativeAnswer`, then observes the real allowance close (`nativeClosed`) before joining the
`NonCancellable` drain. It keeps the original claims: cancellation of the drain caller must not
discard the accepted join, and no false drain/close receipt is asserted. The candidate’s
transfer-based shape was also green in run015/016, but the run008 trace alone does not prove why
that earlier candidate hung; we do not claim a production root cause from it. To prevent an
unbounded orphaned worker from holding the build slot, the task-owned runner (`.work` only, not
committed build configuration) dumps `jstack` and kills only processes whose cwd/command line is
this worktree. A failed watchdog attempt is preserved, never counted as a test result.

## EX34 correction

`DownloadPolicyRoomTest.twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit` keeps
the real Room owners, 7 completions, global 1/2/4 limits, 4→1 revocation, one transfer/owner query
per subject, no duplicate transfer, normal slot revocation/unwind and the published local recheck
(5/10/20/40/60 s). The fake-clock completion loop now uses the prior **15 s** bound; the only
change is `withTimeout(20_000)` → `withTimeout(15_000)`. No production scheduler policy changed.

## Fixture-helper changes and direct consumers

`DownloadAcquisitionRoomTestSupport.kt` now provides: real `FileSystem` injection; suppression of
only invalidation hints on a real Room connection; a live canonical COMMITTED admission-token check
in `enqueue`; actual failing-transfer `runtimeRetry`; real foreign claim/crash setup under OS
slot/path locks; release-on-close for authored held claims; bounded state/job diagnostics; and
before-commit/after-read seams. Every helper command is canonical Room; no legacy setter is
weakened.

Direct consumers discovered with `rg acquisitionDownloadRoomCase/DownloadAcquisitionRoomFixture`
and run after the final helper change (run036, 15 suites, 157 tests, 0 failures/errors/skips):
`DownloadSchedulingRoomTest`, `DownloadPolicyRoomTest`, `DownloadBatchRecoveryRoomTest`,
`DownloadFreshOriginUseRoomTest`, `DownloadFreshOriginUseSchema2RoomTest`,
`DownloadMemberRetryRoomTest`, `DownloadMemberRetryLifetimeRoomTest`,
`DownloadPendingLifetimeRoomTest`, `DownloadPhysicalCoordinatorRoomTest`,
`DownloadPlaybackValidationRoomTest`, `DownloadSchema2AdoptionRoomTest`,
`DownloadValidationReaderRoomTest`, `PodcastRestartOfflineTest`, `AppSourceResolverRoomTest`,
`AppSourceResolverSchema3GuardTest`.

Known other-scope baseline-red classes were not rerun here and are not claimed: `RoomLibraryStoreTest`
(2), `DownloadCleanupRoomTest` (4), `DownloadHashCancellationRoomTest` (3),
`DownloadIntegrityRoomTest` (9), `DownloadMaintenanceRoomTest` (8), `DownloadOwnershipRoomTest` (5),
`DownloadRecoveryRoomTest` (4). They belong to sibling slices and remain part of the lead’s combined
suite gate.

## Verification evidence

All test runs used `scripts/gradle.sh`, the shared
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`, a fresh evidence
directory, one fork, 384 MiB test heap / 256 MiB metaspace, SerialGC, 2 CPUs and the literal
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`.
The initializer is `.work/verification/pr4-download-scheduling-ports/bounded-tests.init.gradle`.
XML was written fresh under each run directory; source was clean during run036.

| Run | Command (focused) | Exit | Actual XML result | Evidence / SHA-256 |
|---|---|---:|---|---|
| 001 | `:shared:compileTestKotlinJvm` | 1 | candidate did not compile (`retain` name); no test claim | `run-001-compile/` log `3d4b1cfd…` |
| 002 | `:shared:compileTestKotlinJvm` | 0 | corrected candidate compiles | `run-002-compile/` |
| 004,007,008,011,019,021,023,025,029 | focused intermediate attempts | 1/abort | retained failure/abort evidence, no green claim | named run dirs; run023 live jstack preserved separately |
| 016 | `:shared:jvmTest --tests DownloadSchedulingRoomTest` | 0 | **27 tests, 0/0/0** | `run-016-full-scheduling/` log `f235a230…` |
| 017 | `:shared:jvmTest --tests DownloadPolicyRoomTest` | 0 | **10 tests, 0/0/0** | `run-017-policy/` log `48be55f0…` |
| 018 | 14 direct-consumer classes | 0 | **147 tests, 0/0/0, 14 suites** | `run-018-helper-consumers/` log `702cc1ad…` |
| 031 | Scheduling + Policy | 0 | **37 tests, 0/0/0** | `run-031-two-classes/` log `d1ba9c7d…` |
| 032 | 15 direct consumers (pre-final cleanup) | 0 | **157 tests, 0/0/0, 15 suites** | `run-032-final-consumers/` log `c19bf72b…` |
| 033 | `:shared:compileTestKotlinJvm` | 0 | actual compilation on the final test sources | `run-033-compile/` |
| 036 | `:shared:compileTestKotlinJvm :shared:jvmTest` + 15 classes, clean `e7c41a4` | 0 | **157 tests, 0 failures, 0 errors, 0 skipped, 15 suites**; fresh XML | `run-036-final-full/` log `6770fa78…`; Scheduling XML `3aa2be75…`; Policy XML `92b2488a…`; pre/post input manifests show 506/506 identical tracked files, no drift and clean status |
| 037 | `:shared:compileTestKotlinJvm --rerun-tasks` | 0 | actual compilation rerun on clean `e7c41a4` | `run-037-final-compile-rerun/` log `5a648c99…` |

Run036 input freeze: HEAD `e7c41a48428dfb087529746801653ebd28f55e77`, tracked inputs 506/506
unchanged before→after, `git status` empty both times, submodule
`c0a08090fe7dbd76e20216559b8184ad2cccde63` clean, schema SHA unchanged. The final test-source
hashes are:

- `DownloadSchedulingRoomTest.kt` `bbe75c5c1b11b187c470b79cba616c984f3f22f781d2933edc8a53b40f6361ac`
- `DownloadAcquisitionRoomTestSupport.kt` `280dc75865fafea768654472ef1ded604a85a63d527f97233db439f173cebe96`
- `DownloadPolicyRoomTest.kt` `b8dff1e16f7968dccfe2ec79a12947183e725f81bbe523d6ea65b00284352b24`

`run-036` is the tested code SHA `e7c41a4`. Any later docs-only commit is distinguished from it;
this report does not relabel a docs revision as a newly executed test revision.

## Honest limitations and remaining work

- This slice verifies only the assigned scheduling/policy classes and the direct consumers above.
  It does **not** claim the full shared suite, the AppContainer suite, native/iOS/Xcode, a simulator
  or physical device, real audio, codec/crossfade behavior, live source/authentication or release
  artifacts.
- Source selection, transfers, validators, allowances and native/audio answers remain authored test
  fakes over non-audio bytes; Room/SQLite/files/OS locks are real.
- iOS/Xcode is unavailable locally. Task B owns native/platform verification after the lead accepts
  the Task A integration SHA. The full combined shared suite is the lead’s gate.
- The `t3-98140935` checkout, lead evidence and packet remain read-only; the interrupted run008
  directory is preserved exactly as a failed/incomplete run.

## Remote and review

- Remote branch: `t3/pr4-download-scheduling-ports`; PR base `t3/typed-room-download-recovery`.
- PR: https://github.com/Apdelrahman1911/veyra/pull/6 (draft; lead review/integration only; no
  merge/watch/workflow dispatch).
- No tests deleted/skipped/ignored; no guard weakened; no production/schema/pin/workflow/legal edit.
