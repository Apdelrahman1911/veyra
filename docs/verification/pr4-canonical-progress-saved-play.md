# Stacked progress, saved-R1 playback and F2 UI wiring

Status: **draft integration candidate; not Task A acceptance.** This topic stacks PR12's tested
canonical-consumer correction on the frozen PR18 canonical podcast progress and PR15 activation
ACK candidate, with the frozen PR17 retained-validation API merged provisionally for F2 UI
wiring. PR17 remains unaccepted and under shared-owner correction; its connected F2 execution
test is intentionally not claimed green here.

## Stack

| Layer | Ref | State |
|---|---|---|
| Parent PR #4 | `t3/typed-room-download-recovery` at `6d09ed5` | draft, unchanged |
| PR12 correction | `e27e3c5` (tested code `aa583a8`) | frozen input |
| PR18 canonical podcast progress | `d59d6a7` (tested `4d055d7`) | frozen input; merged ordinarily |
| PR15 activation ACK | `a663708` (tested `ab686ef`) | frozen input; merged ordinarily |
| PR17 retained validation | `bc46d42` (tested frozen candidate) | merged provisionally; **unaccepted**, shared owner correcting |
| This topic | `t3/pr4-canonical-progress-saved-play` | new draft PR |

No frozen remote head was modified. The merge preserves PR12's canonical queue/player parameters
and PR14's download `screenDownloads`/`screenOptions` wiring.

## Implemented behavior

### Canonical podcast progress

- `VeyraApp` collects `container.podcastProgress` and passes it to both `EpisodeDetailUiState`
  and `PodcastShowUiState`; the hub already receives it through `podcasts.copy`.
- Canonical `MarkPlayed`/unplayed and real controller checkpoint positions reach the existing
  show/detail surfaces. Authored engine positions are checkpointed through the actual controller
  and read back as `MATCHED` resume data.
- PR18 legacy fallback correction: a nonnull paired view containing a `LEGACY` row no longer
  routes that row through the canonical-current-occurrence comparison. The genuine schema2
  progress-vs-downloaded/next-play receipt comparison is preserved. Canonical missing/invalid
  rows remain canonical `UNVERIFIED` and never fall back to unrelated legacy progress.

### Saved-R1 playback (F3)

- `MusicUiAction.PlaySaved` and `PodcastAction.PlaySavedEpisode` carry the exact binding ID from a
  currently verified typed catalogue download row.
- `AppContainer.playSaved` revalidates the current paired projection: media kind/id, typed
  subject/request/receipt/binding/recording lineage, exact R1 `AssetUse`, and current-process
  verified local readiness. It never selects a newest binding or performs a title lookup.
- If an exact queued R1 occurrence exists, it uses `PlaybackCommand.PlayOccurrence`; otherwise it
  creates a new occurrence for the SAME accepted request via the existing admission factory and
  atomically replaces/activates the queue. Resolution attaches the existing R1 binding.
- Cold restart remains gated until real local validation. A fresh `Play` still manufactures R2
  and fails offline; no R1 asset is borrowed. Invalid/stale binding requests fail closed without
  queue, stream or native side effects.
- UI entry points: library download row, media row, track detail, podcast row and podcast
  detail controls use the exact binding when the paired row is a ready typed catalogue row.
- Neutral source rows keep `bindingId = null` and never receive play/validate catalogue authority.

### F2 retained-FAILED UI wiring (candidate)

- `DownloadSurfaceEntry` exposes the persisted typed producer binding for catalogue rows even
  when the current process has not verified readiness; this is not a readiness or AssetUse
  grant. Neutral rows still expose no binding.
- Music download rows/menus/track detail and podcast download actions offer localized
  `Validate local file` only for typed retained FAILED bytes with an exact binding. Legacy rows
  keep ordinary Retry. Neutral retained failures show a truthful unavailable message and a
  separate confirmed Remove.
- `AppContainer.validateRetainedDownload` rechecks exact job/binding/subject/request/media and
  calls `DownloadCoordinator.validateRetainedAssetUse(downloadId, bindingId)`. Success is not
  consumed as playback authority; a failure preserves the retained failure/bytes.
- **Pending:** PR17 is unaccepted. Its API currently uses `downloadId + bindingId` and cannot
  disambiguate repeated batch claims; shared owner is specifying corrected exact/rejection
  semantics. A connected retained-FAILED AppContainer execution test is therefore **not**
  claimed; the earlier attempt timed out before producing a retained state and is preserved as
  red evidence. F2 UI/action selection has pure/Compose coverage.

## Tests added

- `PodcastProgressAppJourneyTest` (2): real `VeyraApp` show/detail canonical MarkPlayed/unplayed;
  authored native Position → actual controller checkpoint → canonical 45s Resume in detail.
- `PodcastProgressLegacyPairedViewTest` (3): production-shaped nonnull paired view with legacy
  matching/changed schema2 receipts, and canonical invalid row staying `UNVERIFIED`.
- `SavedRecordingPlaybackTest` (4): real AppContainer/Room/controller music and podcast saved-R1
  offline playback, fresh-R2 negative, cold validation gating, and mismatched-binding rejection
  without side effects.
- `RetainedValidationActionTest` (3) and `RetainedDownloadSurfacesTest` (2): exact typed
  Validate action, neutral no-binding honesty, separate Remove, no-asset Retry, Arabic layout.
- Existing PR18 progress projection/surface/compatibility classes and PR14 typed podcast
  download state tests are retained.

## Executed verification

All final runs used the host gate's existing project lock, offline Gradle, the exact bounded JVM
argument and one 384 MiB SerialGC/2-CPU Test fork.

- `final-stack-core-001` at `060f1c3`: **44/44 pass**, 0 failures/errors/skips, exit 0; 14
  CoreShell + 7 lifetime + 18 platform ownership + 5 startup methods. Inputs unchanged
  (`b3e331dd7fc436bdba18c151a353d886b4e500d502eb6fc3c9846ac1eb5e3f8e`), dirty none, drain none.
  Log SHA256 `9423888698cd00df456f2af56d41ddfc8a1268cfa84cf49c14b2ea13744a9ae7`.
- `final-stack-features-001` at `060f1c3`: **28/28 pass**, 0 failures/errors/skips, exit 0
  (9 classes: F3 connected, progress journeys, legacy paired view, progress projection/surfaces/
  compatibility, retained action/UI, typed podcast download state). Same unchanged input
  manifest, drain none. Log SHA256
  `b843f6d49558f129391570aec40014d8aa3a793c7c3759ee7febc417e353b09c`.
- `final-stack-pr12-music-002` at `060f1c3`: **24/24 pass**, 0 failures/errors/skips, exit 0;
  16 PR12/new typed queue-player methods plus 8 selected legacy music presentation/provenance
  methods. Inputs unchanged (same `b3e331dd…`), dirty none; the captured idle Kotlin compile
  daemon was terminated and a post-cleanup scan found no owned compiler/test process. Log SHA256
  `1ccf98cf2accb28bde52e1156b6f3af15c179a1566f40c646502ee99efa97a44`.

Red attempts preserved under `.work/verification/pr4-core-shell-corrections/runs/`:
`red-pr18-legacy` (real 2/3 failures, source bug confirmed), `f2-f3-focused-001` (F2 retained-state
fixture timeouts and neutral-action failure; fixed UI action, connected F2 held for shared PR17),
`progress-journey-005..011` (test/test-only harness defects and the authored seekSequence filter;
each was changed before the next attempt), and the earlier PR12 reds. The legacy crossfade
`crossfade-progress` scroll hang remains unaccepted from PR12; it is not in the final selected
music set.

## Limits

Authored gateway/transfer/native answers are non-audio doubles over real Room/files. No live
source, native decoder, physical audio/crossfade, emulator/device, package, signing, workflow or
Task B evidence is claimed. F2 physical semantics and PR17 acceptance remain with the shared
owner; this branch is a provisional integration candidate, not accepted Task A.
