# Canonical podcast progress compatibility (PR14 topic branch)

Status: **source/test candidate; not merged and not accepted as Task A.** This topic branch connects
the paired typed playback progress view to the existing podcast screens without fabricating legacy
progress/bindings. Show/detail wiring in `VeyraApp.kt` and the separate F3 saved-play action remain
lead/PR12 handoffs. Parent PR #14 is frozen at `f097623`; this branch is a new stacked review topic.

## Source and ownership

- Base: PR14 head `f097623cabdd920ad8b3adebe563aa9a46d5be40` (PR14 remote remains frozen).
- Current parent merged ordinarily: `6d09ed5fa24f39e97a6d7b9689e9e30a008e1857`
  (`t3/typed-room-download-recovery`); the merge commit is `4d055d7`.
- Branch: `t3/pr14-canonical-podcast-progress`.
- Tested code/merge head: `4d055d70ca47e53abab407919da0f724b019b9fb`.
- Exclusive edits: `PodcastUiContract.kt`, `PodcastScreens.kt`, new
  `PodcastProgressProjection.kt`, new tests, and the canonical podcast-progress state/projection
  wiring in `AppContainer.kt`. `VeyraApp.kt`, `LibraryScreens.kt`, `PlayerSurfaces.kt`,
  `PlaybackProvenance.kt`, `MusicUiContract.kt`, `AppStrings.kt`, controller/shared/runtime/
  schema/pin/workflows and existing AppContainer regression classes were not edited.
- Pin `c0a08090fe7dbd76e20216559b8184ad2cccde63`; schema-3
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` unchanged.

## Actual source gap and behavior

The existing podcast UI reads `AppSnapshot.progress` and legacy `PlaybackBinding` values. Typed
playback publishes canonical `PlaybackAcquisitionView.catalogProgress`/`checkpoints` and
`PlaybackController` `MarkPlayed` writes only `catalog_progress`; the legacy snapshot fallback is
therefore stale for typed episodes. The UI did not consume the paired canonical view.

`PodcastProgressProjection.kt` is a pure read-only projection over the paired
`PlaybackAcquisitionView` (`library.snapshot` + `acquisition` + `checkpoints` + `catalogProgress`):

- A canonical `catalog_progress` row overrides legacy snapshot progress for that mediaId.
- The stored execution binding is resolved through `acquisition.bindings`/`subjects`/`recordings`;
  it must name the same catalogue episode and a real recording. Missing/mismatched binding is
  explicit `UNVERIFIED`/`CHANGED`, not a fabricated `PlaybackBinding`.
- The current canonical PODCAST queue occurrence supplies the only current recording identity.
  Other rows remain historical/unverified; a fresh Play may establish a different recording.
- Played/unplayed is independent of resume compatibility. `played`/Continue/New/show filters use
  canonical played/position; `positionMs`/duration/resume require same-recording `MATCHED`.
- A genuine schema2 `snapshot.progress` row remains the fallback when no canonical row exists;
  all prior legacy tests and download behavior are retained.
- No source query, native read, Room write, invented `MediaItem`/`PlaybackBinding`, or synthetic
  COMPLETE is performed by the projection.

### AppContainer API delta (exact)

```kotlin
private val mutablePodcastProgress = MutableStateFlow<PodcastProgressView?>(null)
val podcastProgress = mutablePodcastProgress.asStateFlow()

private fun publishPodcastProgress(view: PlaybackAcquisitionView?) {
    val projection = podcastProgressView(view)
    mutablePodcastProgress.value = projection
    mutablePodcasts.update { it.copy(progressView = projection) }
}

// after playback.initialize(), on the application effects scope:
effects.launch {
    store.state.collect {
        try { publishPodcastProgress(store.acquisition.playbackView()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { report(error) }
    }
}
```

`store.acquisition.playbackView()` is one same-owner read barrier; it is not a second controller,
Room owner, source client or player. The `PodcastsUiState.progressView` field is preserved by
`VeyraApp`'s existing `podcasts.copy(snapshot = ..., downloads = ...)`, so the podcast hub's
Continue/New rows are connected.

**Pending VeyraApp handoff (PR12-owned file; not edited here):**

```kotlin
val podcastProgress by container.podcastProgress.collectAsState()
DetailKind.EPISODE -> EpisodeDetailScreen(EpisodeDetailUiState(
    detail.id, snapshot, currentBinding = ..., downloads = screenDownloads,
    progressView = podcastProgress), container::onPodcastAction)
DetailKind.SHOW -> PodcastShowScreen(PodcastShowUiState(
    detail.id, snapshot, ..., downloads = screenDownloads,
    progressView = podcastProgress), container::onPodcastAction)
```

The show/detail screens already accept `progressView`; only those two constructor sites and the
new collect are required. No controller contract is requested for canonical progress.

## Verification

Evidence root (ignored, task-owned):
`/root/.t3/worktrees/Veyra/t3-100c62d1/.work/verification/pr14-podcast-progress`.

- `result.json` SHA-256:
  `f6a917946f49fde9638a45c1ad1eac5e0134780bd13c9d7736ba7b96b350b2ee`
- `inputs-before.json` / `inputs-after.json` (both 519 tracked files) SHA-256:
  `0275dd0998a4b6203c75fd5a951be3b339524fe6ff0246dadadd6bc7481c53ef`
- Complete evidence manifest SHA-256:
  `fa9fc9f16dfd1d20ca2b3c0f28520b4ceb301b0460dda8ce74ebb803b2bd5d39` (59 files)
- Drain SHA-256: `f8a53dda822482c937d46158dfc7e7b7f2a52ddc3b84c55f3f95cf146049f5bb`;
  no owned JVM/Gradle/test process remained.
- Genuine red: `run-002-red` preserves the actual AppContainer/Room/controller failure
  (1 test/1 failure; log SHA-256
  `0dc6b552ec0b03253c9e3482ecd5753b6b178e82dbc8bf54bd54f5ac98bc6dd9`). The test failed because
  the existing podcast row did not consume canonical `MarkPlayed`; `run-001-red` preserves an
  earlier test-compilation typo (0 tests, no source defect claim).
- Final focused run: `run-011-final`, 10 suites/80 tests/0 failures/errors/skips, log SHA-256
  `d5a45ec01686a175d98a398676cc665956204924bb913c63f4f9021659b50f33`.
- Additional selected AppContainer podcast lifecycle cases: `run-013-app-podcast`, 5 tests/0
  failures/errors/skips, log SHA-256
  `1bf95c54dd6f11fae7821bca0e489ecf95bbcefd65c1c4bcefa5063db96b4d89`.
- Final compile: `run-010-compile` (`:composeApp:compileKotlinDesktop
  :composeApp:compileTestKotlinDesktop`) exit 0.
- Android compile: `run-012-android-compile` (`:shared:compileDebugKotlinAndroid
  :composeApp:compileDebugKotlinAndroid`) exit 0.
- All final heavy runs used `/usr/local/bin/vps-build run --profile normal` with the existing
  Veyra project lock passed to the gate; the child did not double-flock. Commands include
  `scripts/gradle.sh --offline`, bounded init/JVM flags and fresh exact-task XML. Inputs were
  captured before and after; drift is empty.

### Tests added/updated

- `PodcastProgressProjectionTest`: canonical match/resume/duration, R1/R2-recording mismatch,
  played independent of resume, stale/missing binding, schema2 fallback, Continue/New/show filters.
- `PodcastProgressSurfacesTest`: existing hub Continue/Show filter and row/detail in English,
  Arabic and RTL, including mismatch disclosure and no fabricated resume.
- `PodcastProgressCompatibilityTest`: real AppContainer/Room/controller `MarkPlayed`/unplayed and
  cold reopen through the new AppContainer `podcastProgress`/`mutablePodcasts` wiring.
- Existing podcast tests (`PodcastScreensTest`, `PodcastUiStateTest`,
  `PodcastDownloadActionTest`, `PodcastPresentationTest`, `PodcastBindingTest`,
  `PodcastProgressRecordingTest`, `PodcastUnavailablePlaybackNoticeTest`) remain unchanged and
  green.

## Limits / held dependencies

- No native checkpoint/position test was executed: canonical position/resume/mismatch are tested
  over authored read-only `PlaybackAcquisitionView` state; the real AppContainer test exercises
  canonical `MarkPlayed` and cold reopen only. Authored source/native answers are non-audio; no
  live/native/device/release claim.
- Show/detail production wiring needs the exact `VeyraApp.kt` handoff above; the podcast hub is
  wired through `PodcastsUiState.progressView`.
- **F2** (retained-FAILED local revalidation) and **F3** (fresh R2 vs saved R1 action) remain
  separate owner/lead work; this branch does not alter shared/controller/Room/AppContainer action
  semantics beyond reading canonical progress.
- Parent `6d09ed5` is merged for compatibility; combined Task A QA after PR14/integration is
  still required. No merge, Task B, platform/package/workflow/native proof.
