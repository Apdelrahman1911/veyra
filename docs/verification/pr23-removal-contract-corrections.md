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
  observe a controlled foreground retry. Once an owner has been observed, later owner-registry
  disappearance is not terminal: the observation waits within the same bounded per-phase handoff
  for durable `cleanupPending=false` or a terminal failure, so a transient cancelled-owner failure
  does not become false success. The original `cleanupExpiration...` success-after-resume contract
  remains. The bound is at most two `removalOwnerGraceMs` phases (5 s each), not an inflated
  timeout;
- schema2 adoption, which is a destructive replacement, uses the actual-cleanup wait before
  requiring the legacy generation cleared. It uses terminal-failure semantics and the same
  per-phase bound; a cold/suspended/closing host reports `BACKGROUND_RESTRICTED`
  (`download_owner_drain_unconfirmed`) while preserving intent, bytes, journal and locks.

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

## Causal correction after frozen 04b1d07

The captured focused red `cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback`
failed in `final-focused-1` at `0.648 s`, below either five-second wait. The cause is the
**settled** `awaitRemovalOutcome` predicate treating `!ownerPresent` as terminal after the first
cleanup owner had been legitimately consumed. The first owner's finalizer removed itself while
`cleanupPending` was still true; the controlled foreground retry had not yet cleared it. The
initial owner-presence phase, generation scoping, terminal `Failed` behavior, final authoritative
asset/staging/finalization/reservation/lease check, caller cancellation propagation and truthful
missing-owner timeout are preserved.

The correction removes only the `!ownerPresent` clause from the settled predicate. The observation
now succeeds only on `cleanupPending=false`, returns a terminal `Failed` immediately when that is
authoritative for the normal path, and otherwise reports the existing bounded
`BACKGROUND_RESTRICTED`. The dirty 15-second `maxOf` experiment is reverted; it did not alter that
predicate and its smoke green was timing perturbation, not causal proof. The documented bound is at
most two `removalOwnerGraceMs` phases (5 s each), and tests inject the grace parameter rather than
inflating production timeouts.

Deterministic evidence:

- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadRemovalObservationCorrectionsTest.kt`:
  starts the observation with an owner present, consumes that owner while `cleanupPending` remains,
  asserts the observer is still waiting, then clears the durable pending flag and asserts success.
  A normal-removal variant covers the same consumed-owner rule; a missing-owner interrupted case
  uses a 40 ms injected grace and proves bounded truthful failure with preserved intent.
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadRemovalInterruptedRetryRoomTest.kt`:
  real Room/files/OS-lock integration. The first cleanup owner is cancelled; after its
  noncancellable unwind is consumed the removal must still be in flight, and a second retry owner
  is held in a `beforeDelete` barrier while bytes and the path lease are still present. Releasing it
  yields the original normal success, exact two releases/closes, stale-callback protection and
  dependency-release assertions.
- The pre-fix `final-focused-1` red remains a distinct historical observation; its `gate.log` seal
  mismatch is retained and it is not presented as fully sealed. No synthetic red was manufactured
  after the fix.

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

- `DownloadRemovalObservationCorrectionsTest` deterministically covers owner-present →
  owner-consumed-while-pending → still-waiting → real durable cleanup success for both the normal
  and interrupted paths.
- A missing-owner interrupted case uses a 40 ms injected grace and proves `BACKGROUND_RESTRICTED`
  within the bound, preserving intent and generation.
- A terminal `Failed` owner remains registered but the observation settles immediately instead of
  waiting for owner removal.
- `DownloadRemovalInterruptedRetryRoomTest` adds the real integration second-retry barrier:
  removal is still pending after the first owner is consumed, remains pending while the second
  owner holds the actual delete, then succeeds only after the bytes are deleted. The original
  `cleanupExpiration...` expiration/native-unwind/old-callback/lease/dependency assertions remain
  unchanged.
- Healthy external-writer, host-held no-false-success, and controlled foreground-resume cases are
  real Room/coordinator tests above.
- Successful cleanup with a live owner and the `cleanupExpiration...` foreground retry remain
  unchanged.

### Schema2 adoption cleanup wait

`adoptSchema2` now waits for actual retired-generation cleanup when durable `cleanupPending`
survives the inline cleanup attempt, instead of requiring `requireClearedSchema2` immediately:

- the exact request/binding/media validation remains before destructive intent;
- the wait is outside all admission locks and before the reader drain/adoption commit;
- `requireIntentCurrent`, exact producer/claim/generation checks, the reader/path barrier, no
  title lookup and the one-shot adoption command remain authoritative;
- a cold, suspended, closing or draining host returns `BACKGROUND_RESTRICTED`
  (`download_owner_drain_unconfirmed`) with intent, journal, bytes and locks preserved; the legacy
  `RECOVERY_REQUIRED`/`download_legacy_cleanup_required` code is no longer returned for this
  host-held path;
- the adoption wait uses terminal-failure semantics and at most two `removalOwnerGraceMs` phases,
  unlike the interrupted-removal transient-failure tolerance. It does not claim the same policy or
  duration.

`DownloadSchema2AdoptionRoomTest.adoptionOnColdHostPreservesPendingCleanupInsteadOfAdoptingDestructively`
is the real-Room cold-host case: the failed removal intent and bytes remain, adoption refuses
truthfully without an adoption row, activated cleanup deletes the generation, and the explicit
retry then adopts with the exact request/binding. The existing
`theRetiredGenerationLockRemainsRequiredAfterTheReaderCallbackAndPriorCleanup` reader-integrity
case remains unchanged. A deterministic scheduler-owned adoption wait is a residual test gap: the
scheduler cleanup path itself is covered by `retiredPathCleanup`, but the adoption-specific race
would need a new test seam; no such seam was added in this scoped correction.

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
- Historical red/observation evidence is retained without backfilling: `final-focused-1` at
  `04b1d07` contains the original 113 tests / 9 suites / 1 failure (`cleanupExpiration...`,
  0.648 s). Its `run.log`/XML are internally consistent, but its `gate.log` does not match the
  hash sealed in that attempt's `attempt.sha256`. The mismatch is deliberately left unchanged and
  that attempt is not described as fully sealed. Clean `04b1d07` compile exit 0 and full
  1,189/100/0 are distinct observations; the dirty 113/0 smoke is not final acceptance.

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
- `observation-fix-1`: focused compile stop; the new test used `async` without a scope. Retained.
- `observation-fix-2`: 20 tests / 3 suites / 1 red before the settled-owner correction was complete;
  retained.
- `removal-debug-*`, `observation-debug-*`: failure-only diagnostic attempts used to isolate the
  observation branch; not acceptance evidence.
- `adoption-test-1` through `adoption-test-6`: adoption-branch diagnostics (teardown race and
  ordering), retained; they are not acceptance evidence.
- `removal-final-smoke-1`: 26 tests / 4 suites / 0 failures for the observation, integration,
  adoption and maintenance classes; pre-final only.
- `final2-compile-1`, `final2-focused-1`, `final2-full-1`: the final same-commit executed gates at
  source `55931d5`; results and hashes are recorded above.

Final executed compile/focused/full results are recorded by the docs-only successor commit with
the exact tested source SHA. No historical evidence was backfilled.

## Final same-commit evidence (source `55931d51e0fabaa868bf67b44919a3776aa27967`)

All three attempts were run through `/usr/local/bin/vps-build run --profile normal` with the
existing project lock, `scripts/gradle.sh --offline`, one worker, and the single literal JVM
argument `-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC
-XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`. Each attempt has a fresh directory, exact
numeric exit, start/finish, sealed inputs/XML/log/drain, `inputs_equal=0`, clean HEAD/status,
pin `c0a08090fe7dbd76e20216559b8184ad2cccde63`, indexed gitlink and actual submodule head, and
schema3 `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`. `/proc/locks`
and `lslocks` record the actual outer vps-build holder PID/start ticks for the same
device:inode; the harness did not re-acquire the lock. Drain scans report zero owned matches.

- `final2-compile-1`: exit 0; actually executed `:kmpYT:kmpyt:compileKotlinJvm`
  (UP-TO-DATE for the existing pin), `:shared:compileKotlinJvm` and
  `:shared:compileTestKotlinJvm` under `--rerun-tasks`; `run.log` SHA-256
  `b63175913adc3b2d65fe29158a0a962a23a2b238ca4001022641f9d0b70106d7`, attempt seal
  `f606a694c089fa6cff035b09e14e4cdd957a5a61e5910067f2a3f466bd1f09c1`.
- `final2-focused-1`: exit 0; 118 tests / 11 suites / 0 failures / 0 errors / 0 skipped; includes
  both new focused test classes, the real interrupted-retry integration case, the cold-host
  adoption case and all historic required classes; `run.log` SHA-256
  `d816220d4b5fc832c6b7627c2fe7be487738f6424eee18a04faa33eae0b35c3e`, XML seal
  `f7e782d23efce2e5a259fdfcab641f8d174840662d559d6e544da4382778b19c`, attempt seal
  `a97adaad1f7d1284ea2da647a339dcb0852c37832259eb1621e105b275f54b55`.
- `final2-full-1`: exit 0; 1,194 tests / 102 suites / 0 failures / 0 errors / 0 skipped;
  `run.log` SHA-256 `4192e509a18c8c5741200d327a39b9020b3467c8a86047f9b8e205f6c4ca26ca`, XML
  seal `d2cbb76ac033431ff1f9f3c001ce7dc5d5f627be91fad6dccddadd5b609dd826`, attempt seal
  `4efd50eb7a719ff5ef9497d52777f629dc5cbfc83b17b1969ea7cce7349ddb2b`.

The tested source is `55931d5`; the docs-only successor changes only this verification document.
The older base `04b1d07` observations remain distinct: compile exit 0, focused 113/9/1 with the
original 0.648 s `cleanupExpiration...` failure, full 1,189/100/0. The dirty 113/9/0 smoke is not
acceptance. `final-focused-1`'s known `gate.log` seal mismatch remains deliberately unrepaired
(actual `492c554c…`, sealed `f04019c4…`); its XML/run log are retained as the genuine red, but the
attempt is not described as fully sealed.

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
