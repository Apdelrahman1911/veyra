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
- Tested code/test commit: `b7308cc912e21efeaddcd4e755bfe7313ea02120`
  (`Cover queued and cancelled typed surface rows`); its production code is identical to
  `0184e50185c158c6f1e99693dfad963cfb61112f` (`Connect typed downloads to existing music and
  podcast surfaces`).
- Evidence was captured at that commit. Subsequent commits are documentation-only; rehashing all
  tracked files shows only this document differs from the tested input set, so production/test
  hashes remain exactly equal.
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
native handle or Room row.

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
  real captured title and only pause/cancel/remove, never catalogue membership, Play or Ready.
- Downloads list/filter/storage and podcast downloaded filter use actual typed state; shared
  physical bytes count once; English/Arabic labels and RTL layout are exercised.
- Real AppContainer/Room integration consumes the same surface in `LibraryScreen`; source/native
  answers are authored fakes over **NON-AUDIO** bytes, files/Room are real.

## Verification evidence

Evidence root (ignored, task-owned):
`/root/.t3/worktrees/Veyra/t3-100c62d1/.work/verification/typed-download-screen-compatibility`.

- `result.json` SHA-256:
  `8b8279fd2037c92437f4452c0514e91a59fc0f5441fff8e7ffd0b3a6529b0c34`
- `inputs-before.json` (513 tracked regular files) SHA-256:
  `b94b173531ad7ce346da1f81ebe36dcda1717b4b172cd68a0ea614de948aff87`
- Complete evidence manifest SHA-256:
  `faad77073c31cd04599b225b9e1be52d7d2931ce73f301a4434e399793e8cc43`
  (`manifest.sha256` covers 36 log/XML/result files; no source drift)
- Tracked input drift: **none**. Working tree clean before/after. Host:
  `Linux-6.1.72-x86_64-with-glibc2.39`, 12 logical CPUs, OpenJDK 21.0.12.
- UTC window: `2026-11-07T20:41:29Z` to `2026-11-07T20:44:02Z`.

All commands ran from the worktree root, under the shared lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock`, with the bounded
test initializer and the single Gradle JVM argument
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`.

| Run | Command task(s) | Exit | Suites | Tests | Failures/errors/skips | Log SHA-256 |
| --- | --- | --- | --- | --- | --- | --- |
| run-001 | `:composeApp:compileKotlinDesktop` | 0 | 0 | 0 | 0/0/0 | `b13c446a1d15499b0c53859fc0261a4a7eecfc13319e3c65a90ac758fda0be40` |
| run-002 | `:composeApp:compileTestKotlinDesktop` | 0 | 0 | 0 | 0/0/0 | `5903410c484cf0331d716e0839c28d8fcbf7d59e73c2ce0492d3db1f5f514191` |
| run-003 | focused common tests (11 classes) | 0 | 11 | 92 | 0/0/0 | `b8f07b0a4417a6a72061feac7d717795961369eda3a389f09142148aa1d2c016` |
| run-004 | focused existing music/podcast surfaces (13 methods) | 0 | 3 | 13 | 0/0/0 | `cff3fabdaf3f8014f32a8914ddd8be2b450aad644b6342341d10b5de15eece30` |
| run-005 | new typed surfaces + real AppContainer/Room integration (6 tests) | 0 | 2 | 6 | 0/0/0 | `74fecfa1a2c0930754b4a46220fa9c489cb4e410a7b5b80c15874aff66bb6755` |
| run-006 | `:shared:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid` | 0 | 0 | 0 | 0/0/0 | `ec6e83eb4adeaee43bbe16a4adf60dc03e2b2cd2cbffb8cfbc1954886ca60a14` |

`run-003` classes: `AppStringsTest`, `DownloadSurfaceTest`, `TypedPodcastDownloadStateTest`,
`MusicPresentationTest`, `TrackDetailPresentationTest`, `PodcastDownloadActionTest`,
`PodcastPresentationTest`, `PodcastBindingTest`, `PodcastProgressRecordingTest`,
`PodcastUiStateTest`, `PodcastUnavailablePlaybackNoticeTest`.

`run-004` retained methods are the 13 mapped rows above. `run-005` is
`TypedDownloadSurfacesTest` (5 tests) plus
`TypedDownloadPresentationIntegrationTest.coldCompleteTypedJobIsCheckingOnTheExistingScreenAndBecomesReadyOnlyAfterThisProcessValidates`
(1 test). Exact-task XML is under each `run-*/composeApp-desktopTest/xml/`; all `failures.json`
files are the empty array (SHA-256
`37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570`).

A preliminary broad UI invocation (all of `MusicSurfacesTest`, `TrackDetailFlowsTest`,
`PodcastScreensTest`) exceeded Gradle's configured 15-minute test-task timeout after the download
tests had already passed; it is not presented as a full-suite pass. Final evidence uses the exact
focused download/readiness methods above.

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
