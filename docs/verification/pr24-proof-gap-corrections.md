# PR24 proof-gap corrections

Status: scoped correction on a successor to frozen PR24 `b600628401b87ac6a6c938b832fef9802743f679`
(tested source `55931d51e0fabaa868bf67b44919a3776aa27967`), stacked on frozen PR23
`6eed625373731dd9d295e7d71626b81886719034`. Task A remains incomplete; UI/API integration is held and
Task B is gated. No native/audio, device/emulator, live-source, full-application, signing, packaging,
release or shared-API acceptance is claimed.

## R1 — committed cleanup identity

The real-Room invariant is now explicit before the discriminator:

- `applyClaimMaintenance` checks the exact producer and ownership generation, rejects a live lease, and
  derives the maintenance fence as `before.ownershipGeneration + 1` with the command owner and exact
  attempt identity. No canonical command can mutate the claim set of a cleanup-pending job: a PAUSED or
  newly retaining intent is `Stale`, a non-retaining CANCELLED intent is a no-op, and new admission is
  rejected by `download_cleanup_reserved`.
- `DownloadCleanupRoomTest.canonicalCleanupClaimKeepsPreReadFenceAndClaimIdentity` exercises those
  canonical rejections/no-op between the runtime pre-read and the actual Room claim, then asserts the
  committed fence equals the pre-read proposal and the committed claim set equals the pre-read set.
- The existing `committedCleanupClaimJoinChangeRollsBackReturnedFenceAndRetriesWithoutStickyLease` remains
  as the reachable post-read path-change case; it holds the retry before its claim and before deletion,
  proves the committed lease is cleared, and requires the later actual cleanup.
- Because a canonical Room fence/claim difference is not legitimately reachable during cleanup,
  `DownloadCommittedCleanupIdentityCorrectionsTest` is a deliberately coherent **runtime fake repository**
  oracle: it returns a different committed fence/claim set, then asserts the rollback finish receives
  exactly that returned identity, not the pre-read proposal. It is explicitly defensive-API evidence, not
  a fabricated Room race. The fake also proves no orphan maintenance lease and preserved cleanup intent.

This closes the earlier equal-identity test gap without fabricating an invalid Room graph.

## R2 — scheduler-owned schema2 adoption

The new real-Room test `DownloadSchema2AdoptionRoomTest.adoptionWaitsForSchedulerOwnedCleanupBarrier`
cold-accepts removal, starts `DURABLE_WORK`, and holds the scheduler at its maintenance allowance after
the scheduler owns `maintenanceKind=CLEANUP`, the lease and the retired path lock. It then invokes exact
`adoptSchema2` and asserts no adoption row, the exact Schema2 producer and CANCELLED/non-retaining claim,
`cleanupPending`/`removeWhenClean`, bytes/journal and the held path lease. Releasing the scheduler yields
the exact request/binding adoption only after actual deletion.

The probe exposed a real source race: `adoptSchema2` was draining the same observation epoch that the
scheduler maintenance held, cancelling the external cleanup before it could delete. The narrow fix in
`DownloadPhysicalCommands.adoptSchema2` detects an already-active CLEANUP owner and waits for its actual
completion outside the command lock before re-entering admission. It does not drain/cancel that owner,
does not accept intent-only success, and preserves the exact pre/post request/binding/producer/claim/
generation checks, reader/path barrier and no-title-lookup rule. A cold/suspended/closing/draining host
still returns truthful `BACKGROUND_RESTRICTED`/`download_owner_drain_unconfirmed` with intent/bytes
preserved.

## R3 — compiler strategy and drains

New ignored runner files under `.work/verification/pr24-proof-gap-corrections/tools/`:

- `exact-bounds.init.gradle` applies `kotlin.compiler.execution.strategy=in-process` through
  `gradle.beforeProject` for root and included projects, logs the effective project/task/value, and bounds
  Test tasks to one 384 MiB / 256 MiB-metaspace SerialGC/2-CPU fork. It does not edit the kmpYT pin,
  upstream `gradle.properties`, shared/global caches or committed build configuration.
- The new `run_attempt.sh` seals the runner/init bytes, captures a stable in-lock gate-admission prefix
  with byte count/hash, and labels the full outer `gate.log` as outside that seal. It retains the actual
  lock device:inode/holder PID/start snapshots, full input manifests, XML seals and positive argv/cwd/fd
  drains.

Probe `strategy-probe-2` compiled at the current source and logged
`VEYRA_KOTLIN_STRATEGY project=:kmpyt ... value=in-process`, `project=:shared ... value=in-process` and
the test compile, with `drain.after` zero. This is configuration plus a positive process sample, not a
full worker-exec trace. The final committed-source compile/focused/full results are recorded below.

## Related LOW corrections

- **R4:** `gate.log` is not sealed by `attempt.sha256`; new attempts seal only the stable in-lock admission
  prefix (`gate.admission.inside.*`). A complete outer-log hash, if written after the wrapper exits, is
  labelled outside-gate separately. No old seal was backfilled.
- **R5:** `final2-compile-1/drain.after` had one transient Kotlin daemon (PID 4709); `drain.done` was zero.
  The old document's blanket zero-drain claim is corrected here; the dirty `focused-smoke-5` is
  113 tests / 9 suites / 0 failures, not 113/0.
- **R7:** the `awaitRemovalOutcome` KDoc now states owner presence is only the initial phase; the settled
  phase waits for durable completion or a terminal failure.
- **R8:** the attempt inventory below names the new correction attempts, including reds and gate waits.
- **R9:** the historical 0.648 s red is the strongest source/timing/path inference, not a traced original
  owner/detail; the deterministic tests prove the corrected branch.
- **R10:** `DownloadSharedSiblingRemovalRoomTest` adds a real-Room shared-job case: removing one standalone
  demand while two batch claims retain the completed asset returns normally with no cleanupPending, leaves
  the asset bytes in place, records the exact removed claim as CANCELLED/non-retaining, and keeps both
  sibling claims ACTIVE/retaining. No global deletion is inferred from a claim-scoped removal.
- **R6:** finalization-only normal-success, post-intent closing-host and public failed-owner coverage remain
  source-reasoned residual coverage; no invalid graph was injected to manufacture them.

## Attempt inventory

- `strategy-probe-1`: gate admission wait failure (disk floor), preserved; no Gradle execution.
- `r2-probe-1`: R2 red: scheduler-owned adoption drained the external epoch and timed out; preserved.
- `r2-probe-2`: R2 green after the narrow pre-wait fix; preserved.
- `r1r2-probe-1`, `r1r2-probe-2`: compile/test attempts with missing imports and type mismatch, preserved.
- `r1r2-probe-3`: R1/R2 test green after corrections.
- `r10-probe-1`: shared-sibling test red on an identity assertion; preserved.
- `r10-probe-2`: shared-sibling/R1/R2 green.
- `strategy-probe-2`: successful in-process-strategy compile probe; preserved.
- `prefinal-focused-1`: 122 tests / 13 suites / 2 load-sensitive inherited failures
  (`cleanupExpiration...` retry and `sqliteRollback...` timeout); preserved as pre-final evidence, not
  acceptance.
- Final committed-source compile/focused/full attempts are recorded after the source commit and before
  the docs-only successor commit.

## Limits

Authored non-audio bytes and fake source/native seams; real Room/SQLite/files/OS locks only. The
project-lock and process evidence are snapshot/runner-attested, not continuous kernel traces or full
worker-exec attestation. The full shared suite is shared-seam evidence, not full-app/native/live proof.
