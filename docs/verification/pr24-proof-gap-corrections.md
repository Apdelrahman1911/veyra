# PR24 proof-gap corrections

Status: scoped correction on a successor to frozen PR24 `b600628401b87ac6a6c938b832fef9802743f679`
(tested source `55931d51e0fabaa868bf67b44919a3776aa27967`), stacked on frozen PR23
`6eed625373731dd9d295e7d71626b81886719034`. Task A remains incomplete; UI/API integration is held and
Task B is gated. No native/audio, device/emulator, live-source, full-application, signing, packaging,
release or shared-API acceptance is claimed.

## R1 — committed cleanup identity

The canonical fence is constrained, but the claim set is **not** immutable:

- `applyClaimMaintenance` checks the exact producer and ownership generation, rejects a live lease, and
  derives the maintenance fence as `before.ownershipGeneration + 1` with the command owner and exact
  attempt identity. `applyStandaloneIntent` and `applyBatchIntent` both permit a legitimate CANCELLED
  retaining→nonretaining transition while cleanup is pending and increment claim generations/revisions;
  a new admission is still rejected by `download_cleanup_reserved`. `reconcileJob` under
  `CANCEL_UNFINISHED_KEEP_COMPLETE` can leave a retaining claim with `cleanupPending=true` for staging
  or finalization. Equal claim snapshots are therefore a sampled case, not an invariant.
- `DownloadCleanupRoomTest.cleanupPendingRejectsNonCancelIntentAndKeepsANonRetainingClaimUnchanged`
  (renamed/narrowed) samples the already-nonretaining case: non-CANCELLED/retaining intent is `Stale`,
  CANCELLED/nonretaining is a no-op, and the committed fence follows the canonical derivation. It does
  not claim universal claim-set immutability.
- `DownloadRetainingCleanupIdentityRoomTest.retainingCleanupPreReadDiffersFromTheCanonicalCommittedClaimSet`
  is the mandatory real-Room discriminator. A real in-progress staged transfer is drained without
  deleting its bytes; a canonical CANCELLED/retain=true intent creates the retaining cleanupPending
  state. The controlled runtime host invokes the production
  `cleanupStoppedStandalone(expectedOwner)` path. In the existing `beforeMaintenanceClaim` hook, after
  the runtime pre-read and before the actual Room claim, a canonical CANCELLED/retain=false command
  drops retention and increments the claim generation/revision. The test asserts the committed claim
  set differs from the retaining pre-read, the runtime takes its post-read mismatch rollback, the
  finish receives exactly the returned committed fence/claims, the lease is not stranded, the
  reservation/staging bytes/path ownership remain, and a subsequent exact cleanup deletes the staged
  generation and releases owned resources.
- The existing `committedCleanupClaimJoinChangeRollsBackReturnedFenceAndRetriesWithoutStickyLease` remains
  as the reachable post-read path-change case.
- `DownloadCommittedCleanupIdentityCorrectionsTest` remains a deliberately coherent **runtime fake
  repository** oracle for the defensive exact-fence/claim rollback; it is labelled defensive-API
  evidence, not a fabricated Room race. The canonical fence derivation is proved by the real-Room case;
  claims are proved changeable by the retaining transition above.

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

## Previous correction evidence (source `7825b9a5adb6a9825fac81408ba98dc7a2927d3c`)

All final attempts use the new exact-bounds init, the existing project lock, one worker, the single
literal JVM argument, fresh unique directories, clean HEAD/status, complete before/after input manifests,
actual `/proc/locks`/`lslocks` holder identity, an in-lock gate-admission prefix seal, and positive
argv/cwd/fd drains. The full `gate.log` is outside the in-gate attempt seal; `gate.log.outside.meta`
records its post-wrapper hash/bytes separately.

- `final3-compile-1`: exit 0; actual `:kmpYT:kmpyt:compileKotlinJvm`, `:shared:compileKotlinJvm` and
  `:shared:compileTestKotlinJvm` under `--rerun-tasks`. All three logged
  `VEYRA_KOTLIN_STRATEGY ... value=in-process`; `drain.before/after/done` all report zero matches
  (no Kotlin compiler daemon observed). `run.log` SHA-256
  `0707e9a26e48f5805cd95cdbc940234a43455d8b0f8eb38c68224e85fa5a0282`, attempt seal
  `a19beee18b797e2d7de59f31a44c98879993d665408c2f37ab0309332b0bbefb`, admission-prefix seal
  `8b2810f77c7973328f95aa0c5007dff63942f7038faf8b94cee017ffbfdc14fb`.
- `final3-focused-1`: exit 0; 122 tests / 13 suites / 0 failures / 0 errors / 0 skipped. Includes the
  previous nonretaining canonical-claim sampler, coherent runtime identity oracle, scheduler-owned
  adoption barrier, shared-sibling removal, and all inherited cleanup/adoption/ownership/maintenance/
  retained classes.
  `run.log` `1a0985ebc2185b1c3756bd81871d4571af3094e5bd8b520d31bfe2169f4f30c6`, XML seal
  `c7ad32185db5052d32a998b7f76b689b4b74a3ed55273a0bf20175b561b93c08`, attempt seal
  `144d138c9a3eea05baf8e9a1383daa2db3b5050b8d04fe0b23d2bf645be432c7`, admission-prefix seal
  `ac73e5ada8672c31393413896cac14db222440ca2f4ade0deee6164d8cb5804c`.
- `final3-full-1`: exit 0; 1,198 tests / 104 suites / 0 failures / 0 errors / 0 skipped. `run.log`
  `85511cb47f611fd8555c7caecf04de39771fc3efe736b7cda54937a772452ea0`, XML seal
  `ccf1a3859ab71698f6d8133370f522f222f25331bab36a771250b90752ac7362`, attempt seal
  `f5b318c56e11ca3eaaff65ab80974b3eaa30b5653a9edfd97d21c616542b71c9`, admission-prefix seal
  `596225373882fbc33e095cfd0a82f730b634b2427264e3796c217a0ac6f4466c`.
- Pin `c0a08090fe7dbd76e20216559b8184ad2cccde63` (file/indexed gitlink/actual submodule) and schema3
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`; `inputs_equal=0`; worktree clean.
  `harness.bytes.sha256` for the runner/init is
  `546fe746f337956913ec959c83208fe02bcb0c62d4e4a14669bd6aba46047ada`.

The docs-only successor changes only this verification document. The tested source is `7825b9a`;
PR24 remains frozen at `b600628` (tested `55931d5`).

## Final successor evidence (source `ae28140a538bcff7dda3b05ce26d2fc145f141cf`)

This successor changes only test/helper/documentation paths; accepted production source from
`7825b9a` is unchanged. The changed test Kotlin compiled and executed under normal dependency
execution; the accepted included-build in-process evidence remains the immutable `final3-compile-1`
record and was not re-forced for this test-only change. The branch was created from frozen PR25
`68ea74e` after the commits were made; the attempt `BRANCH` field still records the predecessor local
branch name `t3/pr24-proof-gap-corrections`, while local/remote successor head is
`t3/pr25-real-room-claim-discriminator`; the tested commit is identical.

- `final4-scoped-1`: exit 0; 123 tests / 14 suites / 0 failures / 0 errors / 0 skipped; includes the
  new retaining-to-nonretaining real-Room discriminator, the fake defensive oracle, the old
  cleanup/fake cases, adoption, ownership, maintenance, scheduling and retained classes. The log
  shows actual `:shared:compileKotlinJvm` and `:shared:compileTestKotlinJvm` execution with
  `VEYRA_KOTLIN_STRATEGY ... value=in-process`. `run.log`
  `7176691aeaa176baa06116acb638e938a101bb98e316c929e4e0ff1a1171ebd2`, XML seal
  `9cc56f1b8fa7ad0e01addd401a1b1334ba2ab032cc2f896b22b2934cca4a6af1`, attempt seal
  `7ba993ad93be79ede1206f58839e873b7e50c1899e4ddf1a2a4f0027adbd2605`, admission-prefix
  `5abfe869a4b884c9cc270d3cab90a0212ab9b3c07ecb55a6dfedd7c366f0e629`.
- `final4-full-1`: exit 0; 1,199 tests / 105 suites / 0 failures / 0 errors / 0 skipped; `run.log`
  `957459310d702bf25afbaadca2e45896b2fe57874ca8bb384adcf4ff6d030d53`, XML seal
  `1a1730051094d075ef61ae202032af4df13899b2b14a0f9b6dd14b621863e3ed`, attempt seal
  `21915552a0d6891314bc0b2c45f63fb7d79c2cb8cf0da4b18eb8dc7735c3d64e`, admission-prefix
  `3e5e60d6ef22fcf22b65ac65f2c36dd0600e30254ba86bff3cc6806a0719358f`.
- Both attempts: clean HEAD/status, `inputs_equal=0`, pin/schema identical, actual lock
  device:inode/holder PID/start snapshots, and zero `drain.after`/`drain.done` matches. Full outer
  `gate.log` is outside the attempt seal; `gate.log.outside.meta` is separately timed.

The docs-only successor after `ae28140` changes only this verification document. Frozen PR25
`68ea74e` (tested `7825b9a`) and all earlier refs remain unchanged.

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
- `prefinal-focused-1`: 122 tests / 13 suites / 2 unexplained inherited failures
  (`cleanupExpiration...` retry and `sqliteRollback...` timeout); preserved as pre-final evidence, not
  acceptance. No cause is claimed without the missing exact dirty bytes; lead inventory labels these
  unexplained.
- `final3-compile-1`, `final3-focused-1`, `final3-full-1`: the previous successor gates at `7825b9a`;
  results/seals are recorded above.
- `final4-scoped-1`, `final4-full-1`: the final successor test-only gates at `ae28140`; results/seals
  are recorded above.
- `r1-retaining-probe-1` through `r1-retaining-probe-5`: iterative scheduler/inline discriminator
  experiments; retained. They include compile/type stops and a scheduler-owned path with no
  expectedOwner mismatch; not acceptance evidence.
- `r1-retain-room-1` through `r1-retain-room-6`: real-Room controlled-runtime host experiments; `-6` is
  the green retaining-to-nonretaining discriminator. Earlier attempts retain setup/compile stops.

## Limits

Authored non-audio bytes and fake source/native seams; real Room/SQLite/files/OS locks only. The
project-lock and process evidence are snapshot/runner-attested, not continuous kernel traces or full
worker-exec attestation. The full shared suite is shared-seam evidence, not full-app/native/live proof.
