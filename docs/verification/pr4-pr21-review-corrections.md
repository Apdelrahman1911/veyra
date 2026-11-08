# PR21 review corrections: cross-context exact resume and legacy execution

Status: **draft successor correction; not Task A acceptance.** This topic starts from frozen PR21
`6e0a64a6a2cd3b18259e6c9568e785390113da1e` after the independent PR21 review. PR19 `c31b33d`
and parent PR4 `6d09ed5` remain unchanged and unaccepted. No shared API, PR20 or Task B work is
integrated.

## Stack

| Layer | Ref | State |
|---|---|---|
| Parent PR #4 | `t3/typed-room-download-recovery` at `6d09ed5` | draft, unchanged |
| Frozen PR19 | `t3/pr4-canonical-progress-saved-play` at `c31b33d` | unaccepted, unchanged |
| Frozen PR21 | `t3/pr4-pr19-review-corrections` at `6e0a64a` | unaccepted, unchanged |
| This correction | `t3/pr4-pr21-review-corrections` | successor draft |

## Corrections

### PR21-F1 — cross-context exact PODCAST Resume

`PodcastProgressView` now projects the exact displayed PODCAST queue occurrence, binding, canonical
recording and queue edit revision. The canonical current episode remains a genuine Resume target
while MUSIC is the active native context. `PodcastEpisodeUiState` exposes
`resumeOccurrenceId`/`resumeBindingId`/`resumeQueueRevision`; row, hub Continue and detail use the
same exact target. `AppContainer.resumeEpisode` refreshes the current Room queue, verifies the exact
occurrence/media/binding/revision, and dispatches `ResumeOccurrence`. It does not require the podcast
to be the active selection, substitute active music, choose a newest same-media binding, or fall
back to fresh Play. Duplicate occurrences and stale revision/binding identity fail closed.

The connected journey adds a MUSIC-active case: while the PODCAST queue retains its 45 s occurrence,
opening the real detail and clicking Resume produces `[podcast 0, music 0, podcast 45000]`, the exact
PODCAST binding/occurrence/context, and no new title query. A row/Continue action test verifies the
same exact action identity.

### PR21-F2 — accepted non-atomic saved-authority boundary

The fresh-Room preflight is before `Activate`/`AdmitQueue`; revocation observed there rejects
`saved_local_authority_lost` before queue/native/gateway mutation. Between that read and the later
activation there is **no atomic guard**. The queue/occurrence/admission/checkpoint may commit before
the final local-only resolver rejects, and no rollback is promised after a positive ACK. The
non-negotiable invariant is narrower and enforced: no online preparation and no native load without
an exact successful local check.

`PlaybackTestHooks` is a test-only observation seam after the preflight and before `Activate`. The
deterministic race test revokes only the exact durable claim at that point, releases the actor, and
records the actual queue edit/current-occurrence commit, the exact
`offline_asset_removal_pending` local-only failure, zero new native loads and unchanged gateway
counters. The queue/checkpoint mutation is asserted, not denied. PR15 post-ACK stage-retirement
ordering remains unchanged for normal paths.

### PR21-F3 — real schema2 legacy limitation, not a simulated Resume

`AppContainerSchema2Fixture.seedPodcast` creates a genuine exported-schema-2 podcast database with a
legacy queue occurrence, historical binding, 45 s progress, COMPLETE download and authored local
file, then lets production migration create the schema-3 graph. Real AppContainer/Room/controller
inspection proves the migration result:

- the queued schema2 occurrence is `LEGACY_UNBOUND`;
- `bindingId` is null;
- no materialization/use authority is attached to that occurrence;
- `podcastProgressView` exposes no queued legacy Resume target;
- `PodcastEpisodeUiState.resumeActionable` is false and no Resume action is offered;
- a forced exact `ResumeEpisode` against that occurrence rejects
  `resume_target_unavailable` without a native load or online preparation.

No production command may invent the historical binding for a `LEGACY_UNBOUND` occurrence, and no
schema2 provenance is converted to typed identity. The previous pure action-shape test remains a
projection-only test with a synthetic exact target; it does not claim runtime legacy Resume success.
This is the concrete availability limitation required by the review.

### PR21-F4 — podcast saved action cannot override canonical Resume

Podcast `savedPlayBindingId` now comes from the same unambiguous typed-catalogue rule as the music
media row/detail (`DownloadSurface.unambiguousCatalogueBinding`), not `forMedia` newest. Resume wins
when an exact canonical target exists, so a `ResumeAt` for recording B can never dispatch
`PlaySavedEpisode(A)`. Two ready recordings with no explicit choice expose no saved binding; exact
Downloads rows remain exact. Tests cover two ready A/B, typed ready A plus canonical current/progress
B, exact legacy matching target shape, and action precedence.

### PR21-F5 — test-strength closure

- Pre-admission revocation asserts exact `saved_local_authority_lost`.
- The saved-music second tap asserts the exact occurrence ID and unchanged queue edit revision.
- The saved-podcast second tap asserts unchanged gateway counters as well as the second 45 s load.
- A repeated current saved tap reloads the same occurrence without a new queue edit.
- The resolver's defensive selection policy is tested directly for stale-sibling filtering and the
  exact `saved_local_asset_ambiguous` detail. Production validation forbids two physical jobs for one
  producer/subject, so a valid Room graph cannot construct two verified assets for one binding; this
  is documented as a defensive/corrupt-graph branch, not a fabricated Room claim.
- The real schema2 limitation and missing-target rejection are executed.

### PR21-F6 — corrected evidence classification

The PR21 final runs executed **dirty at `c31b33d`**, not clean at published `6e0a64a`. All production
bytes matched `6e0a64a`; `SavedRecordingPlaybackTest.kt` and `SavedLocalOnlyResolverTest.kt` were
independently reconstructed as formatting/import-order-equivalent, not byte-identical. Docs were
changed after those runs. `argv.txt` is runner-written; Test bounds and in-process compilation are
configured/requested, not independently attested; the forced included compile actually executed and
left no observed compiler daemon. `core-music-pr18-001` retains 0 XML, worker exit 143 and positive
drain; the earlier jstack supports the `performScrollTo`/AWT hang location, but no in-run worker
PID/start/TERM identity was retained. `podcast-ui-002` is not included as a final 84-method result.

## Verification

Final successor runs use committed source/tests head `bd9edf7`, the existing project lock, offline
Gradle, the literal bounded
JVM argument and one configured 384 MiB SerialGC/2-CPU Test fork. Every listed run has identical
before/after input manifests, fresh XML/log, exit 0 and `owned_processes=0`/`drain=positive`.

- pin `c0a08090fe7dbd76e20216559b8184ad2cccde63`
- schema3 `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 entities)
- final input hash `48c4755ad3101854d88d642048d0d048cf92bd2cc05a827ba6286d0ed8e0500a`
  before and after for all three final runs

| Run | Scope | Result | Log SHA256 |
|---|---|---|---|
| `pr21-final-ui-002` | 13 affected podcast/UI/journey/saved classes | **98/98 pass** | `dd5db5c743b7cf78b000906bbdbe65ef450bade36a7cbc1bfb21537f7dfd636e` |
| `pr21-final-shared-004` | resolver + 8 shared playback/admission/command/restart classes | **98/98 pass** | `f7582fefe0a2e698ee5b5b3ffb00cd702c1871487a44a4c6a0fb064f2351029a` |
| `pr21-final-core-001` | core 44 + music 16 + PR18/retained 9 | **69/69 pass** | `243a6d984a5795e63d78f6e0ebfa74d6c486dc7eaa77371aacc32ca84500e37d` |

Class counts: UI includes journey 3, saved 8, SavedCatalogue 3, and the 84 existing podcast/UI
methods. Shared includes resolver 2 (no-use local-only plus selection policy), activation admission
11, playback acquisition 13+17+26, selection 12, restart 9 and shutdown 8. Core/music/PR18 is 44
core + 16 music + 9 retained/progress methods.

### Red attempts preserved

The PR21 historical reds remain under the published evidence; successor reds are also retained:

- `pr21-corrections-002`: test compilation failed, 0 tests (`view.library` receiver).
- `pr21-corrections-003`: 49 tests, 4 failures (cross-context 0 load, missing expected-binding
  rejection, schema2 null binding, F2 revocation timestamp).
- `pr21-corrections-004`: test compilation failed, 0 tests (`assertFalse` import).
- `pr21-corrections-005`: 4 tests, 2 failures (cross-context load timing; F2 command-vs-async
  failure observation).
- `pr21-corrections-006`: 4 tests, 1 failure (F2 expected command failure instead of post-ACK
  resolver failure).
- `pr21-corrections-007`: 4 tests, 1 failure (actual post-revocation detail is
  `offline_asset_removal_pending`, not `saved_local_authority_lost`).
- `pr21-final-shared-001`/`002`/`003`: shared test compilation/fixture reds before the pure
  selection-policy tests were corrected.
- `core-music-pr18-001` from PR21 remains the known `MusicSurfacesTest.performScrollTo` hang (0
  finalized XML, worker exit 143, positive drain). It is excluded and not called green.

## Limits

Authored gateway/transfer/native answers use non-audio bytes over real Room/files. No live source,
native decoder, audio, device/emulator, package, signing, workflow, Task B or Task A acceptance is
claimed. PR17 F2/Validate/foreground, PR20 and the shared successor remain separately unaccepted; no
API was integrated. The real migrated schema2 queue occurrence is `LEGACY_UNBOUND`, so no runtime
legacy Resume success is claimed; a legacy Resume is only offered when an exact BOUND target is
provided by a future accepted owner path. The known scroll hang remains unaccepted.
