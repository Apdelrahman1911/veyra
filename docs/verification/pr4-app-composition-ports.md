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

The class now has 37 tests: the 35 original methods (one explicitly renamed as
above), `podcastStartOverForANonCurrentEpisodeKeepsFreshAdmissionSemantics`, and
`podcastStartOverWithACompetingQueueActivationDoesNotRestartTheWrongOccurrence`.

## Method mapping (original → typed seam → retained assertions)

All 35 original methods are preserved. One method is explicitly renamed because
its external caller no longer exists on the schema-3 path:
`finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`
→ `finalCloseCancelsAppOwnedTypedResolveAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`.
The old external entrypoint was `AppContainer.resolver`; that legacy resolver
cannot acquire a schema-3 binding (legacy snapshot binding writes are
intentionally rejected), so it is not the app execution path and the app-owned
typed controller resolve is the legal current route. The rename is recorded in
the table below rather than silently changing the claim.

Every original safety claim is kept; where the old test described a removed
authority (legacy snapshot rows, legacy `PlaybackBinding`, DOWNLOAD
`PlaybackResource`, synthetic coverage/backoff rows), the replacement is
explained rather than loosened to "any failure".

| Original method | Typed seam | Retained guarantees |
|---|---|---|
| `productionGatewayRejectsFirstChannelInsteadOfPlayingLaterVideoAndKeepsRawReceiptInRoom` | production `YouTubeKmpMediaGateway` acquire → canonical Unplayable outcome | one complete-title query; raw CHANNEL ordinal-0 receipt/kind/id/title/public URL; no later-video binding/recording/subject; occurrence UNPLAYABLE; no legacy binding; no native prepare/extraction; typed failure is `RECOVERY_REQUIRED/acquisition_selection_unplayable` (the ambient `UNSUPPORTED_SOURCE` stage was removed) |
| `cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce` | `Play` → typed `preparePlayback` cancelled at the policy/online return | resource closed exactly once, never handed to native; one committed request and one binding, no legacy binding; close does not re-close |
| `rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky` | same, first release throws | failure sanitized (`UNEXPECTED`, no raw cause/text); owner retained and retried exactly once at explicit close; dependencies released once |
| `persistentlyFailedRejectedResourceRemainsRetriableAfterFinalCloseWithoutRepeatingGlobalClosures` | same, always failing until allow | one retry per explicit close; sticky failure; later success cannot retroactively certify clean shutdown; global closes exactly once |
| `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` → `finalCloseCancelsAppOwnedTypedResolveAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` (explicit rename) | app-owned typed controller resolve; close awaits policy-return cleanup | close blocked until the late resource is released; no runtime/source/http/Room close early; sanitized sticky failure; one global close set; app-owned resolver cancellation observed. The former external legacy-resolver entrypoint cannot acquire a schema-3 binding and is intentionally no longer the execution path |
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
| `uncoveredMusicCollectionPodcastAndResumeTapsRejectAtThePublicBoundaryEvenWithAHeldActorAndCannotReplayAfterCancel` | canonical paused/staged jobs + held actor/coverage | tap rejection is synchronous and not actor-dependent; paused status/intent is inspected through the already-published view while the authored Room writer is still held; both Cancel intents are admitted at the public boundary during the hold, then authoritative Room reads/cleanup run after release; no source/download execution; Cancel retains (Remove would be the separate non-retaining contract) |
| `musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation` | canonical complete/staged jobs; coverage failure | local intents persist while restricted; no gate/native work; activation ACKs coverage once and deletes removed assets/cancelled staging; paused work retained; immutable receipts survive; no validation/source calls |
| `podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording` | restored offline occurrence + delayed seek + StartOver | same occurrence/binding/local asset at 0 with a new generation; no new query/resolution/transfer; outgoing held seek not lost; a real native zero sample is flushed to the canonical occurrence checkpoint and catalogue progress; delayed old seek rejected; canonical checkpoint/catalogue progress/queue/controller at 1,000 ms after cold reopen; retained job/asset lineage. Typed progress is asserted through `AcquisitionRepository.playbackView()`, not the legacy `snapshot.progress` compatibility map |

New controls for the repaired path:

- `podcastStartOverForANonCurrentEpisodeKeepsFreshAdmissionSemantics`: a
  different, non-current episode keeps the established fresh `Play`
  admission/new request and never reuses the current episode's binding.
- `podcastStartOverWithACompetingQueueActivationDoesNotRestartTheWrongOccurrence`:
  after a competing canonical queue activation outside the playback owner, the
  advisory `currentSelection` can disagree with the authoritative
  `currentOccurrenceId`. Start Over neither reactivates the competing occurrence
  nor reuses the stale one; it falls back to fresh admission of the *requested*
  episode, and the competing occurrence stays historical. A binding/queue CAS
  failure after dispatch is propagated and never converted into a source
  fallback. The playback-state flow remains advisory; the authoritative queue
  and `PlayOccurrence` CAS remain the fence.

The existing screen-facing download/podcast projection is still the legacy
`library` snapshot (`snapshot.downloads`/`snapshot.progress`); typed physical
jobs and canonical catalogue progress are not yet projected there. This PR
deliberately does not claim that UI contract is verified and does not modify the
separate UI compatibility lane's files. The AppContainer test asserts the
canonical current-process projection honestly and records that the legacy
projection is empty for typed jobs, rather than treating emptiness as a passing
screen Ready state.

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
`restartCurrentPodcast(mediaId)` reactivates the exact CURRENT bound podcast
occurrence when the current selection is that catalogue episode, using
`PlaybackCommand.PlayOccurrence(queue revision, occurrence, expected binding,
startFromBeginning = true)` — the existing atomic Activate/checkpoint/reset path.
It does not capture a request, select by catalogue id, choose a duplicate, mint a
binding/use, write a separate progress snapshot, or perform source I/O while
offline. If no appropriate current bound occurrence exists, the established fresh
`Play` admission is preserved; a stale queue/binding CAS fails truthfully instead
of silently recapturing a different request. Controls cover both the non-current
episode fallback and a competing queue activation where the advisory selection
and authoritative current occurrence disagree.

## Review corrections applied

- The held-actor test keeps the authored settings transform/Room writer held
  while both Cancel intents are admitted at the public boundary; it inspects
  only the already-published `acquisitionViews.value`, then releases the writer
  and performs authoritative Room reads and cleanup assertions. The actor latch
  is a bound, not the fix.
- `run-full-002`'s cached-read explanation was wrong: `acquisitionView().snapshot`
  still reads the legacy progress relation. The canonical progress is
  `AcquisitionRepository.playbackView()`; the test now asserts the exact
  occurrence checkpoint and catalogue progress at 0 and 1,000 ms, plus cold
  reopen. `d06b53f`'s commit message is also imprecise: typed execution **does**
  write `catalog_progress`; only the legacy snapshot map is separate.
- Input capture now fails closed, records the actual checked-out submodule
  HEAD/status in addition to the indexed gitlink and `gradle/kmpyt.commit`, and
  includes owned untracked inputs. `run-focused-001` remains labelled diagnostic
  with unavailable before/after hashes.
- The final-close method is explicitly renamed to its app-owned typed scope; the
  legacy external `AppContainer.resolver` cannot acquire a schema-3 binding and
  is intentionally not the app execution path.
- A competing queue-activation control covers stale advisory selection versus the
  authoritative current occurrence; the competitor is never restarted or
  relabelled.
- The three `DesktopRetainedLifetimeTest` failures are not waived: they are an
  unaccepted Task A blocker owned by a separate isolated test-only lane. PR #5
  does not modify that file or claim its host/process-lock contract.
- Screen-facing music/podcast download and progress presentation still reads the
  legacy snapshot; that UI compatibility work is a separate lane and is not
  claimed verified here.

## Verification evidence

Every heavy operation used the shared
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`
with `flock -x`, held to process completion. One test fork, 384 MiB heap/256 MiB
metaspace, SerialGC, two CPUs, project-local Gradle/home/temp. Runs redirect
fresh exact-task XML with `-Dveyra.lead.evidence=<unique-dir>`.

The full-class acceptance run is `run-full-004` at code HEAD
`8411190d61bbe9c8ea462cd7fc5b43c6b3864d5f`:

- `:composeApp:compileKotlinDesktop` and
  `:composeApp:compileTestKotlinDesktop` succeeded in the same invocation;
- `:composeApp:desktopTest --tests dev.veyra.app.AppContainerIntegrationTest`
  executed **37 tests, 0 failures, 0 errors, 0 skipped**, exit 0, `BUILD
  SUCCESSFUL` (35 original methods, one explicitly renamed, two new controls);
- log: 15,264 bytes, SHA-256
  `14c9d6af084af239f7be7a45bf7b0a2450e00115abc95c1daa8fc4680ae440cb`;
- XML `TEST-dev.veyra.app.AppContainerIntegrationTest.xml` SHA-256
  `7f1e5fc8526b5d8dd3ebca2555649f4e72edb524326f51982c05879fb4dca1da`;
- UTC window 2026-11-07T17:21:18Z..17:22:33Z; clean worktree; fail-closed
  before/after input manifests equal (508 files, no untracked inputs); actual
  checked-out submodule HEAD/status
  (`c0a08090...`, clean) and indexed gitlink/pin
  (`c0a08090...`) plus schema `aab30e43...` recorded;
- `run-android-002` at the same SHA executed both Android compile tasks
  (`--rerun-tasks`, 52 tasks executed), exit 0, `BUILD SUCCESSFUL`.

| Run | HEAD / tree | Scope | Result |
|---|---|---|---|
| `run-focused-001` (red) | `66677e8` + dirty port (pre-repair) | 6 AppContainer methods | 2 failures / 0 errors / 0 skipped, exit 1: verified Start Over red (`OFFLINE_POLICY/acquisition_offline`, `selection=PENDING`, `binding=null`, `media=2`) plus a test-owned held-writer read deadlock (corrected later) |
| `run-accept-001` | `4b7ff20` | compile, Start Over/held-actor, 4 headless classes, Android shared compile | 38 tests / 5 failures / 0 errors / 0 skipped across five classes, exit 1: AppContainer 3 tests/2 failures (two test-logic assertions fixed later); headless **35 tests/3 failures** (DesktopLifetime 7/0, PlatformOwnership 18/0, StartupJobs 5/0, RetainedLifetime 5/3). `:shared:compileDebugKotlinAndroid` completed; **`:composeApp:compileDebugKotlinAndroid` did not execute** because Gradle stopped at the failing desktop test task |
| `run-full-001` | `4d8ab98` | full AppContainer class | 36 / 1 / 0 / 0, exit 1: the zero row was durably written in canonical `catalog_progress`; the test read the legacy snapshot instead |
| `run-full-002` | `7b6bd05` | full AppContainer class | 36 / 1 / 0 / 0, exit 1: `acquisitionView().snapshot` is still the legacy progress relation; canonical `playbackView()` checkpoint/catalogue-progress reads were added at `8411190` |
| `run-full-003` | `d06b53f` | full AppContainer class (pre-review-correction code) | 36 / 0 / 0 / 0, exit 0; superseded by the review-correction code and not claimed as final |
| `run-final-001` | `d06b53f` | three headless classes + Android shared/app compile (pre-review-correction code) | 30 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL; `:composeApp:compileDebugKotlinAndroid` executed; superseded for final acceptance by `run-final-002` |
| `run-full-004` | `8411190` | full AppContainer class (final corrected code) | **37 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL**; fail-closed equal inputs, actual submodule clean at `c0a08090...` |
| `run-final-002` | `8411190` | three relevant headless classes + Android compile tasks | **30 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL**; headless 7/18/5 all pass; both Android compile tasks reported UP-TO-DATE |
| `run-android-002` | `8411190` | forced executed Android shared/app compile | **exit 0, BUILD SUCCESSFUL**; both `:shared:compileDebugKotlinAndroid` and `:composeApp:compileDebugKotlinAndroid` **executed** (52 tasks executed, `--rerun-tasks`), fail-closed equal inputs; window 2026-11-07T17:36:30Z..17:39:16Z, log 8,643 bytes SHA-256 `70c10f63fe42ea48321a0c0c0b907efe7bc413555e39866ada179e038faa6f2a` |

`DesktopRetainedLifetimeTest` (5 tests) is unchanged from the parent and still
authors the removed DOWNLOAD `PlaybackResource`; its three failures
(`retainedDownloadAllowancePinsHostGraph...`,
`concurrentAndCancelledCallersShareOneRetainedAttempt...`,
`cancellationCannotReleaseTheHost...`) are an **unaccepted Task A integration
blocker**, not waived here. They belong to a separate isolated test-only lane
that must port that class's host/process-lock guarantees; PR #5 does not own or
modify that file and does not claim its failures fixed or unrelated to Task A.
The other three relevant headless classes passed at the final
code SHA in `run-final-001` (7, 18 and 5 tests, 0 failures, exit 0; window
2026-11-07T16:57:04Z..16:58:41Z; log 16,205 bytes SHA-256
`38c53e116dc6c87f60a852ddcee1fe48d99cdda74adfc27276c8bc918cb06352`; Android
`:shared:compileDebugKotlinAndroid` and
`:composeApp:compileDebugKotlinAndroid` both reported UP-TO-DATE in that
invocation; `run-android-002` separately forced actual execution of both tasks
at the same frozen SHA, exit 0, BUILD SUCCESSFUL). The parent's known 50 shared-test failures
are the sibling's scope and are not claimed fixed. No iOS/Xcode/native/device,
audio, signing or distribution proof is claimed; native/source/transfer answers
are authored fakes over non-audio bytes while Room, SQLite files and OS locks are
real.

All acceptance runs after the review corrections were code-frozen with
`git status` clean and fail-closed equal before/after manifests; the later
doc-only update is not claimed as a new code execution. The tested code SHA is
`8411190`; its doc-only successor is identified in the PR. `run-full-003` and
`run-final-001` remain preserved but describe the pre-review-correction code.
