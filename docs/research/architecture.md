# Veyra / ڤيرا — architecture and verification contracts

Updated **2026-11-06**. D005/D006/D008/D009 in `docs/DECISIONS.md` are authoritative. They supersede the earlier JSON/SQLDelight, direct RSS-media, and optional-crossfade proposals. This document describes implemented contracts and remaining verification, not five-platform completion.

## Baseline and boundaries

- Kotlin **2.2.21**, Compose **1.9.3**, AGP **8.13.2**, Gradle **8.13**, JDK21/JVM17. AGP is outside KGP's published fully tested range; actual compilation remains necessary.
- Room **2.8.5**, bundled SQLite **2.6.2**, KSP **2.2.21-2.0.4**. Android, JVM desktop and iOS use the same normalized schema and migrations, with platform file/lease factories.
- `shared` owns domain, library, source/download contracts and the authoritative playback coordinator. `composeApp` owns UI, native engines, sessions and OS orchestration; `iosApp` supplies the native host.
- `LibraryStore` and `PlaybackController` have application lifetime. Screens observe immutable projections and dispatch commands; neither navigation nor native players own a second authoritative queue.
- Upstream execution is pinned by **`gradle/kmpyt.commit` and the staged Git submodule**, not a floating branch. A local development pin is not evidence of upstream publication or review.

## Complete normalized Room persistence

`AppSnapshot` is an in-memory domain/UI projection only. No production JSON snapshot, blob database, destructive reset or silent in-memory fallback exists. The internal `LibraryPersistence` test seam is not an alternate production backend.

Schema2 has **30 normalized tables**:

| Area | Tables/relations |
|---|---|
| Music/discovery | artists, albums, album_artists, media, tracks, track_artists, album_tracks |
| Podcasts | podcasts, podcast_feed_aliases, episodes, podcast_episodes |
| Library | playlists, playlist_entries, favorites, saved_episodes, followed_podcasts |
| Playback | queue_contexts, queue_entries, queue_cursors, playback_bindings, media_progress, playback_history |
| Downloads | offline_assets, downloads, download_attempts, download_finalizations |
| Preferences/metadata | podcast_speeds, navigation_state, store_meta, settings |

Foreign keys, unique media download ownership, immutable binding IDs and occurrence/order uniqueness are explicit. Reordering temporarily stages indices negative **inside one immediate transaction**; neither readers nor committed state can observe that intermediate order. Every facade update reads the latest committed revision inside that transaction before applying the caller's transform once. Revision CAS, semantic transition validation and cancellation-safe commit/publication prevent stale screen snapshots from overwriting unrelated fields.

The schema retains original discovery identity, artists/releases/shows, cached artwork, independent favorites/saves/follows, playlist duplicates, two occurrence-ordered queues, shuffle order/cursors, recording-bound progress/history, downloads/attempts/finalization/cleanup intent, preferences and navigation. Missing subtype rows, orphan offline assets, malformed ordered relations and partial initialization are rejected rather than silently projected away. Metadata upserts and favorite/follow/playlist actions preserve existing local artwork unless an explicit update intentionally clears it.

### Schema history and recovery

- `shared/schemas/.../2.json` is the actual Room KSP export.
- `1.json` is an **authored, never-shipped baseline fixture**, not a historical release/export. The explicit 1→2 migration adds safe defaults for crossfade preferences and durable download-cleanup intent. Real `MigrationTestHelper` tests populate all normalized tables and compare the reopened projection.
- Ordinary durability is SQLite WAL with writer `synchronous=FULL`. A file-backed store holds a lifetime OS lease; JVM/Android also reserve the canonical path before opening any competing descriptor, avoiding the POSIX close-on-failed-lock-probe hazard. Release is idempotent.
- Startup checks SQLite header, schema version, `quick_check`, foreign keys and domain relations. A missing initialized database, damaged file or interrupted recovery marker fails visibly. Newer schemas refuse downgrade.
- Backups use SQLite **`VACUUM INTO`**, including committed WAL pages, then validate/checkpoint/sync before replacement. They are not copies of a live main database file.
- Recovery is an explicit `store.recoverFromBackup()` action after `StoreStatus.Failed`. It validates a staged candidate through Room before installation, preserves damaged DB/WAL/SHM in quarantine and leaves interruption markers for ambiguous recovery. Missing/invalid backups fail; the direct Room-constructor test seam has no file-backed recovery support.
- **Limits:** directory fsync after rename is not implemented; universal power-loss-safe rename durability is not claimed. Three injected backup/copy/marker fault tests pass on Linux/JVM; primary errors retain suppressed cleanup failures and partial recovery copies are cleaned. Android/iOS/Windows/macOS recovery execution remains open. A backup can be older than the latest acknowledged WAL commit and explicit recovery must disclose that possibility.

## One central media execution path

Every acquired item follows **complete original title → actual kmpYT search → first ordered result**. A collection/non-playable first result is handled explicitly or rejected; the adapter must never skip to a later playable result. RSS, Commons and directory integrations provide discovery metadata only, never a competing media downloader or playback fallback.

An immutable `PlaybackBinding` records original media ID, complete query, first-result identity/type/title/canonical URL, selected video and ordering proof. Original provider/canonical identity/kind cannot be reassigned under an existing media ID. Later title corrections do not rewrite historical execution receipts. Automatic source renewal reuses the exact receipt, is bounded to two attempts and never reruns search to choose another recording.

Publisher durations are advisory. Native measured duration belongs to the selected recording: a different binding with a different selected video cannot inherit an old recording's duration or resume offset. Unknown duration stays unknown across checkpoint and restart. Completion is a confirmed natural end or an explicit user action, not a percentage of publisher duration.

### Central streaming resources

SABR execution needs a real owned bridge, not an opaque plan passed to a decoder. `ResolvedPlayable.resource` transfers ownership **on native prepare/preload invocation**, including failure/stale calls. The coordinator closes all rejected, cancelled, unhanded and superseded plans; native owners close accepted resources after detaching/draining readers.

`EphemeralPlaybackEndpoint` authorizes only an exact resource-owned `http://127.0.0.1:<port>/<capability>` endpoint with an unguessable route. It is neither a general private-network exception nor an offline/local source. URI/header/capability diagnostics are redacted; resources are never persisted. Offline mode blocks remote bridges as well as external traffic. A real central SABR stream and ownership/failure behavior still need live native verification.

## Two queues, one coordinator, managed native decks

- Music and podcast contexts persist independently. Browsing changes viewed context only; activation atomically checkpoints the outgoing context. Cold restore is paused and makes no source request.
- Stable occurrence IDs preserve duplicates; shuffle retains original order, manual reorder visibly disables shuffle, and explicit replacement remains undoable. Atomic `Play(..., shuffle=...)` preserves one replacement undo.
- Generation and seek tokens reject stale callbacks; guarded absolute/relative seeks cannot target a later queue occurrence. Position samples are drained before critical commands/events. Stop/error barriers do not create additional listening-history occurrences.
- Podcast speed has a live global default and independent show overrides. Settings changes update an active unoverridden podcast without changing music speed or clearing a show preference; paused restore derives the same effective rate. Duration timers use monotonic wall time; end-episode timers bind to an occurrence and stop before advance. Duplicate interruptions cannot invent a new resume intent.
- A failed native event stream fails/stops playback rather than leaving a frozen playing indicator. Failed stop/release is a sticky deletion veto, not an infinite automatic stop loop.

### Required music-only crossfade

Persisted enable/disable and 2/3/4/5-second/custom bounded preferences control two native decks. Incoming audio preloads silently. Equal-power `cos/sin` gains use fixed music headroom **0.6683439 (-3.5dB)**, giving worst correlated overlap approximately0.9454. Podcasts remain unity gain and never automatically overlap. Short known recordings cap overlap to half either recording; unknown duration disables overlap.

Native `CrossfadeCompleted` is a **handover proposal**, not permission to change logical owner. The old owner remains valid until the coordinator commits queue/progress/history and calls `confirmCrossfade`. ACK promotes the already-playing incoming deck without reprepare/seek. Pause/seek/stop cancel both decks even between proposal and ACK; ordinary cancellation ramps for at least40ms, while explicit stop/interruption may silence immediately.

Fakes validate math, durable handover and races; they do **not** establish accurate native gain timing, audible output, codec parity or physical-device correctness. Mandatory physical audible verification on Android/iOS/Windows/macOS/Linux remains unmet.

## Durable downloads and physical deletion

kmpYT performs transfers/resume/source renewal. Veyra's Room coordinator owns queue policy, bounded parallelism, leases/attempts, retry/backoff, progress, OS allowance, validation and finalization receipts. Native workers must report paused/NeedsForeground when execution cannot legally continue, especially on iOS. An implementation or green fake transfer is not proof of complete platform background execution.

Ready requires validated bytes/container/decoder/hash, not just publisher size or EOF. Filesystem publish and Room commit are bridged by durable finalization receipts and reconciliation. Cleanup first persists `cleanupPending`, which blocks new source acquisition, then waits for `PlaybackController.releaseForAssetRemoval(mediaId)` **before physical deletion**. Ordinary `dispatch(Stop)` acknowledges queued intent only.

The release ACK waits for retired matching resolver jobs (including validators), queued native stop/cancel/handover completion, and disposal of unhanded resources—even after current/staged UI pointers changed. Newer generations or native/resource cleanup failure veto deletion so the journal can be retried. Android tracks actual DataSource close; iOS AVPlayer exposes no public file-descriptor-close ACK, so detach/drain limitations and native tests remain explicit gates.

## Evidence status and next gates

- Inspected published Room/SQLite/KSP metadata and source jars are retained under `.work/architecture/room-research`.
- `.work/room-codegen.log` records JVM Room generation. At local upstream pin `c0a08090fe7dbd76e20216559b8184ad2cccde63`, `.work/shared-tests-second.log` compiles current shared JVM production and `.work/shared-tests-third.log` compiles all shared JVM tests. These are not successful application/native builds.
- **65 architecture-owned test methods are authored**:39 common controller/crossfade and26 JVM Room/migration/recovery/process/fault. The third full run executed57 actual owned tests: **54 passed,3 failed**; a separate JUnit initialization error prevented the8 `RoomLibraryStoreTest` methods from running. All18 other Room tests pass, including real killed-writer commit/rollback, cross-process lock isolation, migrations and injected filesystem faults. Both live podcast-speed regressions pass. The failures expose a nullable-duration test-fake mistake and a real late-resolver-after-failed-stop state race; corrections require a rerun.
- Whole shared run: **247 test results,241 passed,6 failures,0 skipped** (including the class initialization error and two adapter-fixture failures). Preserved XML/log: `.work/architecture/evidence/shared-third/`. Heavy builds remain serialized through the lead; use `scripts/gradle.sh`, project-scoped homes/caches/temp and authored fixtures only.
- Remaining gates: final-checkpoint shared tests, Android/iOS database compilation/tests, fault-injected recovery/finalization, native resource closure, real complete-title/first-result play→seek→download→restart→network-denied offline proof, background/interruption/routes, and five-platform physical audible crossfade. Prior authorization refusals and absent hardware do not erase these requirements.

## Research sources

Retrieved/inspected 2026-11-06; documentation is design evidence, not execution evidence.

- [Room KMP setup, drivers, coroutine transactions](https://developer.android.com/kotlin/multiplatform/room); [Room release notes](https://developer.android.com/jetpack/androidx/releases/room); published Room2.8.5 runtime/testing/compiler sources and schema codegen.
- [SQLite AndroidX releases](https://developer.android.com/jetpack/androidx/releases/sqlite); [SQLite VACUUM INTO](https://www.sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause); [WAL durability](https://www.sqlite.org/wal.html); [SQLite transaction isolation](https://www.sqlite.org/isolation.html).
- [KSP multiplatform processing](https://kotlinlang.org/docs/ksp-multiplatform.html); published KSP2.2.21-2.0.4 metadata.
- [Compose1.9.3 tagged release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.9.3); [KMP compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html).
- [Native/desktop contracts](mobile-audio.md), [desktop experiments](desktop-runtime.md), [central media audit](kmpyt-audit.md), [source feasibility](integration-feasibility.md), and the pinned upstream source; helper/null-sink/direct-RSS probes are not compliant central application playback evidence.
