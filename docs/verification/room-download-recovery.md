# Typed Room schema 3 and download recovery — Task A draft

Status: **incomplete implementation, not mergeable or release-ready.** This record covers
the normalized schema-3 acquisition graph, migration, catalogue-owner edits and the physical
download runtime brought into the real application. The typed Room/download layer compiles on
JVM/Android/desktop and its authored Room regressions pass, but two integration gates remain
open: the application's legacy `PlaybackController` still commits queue/history/binding state
through the now-guarded snapshot path, and the inherited schema-2 coordinator/application test
suites still seed physical state through that same guarded path. The typed kmpYT selection/
execution APIs are also absent from the pinned revision. See **Open blockers**.

## Provenance

| Item | Value |
|---|---|
| Integration base | `5328be09b6e9abcba4ee4a9793b934116e4e86f5` (`feat/veyra-integration`, Room foundations PR #2) |
| Branch | `t3/typed-room-download-recovery` |
| kmpYT pin | `c0a08090fe7dbd76e20216559b8184ad2cccde63` (unchanged, gitlink and `gradle/kmpyt.commit`) |
| Kotlin / Compose / AGP / Gradle / Room / SQLite / KSP | 2.2.21 / 1.9.3 / 8.13.2 / 8.13 / 2.8.5 / 2.6.2 / 2.2.21-2.0.4 (unchanged) |
| Input manifest | `/root/projects/Veyra/.work/delegation/room-download-20261107/typed-recovery-inputs.json`, SHA-256 `3102c6bf7cfe70792f69766910843432480d5b5244e3443b38d732fa6eedeecf` |
| Manifest records | 461 packet files + 349 committed-base files, all rehashed against actual bytes at copy time: 0 missing, 0 drifted |
| Immutable input snapshots | `.work/evidence/inputs/typed-recovery-inputs.json`; `.work/evidence/inputs/packet/` |

The canonical packet is the overlay at
`/root/projects/Veyra/.work/veyra-acquisition/.work/d010-staging/`. It is **not** a standalone
validated project. The old `feat/acquisition-lineage` tree, the private compile assembly and
review/derivative trees were read-only references; none was merged or copied wholesale.

### Assembly-manifest deviation (documented, not hidden)

`DownloadAssemblyManifest.json` still records the Coordinator primary patch as
`bb938472…` / 43,585 bytes. The lead's authoritative manifest and the actual packet bytes agree
on a **newer** patch: `251be78912e74e4bf10e8011f855ea14be12b9bd07b39948a03e79bdd150ca83` /
44,551 bytes. That current patch was applied **once** against the exact baseline
`c7e2a92509b57b3ec648b935d0abea8d86b2d94d72d8f5a1bb28bb7cd73e6e75`
(100,564 bytes) and produced `e6dd26d77c8690d9b9c8f568177695c2bd58222a4799bd9cf08ee04b9b81dc3c`
(119,798 bytes). The difference from the old expected postimage `ef8e13a7…` is the later
repair content (host-finish diagnostics, `failCheckpointAdmission`, canonical parallelism
checks). The `DownloadFiles.d010.patch` target matches exactly:
`189738a0b501fc6902941a98d42a01c65582d8b3eaef3b5873219892d0cf7673` (16,179 bytes).

Mechanical repairs made after applying the reviewed packet sources:

- `DownloadCoordinator.kt` was missing the `LocalMediaValidationReaderUnacknowledged` import
  used by later repair hunks; added it.
- `DownloadPhysicalRuntime.withMaintenance` now converts a non-cancellation exception to
  `CancellationException` when its own maintenance scope was cancelled. Required by
  `DownloadMaterializationRejectionRuntimeTest`; without it a native-negative-then-cancel path
  could return an `Unavailable` value instead of propagating cancellation.
- The held-cleanup playback regression now arms its barrier only for the explicit validation
  allowance (`owner.validations.size` grew). The old barrier also caught an earlier publication
  cleanup and asserted against the wrong point in time; the assertions themselves are unchanged.
- D's `SourceProvider.MUSICBRAINZ` enum addition was not needed without the release network
  producer and caused unrelated exhaustive UI branches; it was not retained. One authored test
  uses `COMMONS` instead.

### Scope deliberately not copied

- `source/release/MusicBrainzMetadataHttp.kt` and `MusicBrainzReleaseSource.kt` (network
  discovery producer; not part of Room/download recovery).
- D's typed YouTube gateways and D's older `YouTubeMediaSource`/`YouTubeKmpMediaGateway`;
  the current newer source/execution policy is retained. Typed acquisition therefore uses
  **explicit unavailable seams** in the composition root, not fabricated success.
- D's `PlaybackController`/platform engine refactor; the current playback/crossfade behavior is
  retained. This is the main open blocker below.

## What was integrated

| Area | Paths | Purpose |
|---|---|---|
| Typed models | `shared/src/commonMain/kotlin/dev/veyra/model/{AcquisitionModels,CollectionModels,DownloadBatchModels,ExecutionModels,PhysicalDownloadModels,SavedLibraryOrigin}.kt` | Immutable request/receipt/binding/occurrence/queue/batch/physical models |
| Pure release model/validation | `source/release/{ReleaseMetadata,MusicBrainzReleaseParser}.kt` | Strict schema/model dependency of release observations; **no HTTP/source/UI wiring** |
| Execution ports | `source/execution/{AcquisitionGateway,AcquisitionExecutionGateway,LegacyPreparedDownloadGateway}.kt`; `source/SourceReaderOwner.kt` | Authored port seams and retained-reader veto |
| Data owner | `data/{LibraryStore,LibraryPersistence,AcquisitionRepository,AcquisitionExecutionContexts,LibraryAcquisitionView,PhysicalDownloadRepository,PhysicalDownloadRetry,PlaybackAcquisitionRepository,ReleaseCatalogRepository,CatalogLibraryEdits}.kt` | One `LibraryTransactionOwned` owner, coherent post-COMMIT projections, same-owner catalogue/Undo edits |
| Schema 3 | `data/room/*` (32 files) plus schema-3 `Entities`, `VeyraDatabase`, `RoomLibraryPersistence`, `RowMappings` | Normalized acquisition/physical tables, exact migration 2→3, complete transactional readback |
| Acquisition coordinators | `acquisition/*` (12 files) | Capture/admission/collection/queue/batch/retry policy over the existing owner |
| Physical downloads | `downloads/*` (12 new files, Coordinator/Files primary patches) | One scheduler/mutex/slot/lease owner; typed and schema-2 physical jobs; reader retention; ownership epochs |
| Local validation | `playback/LocalMediaValidator.kt`, `AcquisitionPlaybackResolver.kt`, platform asset validators | Identity-free decoder/header/duration validation with positive-reader-release semantics |
| App root | `composeApp/.../AppContainer.kt`, `PlatformServices.kt` | Same-owner dependencies, materialization evidence, catalogue physical downloads, coverage admission, reader-drain hooks |
| Schema export | `shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json` | Actual KSP export, committed as versioned schema source |

### Preserved PR #2 safeguards

`VeyraSQLiteDriver` and `LibraryDao`/`RowWriter` are byte-identical to the merged PR #2
versions. The schema-3 `RoomLibraryPersistence` continues to use one IMMEDIATE transaction,
`PRAGMA synchronous = FULL`, stale-revision checks, complete raw-relation write difference and
full graph/state/readback equality before COMMIT. `AcquisitionGraph.applyCatalogSnapshot` still
rejects arbitrary legacy physical/binding/queue/history/progress/saved-identity mutation.

### Schema 3 identity

KSP generated 92 tables with Room identity
`a51a13de7a6c06c5b3ff5f9890c7ddd2`, structurally identical to the independently reviewed
reference export at
`.work/architecture/evidence/d010-shared1126-invalid-state-triage-v1/catalog-library-edit-v1/references/shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json`
(no missing/extra/differing table or index). Export SHA-256:
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`.

## Verification executed

All commands used `scripts/gradle.sh --offline`, the literal bounded JVM argument
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
and the ignored task-local Test initializer
`.work/verification/room-download-recovery/tools/shared-test.init.gradle`
(`maxParallelForks=1`, 384 MiB heap, 256 MiB metaspace, SerialGC, 2 CPUs). No committed build
file was changed. Runs are at HEAD `cb1eb81bb06a50eb8638aedb306762ebc6f3a619` unless noted.

| Run | Command | Exit | Actual result | Evidence |
|---|---|---:|---|---|
| 023 | `:shared:compileKotlinJvm` | 0 | JVM main compiled | `.work/verification/room-download-recovery/run-023-shared-jvm/` |
| 024 | `:shared:compileTestKotlinJvm` | 0 | JVM tests compiled | `run-024-shared-test/` |
| 025 | `:shared:compileDebugKotlinAndroid` | 0 | Shared Android production compiled | `run-025-shared-android/` |
| 026 | `:composeApp:compileDebugKotlinAndroid` | 0 | Android application compiled | `run-026-app-android/` |
| 027 | `:composeApp:compileKotlinDesktop` | 0 | Desktop application compiled | `run-027-app-desktop/` |
| 028 | `:composeApp:compileTestKotlinDesktop` | 0 | Desktop tests compiled | `run-028-app-testcompile/` |
| 022 | `:shared:jvmTest` filtered to the 38 typed suites | 0 | **450 tests / 38 suites, 0 failures, 0 errors, 0 skipped** | `run-022-typed-suite/` (fresh XML archived); log SHA-256 `48c9a1adf23452a702cd80863be462e5155cfbfb77459d478ad755fbe654c650` |
| 021 | full `:shared:jvmTest` | 1 | **1,066 tests / 87 suites, 153 failures, 0 errors, 0 skipped** — all 153 are legacy schema-2 snapshot-seeding tests (below) | `run-021-full-shared/` (fresh XML archived); log SHA-256 `a073f3a91fbad6b5bffa8593f208dabba344ce2a92d8edbe014d731e07a870d9` |
| 019 | `:composeApp:desktopTest --tests 'dev.veyra.app.AppContainerIntegrationTest'` | 1 | 31 tests, **26 failures** (same legacy-seeding cause); not application green | `run-019-desktop-app-tests/`; log SHA-256 `0dbf1e4218c81729c5dbe6629cd3dffbefc535edaf18c74a4f721a4b312a6aed` |

Failed attempts are retained separately: missing dependency cache (run-001), missing import
(run-002), test-construction mismatches while adding the compatible test files (run-005),
nullable-representation adaptation (run-009 first invocation), unnecessary enum/UI exhaustiveness
(run-010 first invocation), and the two deterministic recovery failures fixed above
(run-011/run-013). Each corrected retry names the changed cause; no failing run was overwritten
by a differently-labelled green run.

Material coverage in the 450 passing typed methods (fake source/native answers and authored
non-audio bytes; real Room/SQLite/files/OS locks):

- exported 1→2→3 and 2→3 migration, exact IDs/orders/kinds/amounts/provenance/physical fields,
  unknown facts stay unknown, full FK/integrity checks, missing-migration/corrupt/newer
  fail-closed, migration constraint rollback and per-connection FK;
- catalogue favorites/playlists/same-owner Undo, receipt after COMMIT only, real saved changes,
  external revision gaps, unrelated owner transactions, reopen/recovery/close;
- queue/collection commands, batch admission/retry, competing claims, interruption/cancel/
  pause-retain, finalization windows, stale progress, schema-2 adoption and reader retention;
- fresh-origin `AssetUse`, exact R1/R2, cold `Checking`, cleanup reservations and
  materialization invalidation (including the fixed native-negative+cancellation path).

## Open blockers (why this is a draft)

1. **Playback/queue runtime is not schema-3 safe.** The retained `PlaybackController` writes
   `queues`, `history`, `progress` and bindings through `LibraryStore.update`; schema 3
   deliberately rejects those identity-bearing snapshot mutations. The canonical typed
   controller (`PlaybackController` packet version) and the platform engine additions
   (`prepare(AcquisitionPlaybackSource, PlaybackSelection)`, `awaitRetiredReaders`, typed
   selection/resolver services) are **not integrated**. Merging this branch as-is would break
   queue editing, history/progress checkpoints and typed playback at runtime. This is the
   primary blocker and needs its own reviewed slice with the crossfade/reader platform tests.
2. **Legacy tests still seed through the guarded snapshot API.** 153 full-suite failures are:
   `RoomDifferentialWriteTest` 9, `RoomFileFaultTest` 3, `RoomLibraryStoreTest` 7,
   `RoomMigrationTest` 1, `RoomProcessRecoveryTest` 2, `RoomRecoveryTest` 9,
   `RoomWriteVerificationTest` 5, `DownloadAllowanceRoomTest` 7, `DownloadCleanupRoomTest` 14,
   `DownloadHashCancellationRoomTest` 4, `DownloadIntegrityRoomTest` 9,
   `DownloadMaintenanceRoomTest` 12, `DownloadOwnershipRoomTest` 11, `DownloadPolicyRoomTest` 10,
   `DownloadRecoveryRoomTest` 8, `DownloadSchedulingRoomTest` 25, `PodcastRestartOfflineTest` 1,
   `AppSourceResolverRoomTest` 16. Every failure is `INVALID_STATE` at seeding/mutating a
   schema-2 snapshot, not an assertion relaxation. Their typed counterparts exist and pass,
   but the prior assertions have not yet been mapped/ported one-by-one, and the PR #2
   differential-staging assertions in particular still need a typed-table port.
3. **Typed upstream APIs are unavailable in pin `c0a0809`.** `AcquisitionGateway.acquire/
   restore/collection/more/inspect` and prepared typed execution are implemented by the
   candidate `711b43e`/later, which is not the delivered pin. The app constructs explicit
   `typed_acquisition_upstream_unavailable` / `typed_execution_upstream_unavailable` seams.
   Existing catalogue MEDIA playback/download still uses the pinned resolve/download primitives;
   typed selection/download is not functional. No pin change was made.
4. **No iOS compilation.** This environment has no Xcode; the iOS validator/engine changes are
   source-only and unverified.
5. **No native/live/performance/distribution evidence.** All source/native answers are authored
   fakes; bytes are authored non-audio. No emulator, physical device, real kmpYT source, real
   codec, crossfade/audio output, performance benchmark or package was run. The preserved
   pre-existing performance stop (p95 ≈ 104.3 ms at 1,000 items) is not re-run or waived.

## Evidence layout

Raw logs, fresh XML, the init script and input snapshots stay under the ignored
`.work/verification/room-download-recovery/` and `.work/evidence/` directories. The full run-021
XML archive contains 87 suites; run-022 contains the 38 typed suites. No evidence from the
lead's room-foundations runs or the private 1,126-method run was reused as this run's evidence.
