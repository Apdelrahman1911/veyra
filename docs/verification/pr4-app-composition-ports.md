# PR #4 app-composition regression ports (`t3/pr4-app-composition-ports`)

Scope: test-only port of the 21 failing `dev.veyra.app.AppContainerIntegrationTest`
methods at parent `t3/typed-room-download-recovery` head
`67594669c6cd51e63ca772b281d4fc56144cafdd`, plus one narrow existing-app
compatibility repair (see below). Base for this stacked PR is that parent branch;
the parent branch is not modified.

Accepted launch: provider `opencode`, model `opencode-go/deepseek-v4.1-flash`,
variant `max`. No independent runtime/backend/variant attestation is exposed.
The kmpYT pin (`c0a08090fe7dbd76e20216559b8184ad2cccde63`) and schema-3 export
(`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`) are
unchanged. This is a test-and-narrow-compatibility change; no schema, dependency,
pin, workflow or native/platform implementation was changed.

## Starting state and imported candidate

The supplied worktree started as a `feat/veyra-integration` descendant at
`9b1b0479a2748012988d378d1f631cf5a50fff79`. The parent PR branch remote head was
`67594669c6cd51e63ca772b281d4fc56144cafdd`; an ancestor check permitted a
fast-forward of this local branch to that exact head. The delegation manifest
`pr4-ports-wave-inputs.json` rehashed to
`1d41eec893d2e550c037fae307f2242220d48291efa2cac1567e96db3da75c41` and all
506 tracked starting hashes matched before edits.

The interrupted writer's only tracked modification and its unfinished note were
imported from the continuation packet. Packet SHA-256 values were verified:

- `snapshot-receipt.json` `eb6301ac...`; `app-test-ports.patch` `f5608abf...`;
- `AppContainerIntegrationTest.kt.snapshot` `f27d560a...`;
  `app-composition-ports-incomplete.md` `b41028b3...`;
- `queued-runner.sh.snapshot` `c9e34c37...` (reference only, never rerun).

After import, all 507 source/document hashes in `sourceSha256` matched the
snapshot receipt; the imported test file hash equaled `f27d560a...`. The
candidate was committed as `WIP: import incomplete AppContainer typed-composition
port candidate` (`66677e8`) before further build work, and the draft stacked PR
#5 was opened against `t3/typed-room-download-recovery`.

## Prior attempts, correctly labelled

- `compile-002` (superseded checkout `t3-5f39449a`, dirty HEAD `6759466`) did
  compile desktop tests. `compile-001` failed. Neither is a run result, and the
  later candidate edits are not covered by it.
- `run-001` (same checkout) executed an **earlier** candidate snapshot: 35 tests,
  6 failures, 0 errors, 0 skipped, exit 1. Its XML SHA-256 is
  `bde79af2537538514522faae68c6d04000411895778637a64dac4a85b849f89f`. The six
  failures were setup-stage (historical seed admission/queue) plus one timeout;
  none reached the podcast restart assertion, so they are **not** evidence of a
  production restart defect.
- A source-equal background run of the imported candidate was queued in the
  superseded checkout behind the shared sibling's scheduling-class run
  (`compile-004`/`run-002`). It never started while this continuation was active;
  it is not adopted as evidence, and the code it would have tested is now
  superseded by the repairs below.
- No implementation commit, push or scoped PR existed before this branch; the
  import commit `66677e8` and draft PR #5 were created here.
- The unfinished note claimed the source freeze was a committed head with a
  completed receipt. That was false at capture: there was no commit, no PR, and
  no second run. `summarize.py` in the superseded checkout also put `.gitmodules`
  text in `pin` and had no real pre/post freeze receipt, so it is not reused. The
  lead snapshot proves current source equality, not the inputs of `run-001`.

## Contract of the fixture

The class keeps one real `LibraryStore`/Room/files lifetime per fixture and the
real `AppContainer` root. Native playback/validation and source transfer answers
remain explicit authored fakes over NON-AUDIO bytes; Room, SQLite files, OS
leases and the app root are real. No test writes `AppSnapshot.downloads`,
`AppSnapshot.playbackBindings`, `AppSnapshot.queues` or `AppSnapshot.progress` on
a schema-3 store.

Canonical historical inputs are prepared by the owned test-only helpers
(`Fixture.seedSelection` / `seedQueue` / `seedJob` / `seedAttemptJob` /
`seedCompleteJob`) using the production commands on the same store:

- `AcquisitionCoordinator.capture/resolve` produces a real request, raw ordinal-zero
  receipt, recording, subject and typed binding;
- `AcquisitionRepository.admitQueue` plus `attachOccurrence` and
  `PlaybackAcquisitionRepository.applyPlayback(Activate/EditQueue)` produce the
  real occurrence, queue order/current occurrence and position;
- `admitStandalonePhysicalDownload`, `claimPhysicalDownloadAttempt`,
  `selectPhysicalDownloadRepresentation`, `heartbeatPhysicalDownload`,
  `beginPhysicalDownloadValidation`, `preparePhysicalDownloadFinalization`,
  `publishPhysicalDownloadFinalization` and `finishPhysicalDownloadAttempt`
  produce the real job/claim/attempt/finalization/materialization/use history.
  Bytes, hash and validator answers are authored; this is retained history, not
  current-process validation or killed-process proof.

Every rejected canonical command fails the fixture (`accept`/`Stale` checks); no
command result is ignored. No snapshot seed invents typed relations. Assertions
read the canonical `store.acquisitionView()` / `acquisitionViews` join, never a
compatibility projection; typed jobs intentionally do not appear in
`state.downloads`, and that legacy projection is asserted empty.

## Gateway fake corrections

`GatewayProbe.prepareDownload` previously called the playback `onResolve` hook and
silently dropped `plan.resource`. The typed prepared-download contract creates no
`PlaybackResource`; a DOWNLOAD resource cannot exist. The fake now:

- has a separate `onPrepareDownload` representation hook and rejects an authored
  `PlaybackResource` instead of losing it;
- refuses to satisfy a required representation by relabelling the plan: if the
  authored plan's representation key differs, preparation fails before transfer;
- carries its issuing gateway plus the exact context/binding, and atomically
  enforces one consumption; a foreign/reused/mismatched prepared value fails
  before the source callback or I/O.

Typed transfer invocations are recorded **at entry**, before the fake source can
block/cancel/fail; `completed` and `typedCompletions` distinguish successful
return. Held/cancelled transfer counts are therefore asserted at the real
boundary, while every no-replay assertion keeps exact one-invocation counts.

Reachable lifetime owners replace the removed DOWNLOAD resource:

- typed playback source ownership: `preparePlayback` / `PolicyMediaGateway`
  rejected-resource cleanup (the former ambient-resolver cancellation tests);
- native `DownloadExecutionLease`: its close in the physical cleanup chain (the
  former DOWNLOAD-resource tests). Already-successful transfer/native-validation
  stages are asserted single-consumption; dependent stages stay withheld until
  the allowance positively closes.

## Method mapping (original → typed seam → retained assertions)

All 35 original method names are preserved. Every original safety claim is kept;
where the old test described a removed authority (legacy snapshot rows, legacy
`PlaybackBinding`, DOWNLOAD `PlaybackResource`, synthetic coverage/backoff rows),
the replacement is explained rather than loosened to "any failure".

| Original method | Typed seam | Retained guarantees |
|---|---|---|
| `productionGatewayRejectsFirstChannelInsteadOfPlayingLaterVideoAndKeepsRawReceiptInRoom` | production `YouTubeKmpMediaGateway` acquire → canonical Unplayable outcome | one complete-title query; raw CHANNEL ordinal-0 receipt/kind/id/title/public URL; no later-video binding/recording/subject; occurrence UNPLAYABLE; no legacy binding; no native prepare/extraction; typed failure is `RECOVERY_REQUIRED/acquisition_selection_unplayable` (the ambient `UNSUPPORTED_SOURCE` stage was removed) |
| `cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce` | `Play` → typed `preparePlayback` cancelled at the policy/online return | resource closed exactly once, never handed to native; one committed request and one binding, no legacy binding; close does not re-close |
| `rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky` | same, first release throws | failure sanitized (`UNEXPECTED`, no raw cause/text); owner retained and retried exactly once at explicit close; dependencies released once |
| `persistentlyFailedRejectedResourceRemainsRetriableAfterFinalCloseWithoutRepeatingGlobalClosures` | same, always failing until allow | one retry per explicit close; sticky failure; later success cannot retroactively certify clean shutdown; global closes exactly once |
| `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` | app-owned controller resolve job; close awaits policy-return cleanup | close blocked until the late resource is released; no runtime/source/http/Room close early; sanitized sticky failure; one global close set; resolver cancellation observed |
| `offlineToggleCancelsRunningGatewayCoroutineAndClosesLateResultBeforeNativeHandoff` | `Play` + offline policy cancellation | late resource closed once; `OFFLINE_POLICY` state; no native prepare; one query/commit/binding |
| `handedDownloadResourceFailureRetainsRoomRuntimeClientsAndLifetimeUntilExplicitCloseRetry` | allowance close fails after publication | job COMPLETE and asset retained; transfer/local validation once; dependent clients/Room/lifetime retained; one explicit retry; sticky sanitized failure; no replay |
| `throwingDownloadAllowanceRetainsDependenciesWithoutReclosingTheAlreadyConsumedResource` | allowance close throws then succeeds | successful earlier validation/transfer never repeated; dependencies retained until positive close; sticky failure |
| `cancelledAppCloseCallerCannotSkipRetainedDownloadCleanupOrItsDelayedDependencyDrain` | allowance close blocks under a cancelled close caller | close is `NonCancellable`; no ACK while the owner waits; Room/dependencies retained; exactly one consumption; nothing done twice; sticky failure |
| `queuedDownloadRetainsTheRecordingChosenAtIntentAfterAnotherFreshPlayOfSameItem` | accepted request/binding frozen before a second fresh Play | completed job/asset keep the first binding/recording; transfer uses that exact binding; current playback moves to the new binding; one query per Play and none for transfer |
| `slowDownloadRemovalCleanupCannotBlockUiPauseOrPersistedOfflineChange` | active typed transfer + RemoveDownload | cleanup pending while the transfer unwinds; UI pause and persisted offline intent finish first; invocation counted, cancellation observed; staging/path drained after release; no legacy row; no extra query |
| `ownedFileRemovalWaitsForNativeStopButDoesNotBlockOfflineUiIntent` | restored typed occurrence + local asset use + RemoveDownload | local prepare with no query/resolution; file retained until native-stop ACK; offline setting changes meanwhile; final deletion and job cleanup |
| `uiLibraryDoesNotAdvertiseRestartedRoomCompleteAsReadyBeforeThisProcessValidatesIt` | seeded canonical COMPLETE + cold VALIDATE | Room stays COMPLETE; current-process projection is VERIFYING and never flashes COMPLETE before validation; no legacy Ready row; no native prepare; COMPLETE only after real local validation |
| `acceptedCoveragePrecedesEveryGateAndTheExistingDurableQueueExecutesOnlyAfterThatAck` | seeded QUEUED typed job; held coverage | no gate/transfer before ACK; gate records ACK=1; durable queue starts only after ACK; transfer reuses the accepted receipt without a query; exact job/binding/asset/use |
| `explicitCoverageRetryPreservesUserPauseAndFutureBackoffUntilItsOriginalDeadline` | seeded PAUSED + WAITING_NETWORK jobs | repeat initialize is not a retry; activation preserves user pause, retry count and original backoff deadline; no work before the deadline; transfer at the deadline; host restriction is process-level, not a synthetic job retry slot (old `NEEDS_FOREGROUND` row is replaced by a real typed network backoff) |
| `unknownAndDisconnectedPlatformNetworkPreventNewNetworkClaimsUntilConnected` | typed Download selection + platform network states | UNKNOWN/DISCONNECTED defer the claim: no gate/attempt/transfer; only CONNECTED transfers; no execution query; coverage ACK precedes gate; receipt/binding remain canonical |
| `coldSchedulingBootstrapPreservesRestorableCollectionQueueAndHistoricalAssetWithoutOpeningAUserSession` | canonical queue/complete+pending jobs + navigation; absent media dir | scheduling init is a read of canonical history; queue position/current occurrence, jobs/asset and collection navigation restore; no native/coverage/network/media/artwork work |
| `schedulingOnlyPauseCancelAndRemovePersistAcrossRoomReopenWithoutMediaDirectoriesOrNativeMaintenance` | canonical staged jobs + intent commands | pause/cancel/remove intents persist across Room reopen; cleanup stays pending; no native maintenance, media dirs, engine or coverage work; typed retry deadline/count survive user intent (legacy cleared the deadline; removed-authority difference, not a weaker claim) |
| `uncoveredMusicCollectionPodcastAndResumeTapsRejectAtThePublicBoundaryEvenWithAHeldActorAndCannotReplayAfterCancel` | canonical paused/staged jobs + held actor/coverage | tap rejection is synchronous and not actor-dependent; cancel commits before ACK and cannot replay; cleanup only after ACK; paused intent and claim preserved; no source/download execution |
| `musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation` | canonical complete/staged jobs; coverage failure | local intents persist while restricted; no gate/native work; activation ACKs coverage once and deletes removed assets/cancelled staging; paused work retained; immutable receipts survive; no validation/source calls |
| `podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording` | restored offline occurrence + delayed seek + StartOver | same occurrence/binding/local asset at 0 with a new generation; no new query/resolution/transfer; outgoing held seek not lost; queue/progress reset and FlushProgress; delayed old seek rejected; durable progress and reopen; retained job/asset lineage |

## Narrow production repair (existing-app compatibility)

The retained podcast test constructs a genuine canonical completed asset and
current bound podcast occurrence, waits for actual current-process validation,
resumes it locally, sets `offlineOnly`, holds an outgoing seek, and taps Start
Over. Before the repair, `PodcastAction.StartOver` called the fresh `Play` route:
it captured a new request/occurrence and, offline, failed without reusing the
retained recording.

Red run (task-owned `run-focused-001`, HEAD `66677e8` plus the then-current
working-tree port, started 2026-11-07T15:59:43Z, ended 2026-11-07T16:13:10Z,
exit 1, 6 tests / 2 failures / 0 errors / 0 skipped): the podcast test failed
with `phase=ERROR`, `failure=OFFLINE_POLICY/acquisition_offline`,
`selection=PENDING`, `binding=null`, and `media=2` committed requests, proving a
fresh request was admitted instead of reactivating the retained occurrence. The
same run passed `coldSchedulingBootstrap...`, `musicAndPodcast...`,
`explicitCoverageRetry...` and `ownedFileRemoval...`. Its second failure was a
test-owned deadlock: the test read canonical Room state before releasing its own
held writer transform, hit the 15 s authored actor latch, and failed at
`assertFalse(actorTimedOut.get())`; that ordering is corrected before the
acceptance runs (it is not evidence about production). The full log and exact
XML are retained in the run directory. The input-hash helper aborted on the
`third_party/kmpYT` gitlink in that run, so the red run did not produce a
before/after input receipt; the helper is fixed for all acceptance runs.

The repair is limited to `composeApp/src/commonMain/kotlin/dev/veyra/app/AppContainer.kt`:

The repair is limited to `composeApp/src/commonMain/kotlin/dev/veyra/app/AppContainer.kt`:
`restartCurrentPodcast(mediaId)` reactivates the exact CURRENT bound podcast
occurrence when the current selection is that catalogue episode, using
`PlaybackCommand.PlayOccurrence(queue revision, occurrence, expected binding,
startFromBeginning = true)` — the existing atomic Activate/checkpoint/reset path.
It does not capture a request, select by catalogue id, choose a duplicate, mint a
binding/use, write a separate progress snapshot, or perform source I/O while
offline. If no appropriate current bound occurrence exists, the established fresh
`Play` admission is preserved; a stale queue/binding CAS fails truthfully instead
of silently recapturing a different request. A control test covers the fresh-Play
fallback for a non-current episode.

## Verification evidence

Every heavy operation uses the shared
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`
with `flock -x`, held to process completion. One test fork, 384 MiB heap/256 MiB
metaspace, SerialGC, two CPUs, project-local Gradle/home/temp. Runs redirect
fresh exact-task XML with `-Dveyra.lead.evidence=<unique-dir>`.

| Run | HEAD / tree | Tests | Result | Evidence |
|---|---|---|---|---|
| `run-focused-001` (red) | `66677e8` + dirty port (pre-repair) | 6 | 2 failures, 0 errors/skipped, exit 1 | `run-focused-001/stdout.log` 18,461 bytes, XML `TEST-dev.veyra.app.AppContainerIntegrationTest.xml` 6/2/0/0 |
| Acceptance compile + focused/full class | post-repair commit below | 36 | PENDING | PENDING |

The parent's known 50 shared-test failures are the sibling's scope and are not
claimed fixed. No iOS/Xcode/native/device/live-source proof is claimed; the
native/source/transfer answers are authored fakes over non-audio bytes while
Room/files/OS locks are real.
