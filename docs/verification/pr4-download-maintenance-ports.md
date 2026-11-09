# PR #4 maintenance-port wave — download cleanup, hash cancellation and maintenance

Status: **implementation complete and honestly blocked (draft).** `:shared:compileTestKotlinJvm`
passes. The three owned classes execute **31 tests: 28 passed, 3 failed, 0 errors, 0 skipped**.
The three remaining reds are not fixture/seed failures: each retained original safety claim is
now exercised on real Room/files/OS leases and exposes a concrete typed-production gap. Per the
wave contract no production source was edited; the exact minimal repairs are listed below for
the lead's decision. No merge, watch, workflow dispatch or release claim.

## Accepted launch

- Provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`. No independent
  runtime/backend/variant attestation is exposed by the harness; this accepted configuration is
  the only launch record. No substitution was made.
- Worktree `/root/.t3/worktrees/Veyra/t3-58fd8a76`; branch `t3/pr4-download-maintenance-ports`.
- Base `t3/typed-room-download-recovery` = `67594669c6cd51e63ca772b281d4fc56144cafdd`
  (fetched and verified as the exact remote head; my initial HEAD `9b1b047` was an ancestor and
  the local branch was fast-forwarded, no reset/rebase/force). Parent PR remains
  https://github.com/Apdelrahman1911/veyra/pull/4 (draft, unmodified).
- Stacked PR https://github.com/Apdelrahman1911/veyra/pull/8 (draft, base parent branch).

## Changed paths (only these)

- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadCleanupRoomTest.kt` (4 methods ported)
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadHashCancellationRoomTest.kt` (3)
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadMaintenanceRoomTest.kt` (8)
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadMaintenancePortTestSupport.kt` (new,
  purpose-specific real-Room post-COMMIT/before-COMMIT hook, acquisition-fixture join/await
  helpers and the shared sticky-blocked assertion; no shared original was modified)

No production, commonTest, AppContainer, schema, pin, build, workflow, legal or generated file
was changed. The kmpYT gitlink and `gradle/kmpyt.commit` remain
`c0a08090fe7dbd76e20216559b8184ad2cccde63`; schema-3 export remains
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`.

## Original method → typed seam → retained assertions

All legacy `seedFinalization`/`seedComplete`/`store.update` seeding is replaced by real typed
publication or the canonical interrupted-finalization fixture. No expected failure was changed
to `INVALID_STATE`, no canonical graph was written by a fixture, and no test was skipped.

| Original method | New setup / command seam | Retained assertions |
|---|---|---|
| `maintenanceAllowanceFailureRetainsFinalizationFilesAndRoomClaimThroughIndependentDrain` | `prepareFinalizationSchema3` journal; real maintenance claim; gate close fails once after the validation starts; independent `drainForBackground` | Finalization folder + Room maintenance lease/attempt retained after the failed allowance close; both path locks still OS-held; slot is free; close retries the exact failed owner once (2 closes), releases Room/paths and keeps the journal; source/gateway never used. |
| `maintenanceFileCloseFailureKeepsRoomClaimWithoutRepeatingConsumedAllowance` | Journal + real `pause(id)` after validation starts; real path-lease close fails once before OS consumption | `pause` reports the typed containment failure (`download_owner_drain_unconfirmed`), cleanup owner retained; fewer than two allowance closes (the successful allowance close is not repeated); Room claim and journal retained; the exact failed lock remains OS-held and is consumed by the explicit close retry. |
| `maintenanceClaimFailureBeforeAndAfterCommitKeepsFileAndRoomOwnershipUntilExplicitClose` | New real-Room `DownloadMaintenancePortPersistence`: transform failure (pre-COMMIT rollback) vs post-COMMIT return failure; one path close fails once | Pre-COMMIT keeps the original unowned room row; post-COMMIT keeps the exact returned maintenance lease; journal unchanged; failed path lock still held; explicit close retries the path release, finishes/releases Room, keeps the journal; no approval/gate/validation/source work. |
| `partialMaintenancePathAcquisitionFailureRetainsRealFirstLeaseBeforeRoomClaim` | Real ordered path acquisition refuses the second lock; first lock's close fails once | Exact canonical job unchanged (no Room claim); journal retained; first real path lock still held after the aggregate failure; explicit close consumes it; no gate/validation/source. |
| `cancelledSourceCloseCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen` | Real complete/publish, offline setting, close/reopen; `BlockingHashReadFileSystem` blocks `Source.close` of the final asset | Close waits for the physical source close; COMPLETE/asset retained with lease until unwind; no native validator call before unwind; bytes unchanged; reopen validates the completed file once and keeps the asset; no transfer/source work. |
| `cancelledEofReadCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen` | Same, boundary = real EOF read | Same retained hash/close/offline assertions; cancellation at EOF is observed before the validator. |
| `closeDuringARealCompletedHashReadWaitsForSourceUnwindAndReopenCanCheckWithoutAcquisition` | Same, boundary = data read | Close remains blocked through the actual read/source close; no validation/publication before unwind; offline cold reopen performs the one real check without acquisition. |
| `completedMaintenanceCloseAndQueuedCallbackCannotSuspendAFreshTransfer` | Real interrupted journal, real recovery publication, then a real blocked transfer; captured old allowance callback invoked while the fresh transfer is RUNNING | Recovery completes with R1 use; old callback cannot change the fresh attempt/lease/generation/status; one fresh transfer, stored selection reused, no extra query/transfer. |
| `expiredCheckpointRollbackStillCancelsMaintenanceAndFinishRetainsTypedJournal` | Real heartbeat checkpoint rolls back once; the registered allowance expiration runs before acquisition returns | The checkpoint failure is observed and stays the published failure; maintenance finishes `NEEDS_FOREGROUND` with lease released; exact typed journal/attempt retained; no validation/source; the allowance closed exactly once. |
| `cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback` | Real complete asset, canonical `remove`; native release blocks in a `NonCancellable` finally; real allowance expiration while blocked; old callback after explicit resume | Cleanup/remove flags, asset identity, maintenance lease and bytes retained throughout unwind; allowance still open (0 closes) while blocked; old epoch callback cannot close/suspend the resumed host; **then the durable cleanup retry after foreground resume fails to start (see blocker 1).** |
| `cleanupCannotReleaseNativeReadersOrDeleteBeforeItsAllowanceIsGranted` | Real complete asset, real `remove`; allowance acquire is held before native deletion | No `beforePhysicalDelete` call, cleanup pending, asset/bytes and final path lock retained until the allowance is granted; after release exactly one native release, tombstoned `CANCELLED` job, live bytes deleted; no source/transfer. |
| `startupReturnsWithHonestCheckingProjectionAndDoesNotBlockAnotherAcquisition` | Real complete/publish; new owner/process re-opens the same store; actual completed-file check blocks; a fresh typed download runs concurrently | Durable job is COMPLETE; the unchecked process projection is VERIFYING; a real allowance exists; the fresh acquisition completes and queries only its own stored selection; release of the check completes it; no Ready map was forced. |
| `cancelledDispatcherReturnAfterRealMaintenanceCommitReleasesBeforeGateAcquisition` | Real canonical interrupted journal; post-COMMIT return hook cancels the dedicated owner scope | The exact returned maintenance lease is released and the journal retained before any new gate **should be acquired**; **the typed runtime acquires and closes one allowance before the cancellation is observed (blocker 2).** |
| `cancelOnlyExpirationAllowsExplicitForegroundResumeWithoutKillingRecoveryScheduler` | Real journal; first validation blocks in a noncancellable finally; registered expiration cancels; the consumed old callback is replayed after a real foreground resume | During unwind the maintenance lease covers the native source, journal is unchanged and the allowance is not closed; after durable `NEEDS_FOREGROUND`, explicit resume re-claims with a new allowance (second validation) and completes with the original materializer facts. |
| `deniedCompletedCheckKeepsDurableCompleteButPresentsNeedsForegroundUntilAnAllowedRecheck` | Real complete/publish; new owner re-checks the file; OS allowance denial; explicit foreground resume and allowed recheck | Denial never fakes a check: asset/bytes retained, presentation `NEEDS_FOREGROUND`, no validator call, exactly one acquire attempt, zero closes; **after resume the durable completed job is left QUEUED and never rechecked (blocker 3).** |

## Verification (real execution, fresh XML)

Every Gradle command ran under the single shared wave lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`, with
`scripts/gradle.sh --offline`, the literal bounded JVM argument
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
and the task-local initializer
`.work/verification/pr4-download-maintenance-ports/bounded-tests.init.gradle`
(one fork, 384 MiB/256 MiB/SerialGC/2 CPUs, fresh `-Dveyra.lead.evidence` per run). The evidence
harness captures a fail-closed manifest before Gradle (tracked regular inputs, mode-160000
gitlink with real submodule HEAD/cleanliness, `gradle/kmpyt.commit`, untracked owned files) and
aborts before launching if capture fails.

| Run | Command | Exit | Actual result | Log SHA-256 |
|---|---|---:|---|---|
| 001 | compile | 1 | Harness manifest bug (missing `-z`); **Gradle never launched** | `d06978445bac0723…` |
| 002 | compile | 1 | Test-source import/scope errors | `cc13b4f62371b3c4…` |
| 003 | compile | 1 | One typed callback type error | `c09a3573567bfe37…` |
| 004 | `:shared:compileTestKotlinJvm` | 0 | JVM test sources compiled | `1741cbf18a4d2685…` |
| 005 | three classes | 1 | 31 tests, 9 failures (4 path probes + 5 maintenance); **4 input-drift files created by bare-name lease probes** | `56984511e6fd58f8…` |
| 006 | three classes | 1 | 31 tests, 4 failures | `804fcc01f6612a47…` |
| 007 | maintenance | 1 | 13 tests, 4 failures (exact retained state diagnostics) | `2edd434359c128da…` |
| 008–013 | single fresh-transfer method | 1 | diagnostics only; the acquisition-fixture variant proved a real typed `INVALID_STATE` before transfer | `8cdad706f5d7f47c…`, `66d067495b338999…`, `a54f205a6f709a88…`, `eb961ddb725aebb6…`, `4a23320fccc89798…`, `97e7b892b3fdef23…` |
| 014 | fresh-transfer method (executor fixture) | 0 | 1 test passed | `063ecdee52d99f0d…` |
| 015 | three full owned classes | 1 | **31 tests, 28 passed, 3 failed, 0 errors, 0 skipped; 0 input drift** | `00afa7ffbc710764…` |

Final tested source revision: `f2f9091e8d31f8c277df4f2c3ec0cad9dbe6755f`. The run-015 manifest
has 510 entries (all tracked regular inputs, the real gitlink/HEAD/clean flag, the new helper,
`gradle/kmpyt.commit`, HEAD and clean status) and re-captured with zero drift after Gradle.
Exact XML is `run-015-owned-classes/shared-jvmTest/xml/TEST-dev.veyra.downloads.*.xml`.
Owned test/support hashes at this SHA:

- `DownloadCleanupRoomTest.kt` `e8f0acb2a7a049123b96c6aa6224616b780370d31ac3a1c569ff06fcb5fc8de6`
- `DownloadHashCancellationRoomTest.kt` `5a85a17daa8b0c8aaf8522b73c08e40127e0cc4eadaa07d72cb4dd45b91b8852`
- `DownloadMaintenanceRoomTest.kt` `05c5e20158be67f96beb3c337f2c23f492452576b4e7b99201ec1369b092d78e`
- `DownloadMaintenancePortTestSupport.kt` `a6aee338819c70b821a3fe4e147e100211fc109e7ed4f56e7a8a8e577ce7b34b`

Run-005's four `shared/path-*` files were test-probe artifacts of bare lock-file names, not
source. They were deleted and all later probes use the real path-lock derivation; runs 006–015
have zero drift.

## Production blockers exposed (no production edit made)

1. **Expired cleanup cannot be re-admitted after explicit foreground resume.**
   `shared/src/commonMain/kotlin/dev/veyra/data/room/PhysicalDownloadCommands.kt`
   `AcquisitionGraph.reconcileJob` returns `this` immediately when
   `job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP`. `finishMaintenance` keeps
   both `maintenanceKind = CLEANUP` and `failure = BACKGROUND_RESTRICTED` on a failed cleanup;
   `resumeHostPending` therefore re-reads the same restricted row, and
   `DownloadPhysicalRuntime.claimMaintenance` refuses it via `backgroundBlocked`. Observed after
   `resumePending()` + the documented 5 s local deadline: `status=NEEDS_FOREGROUND, lease=null,
   maintenance=CLEANUP, cleanupPending=true, failure=BACKGROUND_RESTRICTED
   download_executor_needs_foreground, releases=1, closes=1`; the second `beforeDelete` never
   runs. Minimal repair: on `allowHostResume` for a cancelled/removal cleanup demand, clear only
   the host restriction (status CANCELLED, failure null) and retain `cleanupPending`/
   `removeWhenClean`, so the existing CLEANUP maintenance can re-admit under a new allowance and
   keep all intrusive assertions (Room reservation, native reader, path locks, exact ACK).
2. **Cancellation after the real maintenance COMMIT still acquires one gate.**
   `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalRuntime.kt`
   `withMaintenance` has no cancellation/host re-check between the non-cancellable admission
   handoff and `withContext(NonCancellable) { gate.acquire(...) }` (the execution path has
   `checkpoint(run)` first). Observed: the exact Room lease is released and the journal retained,
   but the gate reports `acquires=1, closes=1` for a cancelled dispatcher return. Minimal repair:
   add `currentCoroutineContext().ensureActive()` immediately before gate acquisition (the
   existing finally then releases the exact Room/path owner without acquiring a gate).
3. **A denied completed check loses durable COMPLETE and is not rechecked after resume.**
   `DownloadPhysicalRuntime.finishMaintenance` maps the denied `VALIDATE` maintenance to
   `NEEDS_FOREGROUND` instead of preserving durable `COMPLETE`; then
   `PhysicalDownloadCommands.reconcileJob` maps `CONTINUE` + `allowHostResume` with
   `assetId != null` to `QUEUED`, so `reconcilePass`'s `status == COMPLETE` VALIDATE branch never
   runs. Observed after resume: `status=QUEUED, lease=null, failure=null, closes=0,
   validations=0`. Minimal repair: preserve durable `COMPLETE` (with the sanitized
   `BACKGROUND_RESTRICTED` diagnostic) for `kind == VALIDATE` in `finishMaintenance`, and/or
   restore `COMPLETE` in `reconcileJob` when `job.assetId != null`; the existing VALIDATE branch
   then performs the one allowed recheck and clears the failure.

These are the only reds in the owned classes; no other method was weakened to pass. Authorizing
either a minimal production slice for these three seams or a separate owner for them is required
before this PR can claim the 31/31 acceptance result.

## Protected invariants and limitations

- Pin/schema/dependency/build/workflow/legal files unchanged; parent branches untouched; no
  AppContainer or commonTest edit; no test deleted/skipped/ignored.
- Real Room/SQLite transactions, canonical claim/finalization/cleanup commands, real OS file
  leases and real hashing/publication are used. Transfers, native validation results and
  allowances are authored fakes over redistributable NON-AUDIO bytes. No live source, auth,
  audio codec, device, iOS/Xcode, native final build, package or release proof is claimed.
- Only the three owned classes were executed; the full shared suite (known other-scope failures)
  and AppContainer suite were deliberately not rerun in this wave. The parent PR stays draft.
