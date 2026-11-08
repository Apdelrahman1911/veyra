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

## Limits

Authored gateway/transfer/native answers are non-audio doubles over real Room/files. No live
source, native decoder, physical audio/crossfade, emulator/device, package, signing, workflow or
Task B evidence is claimed. F2 physical semantics and PR17 acceptance remain with the shared
owner; this branch is a provisional integration candidate, not accepted Task A.
