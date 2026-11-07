# PR #4 second correction — method and evidence mapping

Status: **draft, incomplete.** R7, R8 and the requested R9 contracts are implemented and
executed. R6 is partially complete: the already-typed EX34 scheduler case is fixed and
passing, and this document keeps the original 50-legacy-fixture/21-AppContainer failure
inventory. The scoped successor wave ports the two `RoomLibraryStoreTest` methods (see
`pr4-room-regression-ports.md`); the remaining shared and AppContainer methods are owned by
their separate sibling lanes and are not claimed here. No merge or release claim.

## Accepted launch

- Provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`.
- No independent runtime/backend/variant attestation is exposed by the harness; the
  accepted configuration above is the only launch record. No substitution was made.
- Worktree `/root/.t3/worktrees/Veyra/t3-7216d352`; parent `feat/veyra-integration`
  `9b1b0479a2748012988d378d1f631cf5a50fff79`; remote PR head branch
  `t3/typed-room-download-recovery`.
- Parent input review: all 504 tracked inputs and the schema-3 export rehashed with
  0 drift (`pr4-second-review-inputs.json`, SHA-256 `21234fec…`).

## R7 — immutable tap-time Download metadata

| Original method / action | New setup and command seam | Retained assertions |
|---|---|---|
| `AppContainer.download` / `selectExistingMedia` / `requireTapCurrent` | The displayed `MediaItem` is copied at the action boundary and is the exact `AcquisitionCaptureFactory.media` input; `current(item)` no longer refreshes it. Removal/source-identity change is rejected before capture; same-identity title/metadata edits do not rebase an admitted request. Final admission remains identity-only so later navigation/metadata does not cancel the immutable intent. | Complete-title query is the displayed title; durable `AcquisitionRequest`, `MediaCapture`, first receipt/query title and binding are unchanged; catalogue may move on independently. |
| `downloadTapFreezesTheDisplayedCompleteTitleAcrossAHeldAdmissionAndMetadataNavigationChanges` | Held production `AcquisitionGateway.acquire`; catalogue title updated and `OpenMedia` navigation committed while held. | Exactly one query with the displayed complete title; durable request/media/receipt/binding equal the displayed input; one typed transfer; no network. |
| `staleDisplayedIdentityIsRejectedBeforeAnyCaptureOrSourceQuery` | Action receives a stale displayed row whose source canonical id differs from the current row. | `INVALID_STATE download_action_stale`; zero queries/transfers; no request/binding/physical row. |
| `uiPlayPassesCompleteTitlesForRssCommonsAndAlreadyYoutubeItemsAndPersistsFirstReceipt` (ported) | Play for RSS/Commons/YouTube through the real root/gateway/room owner. | Complete query per provider; typed binding/receipt/recording lineage present in canonical acquisition state; receipt precedes preparation; no transfer/network. |
| `uiPlayThenDownloadKeepsSameReceiptAndOwnedBytesSurviveOfflineRoomCloseReopen` (ported) | Play then Download; cold Room reopen; wait for session maintenance/idle; occurrence-fenced local replay of the restored queue position. | One query, exact tap-time binding/receipt for download; owned file/hash/sha; typed claim/use/job lineage; cold validation through the typed local validator; no source search/resolve/transfer on replay. |

Files: `composeApp/src/commonMain/kotlin/dev/veyra/app/AppContainer.kt`,
`composeApp/src/desktopTest/kotlin/dev/veyra/app/AppContainerIntegrationTest.kt`.

## R8 — blocking page failure through typed selection and restore

| Production change | Test | Assertion |
|---|---|---|
| `youtubeAcquisitionResult` applies a blocking page `AppFailure` **before** the valid-video `Single` branch. The raw ordinal-zero entry/receipt is still returned with `Unplayable(FIRST_RESULT_UNAVAILABLE)`. | `YouTubeAcquisitionGatewayTest.validFirstVideoOnABlockingPageIsUnplayableAndKeepsTheTruthfulRawReceipt` | Problem code, raw id/title/url/ordinal preserved; entry issue stays truthful. |
| Non-blocking page warnings still allow a supported first video. | `aNonBlockingPageWarningDoesNotDestroyAValidSupportedSelection` (real kmpYT parser/transport) | One query; `Single` with raw ordinal-zero receipt. |
| Blocking transport refusal never becomes Single and never issues a second query. | `aBlockingPageTransportRefusalNeverBecomesASingleOrASecondQuery` | `PROVIDER_ACCESS_REQUIRED`, one request, sanitized cause. |
| A blocked receipt is committed as a durable `AcquisitionOutcome.Unplayable`; no binding/occurrence is executable. | `AcquisitionCoordinatorTest.blockedPageWithAValidFirstReceiptStaysUnplayableAcrossRestoreAndCannotBind` (fake-port `AcquisitionTestRepository`) and `dev.veyra.data.RoomAcquisitionRestoreTest.blockedValidVideoOutcomeSurvivesRealRoomCloseAndReopenWithoutBindingOrSourceWork` (actual Room close/reopen) | Receipt retained; no binding; `boundOccurrence` rejects; repeated resolve does not re-query. The fake-port case is not Room proof; the jvmTest class executes actual Room close/reopen and proves the same persisted Unplayable outcome/problem with no binding/occurrence or source query/inspect. |

Files: `YouTubeAcquisitionGateway.kt`, `YouTubeAcquisitionGatewayTest.kt`,
`AcquisitionCoordinatorTest.kt`.

## R9 — wrong-reason successes and prepared contracts

### Asset uniqueness

| Case | Setup | Assertion |
|---|---|---|
| Duplicate asset identity | Supported validator on a two-download/one-asset graph; same-owner Room `writerRowsUpdate` remaps the second download to the existing asset AND adds a VALID unrelated legacy favorite (`music-2`) in that same transaction; raw SQL attempts a second row with the same primary key but a distinct URI. | Exact `"Offline asset identities must be unique"` from the transactional validator; exact `UNIQUE constraint failed: offline_assets.id`; the unrelated favorite row is absent after rollback; full graph/revision unchanged; cold reopen equal; one asset row. |
| Duplicate asset URI | Two distinct asset ids claim the same owned URI AND the same transaction adds a VALID unrelated legacy favorite (`music-2`); raw SQL attempts a second row with a new id and the same URI. | Exact `"Offline asset files must have one owner"`; exact `UNIQUE constraint failed: offline_assets.uri`; the unrelated favorite row is absent after rollback; full graph/revision unchanged; cold reopen equal; no dedup/partial write. |

File: `shared/src/jvmTest/kotlin/dev/veyra/data/RoomWriteVerificationTest.kt`.

### Prepared download gateway

| Contract | Test | Assertion |
|---|---|---|
| Simultaneous single consumption | `simultaneousTransferConsumesOneCapabilityAndTheOtherFailsWithoutExtraWork` | One success, one `INVALID_STATE YOUTUBE_PREPARED_DOWNLOAD_CONSUMED`; exactly one transfer and one initial inspection; loser does no extra work. |
| Foreign genuine capability | `genuineCapabilityFromAnotherSourceIsRejectedWithoutConsumingOrUsingForeignWork` | `YOUTUBE_PREPARED_DOWNLOAD_MISMATCH` before foreign getters/search/transfer; issuer can still consume its capability. |
| Nullable key vs explicit keys | `nullableKeyIsNotAMatchKeyButTheFrozenRepresentationIsTheActualTransferPlan`, `explicitMatchingCallerKeyPreparesTheExactRepresentationAndNonmatchingKeyFailsClosed` | Frozen representation equals the actual transfer result/plan; explicit match succeeds; non-match fails `RECOVERY_REQUIRED YOUTUBE_SELECTED_TRACK_CHANGED` with zero transfer. |
| One total renewal | `initiallyExpiredPreparationSpendsTheOneTotalRenewalAndLaterExpiryCannotRenewAgain` | Preparation spends exactly one re-inspection; later queued expiry fails `YOUTUBE_SOURCE_EXPIRED` with no additional lookup/transfer. |
| Changed renewed representation | `changedRenewedRepresentationIsRefusedWithAUsableUrlAndNoTransfer` | Renewed URL is usable; refusal detail is the exact same-key mismatch; one transfer attempt, two total inspections, capability terminal. |
| Existing preserved contracts | Existing tests plus all new tests | No DOWNLOAD `PlaybackResource`, same-client retained initial plan, cancellation/terminal consumption, sanitized errors. |

File: `shared/src/commonTest/kotlin/dev/veyra/source/youtube/YouTubePreparedDownloadGatewayTest.kt`.

## R6 — retained ports

### Fixed

| Method | Cause | Resolution |
|---|---|---|
| `DownloadPolicyRoomTest.twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit` | Two real Room owners race slot admission after 4→1 revocation. The scheduler correctly takes its documented bounded local recheck (5/10/20/40/60 s) on the injected clock; wall time alone could never reach that deadline. | The test advances exactly the published bounded deadline only while no physical owner is busy, and keeps the 1/2/4-slot, revocation, single-owner-query and all-complete assertions. Run 023 retained XML records **one test / one failure** (teardown), so it is not green evidence; run 025 and the full run 027 pass this method. |
| `DownloadMemberRetryLifetimeRoomTest.coldReopenRetainsRetryAndLaterActivatedExecutionUsesTheOriginalAcceptedContextAndKey` | The older cold member-retry trace (`.work/verification/pr4-review-corrections/run-011-full-shared/stdout.log`) records an **AssertionError**, not an established 15-second timeout. The exact failed assertion/root cause is not retained in the short console trace; it was not reproduced in the later lead run. | Re-ran the exact method three times (all pass). No assertion removed. This remains an observed intermittent with unknown root cause and is not claimed as fixed; three passes do not establish a repair. The ownership/recovery lane owns further investigation. |

### Still failing — legacy snapshot fixtures (50)

All fail at `DownloadFixture.seedFinalization/seedComplete/seedPending` (or equivalent
legacy snapshot seeding) with `INVALID_STATE`, before their intended assertions:

- `RoomLibraryStoreTest` (2): `downloadUniquenessLeaseAndAttemptFinalizationAreTransactional`,
  `cleanupIntentBlocksOrdinaryClaimAndSurvivesRestart`.
- `DownloadCleanupRoomTest` (4): `maintenanceClaimFailureBeforeAndAfterCommitKeepsFileAndRoomOwnershipUntilExplicitClose`,
  `maintenanceAllowanceFailureRetainsFinalizationFilesAndRoomClaimThroughIndependentDrain`,
  `partialMaintenancePathAcquisitionFailureRetainsRealFirstLeaseBeforeRoomClaim`,
  `maintenanceFileCloseFailureKeepsRoomClaimWithoutRepeatingConsumedAllowance`.
- `DownloadHashCancellationRoomTest` (3): `cancelledSourceCloseCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen`,
  `cancelledEofReadCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen`,
  `closeDuringARealCompletedHashReadWaitsForSourceUnwindAndReopenCanCheckWithoutAcquisition`.
- `DownloadIntegrityRoomTest` (9): `bindingChangedDuringFinalizationValidationCannotRenameOrCommitAgainstTheReplacement`,
  `removedAndRecreatedDownloadIdsCannotInheritAStaleReadyMapOrCheckedReceipt`,
  `missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording`,
  `changedBindingValueUnderTheSameIdInvalidatesTheExactCompletedCheckReceipt`,
  `bindingChangedDuringCompletedValidationCannotPublishACheckForTheReplacement`,
  `truncatedCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording`,
  `invalidCompletedMaterializationLinksFailBeforeNativeValidationWithoutLosingTheAsset`,
  `nonregularCompletedPathIsNeverReadyAndExplicitRetryRetainsItsRecording`,
  `invalidFinalizationLinksFailBeforeNativeValidationWithoutLosingPublishedOrStagedBytes`.
- `DownloadMaintenanceRoomTest` (8): `completedMaintenanceCloseAndQueuedCallbackCannotSuspendAFreshTransfer`,
  `expiredCheckpointRollbackStillCancelsMaintenanceAndFinishRetainsTypedJournal`,
  `cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback`,
  `cleanupCannotReleaseNativeReadersOrDeleteBeforeItsAllowanceIsGranted`,
  `startupReturnsWithHonestCheckingProjectionAndDoesNotBlockAnotherAcquisition`,
  `cancelledDispatcherReturnAfterRealMaintenanceCommitReleasesBeforeGateAcquisition`,
  `cancelOnlyExpirationAllowsExplicitForegroundResumeWithoutKillingRecoveryScheduler`,
  `deniedCompletedCheckKeepsDurableCompleteButPresentsNeedsForegroundUntilAnAllowedRecheck`.
- `DownloadOwnershipRoomTest` (5): `removeDuringNoncancellableValidationCannotPublishOrResurrect`,
  `changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates`,
  `twoReplayersHaveOneDurableClaimAndOnePublisher`,
  `publishedFailedIntentIsRecoveredAfterForeignLeaseExpiresWithoutRestart`,
  `staleEnqueueResumeAndPauseCannotDemoteOtherConnectionsCompletedAsset`.
- `DownloadRecoveryRoomTest` (4): `partialDeletionKeepsJournalForPriorAssetAndIntentDestinationUntilReopenCompletesAll`,
  `startupSameSizeCorruptionFailsDurablyAndRetryDeletesOldGenerationBeforeReacquiring`,
  `removeWaitsForActualReleaseCallbackAndCanBeReenteredWithoutCommandLockInversion`,
  `redownloadOfCorruptUnpublishedIntentCleansItInsteadOfReplayingForever`.
- `DownloadSchedulingRoomTest` (15): listed verbatim in the run-027 XML/log.

These are **not** weakened or reclassified as passes. They still use the legacy
`DownloadFixture`/`AppSnapshot.downloads` world whose writes schema 3 intentionally rejects;
each body needs a canonical typed-command port (typed request/binding/job/claim/attempt/
finalization/materialization state) with its original failure/rollback/cleanup claim retained.

### Still failing — AppContainer composition (21)

`musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation`,
`uiLibraryDoesNotAdvertiseRestartedRoomCompleteAsReadyBeforeThisProcessValidatesIt`,
`unknownAndDisconnectedPlatformNetworkPreventNewNetworkClaimsUntilConnected`,
`slowDownloadRemovalCleanupCannotBlockUiPauseOrPersistedOfflineChange`,
`podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording`,
`rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky`,
`schedulingOnlyPauseCancelAndRemovePersistAcrossRoomReopenWithoutMediaDirectoriesOrNativeMaintenance`,
`cancelledAppCloseCallerCannotSkipRetainedDownloadCleanupOrItsDelayedDependencyDrain`,
`cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce`,
`uncoveredMusicCollectionPodcastAndResumeTapsRejectAtThePublicBoundaryEvenWithAHeldActorAndCannotReplayAfterCancel`,
`finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`,
`productionGatewayRejectsFirstChannelInsteadOfPlayingLaterVideoAndKeepsRawReceiptInRoom` (expects the legacy
`PlaybackBinding` snapshot; canonical typed receipt is an Unplayable outcome, so the assertion/editing seam
still needs the typed port),
`handedDownloadResourceFailureRetainsRoomRuntimeClientsAndLifetimeUntilExplicitCloseRetry`,
`acceptedCoveragePrecedesEveryGateAndTheExistingDurableQueueExecutesOnlyAfterThatAck`,
`coldSchedulingBootstrapPreservesRestorableCollectionQueueAndHistoricalAssetWithoutOpeningAUserSession`,
`offlineToggleCancelsRunningGatewayCoroutineAndClosesLateResultBeforeNativeHandoff`,
`queuedDownloadRetainsTheRecordingChosenAtIntentAfterAnotherFreshPlayOfSameItem`,
`persistentlyFailedRejectedResourceRemainsRetriableAfterFinalCloseWithoutRepeatingGlobalClosures`,
`throwingDownloadAllowanceRetainsDependenciesWithoutReclosingTheAlreadyConsumedResource`,
`explicitCoverageRetryPreservesUserPauseAndFutureBackoffUntilItsOriginalDeadline`,
`ownedFileRemovalWaitsForNativeStopButDoesNotBlockOfflineUiIntent`.

Root causes observed: waiting on `store.state.downloads`/`playbackBindings` (legacy projections
that never contain typed physical jobs/bindings) and seeding download rows through the guarded
snapshot path. The two ported Play/Download tests now use canonical acquisition views instead.

## Actual evidence (this worktree)

All runs used `scripts/gradle.sh --offline`, the literal bounded JVM argument
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
the ignored initializer `.work/verification/pr4-r6-r9-fixes/bounded-tests.init.gradle`, fresh
XML output and a project-local cache. Native/audio/source answers remain authored fakes over
non-audio bytes; Room/SQLite/files/OS leases are real.

| Run | Command | Exit | Actual result | Evidence |
|---|---|---:|---|---|
| 001/002/004..019 | focused shared/common/gateway/Room/app tests | 0 on final repetitions | R7/R8/R9 contract tests and ported composition tests pass | `.work/verification/pr4-r6-r9-fixes/run-00*/` |
| 023 | EX34 scheduler case | 1 | **1 test / 1 failure (teardown)**; retained as a failed attempt, not green evidence | `run-023-scheduler/` |
| 025 | EX34 scheduler case (same method, later run) | 0 | 1/2/4 slots, 4→1 revocation, bounded recheck, all 7 complete | `run-025-scheduler/` |
| 026 | member-retry cold reopen, 3 repetitions | 0,0,0 | same accepted context/key; one preparation/transfer; no new query | `run-026-member-retry-*/` |
| 027 | full `:shared:jvmTest` | 1 | **1162 tests / 96 suites, 50 failures, 0 errors, 0 skipped** (scheduler fixed; 9 tests added) | `run-027-full-shared/`; log SHA-256 `ec1cad03cbbd1dc99f6ec30045a4537ad066c3cc0eed4a1f058b20beca2f1f82` |
| 028 | `:composeApp:desktopTest --tests dev.veyra.app.AppContainerIntegrationTest` | 1 | **35 tests, 21 failures, 0 errors, 0 skipped** (2 fixed, 2 added) | `run-028-app-container/`; log SHA-256 `9f07d65af5fa91500203971c273b9f3494fd9516a9aa93021260c9602f6afe8e` |
| 029 | `:shared:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid` | 0 | both Android production compilations pass | `run-029-android-compile/`; log SHA-256 `7ead698b92a80c08509d98835ea0ec1a0b0eda6423966ed8db6a673f7119c42e` |
| — | `:shared:compileKotlinJvm`, `:shared:compileTestKotlinJvm` | 0 | JVM shared + tests compile | `compile-001/` |
| — | `:composeApp:compileKotlinDesktop`, `:composeApp:compileTestKotlinDesktop` | 0 | desktop + desktop tests compile | `compile-002/` |

Pin/schema constants checked: kmpYT gitlink and `gradle/kmpyt.commit` remain
`c0a08090fe7dbd76e20216559b8184ad2cccde63`; export
`shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json` SHA-256 remains
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 tables,
identity `a51a13de7a6c06c5b3ff5f9890c7ddd2`); no schema/migration change.

## Remaining platform verification

iOS/Xcode is unavailable in this environment. This slice does not run emulator/simulator,
physical device, live source, real audio/crossfade, packaging or distribution checks. Task B
performs the native/platform verification after the lead accepts the Task A integration SHA;
physical crossfade and source authentication are not Task A prerequisites, while the existing
release holds stay in force.
