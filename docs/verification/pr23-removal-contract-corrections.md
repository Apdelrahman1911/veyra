# PR23 removal-contract corrections

Status: scoped correction work on a successor to frozen PR23 `6eed625373731dd9d295e7d71626b81886719034`.
No Task A, UI/Validate API integration, native/audio, device, full-application, signing, packaging or release
acceptance is claimed. Frozen PR4/PR17/PR20/PR22/PR23 refs stay unchanged.

## P1 — completed-removal contract

`DownloadPhysicalCommands.stop` now proves authoritative physical removal for normal
`remove(Unit)` calls across retained assets, staging and finalization-only state, not only prior
`failure != null` rows:

- the caller drains its exact ownership epoch and attempts the exact scheduler cleanup;
- if durable `cleanupPending` remains, it waits on the generation-scoped cleanup observation,
  including an external/scheduler-owned cleanup, rather than accepting intent;
- success requires the durable row to have no pending cleanup, live asset, staging path,
  finalization, cleanup reservation, maintenance lease or active attempt;
- an unstarted/suspended/failed host, missing/consumed owner, terminal failed observation or
  expired bounded handoff returns the existing truthful `BACKGROUND_RESTRICTED` while preserving
  intent, assets, reservations and locks;
- a detached observation epoch with null durable settlement is never interpreted as completion;
- caller cancellation still propagates. An active caller whose child cleanup was cancelled may
  observe a controlled foreground retry; a transient cancelled-owner failure does not become a
  false success, and the original `cleanupExpiration...` success-after-resume contract remains.

Shared-asset sibling claims are unchanged; removal never deletes bytes still retained by a
demanding sibling.

### Inherited assertion mapping

| Test / helper | Old assertion | Corrected assertion |
| --- | --- | --- |
| `DownloadRecoveryRoomTest.removeAfterReadyCommitWaitsForThePublishingWriterToReleaseItsGeneration` | `remove` returned normally with `cleanupPending=true` and bytes retained; cleanup checked later after `advance`. | `remove` is in flight while the publishing writer holds its generation; it returns only after the exact writer release and actual deletion. Bytes/receipt/use/history assertions remain. |
| `DownloadSchedulingRoomTest.unactivatedCancelAndRemovePersistIntentButCannotStartMaintenanceBeforeCoverageActivation` | `remove` returned normally before host activation while cleanup stayed pending. | `remove` reports truthful `BACKGROUND_RESTRICTED` before activation, preserving `removeWhenClean`, partial staging and completed bytes; activation then performs the actual cleanup. |
| `DownloadSchema2AdoptionRoomTest.cleanUnknown` (directly equivalent helper oracle) | Cold `remove` returned normally with `REMOVE_CANDIDATE` intent only. | Cold `remove` reports truthful `BACKGROUND_RESTRICTED`, preserves `cleanupPending`/`removeWhenClean`/journal, then activated cleanup deletes the retired generation before adoption. |
| `DownloadMaintenanceRoomTest.cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback` | Existing expiration/retry/native-unwind/old-callback assertions. | Unchanged. The final `remove` succeeds only after controlled foreground resume and actual deletion; no early restriction contradicts it. |
| `DownloadRetainedFailureRevalidationRoomTest.removalImmediatelyAfterRetainedFailureIgnoresFutureLocalRecheckDeadline` (new) | The old suite always advanced 5 s before removal. | `remove` completes actual deletion with a future `nextRetryEpochMs` and **no clock advance**; receipt/use history remains. |
| `DownloadRecoveryRoomTest.removeWhileHostSuspendedFailsTruthfullyAndSucceedsAfterForegroundResume` (new) | No host-held case. | Suspended-host removal fails truthfully, retains asset/intent; foreground resume then performs actual deletion. |
| `DownloadRecoveryRoomTest.removeOfActiveStagingWaitsForWriterUnwindAndDeletesOnlyTheActualStagingGeneration` (new) | No no-asset staging completion case. | Active staging writer is joined; `remove` returns only after the staging path is deleted and the attempt/history projection settles. |

## P2 — authoritative Room cleanup claim

`applyClaimMaintenance` now exempts `PhysicalDownloadMaintenanceKind.CLEANUP` from the local
`nextRetryEpochMs` validation-recheck guard, matching the runtime admission rule. Other
producer/ownership/lease/eligibility/maintenance guards are unchanged. The real-Room P2 test
above removes immediately after a retained failure with a future deadline and no clock advance.

## P3 — committed-claim rollback regression

When a committed maintenance claim's post-read path/claim join differs, the runtime now treats
CLEANUP as a retryable race: it returns without publishing a host failure, and the finally block
settles the **returned committed fence and claim set** (not the pre-read snapshot). A deterministic
real-Room test injects a post-read path via the test-owned repository hook, pauses the retry before
its claim, and asserts:

- `download_maintenance_join_changed` path was reached (injected post-read path);
- the rollback finish used the injected/returned exact fence and claims with
  `cleanupCompleted=false`;
- the committed maintenance lease was cleared, the reservation/intent remained, and real bytes
  remained locked and undeleted;
- the next exact cleanup retried under the delete barrier and completed without a sticky
  `download_checkpoint_stale` or hang.

## P4 — observation cases

- A missing-owner case uses a short bounded test clock and proves `BACKGROUND_RESTRICTED` within the
  handoff, preserving intent.
- A terminal `Failed` owner remains registered but the observation settles immediately instead of
  waiting for owner removal.
- The consumed-Stale case remains from the previous correction.
- Healthy external-writer, host-held no-false-success, and controlled foreground-resume cases are
  real Room/coordinator tests above.
- Successful cleanup with a live owner and the `cleanupExpiration...` foreground retry remain
  unchanged.

## P8 — UNSUPPORTED_CODEC evidence

The R2 unsupported-native test now also asserts the origin member's exact `UNSUPPORTED_CODEC`
failure and re-reads the retained physical bytes, while preserving immutable R1 use/receipt and
the exact requested-use recovery.

## P5-P10 — evidence process

New evidence root: `.work/verification/pr23-removal-contract-corrections/`.

- `tools/run_attempt.sh v2` refuses non-empty attempt directories (allowing only the outer
  `gate.log`), captures exact inner argv/wrapper/max-worker text, HEAD/branch/status, indexed
  gitlink + pin + actual submodule + schema3, all tracked/nonignored input hashes and the same
  after-run snapshot.
- Project-lock evidence parses `lslocks -J` and `/proc/locks` by actual `device:inode`, records the
  exact lock path/device/inode, holder PID/start-tick/PPID/cmdline, and snapshots at start and after
  run/archive/drain while the gate is still held. It does not claim that `fuser` open FDs alone
  prove lock ownership, and the harness does not re-acquire the project lock.
- XML is sealed by `xml.sha256`; the per-attempt seal includes argv, before/after snapshots,
  lock start/end, run/gate logs, finished/exit, XML inventory/summary, inputs equality, drain and
  the harness note.
- Drain scans non-self/non-ancestor `/proc` entries for argv, cwd and open-fd references under the
  repository, with explicit scan limits; it does not terminate unrelated workers. It is positive
  absence evidence for that scan class, not a kernel process-table proof.
- Final sequence is committed-source executed compile (`--rerun-tasks`), then focused regressions,
  then one clean full shared suite at the same commit. No source touching is used to force
  execution.

## Attempt inventory

Historical PR23 attempts remain under `.work/verification/pr20-final-recovery-corrections/attempts/`:
focus/ownership/diagnostic reds and greens through `final-full-gated-7`; `final-precommit-focused-1`
is retained but **excluded from acceptance** because its log/exit record a 6 s offline plugin
failure while its XML records a separate successful run (mixed directory anomaly). It is not used
as compile or execution acceptance. The old `full-shared-5` policy failure and all other red
attempts remain unmodified.

New correction attempts:
- `compile-smoke-1`: compile failure (Kotlin syntax/typing), retained.
- `compile-smoke-2`: compile failure (missing `.job`), retained.
- `compile-smoke-3`: compile success before this final source commit, non-acceptance.
- `focused-smoke-1`: 78 tests, 2 reds (terminal failed owner fixture close; wrong retained byte
  constant), retained.
- `focused-smoke-2`: 7 classes green.
- `focused-smoke-3`: 113 tests / 9 suites / 0 failures, retained pre-final.
- `full-smoke-1`: 1,189 tests / 100 suites / 10 reds (old intent-only removal oracles and
  cancellation-path observation), retained and used to correct the contract.
- `full-smoke-2`: 1,189 / 100 / 0, retained pre-final.
- `harness-smoke-v2*`: harness-only diagnostics; the accepted harness variant and final evidence
  are recorded separately after the final committed-source gates.

Final executed compile/focused/full results are recorded by the docs-only successor commit with
the exact tested source SHA. No historical evidence was backfilled.

## P12 — cleanup audit

The earlier reported deletion of disposable `.work` test case/migration/Room trees (roughly
12.5 GB) has **no retained contemporaneous audit**. This correction does not reconstruct one and
does not claim a fully audited cleanup. Frozen evidence and final acceptance attempts remain intact.
Any future selective deletion must first inspect task ownership/process references and record exact
paths, sizes and disposition while preserving source, tests, evidence and required caches.

## Limits

Authored non-audio bytes and fake source/decoder/transfer seams; real Room/files/OS locks only.
No live source, native audio, device/emulator, UI integration, Task A/Task B, signing, packaging or
release claim. F7/UI remains held. The completed-removal contract is physical-generation removal
semantics, not a user-facing API/UI integration claim.
