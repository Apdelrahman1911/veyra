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

## Method mapping (original → typed seam → retained assertions)

Filled by the implementation; see the diff and the table below. Every port keeps the
original method name and its safety claim; stage distinctions are called out where the
old test described a removed authority (legacy snapshot rows, legacy `PlaybackBinding`,
DOWNLOAD `PlaybackResource`, synthetic coverage/backoff rows).

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
`1d41eec893d2e550c037fae307f2242220d48291efa2cac1567e96db3da75c41`. The source/test
freeze used for the validated run is the committed head recorded with the PR; the run
directory contains the exact argv/cwd/HEAD/pin/UTC window, full log + SHA-256, fresh
exact-task XML, method inventory and totals, and the input-hash receipt.

Planned/actual runs (all under the shared ports-wave `flock` lock, one test fork,
384 MiB heap/256 MiB metaspace, SerialGC, two CPUs, project-local Gradle/home/temp):

1. `:composeApp:compileKotlinDesktop` / `:composeApp:compileTestKotlinDesktop`.
2. Focused changed methods.
3. Full `:composeApp:desktopTest --tests dev.veyra.app.AppContainerIntegrationTest`.

Final actual results, totals and hashes are appended here after the frozen run. The
parent's 50 shared-test baseline is unchanged and is not claimed fixed. No
iOS/Xcode/native/device/live-source proof is claimed; the native/source/transfer
answers are authored fakes over non-audio bytes while Room/files/OS locks are real.
