# Retained-FAILED local validation correction

Status: implementation and focused source/test correction; execution evidence is recorded below.
This document does not claim Task A, platform, native, audio, live-source, signing, packaging or
distribution acceptance.

## Source finding

The independent typed-download review identified F2: a typed `FAILED` job that retained a
materialization asset surfaced a Retry action. On the parent production, that action entered
`DownloadCoordinator.resume` -> `DownloadPhysicalCommands.resume` -> Room intent reconciliation
with `allowHostResume = true`. The job became `QUEUED` while `assetId` was still non-null.
Neither lane can execute that state:

- acquisition requires `job.assetId == null`;
- automatic/scheduled validation requires `status == COMPLETE`;
- reconcile maintenance only validates `COMPLETE` jobs.

The same retained file could already have a real validation negative from
`DownloadPhysicalRuntime`'s validation path, leaving `FAILED` + asset + local recheck deadline.
Foreground return had a related dead end: a host interruption could settle a retained asset as
`NEEDS_FOREGROUND`, and a gated `resumeHostPending` could reconcile it to `QUEUED` while clearing
the failure/backoff that explained why local validation was needed.

## Implemented contract

- `DownloadCoordinator.validateRetainedAssetUse(downloadId, requiredBindingId)` is the narrow
  explicit local-Validate admission API. It accepts a retained `FAILED` physical job, resolves
  either its standalone owner claim or its exact batch member claim, and performs the existing
  owned local validation path.
- The explicit path bypasses only the local validation recheck deadline and the corresponding
  processed `CHECK_ASSET` claim deferral. Host started/closed/suspended/drain state, application
  coverage, ownership epoch/lease, exact claim generation, cleanup/finalization state, path/file
  leases, native-reader release and cleanup owners remain authoritative. It does not activate a
  cold owner or bypass offline local-file validation.
- `DownloadPhysicalCommands.resume` now refuses a retained `FAILED` job with
  `RECOVERY_REQUIRED` / `download_retained_local_validation_required`. A no-asset network Retry
  still follows its existing `QUEUED` + retry-admission path. A retained failure never silently
  loses bytes and never becomes a dead `QUEUED` transfer.
- A successful explicit validation is not a provisional reset. The ordinary validation action
  first performs the complete owned hash/native validation. Its durable success is completed by
  the maintenance finish transaction, after the run's native-reader/allowance/path-close stages,
  using `FinishPhysicalDownloadMaintenance.retainedValidation`.
- That Room transaction requires the exact physical fence, exact expected claim generation and
  exact retained asset. It atomically restores `COMPLETE`, clears `failure` and
  `nextRetryEpochMs`, clears the maintenance lease/kind, and records **only** the requested
  binding's `AssetUse` with compatibility policy 1. It never grants a sibling R2 use, rewrites
  a historic use/receipt, or fabricates a source/asset validation receipt.
- An actual hash/native negative, a lost claim generation, a blocked host, cancellation or a
  cleanup/drain close failure leaves the job failed/blocked with its real provenance and retained
  bytes. Automatic maintenance continues to ignore `FAILED` jobs; only the explicit API admits
  one.
- Foreground return no longer maps a retained `FAILED`/`NEEDS_FOREGROUND` job to `QUEUED`.
  It restores `FAILED` with the local-validation requirement (or preserves the original failure)
  and keeps the local backoff. A previously `COMPLETE` job interrupted during a host-held recheck
  stays durably `COMPLETE` with the host restriction, so a foreground recheck can proceed.

## API contract for the UI/owner wiring

```kotlin
suspend fun DownloadCoordinator.validateRetainedAssetUse(
    downloadId: String,
    requiredBindingId: ExecutionBindingId,
): AssetUse
```

Offer this as an explicit local **Validate** action for a typed row whose job is `FAILED` and has
retained bytes. Do **not** route such a row to `resume`/Retry; keep Retry for no-asset failures.
For a `FAILED` retained row also keep removal as a separate confirmed action. On success the
returned `AssetUse` is the exact requested binding's durable use; callers should still observe
the canonical presentation rather than treating the return as a playback/native token. On failure
the call throws `VeyraException` with the authoritative failure code (for example
`BACKGROUND_RESTRICTED`, `CORRUPT_MEDIA`, `UNSUPPORTED_CODEC`, `INVALID_STATE`), and the job must
be shown as failed/blocked with its retained bytes.

This API deliberately does not require a `PlaybackController` edit. The existing UI/AppContainer
owner can add the action and localized label in its own scope.

## Changed production paths

- `shared/src/commonMain/kotlin/dev/veyra/data/PhysicalDownloadRepository.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/PhysicalDownloadCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/data/room/PhysicalDownloadFinalizationCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadCoordinator.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalCommands.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalRuntime.kt`
- `shared/src/commonMain/kotlin/dev/veyra/downloads/DownloadPhysicalScheduling.kt`

## New focused tests

- `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadRetainedFailureRevalidationRoomTest.kt`
  uses the real schema3 Room store, physical commands/runtime, owned files/OS leases and the
  existing authored transfer/decoder fakes. It covers:
  - real failed validation -> explicit local recovery -> exact-use `COMPLETE`;
  - automatic maintenance remaining stopped for `FAILED`;
  - retained network Retry rejection with unchanged job/claims/bytes;
  - blocked host and offline local validation;
  - cancellation and stale claim/invalid-negative provenance;
  - exact R2 use without rewriting the R1 origin use or receipts;
  - cleanup/path-close failure before durable success.
- `shared/src/commonTest/kotlin/dev/veyra/downloads/DownloadRetainedValidationSchedulingTest.kt`
  covers the pure automatic/explicit maintenance rule and its asset/cleanup/known-producer guards.

## Evidence and limitations

Execution statements below are only valid for the exact attempt records in the ignored
`.work/verification/pr4-retained-failed-revalidation/attempts/` tree. Parent-red, green and
regression execution results are appended after those attempts complete. No live source,
network transfer, physical device/emulator, native audio, packaging, signing, workflow or
benchmark operation is part of this correction.
