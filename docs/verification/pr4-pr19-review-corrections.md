# PR19 review corrections: exact saved resume and local-only execution

Status: **draft correction candidate; not Task A acceptance.** This topic branches from the frozen,
still-unaccepted PR19 head `c31b33dce080d1d89528e4db104e7ea590d9a39b` and stacks on parent draft
PR #4 (`t3/typed-room-download-recovery` at `6d09ed5fa24f39e97a6d7b9689e9e30a008e1857`). The
frozen PR19 remote ref is unchanged. PR17/PR18 and the shared connected F2 execution remain
unaccepted as recorded below.

> **PR21 independent-review successor:** the frozen PR21 review confirmed that canonical Resume
> could still be displayed while the PODCAST cursor was not the active selection and then fail on
> tap; it also found the legacy Resume path source-only and the saved-authority preflight only
> partially bounded. The successor `t3/pr4-pr21-review-corrections` carries exact cross-context
> occurrence/binding/revision identities, records the accepted non-atomic boundary, and proves the
> real schema2 migration result is `LEGACY_UNBOUND` with no queued binding. No runtime legacy Resume
> success is claimed; the real owner test asserts `resume_target_unavailable`. Final evidence is in
> `docs/verification/pr4-pr21-review-corrections.md`. This document's PR21 source refs remain frozen,
> and its historical runs executed dirty at `c31b33d` rather than clean at `6e0a64a`.

## Stack

| Layer | Ref | State |
|---|---|---|
| Parent PR #4 | `t3/typed-room-download-recovery` at `6d09ed5` | draft, unchanged |
| Frozen PR19 candidate | `t3/pr4-canonical-progress-saved-play` at `c31b33d` | unaccepted, unchanged |
| This correction | `t3/pr4-pr19-review-corrections` | new draft PR |

## Corrections

### F1 (HIGH) — Resume now carries an exact occurrence instead of a fresh Play

- New `PlaybackCommand.ResumeOccurrence` activates one exact occurrence through the normal
  `Acquire → ACK → load` transaction and always uses the occurrence/canonical checkpoint resume
  policy. It cannot enter the fresh-capture `Play` path or create R2.
- `PodcastAction.ResumeEpisode` carries the displayed media id and a binding id only when an exact
  resume target is proven. `PodcastEpisodeUiState.resumeActionable` is true only for the canonical
  current occurrence or a legacy downloaded target whose recording matches the recorded progress;
  `resumeBindingId` is the exact canonical/legacy target. Row, hub Continue and detail use the same
  predicate and the same action.
- `AppContainer.resumeEpisode` revalidates the current selection/queue revision and dispatches
  `ResumeOccurrence`; a missing/stale target fails closed with `resume_target_unavailable`. It
  never falls back to `PlayEpisode`.
- The legacy downloaded-target case carries that exact binding in the action. The owner/actor
  re-resolves a queued occurrence for the same binding; if the exact occurrence is no longer
  queued or its recording/authority cannot be verified, the action fails closed with
  `resume_target_unavailable` rather than substituting a fresh Play/R2. This is a documented
  availability limit, not a fresh-Play resume claim.

**Authorized obsolete assertion mapping.** In
`composeApp/src/desktopTest/kotlin/dev/veyra/ui/podcasts/PodcastScreensTest.kt`,
`sameVideoNewLocalReceiptResumesItsMeasuredPositionDespiteDifferentCurrentDisclosure`:

- old: `assertEquals(listOf<PodcastAction>(PodcastAction.PlayEpisode(episode.id)), actions)`
- new: `assertEquals(listOf<PodcastAction>(PodcastAction.ResumeEpisode(episode.id, target.id)), actions)`

Reason: the displayed `ResumeAt 45s` is anchored to the downloaded `target` recording that matches
the recorded progress. The unrelated current disclosure `current` must not borrow that position,
and a fresh catalogue Play was the defect under review. Fixture setup and every other assertion in
that method are unchanged; no method was removed or weakened.

`PodcastProgressAppJourneyTest.authoredControllerCheckpointProvidesCanonicalResumePositionToShowAndDetail`
now clicks the real detail Resume action and asserts the engine `startPositionMs == 45_000`, the
same exact binding, no new gateway title query, and the saved checkpoint before the click.

### F3 (MEDIUM) — saved intent is local-only through actor, resolver and resource admission

- `PlaybackCommand.AdmitAcquisition` and `ResumeOccurrence` carry `localOnly`; `playSaved` sets it
  for both the existing-occurrence and new-occurrence branches.
- `AcquisitionPlaybackResolver.resolve(..., localOnly = true)` filters exact compatibility-v1
  uses, requires one materialization, and fails `saved_local_authority_lost` /
  `saved_local_asset_ambiguous` before `gateway.preparePlayback`. Generic fresh Play remains online
  capable.
- Before any `Activate` or `AdmitQueue` Room mutation, the controller calls
  `AcquisitionPlaybackResolver.requireExactLocalAuthority(bindingId)` from a fresh Room read. A
  revoked/mismatched/ambiguous saved authority therefore rejects with `INVALID_STATE` while the
  queue, occurrence set, outgoing native owner and gateway counters stay unchanged.
- `SavedRecordingPlaybackTest` covers a revoked exact AssetUse before actor admission, an existing
  occurrence with another media's genuine accepted binding, and a stale queue edit revision. Each
  asserts no queue/occurrence/load/gateway mutation. `SavedLocalOnlyResolverTest` verifies no
  online `preparePlayback`/transfer when the exact use is absent.
- Revocation after a positive activation ACK is a different state; this correction does not
  promise rollback of already-accepted native work.

### F4 (MEDIUM) — the reused-occurrence and podcast resume branches are actually exercised

- The saved music test performs a real second `PlaySaved` tap and waits for a second engine load,
  asserting one occurrence, one additional load and unchanged gateway counts.
- The saved podcast test sends a genuine authored 45 s native position, flushes through the real
  controller, asserts the stored checkpoint is 45 s, taps `PlaySavedEpisode` again and asserts the
  next engine load starts at `45_000`.
- The journey test clicks the actual detail Resume action after a canonical checkpoint and asserts
  the same position/binding/no-query conditions.

### F5 (LOW) — ambiguous ready recordings no longer collapse to newest

- `savedCatalogueBinding(mediaId)` now returns a binding only when exactly one ready
  `TYPED_CATALOGUE` recording is present. Two ready different recordings yield `null`; the
  Downloads surfaces remain the explicit choice, and neutral/non-ready rows expose no authority.
- `SavedCatalogueBindingTest` covers sole ready, two-ready ambiguity, and neutral/non-ready rows.

### F2 (MEDIUM evidence) — in-lock runner and drain

- Historical PR19 artifacts are preserved unchanged. Their `inputs-before/after` and drain
  scans were outside the host gate lock; the music run's idle `KotlinCompileDaemon` PID 22252 was
  observed later absent, but no termination action was recorded. Those historical runs are not
  reclassified as in-lock proof.
- The new successor scripts are
  `.work/verification/pr4-core-shell-corrections/run_group_gate2.sh` (host admission) and
  `run_group_inlock.sh` (executed inside the held project lock). The latter records the gate
  parent/lock fd, exact argv, start/end/exit, before/after input snapshots, fresh XML/log and a
  positive owned-process drain while the lock is still held.
- The Gradle invocation adds `-Pkotlin.compiler.execution.strategy=in-process` in addition to the
  root property, covering the included `kmpYT` build. The runner scans only Java/Xvfb executables;
  any task-owned compiler daemon is identified by PID/start/action and positively re-scanned.
  `included-compile-001` separately forced `:kmpYT:kmpyt:compileKotlinJvm --rerun-tasks`; the task
  actually executed (not UP-TO-DATE), BUILD SUCCESSFUL, with a positive drain and no compiler
  daemon. This is executed-task evidence for the requested strategy, not an independent Kotlin
  runtime-strategy attestation.

## Verification

Every final run used the host gate's existing project lock, offline Gradle, the exact bounded JVM
argument and one 384 MiB SerialGC/2-CPU Test fork. The before/after input manifests match in every
final run, and the indexed `kmpYT` pin and schema3 hash are unchanged:

- pin `c0a08090fe7dbd76e20216559b8184ad2cccde63`
- schema3 `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 entities)

| Run | Scope | Result | Log SHA256 |
|---|---|---|---|
| `progress-journey-023` | `PodcastProgressAppJourneyTest` + `SavedRecordingPlaybackTest` | **8/8 pass**, 0 failures/errors/skips, exit 0 | `22bcbbf8b77f6723ec8a90760b54f755a832a66e34d1fc5b9ab8b790290e3fe4` |
| `saved-local-only-002` | `SavedLocalOnlyResolverTest` | **1/1 pass**, exit 0 | `a33c0f78f3a2e665f9e1c9984e114c34c543983b9def14de7fb9cfb07cabcbd0` |
| `podcast-ui-003` | 11 affected podcast/UI classes | **84/84 pass**, exit 0 | `fcdc37b2846020dd728b02ae74e47add86cf22a5d91401fe8677a9cfc5538ecf` |
| `shared-playback-001` | controller/admission/command/restart classes | **96/96 pass**, exit 0 | `2e4e07a4ad6b1ffa2f199e4d54d2252797d6cfcc89f76a43246c3451799ea236` |
| `core-music-pr18-002` | core 44 + selected music 16 + PR18/retained 9 | **69/69 pass**, exit 0 | `2e0efd1d628ab77657b4b47a111d0486cc75b488b4f338e59273197b8b8dfab1` |
| `included-compile-001` | forced `:kmpYT:kmpyt:compileKotlinJvm` | BUILD SUCCESSFUL, exit 0, task actually executed | `46050d93e99eff5bf3cbb0cb4b8893cca405ab2c9e6fd1300f75cbb1fb0bfa3b` |

Final green input hashes: `progress-journey-023`, `podcast-ui-002`/`003`, `shared-playback-001`,
`core-music-pr18-002` and `included-compile-001` use
`45c08ac4673f0d008dc38a57155d386c351f98d194515eec238248a9edff39de` before and after.
`saved-local-only-002` uses
`da1340081fc0a6434927166e3765e9ba90122e7049328955682507723a95293e` before and after. Each listed
run's own `result.json` records its exact manifest. All final drains report `owned_processes=0`
and `drain=positive` while the gate lock was held.

### Red attempts preserved

No red attempt was deleted or reused:

- `progress-journey-015`: test compilation failed, 0 tests (`playbackView` accessor).
- `progress-journey-017`: test compilation failed, 0 tests (two-argument `error` helper).
- `progress-journey-018`: 2 tests, 2 failures (`saved_local_binding_missing`; journey `0` position).
- `progress-journey-019`: 2 tests, 2 failures (one-shot `withTimeout` boolean waits).
- `progress-journey-020`: 2 tests, 1 failure (absolute no-query assertion).
- `progress-journey-021`: 8 tests, 7 pass, 1 failure (immutable `AssetUse` wait).
- `saved-local-only-001`: shared test compilation failed, 0 tests (missing import).
- `podcast-ui-001`: 84 tests, 83 pass, 1 failure (`Replay` label regression).
- `core-music-pr18-001`: **red hang**, 0 finalized XML, worker exit 143, positive drain. The verified
  task-owned worker was stopped after ~12 minutes spinning in
  `MusicSurfacesTest.requestedAndFirstResultTitlesStayDistinctAndOnlyConfirmedOverlapLooksActive`
  at `performScrollTo`/AWT idle. This is the known unresolved legacy scroll hang; it is excluded
  from the green 69-method group and is not called green.

### Exact final source

The final 8/8 run `progress-journey-023` was executed after the `Replay` label correction, the
`CopyOnWriteArrayList` engine publication fix and the bounded `catalogProgress` wait. Its input
manifest is `45c08ac4…` before and after, so the focused F1/F4 evidence applies to the exact source
that is committed. The shared playback and podcast/UI regressions use the same final input hash.

## Limits

Authored gateway/transfer/native answers use non-audio bytes over real Room/files. No live source,
native decoder, audio, device/emulator, package, signing, workflow, Task B or Task A acceptance is
claimed. PR17 F2 connected validation and the corrected shared Validate API remain held for the
lead's frozen handoff. PR20 remains independently reviewed but unmerged and unaccepted; its shared
successor was not integrated. The exact final source preserves PR15's post-ACK stage-retirement
ordering, exercised by the 11 activation-admission and 9 podcast-restart methods in the 96/96
group. A stale-before-admission saved action is rejected before mutation; revocation after a
positive activation ACK is not claimed to roll back accepted work. The legacy crossfade scroll
hang remains unaccepted and is not called green.
