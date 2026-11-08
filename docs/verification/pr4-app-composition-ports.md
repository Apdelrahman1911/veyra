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
pin, workflow or native/platform implementation was changed. The R12
continuation used the same accepted model settings and changed only desktop test
source, this verification document and ignored `.work` evidence; the absence of
independent backend/runtime/variant attestation still applies.

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
a schema-3 store. Historical `playback_bindings` rows exist only as real rows
created in the exported schema-2 fixture before the production migration; no
schema-3 legacy binding is fabricated.

`AppContainerSchema2Fixture` creates `library/veyra.db` from the actual exported
`shared/schemas/dev.veyra.data.room.VeyraDatabase/2.json`: every exported table,
index and `setupQueries` entry is executed after literal `${TABLE_NAME}`
substitution, `PRAGMA user_version` is set to 2, and only authored baseline rows
exist (both queue contexts, store meta, settings, one COMMONS catalog media,
one track, one ordinal-zero VIDEO binding). It creates no offline asset,
download, attempt, finalization, claim, queue occurrence, canonical occurrence
or local media file. Room then opens that file through the production
`LibraryStore`/`AppContainer`, so `MIGRATION_2_3` genuinely runs. The current
catalog title intentionally differs from the historical binding query; raw
schema-2 `media`/`tracks`/`playback_bindings`/`queue_contexts` scalars and the
canonical `SCHEMA2_EXECUTABLE` binding/`FirstResultReceipt.Schema2` are asserted
after migration and after cold reopen. This is not `MigrationTestHelper` (its
dependency is only in shared test source) and not a raw schema-3 seed.

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

The class now has 39 authored test methods: the 37 previously listed (35
original methods, one explicitly renamed, and the two Start Over controls) plus
the two external historical-receipt controls added for R12:
`finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`
and
`offlineToggleCancelsExternalHistoricalResolverAndClosesLateResultBeforeNativeHandoff`.
The exact executed count is taken from the fresh XML, never from an authored
literal.

## Method mapping (original → typed seam → retained assertions)

All 35 original methods are preserved. One method is explicitly labelled
app-owned because its caller is the controller:
`finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`
→ `finalCloseCancelsAppOwnedTypedResolveAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`.
That label does **not** mean the external historical route disappeared. A NEW
fresh legacy acquisition after schema 3 is intentionally guarded (the snapshot
binding write is rejected), but `AppSourceResolver.resolveWithBinding(...,
reuseBindingId)` rejoins a preserved schema-2 VIDEO receipt without a new
first-result query or legacy binding write. Migration 2→3 preserves those
bindings as `SCHEMA2_EXECUTABLE`/`FirstResultReceipt.Schema2`, and
`AppContainer.resolver` is still public. R12 therefore restores the original
external-coroutine condition as a separate, real migrated-history control
rather than treating "new legacy writes are forbidden" as sufficient.

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
| `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` → `finalCloseCancelsAppOwnedTypedResolveAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` (explicit rename) | app-owned typed controller resolve; close awaits policy-return cleanup | close blocked until the late resource is released; no runtime/source/http/Room close early; sanitized sticky failure; one global close set; app-owned resolver cancellation observed. This is the honest app-owned control; the preserved schema-2 external caller is restored separately below |
| `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies` (R12 restored control) | real migrated schema-2 VIDEO receipt through public `AppSourceResolver.resolveWithBinding(..., reuseBindingId)` from a coroutine outside appLifetime/playback/effects | no fresh first-result query, request/capture, receipt/binding write or native handoff; close's real online closed observer cancels the external caller; first rejected-resource release is held and fails once; shutdown cannot finish and Room/runtime/youtube/HTTP stay open; explicit close retries the retained resource exactly once; sticky `UNEXPECTED` has no raw diagnostic/cause; one global dependency-close set; historical scalars/receipt remain exact |
| `offlineToggleCancelsExternalHistoricalResolverAndClosesLateResultBeforeNativeHandoff` (R12 external control) | same migrated schema-2 historical receipt and external caller; app `offlineOnly` policy change cancels it | late returned resource closes exactly once before native handoff; zero fresh query/request/receipt/transfer/native/validator work; app stays `Ready` and dependencies remain open (distinct from app close); binding/media scalars unchanged; cold reopen preserves the exact `SCHEMA2_EXECUTABLE` receipt without recapture |
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
- `finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies`:
  R12 control. Real exported-schema-2 → production-schema-3 AppContainer opens
  one authored COMMONS catalog row, one track and one historical VIDEO binding
  (ordinal 0, canonical locator, full historical query, no completed/offline
  asset or download). The public resolver is called with the exact
  `reuseBindingId` from a coroutine external to appLifetime/playback/effects.
  The gateway holds, catches the real close-driven cancellation and returns an
  authored late resource; its first release is held and fails once. While
  blocked, `AppContainer.close` cannot complete, Room stays usable/Ready, and
  runtime/youtube/HTTP are not closed; the native engine receives no prepare.
  Releasing the hold makes explicit close drain the retained owner, retry once,
  retain the sanitized sticky `UNEXPECTED` failure without a cause/raw text, and
  close each dependency exactly once. No fresh query/request/capture/receipt/
  binding/transfer is created.
- `offlineToggleCancelsExternalHistoricalResolverAndClosesLateResultBeforeNativeHandoff`:
  R12 control. The same real migrated receipt is resolved from an external
  caller; changing `offlineOnly` cancels the live policy operation through the
  real offline observer. The late resource closes once before any native
  handoff; the app remains Ready and Room/runtime/youtube/HTTP remain open,
  distinguishing policy cancellation from app shutdown. Query count stays zero,
  the historical binding survives unchanged, and a cold reopen preserves the
  same `SCHEMA2_EXECUTABLE` receipt without new work.

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
- The typed final-close method is explicitly labelled to its app-owned scope.
  It is not a substitute for the external historical-receipt contract: a NEW
  fresh legacy acquisition cannot write a schema-3 binding, but the public
  resolver's `reuseBindingId` route still executes a preserved schema-2 VIDEO
  receipt. The original external condition is restored as its own real
  migrated-history control.
- R12 correction: the 37-test class retained many safety assertions but had
  moved the original external-coroutine shutdown case to the controller-owned
  typed route. The two new controls exercise the reachable historical route:
  exact exported-schema-2 → production-schema-3 migration, authored gateway
  answers, external caller cancellation by the real closed/offline observers,
  held late-resource release, explicit retained-resource retry, sticky
  sanitized failure, exact dependency ordering, and a cold reopen that proves
  no recapture/new work. No production code was changed for R12; this closes a
  test-coverage gap, not an observed production leak.
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

The previous 37-test full-class acceptance run is `run-full-004` at code HEAD
`8411190d61bbe9c8ea462cd7fc5b43c6b3864d5f`; it remains historical evidence
for the pre-R12 code and is not relabelled as the R12 acceptance:

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

### R12 clean-tree acceptance (`49a4eec`)

`run-accept-clean-001` ran at clean code HEAD
`49a4eecaa910bfd55f16bf6da716b3c472cf6b28` with the shared lock held for the
full process (`--offline`, `--rerun-tasks`, task-owned init). The tracked tree
was clean and there were no untracked non-ignored inputs.

- `:composeApp:compileKotlinDesktop` and
  `:composeApp:compileTestKotlinDesktop` actually executed (not UP-TO-DATE) in
  the same invocation;
- `:composeApp:desktopTest` with four `--tests` filters executed **69 tests,
  0 failures, 0 errors, 0 skipped**, exit 0, `BUILD SUCCESSFUL`, 23 executed
  tasks:
  - `AppContainerIntegrationTest`: **39** methods (all 37 prior methods plus
    the two R12 controls);
  - `DesktopLifetimeTest` 7; `DesktopPlatformServicesOwnershipTest` 18;
    `DesktopStartupJobsTest` 5;
- UTC window 2026-11-07T20:09:45Z..20:13:21Z; log 27,699 bytes SHA-256
  `6c6ea1f77a959678eff85b36e0f616c0e4e42d05c7d5ef80c9f47368a773bccd`;
- XML SHA-256:
  - `TEST-dev.veyra.app.AppContainerIntegrationTest.xml`
    `36cc57e8d7c56d5146bceac8734c4cea250489e2b0fc14b827743d0a9d92f094`;
  - `TEST-dev.veyra.desktop.DesktopLifetimeTest.xml`
    `c248551192e6691b6e35d49b875da9841f77d27ac3d09f4fc608f0f372472f11`;
  - `TEST-dev.veyra.desktop.DesktopPlatformServicesOwnershipTest.xml`
    `2180e1ffc46fb41de83cd5b0bf1f5b801f8cbb7ce2743dbbb6b6484bbc6baee7`;
  - `TEST-dev.veyra.desktop.DesktopStartupJobsTest.xml`
    `9ce7723a77217032013b8b0b3c3bf2fe08d20b54485c1b185570fed2c7db0c9f`;
- fail-closed before/after manifests equal: 509 file entries (508 regular
  tracked files plus the kmpYT gitlink), no untracked inputs; actual
  checked-out submodule HEAD/status `c0a08090...`/clean; indexed gitlink and
  `gradle/kmpyt.commit` `c0a08090...`; schema export `aab30e43...` unchanged;
- focused pre-commit `run-focused-002` executed the two R12 methods 2/0/0/0,
  exit 0; `compile-002` compiled the desktop test source, exit 0.
  `run-full-001` and `run-headless-001` at the same code SHA were run while this
  document was locally modified; they passed but are explicitly superseded by
  the clean run and are not the final clean-tree receipt.

| Run | HEAD / tree | Scope | Result |
|---|---|---|---|
| `run-focused-001` (red) | `66677e8` + dirty port (pre-repair) | 6 AppContainer methods | 2 failures / 0 errors / 0 skipped, exit 1: verified Start Over red (`OFFLINE_POLICY/acquisition_offline`, `selection=PENDING`, `binding=null`, `media=2`) plus a test-owned held-writer read deadlock (corrected later) |
| `run-accept-001` | `4b7ff20` | compile, Start Over/held-actor, 4 headless classes, Android shared compile | 38 tests / 5 failures / 0 errors / 0 skipped across five classes, exit 1: AppContainer 3 tests/2 failures (two test-logic assertions fixed later); headless **35 tests/3 failures** (DesktopLifetime 7/0, PlatformOwnership 18/0, StartupJobs 5/0, RetainedLifetime 5/3). `:shared:compileDebugKotlinAndroid` completed; **`:composeApp:compileDebugKotlinAndroid` did not execute** because Gradle stopped at the failing desktop test task |
| `run-full-001` | `4d8ab98` | full AppContainer class | 36 / 1 / 0 / 0, exit 1: the zero row was durably written in canonical `catalog_progress`; the test read the legacy snapshot instead |
| `run-full-002` | `7b6bd05` | full AppContainer class | 36 / 1 / 0 / 0, exit 1: `acquisitionView().snapshot` is still the legacy progress relation; canonical `playbackView()` checkpoint/catalogue-progress reads were added at `8411190` |
| `run-full-003` | `d06b53f` | full AppContainer class (pre-review-correction code) | 36 / 0 / 0 / 0, exit 0; superseded by the review-correction code and not claimed as final |
| `run-final-001` | `d06b53f` | three headless classes + Android shared/app compile (pre-review-correction code) | 30 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL; `:composeApp:compileDebugKotlinAndroid` executed; superseded for final acceptance by `run-final-002` |
| `run-full-004` | `8411190` | full AppContainer class (previous 37-test corrected code; historical) | **37 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL**; fail-closed equal inputs, actual submodule clean at `c0a08090...` |
| `run-final-002` | `8411190` | three relevant headless classes + Android compile tasks (historical) | **30 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL**; headless 7/18/5 all pass; both Android compile tasks reported UP-TO-DATE |
| `run-android-002` | `8411190` | forced executed Android shared/app compile (historical) | **exit 0, BUILD SUCCESSFUL**; both `:shared:compileDebugKotlinAndroid` and `:composeApp:compileDebugKotlinAndroid` **executed** (52 tasks executed, `--rerun-tasks`), fail-closed equal inputs; window 2026-11-07T17:36:30Z..17:39:16Z, log 8,643 bytes SHA-256 `70c10f63fe42ea48321a0c0c0b907efe7bc413555e39866ada179e038faa6f2a` |
| `compile-002` | `462d7ab` + same working source | desktop test compilation before the code commit | exit 0, BUILD SUCCESSFUL; same two-file source content later committed as `49a4eec`; before/after inputs equal |
| `run-focused-002` | `462d7ab` + same working source | the two R12 external historical-receipt methods | **2 / 0 / 0 / 0**, exit 0, BUILD SUCCESSFUL; XML SHA-256 `e5d0f6de8686ab69faaccde161a2e844637462739da6aff628115e8d3ebc85e0`, log 5,502 bytes SHA-256 `a5f6b855161429402c6dac768335a293441b3744b41adbb5e89d4da4b9dca43c` |
| `run-full-001` / `run-headless-001` | `49a4eec` + uncommitted doc | 39 AppContainer / 30 headless | 39/0/0/0 and 30/0/0/0, exit 0; retained, but accompanied by an uncommitted documentation edit and **superseded** by `run-accept-clean-001` |
| `run-accept-clean-001` | `49a4eec` (clean tree) | forced desktop main/test compilation + 39 AppContainer + 30 headless methods | **69 / 0 / 0 / 0, exit 0, BUILD SUCCESSFUL**; compile tasks executed; fail-closed equal inputs, clean submodule/pin/schema; log and XML hashes above |

`DesktopRetainedLifetimeTest` (5 tests) is unchanged from the parent and still
authors the removed DOWNLOAD `PlaybackResource`; its three failures
(`retainedDownloadAllowancePinsHostGraph...`,
`concurrentAndCancelledCallersShareOneRetainedAttempt...`,
`cancellationCannotReleaseTheHost...`) are an **unaccepted Task A integration
blocker**, not waived here. They belong to a separate isolated test-only lane
that must port that class's host/process-lock guarantees; PR #5 does not own or
modify that file and does not claim its failures fixed or unrelated to Task A.
`run-final-001` is historical pre-review-correction evidence at `d06b53f`: it
also ran the three headless classes (7, 18 and 5 tests, 0 failures, exit 0;
window 2026-11-07T16:57:04Z..16:58:41Z; log 16,205 bytes SHA-256
`38c53e116dc6c87f60a852ddcee1fe48d99cdda74adfc27276c8bc918cb06352`), and in
that invocation the Android shared/app compile tasks **executed**, not
UP-TO-DATE. For that earlier `8411190` correction sequence, the headless run is
`run-final-002`: 30/0/0/0, exit 0, BUILD SUCCESSFUL, with both Android compile
tasks reported UP-TO-DATE; `run-android-002` separately forced actual execution
of both tasks at the same frozen SHA, exit 0, BUILD SUCCESSFUL. The two runs are
preserved with their actual labels and are not relabelled. The parent's known 50
shared-test failures are the sibling's scope and are not claimed fixed. No
iOS/Xcode/native/device, audio, signing or distribution proof is claimed;
native/source/transfer answers are authored fakes over non-audio bytes while
Room, SQLite files and OS locks are real.

The final clean acceptance run for this R12 correction
(`run-accept-clean-001`) was code-frozen at `49a4eec` with an empty
`git status` and fail-closed equal before/after manifests; its desktop main and
test compilation tasks actually executed. A later doc-only successor updates
this document and the PR description and is not claimed as a new code
execution; code files remain byte-identical to `49a4eec`. The tested code SHA
for the two R12 controls is therefore `49a4eec`. The `8411190` runs above remain
the preserved prior 37-test evidence, and `run-full-003`/`run-final-001` remain
preserved pre-review-correction history.
