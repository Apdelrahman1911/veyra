# PR #4 app-composition regression ports (`t3/pr4-app-composition-ports`)

Scope: test-only port of the 21 failing `dev.veyra.app.AppContainerIntegrationTest`
methods at parent `t3/typed-room-download-recovery` head
`67594669c6cd51e63ca772b281d4fc56144cafdd`. Base for this stacked PR is that
parent branch; the parent is not modified.

Accepted launch: provider `opencode`, model `opencode-go/deepseek-v4.1-flash`,
variant `max`. No independent runtime/backend/variant attestation is exposed.
The kmpYT pin (`c0a08090fe7dbd76e20216559b8184ad2cccde63`) and schema-3 export
(`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`) are
unchanged.

Starting state was rechecked, not assumed: the supplied worktree was a
`feat/veyra-integration` descendant at `9b1b0479a2748012988d378d1f631cf5a50fff79`;
the parent PR branch remote head was `67594669c6cd51e63ca772b281d4fc56144cafdd`
and an ancestor check permitted a fast-forward to it. The delegation packet
`pr4-ports-wave-inputs.json` rehashed to
`1d41eec893d2e550c037fae307f2242220d48291efa2cac1567e96db3da75c41`; all 506
tracked starting hashes matched before edits. The relevant failures are the 21
AppContainer methods listed in that packet; the shared 50-failure baseline was not
changed or claimed fixed.

## Contract of the fixture

The class keeps one real `LibraryStore`/Room/files lifetime per fixture and the real
`AppContainer` root. Native playback/validation and source transfer answers remain
explicit authored fakes over NON-AUDIO bytes; Room, SQLite files, OS leases and the
app root are real. No test writes `AppSnapshot.downloads`,
`AppSnapshot.playbackBindings`, `AppSnapshot.queues` or `AppSnapshot.progress` on a
schema-3 store.

Historical composition inputs are prepared by an owned test-only helper
(`Fixture.seedSelection` / `seedQueue` / `seedJob` / `seedAttemptJob` /
`seedCompleteJob`) that drives the actual production commands on the same store:

- `AcquisitionCoordinator.capture/resolve` (authored first-result answers) produces a
  real request/receipt/recording/subject/binding;
- `AcquisitionRepository.admitQueue/attachOccurrence` and
  `PlaybackAcquisitionRepository.applyPlayback(EditQueue)` produce the real
  occurrence/queue;
- `admitStandalonePhysicalDownload`, `claimPhysicalDownloadAttempt`,
  `selectPhysicalDownloadRepresentation`, `heartbeatPhysicalDownload`,
  `beginPhysicalDownloadValidation`, `preparePhysicalDownloadFinalization`,
  `publishPhysicalDownloadFinalization` and `finishPhysicalDownloadAttempt` produce
  the real job/claim/attempt/finalization/materialization history. The bytes, hash and
  validator answer are authored; this is retained history, not current-process proof.

All assertions read the canonical `store.acquisitionView()` / `acquisitionViews`
join, never the legacy compatibility projection. Typed jobs intentionally do not
appear in `store.state.value.downloads`; the legacy projection is asserted empty where
the old test asserted a fabricated legacy row.

## Gateway fake correction

`GatewayProbe.prepareDownload` previously called the playback `onResolve` hook and
dropped `plan.resource`. The current typed prepared-download contract creates no
`PlaybackResource`; a DOWNLOAD resource cannot exist. The fake now has a dedicated
`onPrepareDownload` representation hook and rejects any returned resource instead of
silently losing it. Resource-lifetime guarantees are mapped to reachable owners:

- typed playback source ownership: `preparePlayback`/`PolicyMediaGateway.operation`
  rejection cleanup (the four formerly ambient-resolver cancellation tests);
- native execution allowance: `DownloadExecutionLease` close in the physical cleanup
  chain (the three former DOWNLOAD-resource tests). The transfer and native-validation
  stages that already succeeded are asserted single-consumption; dependent stages are
  withheld until the allowance positively closes.

## Blocker — `podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording`

This retained regression is **failing** at the frozen parent and must stay failing until a
production fix lands:

```
AssertionError: Start Over did not reuse the exact offline recording:
phase=ERROR failure=AppFailure(code=OFFLINE_POLICY, detail=acquisition_offline, retryAfterMs=null)
selection=PENDING binding=null queries=[] media=2
```

Exact production path:

1. `composeApp/src/commonMain/kotlin/dev/veyra/app/AppContainer.kt:748` maps
   `PodcastAction.StartOver(id)` to `play(listOf(episode(id)), startFromBeginning = true)`.
2. `AppContainer.play` (`:459-468`) dispatches `PlaybackCommand.Play`.
3. `PlaybackController` typed `Play` (`:1742-1754`) always creates fresh captures through
   `services.captures.media(...)` and `services.admissions.catalogLeaves(...)`, so a new
   PENDING occurrence/request replaces the exact retained occurrence.
4. `AcquisitionCoordinator.resolve` rejects the new request before any source entry because
   the store is offline (`:96`, `:253-255`), producing
   `AppFailure(OFFLINE_POLICY, "acquisition_offline")` and no native preparation. The
   request/receipt/binding of the downloaded offline recording are not used.

The test's claim is the original one: Start Over must atomically restart the exact retained
offline occurrence from zero with the exact generation/seek/progress/history semantics and no
source fallback. The shared `PodcastRestartOfflineTest` already proves the typed mechanism
(`PlaybackCommand.PlayOccurrence(..., startFromBeginning = true)`); the application UI action
does not use it.

Minimum repair (not made in this test-only wave): in `AppContainer.handlePodcast`, when the
requested episode is the exact current/bound occurrence, dispatch
`PlaybackCommand.PlayOccurrence(QueueContext.PODCAST, currentQueue.editRevision,
currentOccurrenceId, expectedBindingId = currentSelection.bindingId, startFromBeginning = true)`
and only fall back to fresh `Play` when no exact occurrence exists. This is a production
composition fix in `AppContainer` (and only if needed a small typed controller helper); it must
not search again or fabricate an occurrence.

All other 20 original methods are ported and pass in the frozen run; this one is preserved,
not weakened or skipped.

## Method mapping (original → typed seam → retained assertions)

Every port keeps the original method name and its safety claim; stage distinctions are called
out where the old test described a removed authority (legacy snapshot rows, legacy
`PlaybackBinding`, DOWNLOAD `PlaybackResource`, synthetic coverage/backoff rows).

| Original method | Typed seam | Retained guarantees |
|---|---|---|
| `productionGatewayRejectsFirstChannelInsteadOfPlayingLaterVideoAndKeepsRawReceiptInRoom` | Production `YouTubeKmpMediaGateway` acquire → canonical Unplayable outcome/receipt | One complete-title query; raw CHANNEL ordinal 0 receipt; no later-video binding/recording; no native prepare/extraction; playback failure is the typed `RECOVERY_REQUIRED/acquisition_selection_unplayable` stage |
| `cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce` | `Play` → typed `preparePlayback` cancelled at policy return | Resource closed once, no native handoff, one committed request/binding, no legacy binding, close does not re-close |
| `rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky` | same, first close throws | Sanitized sticky `UNEXPECTED`; retained then retried exactly once at explicit close; dependencies released once |
| `persistentlyFailedRejectedResourceRemainsRetriableAfterFinalCloseWithoutRepeatingGlobalClosures` | same, always failing until allow | One retry per explicit close; sticky failure; later success cannot certify clean shutdown; global closes once |
| `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` | `Play` resolve job; close awaits policy-return cleanup | Close blocked until positive/negative owner result; no dependent close or Room close early; sticky failure; one global close set |
| `offlineToggleCancelsRunningGatewayCoroutineAndClosesLateResultBeforeNativeHandoff` | `Play` + offline policy cancellation | Late resource closed; offline failure; no native prepare; one query/commit |
| `handedDownloadResourceFailureRetainsRoomRuntimeClientsAndLifetimeUntilExplicitCloseRetry` | Native allowance close failure after publication | Job COMPLETE/asset retained; transfer+validation once; dependent clients/Room/lifetime retained; explicit retry once; sticky failure |
| `throwingDownloadAllowanceRetainsDependenciesWithoutReclosingTheAlreadyConsumedResource` | allowance close throws then succeeds | Validation/transfer not repeated; dependency retained until positive close; sticky failure |
| `cancelledAppCloseCallerCannotSkipRetainedDownloadCleanupOrItsDelayedDependencyDrain` | allowance close blocks under a cancelled close caller | `NonCancellable` close drains; no ACK while owner waits; Room/dependencies retained; one consumption; sticky failure |
| `queuedDownloadRetainsTheRecordingChosenAtIntentAfterAnotherFreshPlayOfSameItem` | accepted request/binding frozen before second fresh Play | Completed job/asset keep the first binding; transfer uses it; current playback moves to the new binding; one query per Play, none for transfer |
| `slowDownloadRemovalCleanupCannotBlockUiPauseOrPersistedOfflineChange` | active typed transfer + RemoveDownload | Cleanup pending while transfer unwinds; UI pause/offline complete first; staging/claim drained after release; no legacy row |
| `ownedFileRemovalWaitsForNativeStopButDoesNotBlockOfflineUiIntent` | restored typed occurrence + local AssetUse + RemoveDownload | Local prepare without a query; file retained until native stop ACK; offline setting changes meanwhile; then final path deletion |
| `uiLibraryDoesNotAdvertiseRestartedRoomCompleteAsReadyBeforeThisProcessValidatesIt` | seeded canonical COMPLETE + cold VALIDATE maintenance | Room stays COMPLETE; current-process projection is VERIFYING/no COMPLETE flash; no legacy Ready row; no native prepare; COMPLETE only after real local validation |
| `acceptedCoveragePrecedesEveryGateAndTheExistingDurableQueueExecutesOnlyAfterThatAck` | seeded QUEUED typed job; held coverage | No gate/transfer before ACK; gate records ACK=1; transfer reuses accepted receipt without a query; asset/use exact |
| `explicitCoverageRetryPreservesUserPauseAndFutureBackoffUntilItsOriginalDeadline` | seeded PAUSED + WAITING_NETWORK jobs | Activation preserves user pause and the original future retry deadline; repeat initialize is not a retry; no work before deadline; transfer at deadline. The old synthetic NEEDS_FOREGROUND/background row is represented as a real typed network backoff; host restriction is process-level, not a job retry slot. |
| `unknownAndDisconnectedPlatformNetworkPreventNewNetworkClaimsUntilConnected` | typed Download selection + network states | UNKNOWN/DISCONNECTED defer the claim with no gate/attempt/transfer; only CONNECTED transfers; no execution query; coverage ACK precedes gate |
| `coldSchedulingBootstrapPreservesRestorableCollectionQueueAndHistoricalAssetWithoutOpeningAUserSession` | canonical queue/complete+pending jobs + navigation; absent media dir | Scheduling init is a read of canonical history; queue position/current occurrence, jobs/asset and navigation restore; no native/coverage/network/media/artwork work |
| `schedulingOnlyPauseCancelAndRemovePersistAcrossRoomReopenWithoutMediaDirectoriesOrNativeMaintenance` | canonical staged jobs + intent commands | Pause/cancel/remove intents persist across Room reopen; cleanup stays pending; no native maintenance, media dirs or engine/coverage work. The typed scheduling deadline/automatic-retry count are independent persisted metadata and are retained by user intent; the old legacy record cleared the deadline, which is a removed-authority difference, not a weaker assertion. |
| `uncoveredMusicCollectionPodcastAndResumeTapsRejectAtThePublicBoundaryEvenWithAHeldActorAndCannotReplayAfterCancel` | canonical paused/staged jobs + held actor/coverage | Tap rejection is synchronous and not actor-dependent; cancel commits before ACK and cannot be replayed; cleanup only after ACK; no source/download execution |
| `musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation` | canonical complete/staged jobs; coverage failure | Local intents persist while restricted; no gate/native work; activation acknowledges coverage once and deletes removed assets/cancelled staging; paused work retained; no validation/source calls |
| `podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording` | restored offline occurrence + delayed seek + StartOver | **Open blocker**: the real `PodcastAction.StartOver` currently admits a fresh request and fails offline instead of activating the exact retained binding; the test keeps the original offline-recording/generation/progress claim and is expected to fail until that production route is repaired. |

## Evidence

Starting inputs were rehashed after the fast-forward: 506/506 tracked hashes matched the
delegation packet, and the packet itself matched
`1d41eec893d2e550c037fae307f2242220d48291efa2cac1567e96db3da75c41`. After the test-only
edit, 505/506 tracked inputs remain byte-identical; only
`composeApp/.../AppContainerIntegrationTest.kt` changed
(SHA-256 `c3ccd61466206e5b3256c58628844e8637b3542d3ac123b96d9e286fea3d214f`). This
verification doc is docs-only and was not part of the compiled/tested source closure.

All Gradle runs acquired the shared ports-wave lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`, used the
literal bounded JVM argument, one test fork, 384 MiB heap / 256 MiB metaspace, SerialGC,
two CPUs, project-local Gradle/home/temp, and a fresh exact-task XML output per run.

| Run | Command | Exit | Actual result | Evidence |
|---|---|---:|---|---|
| compile-006 | `:composeApp:compileTestKotlinDesktop` (main desktop compiled/validated, test compile executed) | 0 | BUILD SUCCESSFUL; log SHA-256 `044d982ca4ea7704d3e3d7ef4d4641a2fdde85d39d4c83099149afe749ebcf67` | `.work/verification/pr4-app-composition-ports/compile-006/` |
| run-004 | full `:composeApp:desktopTest --tests dev.veyra.app.AppContainerIntegrationTest` | 1 | **35 tests, 34 passed, 1 failed, 0 errors, 0 skipped**; fresh XML SHA-256 `d52ccb9e8fe47dd10cfb958b3d175fb3992a3feaae5e02e0c1d773249360f482` | `.../run-004/`; log SHA-256 `4030c1c3dbfb729360cbd623119045d61e558a3013269a2b495d294c875ebe3d` |
| run-005 | same full class, repeated frozen run | 1 | **35 tests, 34 passed, 1 failed, 0 errors, 0 skipped**; fresh XML SHA-256 `d7e2b7a435aa06da882db2ac3a84d042c3432b928d7b698fdd77c3c5d6836893` | `.../run-005/`; log SHA-256 `086a1e0a34e96dadda30f937570acb6e80cfadfc297c4f202aa691e4f4ada004` |
| catalogue-1..3 | focused `catalogueRootActionsUseCommittedSavedFramesAndUndoSurvivesUnrelatedScalarCommit` | 0,0,0 | 1/1 pass each; fresh evidence under `.../catalogue-{1,2,3}/` | — |

The single repeated failure in both full runs is the retained
`podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording` production blocker
described above. The earlier run-003 full run (35 tests / 3 failures) also exposed a
one-off `ClosedSendChannelException` reported by the app-lifetime handler for the
`catalogueRootActions...` case. With the trace diagnostic added, that case passed in both
frozen full runs and in three focused repetitions; no suppression, retry loop or production
change was introduced. It remains an unresolved pre-existing playback-shutdown observation for
the lead, not a claimed fix.

The parent's 50 shared-test baseline is unchanged and was not rerun or claimed fixed. No
iOS/Xcode/native/device/live-source proof is claimed; native/source/transfer answers are
authored fakes over non-audio bytes while Room/files/OS locks are real.

## Publication note

The delegated remote name `t3/pr4-app-composition-ports` was already occupied by another
active writer (PR #5, which also changes production `AppContainer.kt`). This branch did not
rebase, force-push, reset or modify it. The independently verified test-only commit was
published as `t3/pr4-app-composition-ports-5f39449a` and draft PR
https://github.com/Apdelrahman1911/veyra/pull/7, based on the same parent
`t3/typed-room-download-recovery` `6759466`. The lead must reconcile the two attempts; this
branch intentionally retains the red Start Over regression instead of making the
production fix.
