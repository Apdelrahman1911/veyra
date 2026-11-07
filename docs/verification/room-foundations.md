# Room recovery foundations

Status: **first prerequisite slice of Task A, not release readiness**. This record
covers the original-path schema-2 Room recovery integration: connection-level
foreign-key enforcement and exact differential order staging with the existing
transactional readback guard. Typed schema-3 acquisition/download recovery is not
included and remains a later integration step.

## Provenance

| Item | Value |
|---|---|
| Integration base | `ec97925883f7a26765b7e3409ba5a81e358082b9` (`feat/veyra-integration`) |
| kmpYT pin | `c0a08090fe7dbd76e20216559b8184ad2cccde63` (unchanged) |
| Kotlin / Room / bundled SQLite / KSP | 2.2.21 / 2.8.5 / 2.6.2 / 2.2.21-2.0.4 (unchanged) |
| Database version | 2 (unchanged) |

The eight sources were taken from the read-only packet at
`/root/projects/Veyra/.work/veyra-acquisition/` and matched the authoritative
manifest (`room-foundations-inputs.json`) exactly before use:

| Path | Input SHA-256 |
|---|---|
| `shared/src/commonMain/kotlin/dev/veyra/data/room/LibraryDao.kt` | `a470855d1e035e84c66c6df239d7243db7602e1568bb96f05acd4dc86d73ebfa` |
| `shared/src/commonMain/kotlin/dev/veyra/data/room/RowWriter.kt` | `09f891911278bd4e0705c5e63f3b351cfca037dbf135a716c6e7592d8bfbf9a4` |
| `shared/src/commonMain/kotlin/dev/veyra/data/room/VeyraDatabase.kt` | `5f6b3376f9fc974e1797978ca63aa91f685090fc9ebd95cccdda99cf6bf5d1a9` |
| `shared/src/commonMain/kotlin/dev/veyra/data/room/VeyraSQLiteDriver.kt` | `0fab5fea777a9986c3c5650c4746f6c8ba58575b5fa2109d5d75cd69f2d5f1b9` |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomMigrationTest.kt` | `28da8a9f24649be3c5ac9925ec0f609851e7f82336b16d1d90ca94b11aa7a0d6` |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomProcessRecoveryTest.kt` | `20edc119226e093a90c017f3b36cab0a203cb94644b343867b327c9a4b675cfb` |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomDifferentialWriteTest.kt` | `97822a3712d7d0c695b7f4b583d62f020e31d78a57c1579a7eb018532b309506` |
| `shared/src/jvmTest/kotlin/dev/veyra/data/VeyraSQLiteDriverTest.kt` | `9a45c7c2e22424c418898c9581c330f8650d7934b57421d700dd98e0cd40dfb6` |

The five pre-existing targets matched their manifest `baseSha256` before editing.
The old `feat/acquisition-lineage` baseline was not merged.

## Before / after

**Before.** `buildVeyraDatabase` installed `BundledSQLiteDriver` directly, so SQLite
foreign keys were declared but not enforced; Room's `onOpen` would also run too late
to protect a migration transaction. Order writes staged the whole relation table and
upserted every row in it whenever any row changed, including payload-only history and
playlist updates.

**After.**

- `VeyraSQLiteDriver` delegates to `BundledSQLiteDriver`, executes
  `PRAGMA foreign_keys = ON`, and requires a real `PRAGMA foreign_keys` row equal to
  `1` before returning that same connection. A failed configuration closes the
  connection once, rethrows the original failure, and suppresses a distinct close
  failure. Only `buildVeyraDatabase` changes driver selection; migration registration,
  query context, WAL, schema version and constructors are untouched.
- `LibraryDao` staging statements take exact bounded identities: composite
  album/artist, track/artist, album/track and podcast/episode keys are filtered by
  their parent id; playlists, playlist entries, both queue orders and history are
  filtered by row id. `RowWriter` stages only existing rows whose unique order key
  actually changes, in 256-id chunks, before any positive upsert. Deletions still run
  first, changed existing rows still upsert before inserts, and new rows are not
  staged.
- `RoomLibraryPersistence` is unchanged. Its IMMEDIATE transaction, stale-revision
  rejection, transition validation, complete in-transaction readback and revision
  publication rules are preserved. A failed or SQL-ignored write still cannot publish
  a revision or partial graph.

## Verification executed

Commands used `scripts/gradle.sh`, project-local homes/caches/temp, offline dependency
resolution from the prepared cache, and a single bounded Test worker
(384 MiB heap, 256 MiB metaspace, SerialGC, 2 CPUs) supplied by an ignored init
script. The committed build files were not changed.

| Command | Exit | Fresh result |
|---|---|---|
| `:shared:compileKotlinJvm` | 0 | JVM main compilation passed |
| `:shared:compileTestKotlinJvm` | 0 | JVM test compilation passed |
| `:shared:jvmTest --tests 'dev.veyra.data.*'` | 0 | 52 tests / 8 classes, 0 failures, 0 errors, 0 skipped |
| `:shared:jvmTest --tests 'dev.veyra.downloads.*'` | 0 | 129 tests / 11 classes, 0 failures, 0 errors, 0 skipped |
| `:shared:jvmTest` (supplemental whole shared JVM suite) | 0 | 406 tests / 37 classes, 0 failures, 0 errors, 0 skipped |

Raw logs, fresh JUnit XML, the init script and a machine-readable summary are retained
under the ignored `.work/verification/room-foundations/` directory. Parsing used only
the fresh `shared/build/test-results/jvmTest` directory produced by each invocation.

Material coverage in the fresh suites:

- every driver open is configured before Room create/migration callbacks; delegate-open
  failure, enable failure, missing/disabled readback, close failure suppression and
  connection-pool metadata are exercised;
- real 1→2 migration preserves every normalized table and safe defaults; a missing
  migration rejects without deleting baseline rows; FK enforcement is visible inside
  the migration transaction; pre-commit integrity failure restores schema 1 and data;
  a deferred FK violation rejects `COMMIT` and an explicit `ROLLBACK` leaves no rows;
- 1,000-row history payload checkpoint writes one row; small history swaps, playlist
  renames, music queue reorders, mixed delete/insert/move histories, composite artist
  order, a >256-id staging chunk, an ignored upsert and an aborted post-staging write
  all preserve or roll back the exact graph and revision;
- killed writer after an acknowledged commit reopens with the committed revision;
  killed writer before commit leaves no staged negative positions or partial rows;
- the existing recovery, file-fault, write-verification, download and full-graph
  equality regressions still pass with foreign keys enforced.

## Limitations

- JVM execution only. This PR includes no Android/iOS/desktop application build,
  emulator/simulator start, physical device run, live source/authentication check,
  workflow dispatch or publication. Those remain Task B.
- No performance benchmark was run or re-thresholded. The preserved prior result
  stopped at 1,000 items with p95 ≈ 104.3 ms > 100 ms and the 10,000-item workload
  remains unexecuted; this PR claims no performance SLA.
- Typed schema-3 acquisition tables, download physical runtime, batch/materialization
  recovery, retained-FAILED validation admission and cleanup/drain issues from the
  private staging assembly are **not** part of this slice. The next dependency is the
  reviewed typed schema-3/download-runtime integration, followed by its recovery
  repairs.
- The owner-directed generated legal preview resources remain excluded from this
  repository state. The source-only packaging notice check was not run/greened here;
  notice regeneration and final distribution belong to Task B.
