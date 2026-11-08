# Typed download screen compatibility (Task A)

Status: **implementation complete in a draft stacked PR; focused verification is green, release
gates remain held.** This is compatibility repair for the new Room/download runtime, not Task B
execution and not a release claim. PR #4 remains draft; this branch is not merged.

## Source, ancestry and launch settings

- Repository: `https://github.com/Apdelrahman1911/veyra`
- Integration parent inspected: `9b1b0479a2748012988d378d1f631cf5a50fff79`
- Parent draft PR #4 head: `67594669c6cd51e63ca772b281d4fc56144cafdd`
  (`t3/typed-room-download-recovery`), a fast-forward descendant of the integration parent.
- This worktree was clean at `9b1b047...`; the supplied local branch was fast-forwarded only to
  `6759466...` after ancestor verification. No reset/rebase/force-push or other tree was changed.
- Tested code/test commit: `462858b86afeca1621d56975484c9edab92d5c87`
  (`Keep verified neutral downloads displayable without catalogue binding`). This is the F1
  correction on top of the original typed-screen commit; the evidence below was captured at that
  exact commit. The final docs-only head may advance; production/test hashes remain equal.
- Local branch: `t3/typed-download-screen-compat`; pushed as the scoped stacked branch
  `t3/pr4-typed-download-screen-compatibility`; PR base `t3/typed-room-download-recovery`.
- Accepted launch settings: OpenCode provider `opencode`, model
  `opencode-go/deepseek-v4.1-flash`, variant `max`. No provider/model/variant substitution and
  no child agents were used. No independent backend/runtime attestation is exposed by the harness;
  the model/variant is an accepted launch configuration, not a third-party attestation.
- Pin: gitlink and `gradle/kmpyt.commit` both
  `c0a08090fe7dbd76e20216559b8184ad2cccde63`.
- Room schema 3 export:
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`
  (92 tables, identity `a51a13de7a6c06c5b3ff5f9890c7ddd2`). No schema, pin, dependency or
  workflow file changed.

## Exact before/after behavior

The old screens read only `AppSnapshot.downloads`, which `projectLegacyPhysicalDownloads`
deliberately limits to pre-existing schema2 rows. New typed jobs were therefore invisible, and a
cold durable `COMPLETE` could make an old schema2 UI say Ready before this process checked it.

| Existing surface | Before | After |
| --- | --- | --- |
| `MusicMediaRow` / media menu | looked up one `DownloadRecord`; typed catalogue jobs hidden | consumes the paired `DownloadSurface`; typed job id, projection status/progress and actions are visible; Ready requires checked exact use |
| Library Downloads list/filter/storage | `snapshot.downloads` only | legacy rows plus typed catalogue/neutral rows; active, completed and attention filters use the actual projection; ready physical assets counted once by asset id |
| Track detail download section | legacy record only | typed entry status/progress/failure; Pause/Resume/Cancel/Remove use the physical job id; cold `COMPLETE` projects checking/needs-foreground, never Ready |
| Collection download readiness | legacy record only | same decision over the paired surface; a checked ready typed row is not offered for download again |
| `MusicPresentation.libraryMedia` | only snapshot downloads | typed catalogue subjects are library members; neutral source subjects are not |
| Podcast episode state/row/menu/detail | legacy `DownloadRecord` only | `typedDownload`/`activeDownload` from the paired surface; exact job-id controls, downloaded filter, checking disclosure, English/Arabic labels and RTL layout retained |
| Podcast downloaded filter | legacy committed assets | exact-ready typed catalogue media only; active/checking/neutral rows do not enter the filter |
| App composition (`VeyraApp`) | passed `container.library` and `MusicUiOptions` only | collects the one `downloads.acquisitionPresentation` StateFlow, builds one pure surface, and passes it to existing music/podcast screens; no new controller, client, player or Room writer |

Schema2 histories keep their existing semantics. A typed catalogue row displays the genuine
catalogue `MediaItem` through its `CatalogMedia` subject. A neutral `SourceRecordingSubject` row
shows the captured request/observed title plus a localized "Source recording" label, never a
fabricated RSS episode, catalogue item, `OfflineAsset`, `PlaybackBinding`, `MediaItem`, source URL,
native handle or Room row. Its row-level exact-use `ready` can be true once this process verifies
the retained bytes, but it has no `mediaId`, no catalogue binding, no catalogue membership and no
Play action; `readyMediaIds` (catalogue playback readiness) remains empty for neutral rows. The
neutral row is counted once in storage and offers a confirmed local Remove once verified or
checking.

### F1 correction (2026-11-08)

A verified neutral `SourceRecordingSubject` previously reached `typedEntries` with
`ready=true` and a non-null execution binding while `DownloadSurfaceEntry` required binding IDs
to be catalogue/legacy-only. That threw from composition as soon as the completed neutral row was
observed. The correction keeps the genuine neutral subject/recording/request identity and the
exact current-process `AssetUse`/claim/asset proof, but leaves `bindingId` null for neutral rows.
It never manufactures a `MediaItem`, `PlaybackBinding`, catalogue membership or playback grant.
A connected real-Room test now drives capture/resolve/admission through the production command
facade, completes a neutral transfer, exercises the actual Downloads screen, and cold-revalidates
the same row in a new AppContainer instance. The pre-fix test attempt is preserved as red evidence;
the final run is green.

## Readiness boundary

`composeApp/src/commonMain/kotlin/dev/veyra/ui/downloads/DownloadSurface.kt` is a **pure display
projection**, not a second acquisition owner. Its `verifiedDownload` path mirrors the strict
existing `AppAcquisitionOwner.hasVerifiedLocal` conditions over the SAME paired
`DownloadAcquisitionPresentation` (`view`, `states`, `successfulChecks`):

- exact `executionContext(bindingId)` request/receipt/binding/subject/recording lineage;
- `AssetUse` for the requested binding with policy version 1;
- matching materialization/receipt/producer/representation/validator generation;
- retaining claim, no cleanup/finalization/lease/maintenance and no removing job for that
  subject/recording;
- projected `states[job.id] == canonical COMPLETE job`, which only arises from current-process
  checked evidence.

`successfulChecks` is deliberately **not** consumed: historical C0 alone is not readiness. The
surface never writes Room, performs a file/hash/native check, creates an `AssetUse`, opens a
player, exports a signed path or grants playback. Playback still goes through the existing
resolver/owner. A future non-UI refactor could extract a shared pure readiness predicate from the
canonical owner; this PR intentionally does not modify `AppAcquisitionOwner`, `AppContainer`, or
shared/canonical modules.

## Files changed

Production:

- `composeApp/src/commonMain/kotlin/dev/veyra/app/VeyraApp.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/downloads/DownloadSurface.kt` (new pure bridge)
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/design/AppStrings.kt` (matched English/Arabic
  `SourceRecording` label)
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/MusicUiContract.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/MusicComponents.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/MusicPresentation.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/MusicScreens.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/LibraryScreens.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/TrackDetailScreen.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/music/CollectionDetailScreen.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/podcasts/PodcastUiContract.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/podcasts/PodcastEpisodeComponents.kt`
- `composeApp/src/commonMain/kotlin/dev/veyra/ui/podcasts/PodcastScreens.kt`

Tests:

- `composeApp/src/commonTest/kotlin/dev/veyra/ui/downloads/DownloadSurfaceTest.kt` (new)
- `composeApp/src/commonTest/kotlin/dev/veyra/ui/podcasts/TypedPodcastDownloadStateTest.kt` (new)
- `composeApp/src/desktopTest/kotlin/dev/veyra/ui/downloads/TypedDownloadSurfacesTest.kt` (new)
- `composeApp/src/desktopTest/kotlin/dev/veyra/app/TypedDownloadPresentationIntegrationTest.kt` (new)
- `composeApp/src/desktopTest/kotlin/dev/veyra/app/TypedDownloadPresentationTestSupport.kt` (new,
  purpose-specific real AppContainer/Room fixture)

`AppContainer.kt`, `AppAcquisitionOwner.kt`, `PolicyMediaGateway`, shared acquisition/download/
playback/source/native code, schema/pins/dependencies/workflows, `AppContainerIntegrationTest.kt`
and PR5's branch are unchanged.

## Original coverage retained and new seam mapping

Prior user-visible assertions were retained; no assertion was removed, skipped or weakened.

| Original test(s) | New/changed seam consumed | Retained assertion |
| --- | --- | --- |
| `MusicSurfacesTest.verificationIsNotDisplayedAsReadyAndCancellationUsesTheDownloadId` | `LibraryScreen` -> `MusicUiOptions.downloads`/`DownloadSurface` | Verifying is not Ready; cancel uses the record id |
| `MusicSurfacesTest.recheckingDownloadRowsNeverOfferReadyPlaybackOrInactiveTransferControls` | same | checking row has no Play/Pause/Resume/Cancel; confirmed removal uses id |
| `MusicSurfacesTest.recheckingMediaMenusKeepCheckingStatusAndRequireConfirmedRemoval` | `MusicMediaRow`/menu -> surface entry | checking status and confirmation retained |
| `TrackDetailFlowsTest.currentDownloadedAndPreviousRecordingReceiptsAreDistinctAndDurationsNeverBorrowTheListing` | `trackDetailPresentation` retains legacy receipt fields, surface supplies readiness/duration | current/downloaded/previous receipts and measured durations stay distinct |
| `TrackDetailFlowsTest.activeFinalizationIsNotReadyAndCancellationUsesTheDownloadId` | `TrackDownloadSection` -> surface entry | no downloaded-audio section; cancel uses id |
| `TrackDetailFlowsTest.recheckingExistingAssetsRemainUnavailableOfflineAndRemovalRequiresConfirmation` | same | offline play disabled; no transfer actions; confirmed removal id |
| `TrackDetailFlowsTest.foregroundRestrictedTransfersWithoutAnAssetStillOfferResumeAndCancel` | same | resume/cancel use id; no removal |
| `PodcastScreensTest.readyOfflineAudioCanPlayAndRemovalWaitsForExplicitConfirmation` | `PodcastEpisodeUiState.activeDownload` | Ready plays offline; confirmed removal id |
| `PodcastScreensTest.checkingExistingAssetMenuShowsStatusAndConfirmedRemovalWithoutNoOpCancelOrResume` | active download entry | checking disclosure, no no-op cancel/resume, removal id |
| `PodcastScreensTest.arabicExistingAssetCheckDetailStaysUnavailableOfflineAndRemovalRequiresConfirmation` | same, Arabic | Arabic status/disclosure/RTL surface and id retained |
| `PodcastScreensTest.failedOwnedAssetMenuKeepsRetrySeparateFromConfirmedOfflineRemoval` | active download entry | retry vs local removal separation |
| `PodcastScreensTest.arabicFailedOwnedAssetDetailOffersLocalRemovalWithoutTurningItIntoRetry` | same, Arabic | Arabic retry/removal semantics |
| `PodcastScreensTest.changedDownloadedRecordingRowAndDetailUsePlayAndDiscloseHistoricalPositionInBothLanguages` | legacy next-play binding retained; typed never fabricated | recording/progress disclosure and both languages |
| `MusicPresentationTest`, `TrackDetailPresentationTest`, `PodcastDownloadActionTest`, `PodcastPresentationTest`, `PodcastBindingTest`, `PodcastProgressRecordingTest`, `PodcastUiStateTest`, `PodcastUnavailablePlaybackNoticeTest`, `AppStringsTest` | same pure seams | prior assertions retained |

New focused tests:

- Typed catalogue queued/running job visible with empty `snapshot.downloads`, exact id, bytes and
  progress; Pause/Resume/Cancel/Remove emit existing actions with that id.
- Cold typed `COMPLETE` + owned authored bytes + held current-process validator: screen shows
  checking/needs-foreground, no Ready/Play; after real coordinator validation and exact `AssetUse`,
  Ready appears. No new source query, preparation or transfer.
- R1/R2 shared recording: exact use/claim/recording/generation required; producer completion or
  historical check alone is not Ready; cleanup/lease/maintenance/stale projection stays not Ready.
- Adopted schema2 row keeps one stable physical id (no duplicate); neutral source rows show the
  real captured title and only pause/cancel/remove; physical completion is shown as Downloaded
  and contributes storage/removal, but it is never catalogue membership, a catalogue binding,
  Play authority or catalogue Ready.
- Downloads list/filter/storage and podcast downloaded filter use actual typed state; shared
  physical bytes count once; English/Arabic labels and RTL layout are exercised.
- Real AppContainer/Room integration consumes the same surface in `LibraryScreen`; source/native
  answers are authored fakes over **NON-AUDIO** bytes, files/Room are real.

## Verification evidence

Correction evidence root (ignored, task-owned):
`/root/.t3/worktrees/Veyra/t3-100c62d1/.work/verification/pr14-neutral-correction`.

- `result.json` SHA-256:
  `92802d6ee077b265905b9264952814b9d88cd4d8805f79cdcd315698018ca432`
- `inputs-before.json` and `inputs-after.json` (both 513 tracked regular files) SHA-256:
  `94cce4f2a2f7064938fd2941dca780778a0fdab41af46497ad548f8c48f87bba`
  (identical before/after; source freeze and no drift)
- Complete evidence manifest SHA-256:
  `4fb299fcf2b55226b769ec1293741ea23bf381414593d5b5f1bf07c07ee35647` (49 files)
- Preserved pre-fix red `red-result.json` SHA-256:
  `c95a80632c9086cd3b1bde5867d910abe4e2be1ddbaffcaf64e2e6e89b49a0ab`;
  red log/XML are under `run-001-red/`, and the failure is the exact
  `DownloadSurfaceEntry` constructor exception from the new connected neutral test.
- `drain.json` SHA-256:
  `fdc5749130df732cdedab97f72aa84576fb81be8e5653fd11432f8909928bd59`;
  no task-owned JVM/Gradle/test process remained after the evidence runs.
- New green runs are all `--offline`; actual argv includes `flock`, the shared project lock,
  `scripts/with-display.sh` for GUI, the bounded test init and the single required JVM argument.
  Each run has fresh exact-task XML and its own log/result/failures files.
- Tracked input drift: **none**. Working tree clean before/after. Host:
  `Linux-6.1.72-x86_64-with-glibc2.39`, 12 logical CPUs, OpenJDK 21.0.12.
- UTC window: `2026-11-08T06:48:21Z` to `2026-11-08T07:44:27Z` (captured in `result.json`).

| Run | Command task(s) | Exit | Suites | Tests | Failures/errors/skips | Log SHA-256 |
| --- | --- | --- | --- | --- | --- | --- |
| run-002 | `:composeApp:compileKotlinDesktop --offline` | 0 | 0 | 0 | 0/0/0 | `4f51192c24bc7313488da69db3a96bf2f3c91c8d20c1fe4d35330d8e388ba0ec` |
| run-003 | `:composeApp:compileTestKotlinDesktop --offline` | 0 | 0 | 0 | 0/0/0 | `8144995dd072b7a99b0a0be7cfbf2a81908de5f0cf0381be4dbc70511198c879` |
| run-004 | focused common tests (11 classes) `--offline` | 0 | 11 | 93 | 0/0/0 | `a85b49322f8421657681cb7fb1494fc1bfcac4d427586d358af3ca8d3703be2e` |
| run-005 | focused existing UI surfaces (13 methods) `--offline` | 0 | 3 | 13 | 0/0/0 | `a5760f1a17cbeea4ad137e06da61e0a3653935748cfbd511dd7ccc10c4ccf7a8` |
| run-006 | typed UI + real AppContainer/Room integration (8 tests) `--offline` | 0 | 2 | 8 | 0/0/0 | `b2bc4910dd6889c8012bfa89727077421f8bcf6e9372dde4a705797dcb92f7b5` |
| run-007 | `:shared:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid --offline` | 0 | 0 | 0 | 0/0/0 | `ae014be8e17f5dccdbb3d219d821b52873d1007f8c5deeefd8668f9acfbb898a` |

`run-004` classes: `AppStringsTest`, `DownloadSurfaceTest`, `TypedPodcastDownloadStateTest`,
`MusicPresentationTest`, `TrackDetailPresentationTest`, `PodcastDownloadActionTest`,
`PodcastPresentationTest`, `PodcastBindingTest`, `PodcastProgressRecordingTest`,
`PodcastUiStateTest`, `PodcastUnavailablePlaybackNoticeTest`.
`run-005` retains the 13 original music/podcast download/readiness methods mapped above.
`run-006` is `TypedDownloadSurfacesTest` (6 tests) plus both
`TypedDownloadPresentationIntegrationTest` methods (2 tests), including the connected neutral
completion/cold-revalidation and Arabic RTL neutral removal tests. Exact-task XML is under each
`run-*/composeApp-desktopTest/xml/`; all green `failures.json` files are empty arrays.

The earlier evidence root
`.work/verification/typed-download-screen-compatibility/` remains preserved as history but is
**superseded** for F4: its commands omitted `--offline` and it persisted no after-input manifest.
It is not used as acceptance evidence. The earlier unsupported broad-timeout claim is withdrawn;
no broad-suite pass or retained broad-timeout attempt is claimed. Only the exact focused methods
above are claimed.

These correction runs were executed before the new host-gate policy arrived and used the shared
project `flock` directly; no other lane was killed. Any further heavy work will use
`/usr/local/bin/vps-build` with the same lock. This is evidence from the tested commit, not a
release/acceptance claim.

## Remaining findings / dependencies

- **F2 (unresolved, separate owner):** typed `FAILED` with retained bytes still exposes a Retry
  action in the existing UI while the shared runtime has no executable retained-FAILED local
  revalidation path. This PR does not modify shared/runtime/Room/controller/AppContainer and does
  not simulate `COMPLETE` or hide a required Validate. The shared retained-FAILED owner and the
  coordinated AppContainer/PR12 action wiring must land before F2 can be called resolved.
- **F3 (confirmed, dependency):** a media-level `ready` row can come from R1 while a fresh
  `MusicUiAction.Play`/`PodcastAction.PlayEpisode` reaches the typed controller, captures a new
  same-recording R2, and the exact resolver filters `assetUses` by R2; offline-only then fails
  with `OFFLINE_POLICY`. Call sequence: `AppContainer.play` -> `PlaybackCommand.Play` ->
  `PlaybackController` typed `handleCommand` -> `admissions.catalogLeaves` (new R2) -> `admit` ->
  `start` -> `AcquisitionPlaybackResolver.resolve` exact-use filter. This is a truthfulness issue;
  the execution guard still fails closed. It is intentionally not "fixed" by clearing valid R1
  media-level readiness or forcing a fresh Play to reuse a binding. It needs the PR12
  current-selection/provenance/action-reuse owner and remains unresolved in this correction.
- **F4 (corrected for new runs):** new affected runs above use `--offline`, persist before and
  after input hashes, preserve the genuine red attempt, and make no timeout claim. Old evidence is
  retained but marked non-compliant/superseded.

## Explicitly unverified / held

- iOS/Xcode is unavailable locally. No iOS compile, simulator or device run was performed; no iOS
  claim is made.
- No physical Android/macOS/Windows/Linux device, native decoder, audible output, crossfade,
  signing, packaging, workflow dispatch or distribution verification was performed.
- Source/native/transfer answers in tests are authored fakes over **NON-AUDIO** bytes. Room,
  files and OS locks are real, but this is not playable-audio or native proof.
- No live source, authentication, download or offline restart against a real provider was run.
- The full `AppContainerIntegrationTest` class is PR5/lead-combined-validation scope and was not
  edited or used as fixture evidence. PR5's `t3/pr4-app-composition-ports` branch was not merged,
  copied or edited.
- Task B final builds remain held until the lead accepts Task A. The app is not release-ready.
