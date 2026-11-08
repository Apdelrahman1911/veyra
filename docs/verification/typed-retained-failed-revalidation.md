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

### F4 — remove/caller vs reconciler cleanup ownership (partially corrected; held)

`stop` now observes the exact scheduler cleanup owner and its completion when a removal caller
loses the ownership race; it no longer treats another owner's active deletion as its own success.
The inherited real-Room `missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording`
method (the actual 0.655 s full2 assertion) is green in focused execution after this change.

The gate-expiry catch path remains a held contract question: the caller can still receive success
after a durably accepted removal intent while cleanup is retained for retry. Changing that to a
failure or adding a mandatory wait risks hanging the inherited `cleanupExpiration...` contract,
which expects the removal caller to complete after the retained cleanup retry. This is reported
for lead adjudication rather than hidden.

### F5 — stale accepted-owner finalizer classification (corrected)

A stale maintenance finish is now nonfatal only when exact supersession evidence exists: the
owner epoch is revoked, the current job producer/ownership generation differs, the job row is
gone, or the exact admitted claim set changed. An active owner with unchanged producer,
generation and claims still latches `INVALID_STATE / download_checkpoint_stale`. The focused
fake-owner test proves both sides; the inherited real ownership class remains the acceptance
probe.

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

The new correction attempts are separate under
`.work/verification/pr17-review-corrections/attempts/`; their argv/XML/log/drain evidence is
recorded as they complete.

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
benchmark operation. Full-shared-green is not claimed unless a final clean attempt succeeds.
