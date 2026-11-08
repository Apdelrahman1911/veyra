# Retained-FAILED revalidation and physical-ownership corrections

Status: correction work after the independent PR17 review, on
`t3/pr17-review-corrections` stacked over frozen PR17 `bc46d42778d780428899c9f57f1a2e34d7d35f0e`.
No Task A, platform, native, audio, live-source, signing, packaging or distribution acceptance is
claimed.

## Independent review corrections

### F1 — ordinary Resume bypass of foreground authority (resolved)

`DownloadPhysicalCommands.resume` now rejects both asset-backed `FAILED` and asset-backed
`NEEDS_FOREGROUND` states before any intent mutation:

- `RECOVERY_REQUIRED / download_retained_local_validation_required` for retained failure;
- `RECOVERY_REQUIRED / download_foreground_announcement_required` for a host-held completed check.

`DownloadsCoordinator.resumePending()` remains the only foreground announcement. Tests exercise
ordinary `resume` on both the completed-check marker and a retained-failure host hold, assert the
marker/failure/backoff and archived bytes are unchanged, and then exercise the real
`resumePending` path. Batch admission/retry still requires `asset == null` and does not use
`allowRetryFailure` as foreground authority.

### F2 — shared physical health vs binding-local rejection (resolved; no false AVAILABLE)

Actual `DownloadMaterializationNegative.File` and `.Native` outcomes are shared physical-file
invalidity. When they fail the shared job, the batch projection intentionally marks every member
`FAILED`; retaining the historical R1 use/receipt does not prove current bytes are usable. The
committed graph invariant forbids `AVAILABLE` beside a `FAILED` job. The new test asserts the
origin member, shared failure, retained R1 use/receipt/materialization and no R2 use on a real R2
native negative, then asserts both members become `AVAILABLE` only after successful explicit R2
local validation.

A binding-local stale member/identity rejection is tested separately: it leaves the shared job
`COMPLETE`, the origin member `AVAILABLE`, the origin use unchanged, and grants no use. No Room
projection invariant was relaxed.

### F3 — cancellation at the allowance boundary (bounded protocol implemented)

A pre-acquire `operation.isActive` check plus a post-acquire check are performed inside the
`NonCancellable` admission section. If cancellation arrives inside/after `gate.acquire`, the
returned allowance is stored in the finally-owned `allowance` slot and closed; it cannot become an
orphan. A deterministic test cancels from `gate.beforeAcquire` and verifies one acquire, one close,
zero open path leases, all local owners consumed and no checkpoint failure. An exactly-zero
acquisition for a cancellation that occurs inside a third-party gate's own critical section is not
guaranteed; the contract is the bounded acquire/close protocol rather than the stronger
"never call acquire" claim. This is the explicit adjudication point for the lead.

### F4 — remove/caller vs reconciler cleanup ownership (corrected for retained failures; one contract held)

`stop` now observes the exact scheduler cleanup owner and its completion when a retained-
asset failure removal caller loses the ownership race; it does not treat another owner's active
deletion as its own success. The inherited real-Room
`missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording` method (the actual 0.655 s
full2 assertion) is green in clean focused execution after this change.

The gate-expiry catch path still acknowledges a durably accepted removal intent while cleanup is
retained for host retry. That is the existing `cleanupExpiration...` contract and is explicitly
held for lead adjudication rather than changed to an untested indefinite wait. The clean full run
below retains one unrelated `DownloadPolicyRoomTest` scheduler-slot assertion (`expected 1, was 0`);
no full-shared-green claim is made.

### F5 — stale accepted-owner finalizer classification (corrected)

A stale maintenance finish is now nonfatal only when exact supersession evidence exists: the
owner epoch is revoked, the current job producer/ownership generation differs, the job row is
gone, the exact admitted claim set changed, or a retained cleanup/failed-file removal is
explicitly superseded. Active owners with unchanged producer, generation and claims still latch
`INVALID_STATE / download_checkpoint_stale`; the transfer-finalizer stale oracle remains green.
The focused fake-owner test proves both sides; the inherited real ownership class is green in
isolation. The clean full run's remaining policy-slot assertion is reported separately below.

### F6 — evidence statement correction

The frozen PR17 evidence is retained unchanged. The corrected record is:

- Parent red `red-parent-3`: 3/3 failed, expected F2 defects; test source SHA-256
  `464e7a70...`; no full-suite inference.
- `green-final-focused` (frozen PR17 code): 27/27 tests, 3 suites, before/after manifests equal
  and sealed artifacts.
- `green-maintenance-9`: 27/27 focused maintenance/F2 tests.
- `green-full-shared-1`: at `5834262` with three dirty production inputs; 1,177 tests/99 suites,
  one 15 s `DownloadRecoveryRoomTest` timeout. Not final-source evidence.
- `green-full-shared-2`: at clean production `1c91140`; 1,177/99, one **0.655 s assertion** at
  `DownloadIntegrityRoomTest.kt:413` (`MISSING removal waits for the real release callback`), not
  a timeout. No after manifest/argv was retained for that attempt.
- `green-full-shared-3`: experimental cleanup-waiter source, 12 timing failures; reverted and not
  evidence against this branch.
- `green-full-shared-4`: at docs head `fa14e1f`; 1,177/99, one `DownloadOwnershipRoomTest`
  teardown `download_checkpoint_stale`; before/after manifests equal, but no argv.
- Only attempts that actually contain `command.argv`, `before/after.inputs.sha256` and drain
  files are claimed as such; no historical evidence was backfilled.

The correction topic has new, separate evidence under
`.work/verification/pr17-review-corrections/attempts/`:

- `clean-final-focused` at correction head `fe8ec66`: **30 tests / 5 suites / 0
  failures/errors/skips**, exit 0, before/after tracked-input manifests equal. This covers the
  F1/F2/F8 Room cases, F3/F5 physical cases, all 13 maintenance methods and the inherited
  integrity/stale-transfer oracles.
- `ownership-final-1`: 11/11 `DownloadOwnershipRoomTest` methods, exit 0 (earlier correction
  source).
- `full-shared-4`: 1,181 tests / 100 suites / 0 failures / 0 errors / 0 skips, but it ran with
  final F4/F5 source bytes uncommitted while HEAD was `fd0be85`; it is retained as a non-clean
  observation, not clean-source acceptance evidence.
- `full-shared-5` at clean `fe8ec66`: 1,181 tests / 100 suites / **1 failure / 0 errors /
  0 skips**, exit 1. The failure was
  `DownloadPolicyRoomTest.twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit`
  (`expected <1> but was <0>`), preserved without calling it a flake. No full-shared-green claim
  is made for clean final source.
- `full-shared-3` (earlier correction source) retained one `DownloadRecoveryRoomTest` 15s
  timeout before the final narrow F4/F5 adjustments; preserved but not green proof.

### F7 — UI dependency (core owner)

No UI/AppContainer/PlaybackController edit is made here. The core owner receives this exact
handoff:

```kotlin
suspend fun DownloadCoordinator.validateRetainedAssetUse(
    downloadId: String,
    requiredBindingId: ExecutionBindingId,
): AssetUse

suspend fun DownloadCoordinator.validateRetainedMemberAssetUse(
    batchId: DownloadBatchId,
    memberPosition: Int,
    expectedIntentRevision: Long,
    requiredBindingId: ExecutionBindingId,
): AssetUse
```

Use the member form for repeated-origin occurrences that share one binding; use the simple form
only when the binding identifies one claim. Wire an explicit localized **Validate** for retained
`FAILED` rows, Retry only for no-asset failures, `resumePending`/actual foreground announcement
for `NEEDS_FOREGROUND`, and a separate confirmed Remove. The return is an exact requested-binding
`AssetUse`, not Play/native authority. Errors are `VeyraException` with the authoritative
failure code/manual-only local retry semantics from D010.

### F8 — repeated-origin selection and exactness (resolved)

The compatibility API rejects an ambiguous repeated binding with
`RECOVERY_REQUIRED / download_retained_validation_member_required`; it never selects an arbitrary
claim. The member API carries exact batch/member/revision identity. The finish command now rejects
a batch-member retained grant whose requested binding differs from that claim's binding, while
preserving the intentional standalone same-recording R2 rule. Real tests cover repeated-origin
recovery and a direct repository cross-binding finish that returns `Stale` with no grant and no
`COMPLETE`. Path-close failure retains the maintenance/path lease and blocks a new validation until
real recovery/close acknowledgement; the test asserts that retention and failure, and documents
that no lease is released before close ACK.

## Changed production paths

- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalRuntime.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadCoordinator.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/PhysicalDownloadCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/PhysicalDownloadFinalizationCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/PhysicalDownloadRepository.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/AcquisitionGraph.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/AcquisitionStateMappings.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalScheduling.kt`

New focused tests:
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadRetainedFailureRevalidationRoomTest.kt`
- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadPhysicalRecoveryCorrectionsTest.kt`
- `shared/src/commonTest/kotlin/dev/veyra/downloads/DownloadRetainedValidationSchedulingTest.kt`

## Limits

Authored non-audio bytes and fake decoder/transfer/source seams; real Room/files/OS leases only.
No live source, network transfer, native audio, device/emulator, package, signing, workflow or
benchmark operation. Clean final focused `fe8ec66` is 30/30 green; the clean full run retains one
policy-slot assertion, so no full-shared-green claim is made. The F4 gate-expiry acknowledgment
and F3 bounded acquire/close remain explicit adjudication items.

## PR20 final recovery successor

This successor topic starts from frozen PR20 `761323c` and preserves its raw evidence. Lead
adjudications applied:

- **F2 current-availability.** A genuine local `File` or `Native` negative withholds current
  shared usability and preserves the exact actual failure code; it does not claim physical
  corruption or that every historical request failed. `UNSUPPORTED_CODEC` remains
  `UNSUPPORTED_CODEC`, never rewritten to `CORRUPT_MEDIA`; immutable R1 receipt/use/bytes survive,
  current Ready is withheld, and exact explicit revalidation restores only the requested use.
  Binding-local stale-member rejection leaves healthy siblings alone. `DECISIONS.md` records this
  precise contract.
- **F3 bounded Android host request.** The no-orphan acquire/close protocol is accepted; the
  acquire may transiently install coverage/enqueue foreground work in the Android gate before the
  cancellation is observed. This is documented as a bounded host-request limitation, not a
  side-effect-free claim. The fake deterministic case records the request and exact close.
- **F4 removal observation.** Removal now reads a coherent durable job/cleanup observation rather
  than waiting indefinitely for a consumed registry owner. A consumed/failed Stale observation
  returns a bounded `BACKGROUND_RESTRICTED`/exact recovery failure; a successful foreground retry
  can complete and return normally. The gate-expiry catch waits for the actual durable cleanup
  outcome when the caller remains active. The general public `remove(Unit)` contract still has the
  inherited healthy-external-writer intent-only case; this is retained and reported, not labelled
  completed for every external-writer scenario.
- **F5 snapshot.** Job and claims for stale-supersession classification are read from one
  `repository.state` snapshot.
- **Policy measurement.** `DownloadPolicyRoomTest.twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit`
  has only the publication synchronization repaired: awaiting `inFlight == limit` now also
  requires the published `maximum >= limit`; the original `assertEquals(limit, maximum)` and all
  1/2/4/lowered-limit assertions remain. No oracle was removed or weakened.
- **F7** remains a core/UI handoff; no UI path is edited here.

New successor evidence uses `.work/verification/pr20-final-recovery-corrections/attempts/` with
actual argv/before/XML seal/after/drain captured inside the vps-build gate and project-lock
lifetime. Frozen PR20 evidence remains immutable.
