# PR #4 — canonical typed queue/player consumers (Task A)

Status: **draft; production consumers corrected; original CoreShell oracles retained.** PR12's
five verified failures were consumer mismatches: the owner publishes canonical
`executionQueues`/`queueSelections`/`currentSelection`, while `QueuePanel`,
`PlaylistDetailScreen`, the mini/full player and crossfade disclosure read the legacy
`queues`/`queue`, `executionBinding` field. This revision corrects those consumers without
changing shared production, the controller, schema, pin, dependencies, workflows or downloads.

## Result

- The complete 14-method `CoreShellFlowsTest`: **14/14 pass**; all four headless desktop classes:
  **30/30** (7 lifetime, 18 platform ownership, 5 startup jobs). `final-core-001`: **44/44**,
  0 failures/errors/skips, Gradle exit 0.
- New canonical read-model/UI/neutral journey and playlist compatibility: **16/16 pass** in
  `final-new-001` (7 pure projections, 2 typed surface methods, 1 connected neutral journey,
  6 retained playlist methods), Gradle exit 0.
- A selected existing-music subset is **not accepted green**: the host disk free-space floor
  (6 GiB) was below the gate floor after the two green final runs, so no new heavy build was
  admitted. The attempted subset, earlier pre-freeze results and the one diagnosed legacy
  crossfade hang are recorded below; no broader music pass is claimed.

## Production behavior

| Area | Corrected behavior |
|---|---|
| `QueuePanel` | Renders canonical `ExecutionQueue.displayedOrder` with the paired `queueSelections[id]`. Preserves duplicates, exact occurrence/context, current cursor, displayed-order indices, shuffle state and Undo availability. Canonical empty stays empty even when a stale legacy queue exists. Legacy snapshot rows are used only when no canonical queues were published. |
| Queue rows | Carry captured presentation/identity only. A missing selection is `InformationUnavailable` and non-playable; `PENDING` is resolvable/playable; `BOUND` is playable; `UNPLAYABLE`/`LEGACY_UNBOUND`/`OUTCOME_UNKNOWN` are visibly non-playable. A neutral source recording has no `MediaItem` and no fabricated catalogue/binding/download authority. |
| Playlist replacement | `PlaylistDetailScreen` accepts the published playback state and confirms replacement against canonical upcoming music occurrence IDs. Standalone legacy callers keep the explicit snapshot fallback only when canonical queues are absent. |
| Current player | Mini/full player and provenance derive from `currentSelection` and its exact requested/observed/executed/receipt identity. They never look up a newer catalogue row, a browsed queue, or a future queued recording. Schema2 receipts remain labelled legacy; typed receipts use `FirstResultReceipt.Observed`. |
| Incoming crossfade | Uses `incomingSelection`: requested A is labelled requested, captured member B is labelled captured member, executed C is labelled resolved. No incoming item/receipt is borrowed from a different row. A neutral incoming recording has no fabricated `MediaItem`. |
| Transport | Shuffle/repeat and next availability come from the active canonical `ExecutionQueue`; legacy controls are the fallback only when no canonical queue exists. Podcast skip/speed and music shuffle/repeat follow `controlsMode`, not a fabricated media kind. |

No `PlaybackController`, shared production, Room/schema, download, pin, dependency, workflow or
build-policy file changed. Only narrow bilingual labels were added
(`CapturedMemberTitleValue`, `QueueEntryUnavailable`); no PR14 download strings were copied.

## Original CoreShell method mapping

| Method | Original assertion | Final |
|---|---|---|
| `queueInspectionDoesNotActivateAudioAndExplicitOccurrenceSelectionUsesTheSinglePlayer` | canonical queue inspection; exact `episode-now` PlayEntry; one native load; no catalogue query for inspect | pass |
| `occurrenceReorderDisablesShuffleAndConfirmedClearUndoRestoresOnlyUpcomingEdits` | exact displayed/original order, occurrence IDs, revision 3, position 17,000 ms, shuffle off, clear+Undo | pass |
| `detailsFavoritesAndDuplicatePlaylistMembershipAreConnectedButDoNotPlayOrDeleteDownloads` | canonical queues/physical job unchanged across save/Undo | pass |
| `confirmedPlaylistShuffleHasOneUndoBackToTheOriginalOccurrenceQueue` | canonical upcoming confirmation, shuffle replacement, exact duplicate occurrences, one Undo | pass |
| `backgroundSpaceTogglesButTypingSpacesAndNavigationShortcutsDoNotHijackText` | focus/keyboard behavior; no extra engine loads | pass |
| `aFocusedPlayerSliderOwnsArrowKeysAndSpaceActivatesAButtonOnlyOnce` | seek/focus and one activation | pass |
| `escapeClosesADialogThenQueueThenPlayerWithoutChangingTheAudioOwner` | modal/queue/player Escape and owner identity | pass |
| `queueContextNavigationKeepsTheClickedChipFocusedWithoutStartingPlayback` | inspected context never changes active context/audio | pass |
| `heldSpaceReleasedByAChildDoesNotSuppressTheNextBackgroundPress` | held-key/focus and exact play/pause/load counts | pass |
| `narrowArabicLargeTextMirrorsNavigationButKeepsThePlayerTransportLtrAndReachable` | 320 dp Arabic 175% RTL/LTR, controls and 48 dp reachability | pass |
| `shortLandscapeKeepsLibraryInputAndScrollableRailReachableAboveTheMiniPlayer` | 720×360 Arabic 175% layout/runtime-error behavior | pass |
| `compactNavigationKeepsEverySelectedLabelUnclippedAt200PercentInBothLanguages` | en/ar 200% labels, selected state and focus | pass |
| `resizingLongMetadataKeepsPauseAndSeekVisibleWhileCompleteProvenanceRemainsReadable` | typed current receipt, `player-resolved-title`, complete requested/resolved disclosure, transport visibility | pass |
| `freshTypedLibraryPlayUsesTheCanonicalOccurrenceAndRendersInTheQueuePanel` | real Library Play → canonical occurrence revision 4, no legacy queue/binding, rendered queue entry | pass |

The original assertions, scene input paths and strict checks were not weakened. Fixtures still
create execution state through production capture/claim/commit/admit/attach/EditQueue commands
over real Room; only the optional neutral occurrence is an authored non-audio fixture answer.

## New focused tests

- `CanonicalPlaybackPresentationTest` (7 methods): canonical-empty versus stale legacy,
  duplicate exact IDs/order/context, inspected versus audible transport, current/incoming exact
  receipt after later catalogue updates, requested/observed/executed labels, neutral and pending
  rows without catalogue fabrication, non-playable action states.
- `TypedPlaybackSurfacesTest` (2 methods): current and incoming exact typed receipts with a decoy
  legacy item; canonical-empty QueuePanel renders no stale legacy row.
- `TypedNeutralPlayerJourneyTest` (1 method): real VeyraApp/AppContainer/Room with a neutral SHOW
  source recording, actual queue PlayEntry, no new title query, no `MediaItem`, podcast controls,
  en/ar 320 dp 175% layout and authored screenshots.
- `PlaylistFlowsTest` (6 retained methods): legacy playlist callers keep their behavior,
  including the viewed-context replacement/Undo test.

## Actual validation

All Gradle work used the existing Veyra heavy-build admission gate/project lock, offline
`scripts/gradle.sh`, one bounded Test fork (384 MiB heap, 256 MiB metaspace, SerialGC,
2 processors), and real X11 via `scripts/with-display.sh`.

### `final-core-001` (head `aa583a8`, 2026-11-08T07:49:39Z–07:51:12Z)

```text
:composeApp:desktopTest
  --tests dev.veyra.app.CoreShellFlowsTest
  --tests dev.veyra.desktop.DesktopLifetimeTest
  --tests dev.veyra.desktop.DesktopPlatformServicesOwnershipTest
  --tests dev.veyra.desktop.DesktopStartupJobsTest
```

**44 tests / 44 passed / 0 failed / 0 errors / 0 skipped**, Gradle exit 0. Class counts:
CoreShell 14, lifetime 7, platform ownership 18, startup jobs 5. Before/after inputs identical
(`0b769af4e7b336b885d89217c17ee83d2e41baf2daaa0e6f68230ec159892586`); HEAD unchanged;
gitlink/pin and actual checkout `c0a08090fe7dbd76e20216559b8184ad2cccde63`; schema-3 SHA256
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`; owned process drain none.
Log SHA256 `ac2b77f7bf3eb5e1c63dc765042fd8542eb93d4f17b1e9b940d4166f825469c5`;
result SHA256 `92e24318ce3c61a4856258d97b4617cdbc14399af7888a43cd4127bf89d8566e`.
Fresh XML: CoreShell `0c4111fbe95f3ba739969e1c9c24527684a6b395afeab5d257723216ed6b1741`;
lifetime `0db263851e877ee263b8a14bbee8a21c3a6973d3458f0b2f4820a06f61163c23`; platform
ownership `0a7e87bd375c60652afa854bf13677ab64a26b6d644364c6fb6e9359da97115c`; startup jobs
`1294fa4b36aec19eb5c61c8a9cfba9508c4793bf1bea095c92ff8be02142070d`.

### `final-new-001` (head `aa583a8`, 2026-11-08T07:53:38Z–08:01:10Z)

```text
:composeApp:desktopTest
  --tests dev.veyra.app.TypedNeutralPlayerJourneyTest
  --tests dev.veyra.ui.music.CanonicalPlaybackPresentationTest
  --tests dev.veyra.ui.music.TypedPlaybackSurfacesTest
  --tests dev.veyra.ui.music.PlaylistFlowsTest
```

**16 tests / 16 passed / 0 failed / 0 errors / 0 skipped**, Gradle exit 0 (neutral 1, pure 7,
typed surfaces 2, playlist 6). Before/after inputs identical
(`0b769af4e7b336b885d89217c17ee83d2e41baf2daaa0e6f68230ec159892586`); tested HEAD `aa583a8`;
pin/gitlink/schema unchanged. Log SHA256
`b3874b56997a822feac7c39f8a4e69270fe0787658d026624f43add7ea9f54ff`; result SHA256
`88f78dd393289ea2bd024277cd1456f03182b9fb78b248c8842aa24e592e5a69`.
Fresh XML: neutral `a472fb087120e3dbcf2aea39078f9a8c951dc0056f4904ceb4cb0ab734f96f14`;
pure `0ce543f99428247065bb9f061155514d4048243a29bd156ead9bd51179dd0b63`; surfaces
`66d2d4d81abe3b3204e6bcf7475f8779b336041702b39ae0db25903a4961f820`; playlist
`c12cef43b9a5bd88eac1515dcb801a158bff127df0bc527fb74fd878f61d4027`.
The captured drain listed one idle Kotlin compile daemon (PID 30630) after the Gradle/test
processes exited; it was terminated and a post-cleanup scan found no owned compiler/test
process. No test runner or Gradle worker remains.

Authored evidence:
- `.work/verification/pr4-core-shell-corrections/runs/final-core-001/`
- `.work/verification/pr4-core-shell-corrections/runs/final-new-001/`
- Neutral screenshots under `composeApp/build/test-evidence/music/core-shell-neutral/`
  (not committed).

## Red / unexecuted checks

- Preserved pre-freeze attempts: `port-correction-001` (44 core/desktop methods passed, then two
  newly-authored test-definition defects and a known legacy crossfade step; no XML because the
  aggregate task reached its bound), `port-correction-002-groupB` (the same two authored defects
  plus the legacy step), `pre-new-001` (16 executed / 15 passed / 1 authored UI-scroll
  expectation failed; fixed), `pre-surfaces-002` (cancelled 143 before admission),
  `pre-surfaces-003` (2/2 passed, pre-freeze input drift because the test viewport was edited
  while queued).
- `MusicSurfacesTest.requestedAndFirstResultTitlesStayDistinctAndOnlyConfirmedOverlapLooksActive`
  is **not accepted green**. It advances through the exact current/incoming receipt assertions
  and then stops in `performScrollTo` of `crossfade-progress` on the legacy schema2 fixture; the
  thread dump shows the AWT thread in a Compose semantics scroll animation that does not reach
  idle. This is not one of the 14 CoreShell oracles. The typed incoming receipt UI is covered by
  `TypedPlaybackSurfacesTest`, and the legacy crossfade card path itself remains unchanged.
  Diagnostic attempt and both thread dumps are retained under
  `runs/diagnose-crossfade-jstack/`.
- The selected existing-music subset (`inspectingPodcastQueue…`, `duplicateQueueRows…`,
  `episodeControls…`, `compactMini…`, `repeatOne…`, `aNonFirstReceipt…`,
  `arabicLargeText…`, `seekCommits…`) was **not executed on the final head**. It was queued
  behind the host admission gate, then the shared disk free-space floor dropped below 6 GiB,
  so no new heavy build was admitted. One of those methods (`compactMini…`) had passed in the
  pre-freeze `port-correction-001` aggregate before the aggregate bound was reached; that is
  not claimed as final-head evidence.
- No unfiltered desktop suite, native/mobile execution, packaging, live source, Actions run or
  benchmark is claimed.

## Limits

All gateway/engine/native answers are authored non-audio test doubles over real Room/files.
No live authentication, transfer, native decoder, physical audio/crossfade, emulator/device,
package, signing or distribution evidence is claimed. Schema3 remains
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 entities), the kmpYT
gitlink/pin remains `c0a08090fe7dbd76e20216559b8184ad2cccde63`, and Task B remains gated.
