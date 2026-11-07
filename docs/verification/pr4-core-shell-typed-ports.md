# PR #4 — CoreShell typed fixture/test port (Task A)

Status: **draft and incomplete by design.** The CoreShell fixture and its original 13 GUI
methods now run on the schema-3 typed Room/acquisition composition, plus one fresh typed shell
control. The execution selector is red on five methods: three canonical queue-panel methods,
one playlist replacement-confirmation method, and one typed player-provenance method. All five
are actual production UI/consumer mismatches that this lane does not own. The PR remains draft
and is not mergeable or release-ready.

## Result in one paragraph

`CoreShellTestFixture` no longer writes execution/progress/download/saved authority through
`store.update { initial }`. Catalogue/show/settings/navigation enter through their existing
commands; favorites and the duplicate playlist enter through `editCatalogLibrary`; requests,
receipts, bindings and occurrences enter through real `capture`/`claim`/`commit`/`admitQueue`/
`attachOccurrence`; queue positions/order/shuffle/speed use `AcquisitionPlaybackCommand.EditQueue`;
and the paused download is a real standalone physical job/claim paused by
`UpdateStandaloneDownloadIntent`. The fixture engine now owns a typed prepared load directly,
so remote typed play/seek/stop work without a legacy `PlaybackResource` or legacy `source`
field. The final focused run executed 44 tests: **39 passed, 5 failed, 0 errors, 0 skipped**.
All 30 headless desktop controls passed. The five CoreShell failures are the queue consumer,
playlist shuffle confirmation, and typed player-provenance consumers outside this test-only
scope.

## Fixture and seed changes

| Area | Previous fixture | Ported fixture |
|---|---|---|
| Initial `create()` | `store.update { initial }` with legacy queues/progress/bindings/downloads | `store.initialize`, `upsertCatalog`, scalar settings/navigation/follow update, then real save and acquisition commands |
| Catalogue/show/settings/navigation | mixed into the guarded snapshot write | `upsertCatalog` plus the existing scalar `store.update` ingress |
| Favorites/playlist | snapshot write | `editCatalogLibrary`, preserving `playlist-first` and duplicate `playlist-copy` occurrence IDs |
| Music queue | `QueueSnapshot("music-now","music-next","music-copy")`, position 17,000 ms, shuffle | four canonical requests; three BOUND occurrences attached; `EditQueue` current `music-now`, displayed/original order exact, position 17,000 ms, shuffle true |
| Podcast queue | legacy `episode-now`, 25,000 ms | canonical PENDING `episode-now` occurrence, position 25,000 ms; no pre-play/query/native prepare |
| Paused download | legacy `DownloadRecord("ui-fixture-paused-download", music.id, PAUSED)` | canonical typed physical job + standalone claim, intent PAUSED/userPaused, no staging path/attempt/asset/AssetUse |
| Gateway | `KmpMediaGateway` only | `KmpMediaGateway`, `AcquisitionGateway`, `AcquisitionExecutionGateway`; a separate seed gateway keeps app query counters entry-time truthful |
| Engine | typed prepare set `source = null`; play/seek required legacy source | one active typed/legacy load owner; typed play/seek/stop/replacement, generation filtering, native Position/SeekCompleted samples, exact release bookkeeping |

`attachUninitialized` remains a truthful caller-owned-store startup seam with the existing
canonical containment checks. No production source, schema, pin, build file, workflow or legal
resource was changed.

## Original method → canonical assertion mapping

| Original method | Ported canonical seam | Result |
|---|---|---|
| `queueInspectionDoesNotActivateAudioAndExplicitOccurrenceSelectionUsesTheSinglePlayer` | canonical `ExecutionQueue`, occurrence IDs, typed load `mediaId`, exact seed revision 2 | **red**: QueuePanel does not emit `queue-play-episode-now` |
| `occurrenceReorderDisablesShuffleAndConfirmedClearUndoRestoresOnlyUpcomingEdits` | canonical displayed/original/current/position/shuffle + exact revisions 2→3→4→5 | **red**: QueuePanel does not emit `queue-up-music-copy` |
| `detailsFavoritesAndDuplicatePlaylistMembershipAreConnectedButDoNotPlayOrDeleteDownloads` | canonical queues + physical job map; paused job status/userPaused; same duplicate playlist identities through save/undo | **pass** |
| `confirmedPlaylistShuffleHasOneUndoBackToTheOriginalOccurrenceQueue` | canonical queue facts before/after play and Undo | **red**: `PlaylistDetailScreen` decides confirmation from the legacy queue, so `confirm-action` is absent |
| `backgroundSpaceTogglesButTypingSpacesAndNavigationShortcutsDoNotHijackText` | unchanged user-visible keyboard assertions; typed play count | **pass** |
| `aFocusedPlayerSliderOwnsArrowKeysAndSpaceActivatesAButtonOnlyOnce` | unchanged focus/seek assertions; typed seek acknowledgement | **pass** |
| `escapeClosesADialogThenQueueThenPlayerWithoutChangingTheAudioOwner` | unchanged dialog/queue/player focus and audio-owner assertions | **pass** |
| `queueContextNavigationKeepsTheClickedChipFocusedWithoutStartingPlayback` | canonical queue map unchanged; canonical selection identity unchanged | **pass** |
| `heldSpaceReleasedByAChildDoesNotSuppressTheNextBackgroundPress` | canonical selection identity, typed `loads`, exact play/pause counts | **pass** |
| `narrowArabicLargeTextMirrorsNavigationButKeepsThePlayerTransportLtrAndReachable` | unchanged 320dp Arabic/175%/RTL/LTR assertions | **pass** |
| `shortLandscapeKeepsLibraryInputAndScrollableRailReachableAboveTheMiniPlayer` | unchanged 720×360 Arabic/175% layout and runtime-error assertions | **pass** |
| `compactNavigationKeepsEverySelectedLabelUnclippedAt200PercentInBothLanguages` | unchanged en/ar 200% label/layout assertions | **pass** |
| `resizingLongMetadataKeepsPauseAndSeekVisibleWhileCompleteProvenanceRemainsReadable` | authored typed long query/resolved receipt; canonical current selection/queue assertions retained | **red**: typed receipt exists canonically, but the player renders only legacy `executionBinding`, so `player-resolved-title` is absent |
| `freshTypedLibraryPlayUsesTheCanonicalOccurrenceAndRendersInTheQueuePanel` (new) | real LibraryScreen Play intent → typed capture/acquire/prepare; canonical occurrence revision 4; no legacy queue/binding copied | **red**: after the canonical success assertions, the rendered QueuePanel has no `queue-entry-<occurrence>` node |

## Actual validation

Final run: `port-final-004`, fresh per-attempt JUnit directory, actual Compose X11 display via
`scripts/with-display.sh`, shared heavy-build flock held for the complete Gradle child.

Command selection:

```text
:composeApp:desktopTest
  --tests dev.veyra.app.CoreShellFlowsTest
  --tests dev.veyra.desktop.DesktopLifetimeTest
  --tests dev.veyra.desktop.DesktopPlatformServicesOwnershipTest
  --tests dev.veyra.desktop.DesktopStartupJobsTest
```

Wrapper/guard: `scripts/with-display.sh scripts/gradle.sh --offline`, the one literal
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
and the ignored `desktop-test-bounds.init.gradle` (`maxParallelForks=1`, 384 MiB heap,
256 MiB metaspace, SerialGC, 2 processors, fresh XML output).

Result: **44 tests / 39 passed / 5 failed / 0 errors / 0 skipped**, Gradle exit 1.
- `CoreShellFlowsTest`: 14 tests / 9 passed / 5 failed.
- `DesktopLifetimeTest`: 7/7 passed.
- `DesktopPlatformServicesOwnershipTest`: 18/18 passed.
- `DesktopStartupJobsTest`: 5/5 passed.
- Production `:composeApp:compileKotlinDesktop` and shared JVM compilation succeeded before
  test execution; `:composeApp:compileTestKotlinDesktop` succeeded in the final run.

Fresh evidence (ignored; not committed):

| Artifact | SHA-256 |
|---|---|
| `runs/port-final-004/gradle.log` | `a8cf7e13731124d6b93c4c5c329b7ac230e172f00de0799fc550984c6cf5aa06` |
| `runs/port-final-004/result.json` | `9e57ce71ab1c02dbfaefb4fa04a07f93c15e1fb599a286019a8aff5644042de3` |
| `runs/port-final-004/xml/TEST-dev.veyra.app.CoreShellFlowsTest.xml` | `0e3fb9fbb5c47547a3614590cfc1bbbbcaf478a3d499915b0bc7b8c8d0616d85` |
| `runs/port-final-004/xml/TEST-dev.veyra.desktop.DesktopLifetimeTest.xml` | `4d06dd67d104dfabcc78757002e5159fc370ea8bb005d80fc9c040618b57f562` |
| `runs/port-final-004/xml/TEST-dev.veyra.desktop.DesktopPlatformServicesOwnershipTest.xml` | `ee181ffdbc51018d3583dc412019840644efefa435fd3ea218a029377f6f3762` |
| `runs/port-final-004/xml/TEST-dev.veyra.desktop.DesktopStartupJobsTest.xml` | `ca7ffa42981590316b2c9ee7126bfa641e23a92eebcc69bb58697e67e129c49f` |
| `inputs-before-004.json` / `inputs-after-004.json` | identical, `4508b8f5039cd2bc0230c0cc9e4be8c8b2b2f2d4b090c8f659480aeee3fcc667` |

The before/after manifest covers 507 tracked regular files, one untracked source input, HEAD
`67594669c6cd51e63ca772b281d4fc56144cafdd`, branch `t3/core-shell-typed-ports`, pin/gitlink,
actual submodule `c0a08090fe7dbd76e20216559b8184ad2cccde63` (clean), schema-3 hash
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`, and the three legal
baseline files. It reports **0 drift** across before/after.

Earlier attempts are preserved honestly:
- `port-final-001`: configuration failure before tests; the init script also applied to the
  pinned included kmpYT build.
- `port-final-002`: test compilation failed on one unresolved import; 0 tests.
- `port-final-003`: compile succeeded; 44 tests / 39 passed / 5 failed. One failure was a test
  expectation correction (fresh Play has a replace admission **and** an activation edit, so
  revision is 4), not a production gap.
- The pre-edit baseline attempts were blocked by the shared Gradle lock queue; the original
  fixture failure was **not executed** in this lane. The lead’s source evidence remains
  source-only.

## Production blockers exposed (outside this test-only lane)

1. **QueuePanel and playlist confirmation still read the legacy queue projection.**
   `composeApp/.../ui/music/PlayerSurfaces.kt:523` selects
   `playback.queues[viewedContext] ?: snapshot.queue(viewedContext)`, while
   `shared/.../playback/PlaybackController.kt:1668` deliberately publishes the real typed
   order in `executionQueues`/`queueSelections` and keeps legacy `queues = latest.snapshot.queues`.
   `composeApp/.../ui/music/LibraryScreens.kt:192-194` also decides the shuffle replacement
   confirmation from `snapshot.queue(QueueContext.MUSIC)`. The three queue-panel/node failures
   and the missing confirmation are the exact visible consequences. The UI lane must port the
   rendered queue/action path without reconstructing legacy queues.
2. **Typed playback provenance is not exposed to the player.**
   `PlaybackController.kt:1670` sets `PlaybackState.executionBinding` only for
   `PlaybackTarget.Schema2`; the typed current selection has the exact receipt/context in
   `currentSelection`/`executionQueues` but not in the legacy field.
   `PlayerSurfaces.kt:97,227,253` and `PlaybackProvenance.kt:44` consume only
   `playback.executionBinding`. The long-metadata test therefore has a canonical typed receipt
   but no rendered `player-resolved-title`/provenance receipt.
3. **Typed physical downloads are absent from the legacy snapshot/download UI projection.**
   `shared/.../data/room/RowMappings.kt:151` and
   `shared/.../downloads/DownloadPhysicalPresentation.kt:109-129` only project
   `PhysicalDownloadProducerOrigin.SCHEMA2`; `AppContainer.kt:131` exposes
   `downloads.presentation` to the UI. The canonical paused job is correct and tested, but the
   current Downloads screen cannot see it. The download UI lane owns this read-model correction.
4. **Podcast UI still reads legacy progress.** `PodcastUiContract.kt:154-157,197,220-231`
   reads `snapshot.progress`/`playbackBindings`; canonical catalogue progress and occurrence
   checkpoints live in `store.acquisition.playbackView().catalogProgress`/checkpoints. Not
   exercised by these 14 methods, but source-confirmed.

## Limits

The source answers, transfer answers, native events and screenshots are authored test doubles.
They are not live-source, authentication, transfer, native decoder, physical audio/crossfade,
device, platform-build or distribution evidence. This lane did not run Android/iOS/native
builds, workflow dispatch, packages, publication or a real source. Task B remains gated on the
eventual reviewed, merged and independently validated Task A SHA.
