# Veyra / ڤيرا — architecture research and implementation contracts

Research date: **2026-11-06**. Status: **frozen implementation proposal, incorporating lead and peer review**; not a claim that the application or its five target builds have passed. No application code or build was run for this research. External documents and Maven metadata were fetched read-only, in memory. All authored output is this file. The final lead decision supersedes the initial SQLDelight recommendation: V1 uses a durable, transactional atomic-JSON snapshot store with explicit limits and recovery tests.

## 1. Decisions in brief

1. **Kotlin Multiplatform, shared Compose UI, native audio and platform services.** Android and iOS use their native audio/session APIs. Windows, macOS and Linux use one JVM Compose implementation with per-OS native audio distribution. Do not confuse JVM desktop targets with Kotlin/Native `linuxX64`/`mingwX64` UI support.
2. **Local database is the source of truth for owned data.** No required account, backend, cloud registration or online startup. Favorites, playlists, follows, saved episodes, progress, queue and settings work without a network. A provider cache is not an offline media download.
3. **Exactly one application-lifetime `QueueCoordinator` owns two persistent queue contexts: MUSIC and PODCAST.** It owns each queue's order/current occurrence/progress/settings, one active transport context, play intent and timer ordering. The UI's viewed context is distinct from the active context. Native players transport one selected item; they do not maintain an independent authoritative queue.
4. **Real source adapters, not invented catalog semantics.** kmpYT supplies YouTube video/channel/playlist browsing and resolution. A separate RSS adapter supplies podcast shows and episodes. A YouTube uploader is not automatically an artist, and a playlist is not automatically an album.
5. **V1 durable atomic JSON; one serialized read-modify-durable-write owner.** A versioned immutable snapshot holds app-owned state; same-directory staged replacement, last-known-good recovery and revision guards prevent torn files and lost updates. UI reads observable immutable snapshots, not disk. Progress writes coalesce; critical context switches commit outgoing and incoming state together. SQLDelight is the preferred migration if realistic library-size/write-latency tests exceed this simpler store's limits.
6. **Conservative, exactly pinned dependencies.** Preserve upstream's Kotlin 2.2.21 / AGP 8.13.2 / Gradle 8.13 baseline, with Compose 1.9.3 and its released companion libraries. Disclose the AGP version's position outside Kotlin's published tested matrix; require actual build evidence rather than describing it as officially fully supported.
7. **Lifecycle-aware UI, durable domain state.** Compose Navigation 2.9.1 and ViewModel 2.9.6; serializable routes carry IDs only. `rememberSaveable` is not a durable database and does not promise restoration after a desktop or iOS process termination.
8. **Bounded, cancellable work.** A network callback, JS runtime, parser, decoder or disk operation must never freeze composition or navigation. Download jobs have a separate durable owner and recoverable state machine.
9. **Five-platform capability and verification matrix.** “Compiles”, “launches”, “plays a fixture”, “plays real remote media”, “background works”, and “distributable/signable” are separate evidence levels. Unsupported codecs or provider access failures are truthful states, never fake success.

## 2. Evidence-backed dependency baseline

The lead chose to retain the existing upstream baseline rather than perform a speculative upgrade. Upstream checkout is `.work/kmpYT`, commit `533562f781304338f72b27d32cfe5fb7a83dc3a8` (version 0.1.0). Its `build.gradle.kts`, wrapper and `kmpyt/build.gradle.kts` were inspected directly.

| Component | Pin / decision | Evidence and qualification |
|---|---|---|
| Kotlin Multiplatform + serialization + Compose compiler plugins | **2.2.21**, all three aligned | Upstream pins KMP/serialization 2.2.21. Compose compiler plugin version must equal Kotlin [S1]. |
| Compose Multiplatform plugin/runtime/UI/foundation | **1.9.3** | Stable release [S2]; Gradle plugin POM retrieved with HTTP 200. Do not select current latest 1.12.2 just because current docs show it. |
| Compose Material 3 | **1.9.0** | Exact companion version in Compose 1.9.3 release, based on Jetpack Material 3 1.4.0 [S2]. |
| Material 3 Adaptive | **1.2.0** | Exact companion version [S2]; Maven POM HTTP 200. Manual adaptive shell is acceptable without adding every optional artifact. |
| Navigation Compose | **org.jetbrains.androidx.navigation:navigation-compose:2.9.1** | Exact stable companion [S2]; POM HTTP 200. Do not accidentally copy the newer 2.10.0-rc01 shown in today's navigation tutorial. |
| Lifecycle/ViewModel Compose | **org.jetbrains.androidx.lifecycle:…:2.9.6** | Exact companion [S2]; viewmodel-compose POM HTTP 200. |
| SavedState | **1.3.6**, if explicitly referenced | Exact companion [S2]. Prefer transitive version unless a direct API is used. |
| Android Gradle Plugin | **8.13.2** | Upstream pin; Google Maven POM HTTP 200. **Outside KGP 2.2.21's published fully tested AGP range, which ends at 8.11.1** [S3]. Existing upstream CI evidence is helpful, not proof for Veyra. |
| Gradle wrapper | **8.13** | Upstream wrapper; SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`. Within KGP 2.2.21's listed Gradle range [S3]. |
| JDK | **21**, JVM bytecode **17** | Existing host JDK is 21.0.12 (verification-agent observation); pin the selected vendor/build in CI evidence. Upstream bytecode target 17; desktop packaging requires JDK ≥17 [S1]. No automatic machine-wide JDK installation. |
| Android SDK | **compileSdk 36**, **targetSdk 36**, **minSdk ≥23** | Upstream compiles 36 and requires 23. Final app minimum is the maximum of its native dependency requirements; do not promise API 21 because Compose alone permits it. |
| Xcode | **26.0.x** compatibility line; explicit **26.0.1** runner selection proposed | Kotlin table lists Xcode 26.0 for 2.2.21 [S3]. Verification agent found `/Applications/Xcode_26.0.1.app` on the selected macOS 15 runner image. Actual availability must be asserted in the job; never silently pick “latest”. |
| Coroutines | **1.10.2** | Upstream direct API dependency. Use matching test, Android and Swing dispatcher artifacts when needed. |
| kotlinx.serialization JSON | **1.9.0** | Upstream direct API dependency; same serialization compiler plugin as Kotlin. |
| Ktor | **3.2.3** | Upstream direct API dependency and platform engines. Keep one resolved version; core POM HTTP 200. Newer 3.6.1 was observed but is not a reason for untested migration. |
| Persistence V1 | **kotlinx.serialization JSON 1.9.0 + platform `AtomicSnapshotStorage`** | No extra DB/compiler plugin. Use existing platform file APIs (or an independently pinned Okio adapter); do not invent an unverified Okio version. Requires the recovery/concurrency tests in §5/§10. |
| SQLDelight, evaluated migration alternative only | **2.1.0** | Tagged release/source [S4]; plugin/runtime/native/JDBC metadata HTTP 200. Not a V1 dependency. Native linking/generated-source compatibility would need a separate migration gate. |
| XML parser, RSS adapter only | **io.github.pdvrieze.xmlutil:core:0.91.2** candidate | Published POM and module metadata HTTP 200; its source pins Kotlin 2.2.0 [S5]. Use the bounded common Kotlin reader behind a small port, not regex or a JVM-only XML API. Security behavior must pass the tests below before adoption. |
| kmpYT | **commit 533562f…**, not a floating branch/JitPack snapshot | Source inspection [L1]. Resolve from project-owned checkout/publication/composite wiring, with the exact checkout revision in build evidence. Do not publish to global `~/.m2`. |
| Android/iOS/desktop audio artifacts | Follow the corresponding reviewed audio report | Media3/native Apple/libmpv version and packaging choices must be independently pinned. Do not add a current MediaMP binary that raises the Kotlin metadata requirement above the chosen compiler. |

### Compatibility gate, not a compatibility fiction

- This is an **artifact-available, conservative candidate**, not an experimentally proven full matrix. HTTP 200 for a POM establishes availability only, not transitive resolution or runtime behavior.
- First implementation build must compile common/JVM/Android sources and execute snapshot create/write/read/recovery tests. macOS CI must compile/link the complete iOS app and execute a simulator persistence test before storage is called cross-platform verified.
- If the existing AGP 8.13.2 combination actually fails, first evaluate **AGP 8.11.1** with Kotlin 2.2.21 and Gradle 8.13 (inside official KGP bounds), or a coordinated upgrade to Kotlin 2.3.10 / AGP 8.13.2 (also inside the later official bounds). Do not downgrade/uprev only one module without a resolved dependency and task graph.
- Use AGP 8.x's existing Android/KMP arrangement initially to match upstream: `com.android.library` in `:shared`, `com.android.application` in `:composeApp`, both using `androidTarget`. The official new `com.android.kotlin.multiplatform.library` is a later migration destination; it changes source sets, disables tests by default, and requires extracting the Android application host [S6]. If migrating to SQLDelight, later changelogs mention generated-source fixes around AGP 8.9–8.11; generated-source discovery must be tested.
- Do not use `+`, `latest.release`, changing versions or snapshots. Root owns the version catalog, dependency locking/verification metadata and build scripts. Native distributions require build hosts for their own OS; a Linux success is not a signed macOS/Windows package.

### Recorded metadata fingerprints

Fetched 2026-11-06. These are **POM** hashes for research provenance, not distribution checksums or a replacement for Gradle dependency verification:

| POM | SHA-256 |
|---|---|
| Compose Gradle plugin 1.9.3 | `8e4890cceeee0a34e9cfb1ed928e44fc3a301358989e3b26e1ea0f80c91e26ea` |
| navigation-compose 2.9.1 | `77b5c6f36f61262423d0b6f000c1f157ecaf23bb9829164d2de6da80e7a43290` |
| lifecycle-viewmodel-compose 2.9.6 | `ca3135b7e1f15c51af6dc8c9dcacf978cac023df187731f1b7b6634eb6ffbead` |
| AGP 8.13.2 | `fc7edba93c5b91ce7267fa7bfdc1fa49422efc633ac279a363e02eb0fd29bcad` |
| SQLDelight gradle-plugin 2.1.0 | `777c53ec21a6c7d72c4aa9ed6fe6e09707f6199895f547a7be2ddb01a263566d` |
| XMLUtil core 0.91.2 | `001a70e4731bdc115ccdc4a757fbfa0d97d161a25056cae6f70f36bafaf42eee` |

## 3. Code boundaries and parallel implementation ownership

Frozen root structure: **`:shared` KMP** (models/data/controller/source ports) and **`:composeApp` KMP** (shared UI, Android application host, desktop JVM entry, platform engines and exported iOS framework), plus a real **`iosApp` Xcode app/scheme**. This legacy Android-application-in-KMP arrangement is intentional with pinned AGP 8.x; do not silently upgrade to AGP 9+. Use `androidTarget`, one `jvm` target, `iosArm64`, `iosSimulatorArm64`, and optionally `iosX64` while Intel simulator support is verified. Three desktop OSes share the JVM target. An iOS framework alone is not the iOS application.

Use the following enforceable package boundaries. Domain/data/playback/source contracts live in `:shared`; UI/design/features/native engines live in `:composeApp`. They can be extracted into modules later without changing ports. No feature imports another feature's implementation; common source imports no Android/JVM/Apple platform classes.

| Boundary | Owns | Must not own |
|---|---|---|
| `domain` | IDs, immutable entities, source capabilities, typed failures, repository/engine/storage contracts | Compose, SQL, Ktor, kmpYT DTOs, platform audio objects |
| `data.local` | Snapshot schema/migrations, serialized transactional mutations, recovery/atomic storage port, observable repositories | Network fetching, playback commands, UI state |
| `playback` | `QueueCoordinator`, reducer, command/event serialization, sleep policy, progress ordering | Native decoding, a second playlist, disk/network loops |
| `source.youtube` | kmpYT lifecycle, DTO mapping, source browse/search/resolve/download-plan adaptation | Podcast assumptions, accounts, UI widgets, native playback |
| `source.podcast` | Feed identity, genuine RSS parser, conditional refresh, optional directory adapter | Fake catalog results, interpreting channel videos as episodes |
| `downloads` | Durable job state machine, quota/retry/pause/cancel policy, media-file reference lifecycle | A second playback queue, independent untracked native download jobs |
| `design` + `navigation` | Shared tokens/resources, adaptive shell, accessible controls, typed route graph | Business-state persistence, direct clients/players |
| `feature.music` | Home/search/collections/creator/library screens and their ViewModels | Source-specific transport, inferring missing artist/album metadata |
| `feature.podcasts` | Discover/show/episode/follows/saved/progress screens and ViewModels | A second podcast player or queue |
| `platform` in platform source sets | Audio transport/session, app directories, file/URL access, scheduling, secure OS integrations | Independent business logic or catalog models |

**Nine-agent split for the lead:** (1) architecture/model/store/controller; (2) design/adaptive shell; (3) desktop transport; (4) Android/iOS transport; (5) kmpYT source; (6) RSS/directory adapter; (7) music feature; (8) podcast feature; (9) tests/CI. Root owns downloads, Gradle/Xcode manifests, dependency injection and composition root. Queue core has one owner, never separate implementations in both audio agents. Contracts land first. Frozen navigation: **Listen / Search / Podcasts / Library**.

Canonical proposed contract paths (root resolves any naming change once): `shared/src/commonMain/kotlin/dev/veyra/model/Models.kt`, `data/LibraryStore.kt`, `data/SnapshotStorage.kt`, `playback/PlaybackController.kt`, `playback/AudioEngine.kt`, `source/MediaSource.kt`. UI packages begin at `composeApp/src/commonMain/kotlin/dev/veyra/ui/`. Platform actual storage/engine files are owned by their assigned platform agent; shared contracts are not independently redefined.

Use constructor injection and a small app-lifetime `AppContainer`; no DI code-generation dependency is needed. Each repository/client/engine is constructed once per application process, closed by its real owner, and injected as interfaces. Android service and activity access the same application container; do not construct a player/container per screen or per Activity recreation.

## 4. Shared contract specification

This is a contract description, not application code. Implement these names or map them once in a reviewed contracts file before assigning agents.

### Identity and data

- `MediaId(provider, opaqueId)` is stable, namespaced, serializable and independent of a resolved URL. Examples: YouTube video ID; RSS show key plus stable GUID. Never use a title or a signed CDN URL as the primary identity.
- `QueueEntryId` identifies an **occurrence**, not a media item: the same episode or song can appear twice. A current entry remains correct after reordering.
- `MediaItem` carries media kind, title, creators with explicit roles, duration if known, artwork reference, source locator, and provenance. Album/artist fields are nullable and only set when actually supplied or user-authored. A YouTube channel is `Uploader`; an RSS channel is `Publisher`/show.
- `MediaLocator` is a stable re-resolution instruction, not an HTTP credential or expired playback plan. `ResolvedMedia` is short-lived, contains transport/container/codec/headers/expiry/seekability metadata, and is not logged or used as a durable ID.
- `Availability` distinguishes verified local bytes, metadata-only cached entry, online-resolvable, source blocked, and incompatible codec. “Offline” is not a single overloaded boolean.
- `DomainFailure` distinguishes `NetworkUnavailable`, `OfflinePolicy`, `ProviderAccessRequired`, `RateLimited(retryAfter)`, `NotFoundOrRemoved`, `UnsupportedSource`, `UnsupportedTransport`, `UnsupportedCodec`, `PermissionDenied`, `StorageFull`, `CorruptLocalMedia`, `ParseFailure`, `Unexpected`. `CancellationException` propagates as cancellation, never as a user error.

### Repositories and service ports

| Port | Required behavior |
|---|---|
| `LibraryStore` / `LibraryRepository` | Read-only `StateFlow<AppSnapshot>`; one serialized `suspend update(transform: (AppSnapshot) -> AppSnapshot): StoreResult`, plus `flush`; observable filtered/paged views, duplicate-safe identities. The transform uses the latest committed snapshot, not a caller's stale captured copy. Deleting an entry does not implicitly delete history/download. |
| `CatalogRepository` | Cached browse/search page first; explicit cancellable refresh; continuation belongs to provider + request/filter/language generation; do not mix old query results into new UI. |
| `PlaybackStore` | Read versioned durable snapshot; use `LibraryStore` transactions to atomically commit both queue contexts/current occurrences/active context/outgoing progress. Condition checkpoints by session/generation/sequence. No independent whole-file writer. |
| `SourceAdapter` | Capability declaration; suspend browse/search/details/resolve; close owned runtime. Unsupported operations return typed capability failures, not empty fake successes. |
| `SourceResolver` | Select local verified media first when allowed; otherwise use adapter and native `EngineCapabilities`; bounded re-resolution when expiry is recoverable; forced-offline policy prevents any network call. |
| `AudioEngine` | `prepare(resolved, startPositionMs, generation)`, play, pause, seek(position, seekSequence, generation), setRate, stop, close; immutable state/events tagged with generation. All operations main-safe at their public boundary. |
| `QueueController` | `dispatch(command)` and read-only `StateFlow<PlaybackState>`; acknowledgement describes accepted/committed/error, not assumed successful audio output. |
| `DownloadRepository` / `DownloadCoordinator` | Durable observable jobs, enqueue/pause/resume/cancel/remove, deduplication, retry leases; one owner of each transfer and staging file. |
| `MediaFileStore` | Opaque `OfflineAsset(handle, kind, representationIdentity, validatorGeneration, measuredBytes, hash?)`; staged finalization/reopen/native validation/reference-aware deletion. V1 implements progressive `FILE` assets. Future Media3 cache or iOS HLS packages are separate kinds, never mislabeled as a local file. |
| `AtomicSnapshotStorage` | Suspend `read(): ByteArray?`, `writeAtomically(bytes: ByteArray)`, `close`; platform adapter implements staging/flush/replacement/recovery contract, injected into shared store. Only genuine absence returns null; corrupt/unreadable data is not silently an empty library. |
| `PlatformScheduler` | Ask native platform to continue/wake work and report actual scheduling constraints; no promise of unrestricted iOS background execution. |
| `AppDispatchers`, `Clock` | Inject UI, I/O and CPU contexts; monotonic elapsed clock and wall clock are different. Deterministic test implementations. |

Minimum `AppSnapshot` fields: schemaVersion, revision, settings, favorites, playlists, followedShows, savedEpisodes, progress, `queues: Map<QueueContext, QueueSnapshot>`, activeContext. `QueueSnapshot` contains occurrence entries, currentEntryId, positionMs, shuffle and repeat; speed can be per context/show. `QueueContext` is MUSIC/PODCAST. `PlaybackController.dispatch` supports explicit Play(context, items, startEntry), view-context selection without activation, PlayPause/Pause/Next/Previous/Seek/SetRate/Enqueue/Remove/Move/SetRepeat/SetShuffle/Sleep. Viewing another context never stops current audio. Activating it explicitly atomically saves outgoing progress and changes the one active context before preparing the incoming item.

All mutable flows stay private; consumers receive read-only flows. Long operations return progress through events/flows rather than blocking UI callers. Errors have localized presentation separate from internal typed causes, with sensitive URLs/tokens omitted.

## 5. Persistence, transaction and recovery design

### Schema ownership

Use one versioned SQLite database for app-owned relational state:

- `media_item`, `creator`/relationships, source locators and metadata provenance;
- `playlist`, `playlist_entry` with stable entry ID and explicit order;
- favorites, followed podcast shows, saved episodes;
- `podcast_show`, `feed_alias`, `podcast_episode`, conditional HTTP validators and refresh status;
- `playback_snapshot` + `queue_entry`, shuffle order/seed, repeat mode and snapshot revision;
- `playback_session`, `media_progress` and explicit completion/bookmark state;
- `download_job`, verified local-file records and staging/cleanup journal;
- settings and minimal versioned navigation restoration record.

Do not put full audio/image blobs or live signed URLs into SQLite. Use owned filesystem storage with DB references. Keep cache tables separate from owned library/progress so clearing cache cannot clear a person's collection. Foreign keys, unique constraints and indexes enforce invariants; do not rely only on UI prevention of duplicates.

### Transaction rules

- One logical DB writer (`Dispatchers.IO.limitedParallelism(1)` or equivalent injected dispatcher) runs short synchronous SQLDelight transaction blocks. Do not suspend or hop dispatchers inside a transaction. Read queries/Flow mapping also run on an I/O dispatcher [S7]. A serialized dispatcher is not a license to perform network I/O in transactions.
- Enable and verify foreign-key enforcement for each connection. Choose/test WAL and busy timeout per driver, rather than assuming every platform driver defaults match. Use SQL syntax supported by the oldest selected SQLite runtime, or deliberately bundle and test a common runtime.
- Atomically change queue entries + current occurrence + revision. Atomically reorder a playlist. Atomically upsert a complete validated feed page + validators; a failed parse must leave the last valid feed available.
- SQLDelight rolls back on exceptions [S7]. Migration files and generated schema are checked in; test empty-create and every supported historical migration. Never silently delete a production DB on migration failure. Surface recovery/export options and preserve the original.
- Snapshot objects are bounded. A DB failure must return a visible mutation failure, not leave the UI permanently showing a “saved” state that never committed.
- Desktop is single-instance per data directory, or must implement an explicit cross-process ownership/lease protocol. File locking protects the one active coordinator; multiple app windows share it. Android media service remains in the default application process unless a deliberate IPC design is added.

### Progress and history rules

- Checkpoint approximately every **5 seconds while actually playing**, plus immediately on confirmed seek, pause, item transition/end, and background lifecycle opportunity. This bounds expected crash-loss; it is not a promise of a final callback after OS kill.
- Key checkpoints by session ID, media ID, queue occurrence, generation and monotonic command/sample sequence. Old engine ticks or seek completions cannot overwrite a newer seek or a new item.
- Position is **not monotonic**: seeking backwards must persist the smaller position. Never use `max(oldPosition,newPosition)` as race prevention. Use generation/sequence guards instead.
- Store a history session/meaningful transition, not a row per tick. Keep completion state independent of last position and local download state. Manual “mark played” does not delete bytes/history; “reset progress” is separate.
- Duration is nullable; unknown/live/zero-duration media has no fabricated percentage. Clamp valid positions against a known nonzero duration, and re-evaluate if the media's real duration changes.
- On restart, restore queue/current item/progress **paused**. Do not autoplay merely because a prior snapshot was playing. Explicit play revalidates local bytes or resolves a fresh source. Podcast resume uses that episode's persisted progress; music resume policy can differ without introducing a second queue.

### File/DB atomicity is a recovery protocol

SQLite cannot atomically commit a file rename and a DB transaction. Downloads therefore use a journal: create job/staging intent → stream owned `.part` file → validate measured bytes/format → flush/close → same-filesystem final rename → mark DB ready. Recovery reconciles an interrupted finalization by job token and verified file metadata. An incomplete file is never exposed as downloaded.

RSS `<enclosure length>` is advisory: live integration research found real feeds whose advertised length differed from HTTP `Content-Range`. Use actual response length/range and validators for transfer integrity, not RSS size. A computed hash detects local corruption but is not authenticity proof without a trusted expected hash.

Deletion invalidates references safely: pause/release or retain the active file while it is in use, remove the download reference transactionally, then clean owned bytes through the journal. Cancel/error may remove only that job's staging files, never arbitrary media paths. SAF/user-folder export is a separate operation, not assumed to support atomic rename.

## 6. Queue, player and background work ownership

### State machine

`Idle → Resolving → Preparing → Ready/Playing/Paused/Buffering → Ended/Error` is driven by tagged commands/events. `desiredPlaying` (user intent) is separate from effective native playback. `PlaybackState` includes queue revision/current occurrence, media metadata, position/duration/buffered/seekable, intended/effective rate, intent, actual state and typed failure.

Commands from UI, Android media notification/controller, iOS remote command center and desktop shortcuts all enter the **same command loop**. It serializes domain mutations and short DB commits. It launches network/decoder preparation as cancellable effects and processes their tagged completions; it never awaits an unbounded network request while preventing Pause/Skip/Cancel.

- Each item transition increments generation and cancels obsolete resolver/preparation effects. A late completion for item A cannot replace item B.
- Each seek has a seek sequence. An old native seek callback, including a cancelled iOS callback, cannot roll back the current displayed or durable position.
- Shuffle uses a persisted order (or seed plus deterministic algorithm/version) of occurrence IDs; avoid reconstructing a different order on every recomposition. Repeat-one, repeat-all and end-of-queue behavior are explicit and unit-tested.
- Native `Ended` is handled once for a matching generation. Sleep-at-end prevents matching auto-advance; timer expiry invalidates any pending resume token.
- Transient interruptions may resume only when an unchanged generation/user-intent token permits it. User pause, headphone/Bluetooth removal, audio-reset, sleep expiry or an intervening skip revokes the token. Reconnecting headphones never starts playback automatically.
- Duration sleep timers use monotonic **elapsed time**, not playback position (2× playback must not halve a 30-minute timer). Restore after process death stays paused, regardless of timer state.

### Native boundaries

- **Android:** one ExoPlayer/native transport in application/service lifetime, MediaSessionService and a tested Player/session facade routing queue commands to the common owner. Activity/ViewModel is not the service or audio owner. Audio focus, becoming-noisy and foreground-service restrictions are platform responsibilities.
- **iOS:** one AVPlayer in app lifetime, AVAudioSession playback configuration, Now Playing and remote commands routed to the coordinator. AV interruptions/media-services reset follow Apple's actual rules. A background capability declaration alone is not proof of background playback.
- **Desktop:** JVM UI and native audio engine outlive individual feature screens. libmpv/JNA is a candidate capable transport; its binary/license/CPU packaging and system media integration belong to desktop research. Do not require the user to install an undocumented external CLI for a claimed self-contained release.

### Work policy

- Feed/search HTTP uses bounded timeouts, limited per-host concurrency, cancellation and backoff respecting `Retry-After`. CPU parsing/sorting happens on a CPU dispatcher. Blocking file/JNI/SQL calls happen off composition/main where the API permits; Apple/UI-bound runtime calls are dispatched to main internally but never perform blocking loops there.
- Search uses debounce + `distinctUntilChanged` + latest-request cancellation, and a generation guard because not every underlying provider cancels promptly. Old error/results cannot replace a newer query.
- Download coordinator has a persistent state machine: queued/resolving/running/paused/waiting-network/needs-foreground/completing/complete/failed/cancelled. A job lease and stable native task mapping prevent two owners after OS callbacks/relaunch.
- Android scheduling/foreground work and iOS background URLSession are execution adapters, not competing app-level download queues. kmpYT transfer code may handle a job's bytes only when the same job is not simultaneously handed to another transport.
- iOS cannot generally run a browser/JS source resolver after suspension. A foreground-resolved progressive URL may be handed to a background session; expiry requiring re-resolution can legitimately become `NeedsForeground`. User force-quit cancels/suppresses work according to iOS rules; UI must not promise otherwise.
- “Offline mode” actively blocks all network adapters (including artwork/directory/feed refresh), not just disables a button. Cached UI plus downloaded playback remains functional. No mandatory telemetry/auth token exchange on launch.

## 7. Source adapters: truthful capability and data lineage

### kmpYT

Upstream exposes suspending `search`, `home`, `open`, `more`, `channel`, `playlist`; `inspect` produces an extraction report; `select(audioOnly=true)` chooses formats; `playbackPlan` produces `SourcePlaybackRequest`; planning/downloading/refreshing have separate methods [L1]. **It is not a media player.**

The audit found that playback planning permits progressive/static SABR while rejecting HLS/DASH playback plans even where downloads exist. `audioOnly=true` prefers language/default/DRC/bitrate properties, not all native decoders' codec constraints. Veyra must negotiate `EngineCapabilities` and choose compatible container + audio codec, e.g. AAC/MP4 for the AVPlayer baseline; a direct signed WebM/Opus URL is not automatically playable everywhere.

- Prefer a compatible progressive source. Static SABR needs a real streaming read/seek adapter or a completed permitted download; do not hand an opaque SABR plan to AVPlayer/Media3 and report it playable.
- Re-resolve recoverable expired URLs with a bounded attempt count; avoid retrying policy/bot/access failures as an infinite spinner. Distinguish `ProviderAccessRequired` from offline and codec failure.
- kmpYT's desktop runtime currently requires Node 22 and Chromium; combined media may require FFmpeg. Native source execution and all subprocesses need bounded ownership/cleanup. A package without those requirements resolved cannot claim full online source parity.
- No invented albums/artists, synthetic “live” results or fixture substitution on remote error. Author-authored fixtures are permitted in tests/demos only when visibly labeled and not returned by production source adapters.
- Upstream lacks a root license at the inspected revision; lead is seeking explicit owner permission/license. This is a distribution gate, not an excuse to fabricate permission or stop safe architectural work.

### Genuine RSS podcasts

- RSS is a separate provider with `PodcastShow`/`PodcastEpisode` models, enclosure media and publisher attribution. Follow/save/queue/download/completion are independent states. Direct feed-URL subscribe works without an account or directory API.
- An optional Apple Search directory can assist discovery. It is a search/metadata source, **not the playback source**; follow its API limits/cache/artwork terms. Public no-key access is not a guarantee of uptime or unrestricted use. Direct RSS still works when directory access fails [S8].
- Canonical show identity is the feed URL with a redirect/alias history. Preserve path/query semantics; do not strip feed parameters indiscriminately. Apple catalog IDs can duplicate the same feed (observed by integration research), so do not duplicate local follows by catalog ID.
- Episode GUID is opaque and is not necessarily a URL. Identity is show ID + GUID. Signed enclosure URLs, dynamic ad media and artwork may change without creating a new episode. Where GUID is missing, use a versioned deterministic fallback over stable item attributes and surface ambiguous collisions rather than silently overwriting an episode.
- Refresh uses ETag/Last-Modified with correct conditional requests, redirects with limits, capped response/decompressed size and bounded XML depth/item/text length. A 304 does not erase stored episodes. Removed episodes may remain in the user's library/history/downloads.
- Candidate parser: XMLUtil 0.91.2 common `KtXmlReader` behind `FeedParser`. The tagged source has no platform network resolver in that reader; defaults include `expandEntities=false`, and it emits document declarations [S5]. Still explicitly reject DOCTYPE/custom entities, never delegate to an unconfigured platform XML parser, reject excessive nesting/output, and run XXE/billion-laughs tests on all targets. Namespaces, CDATA, UTF-8 Arabic, quoted attributes, relative URLs and multiple enclosures require proper parsing, not regex.
- Treat URLs/descriptions/artwork as untrusted. Allow supported network schemes only; reject embedded credentials and local/private/link-local/loopback/metadata endpoints unless an explicit future local-network feature opts in. Revalidate redirects and defend against DNS rebinding in the networking layer; a string-prefix check is insufficient. Render sanitized plain/rich text, not executable HTML/JavaScript. OPML is bounded XML too.
- Choose an audio enclosure by supported MIME/codec/content characteristics. Do not assume `<enclosure length>` is exact or a redirect destination permanent. Keep the canonical feed + GUID, not a transient CDN URL, as durable identity. Real feed requirements include stable GUID/enclosure data and HTTP range support [S9].

## 8. Navigation, adaptive UI and en/ar restoration

- `AppRoute` is a serializable sealed hierarchy in the app/navigation boundary; route parameters contain IDs and small filters, not whole media objects, bitmaps, clients or players. Read fresh data from repositories at destinations [S10].
- One root navigation owner with independent top-level tab back stacks; selecting a tab does not erase its scroll/search state. The now-playing surface is attached to the shell and observes the app-level coordinator, never a route-owned player.
- Preserve state by stable item keys, ViewModel state and saveable UI state during normal recreation. Durable restoration stores a versioned minimal root route/tab/selected IDs plus bounded list anchor+offset; on cold start validate referenced IDs, fall back safely, and restore playback paused. Do not serialize stale ViewModels or native handles. Do not claim that Navigation/`rememberSaveable` alone survives process death on every OS.
- Adaptive layout is based on current window width and posture/insets, not OS name. Starting breakpoints: compact <600dp, medium 600–839dp, expanded ≥840dp. Compact uses bottom navigation and full-screen detail/player; wider layouts use rail/sidebar plus list/detail or side player. Resizing preserves the selected item and current audio, with no new player allocation. Final structure/tokens follow design/UX research.
- Ship complete English and Arabic resources in shared `composeResources`; all visible strings, errors, actions, accessibility descriptions and plurals use resources. Arabic needs zero/one/two/few/many/other distinctions [S11]. CI compares resource keys and format placeholders, not just file existence.
- Language selection is persistent: system/default, English, Arabic. Update both resource environment and root layout direction together. Use start/end padding and alignment, logical navigation order and auto-mirrored directional navigation icons. Do not mirror artwork, logos or time-transport symbols indiscriminately. Verify mixed Arabic/English titles, digits and URLs with bidi isolation while preserving stored identity text.
- Bundle a properly licensed font that contains the Arabic letters including **ڤ**, rather than depending on network-downloaded fallback. Font scaling, TalkBack/VoiceOver, keyboard access, visible focus, reduced motion, high contrast and sufficient target size are acceptance criteria, not optional platform polish.
- Controls display actual capability/state: an unseekable stream has disabled scrubber; a source without a supported codec has an actionable explanation; unknown progress is indeterminate. Retry, offline, empty, permission-denied and degraded-source states are first-class UI.

## 9. Alternatives and why they are not the default

| Alternative | Benefit | Cost / decision |
|---|---|---|
| Native UI per platform | Maximum native interaction fidelity | Duplicates features/localization/state bugs; conflicts with requested shared Compose direction. Keep native services, not five independent app implementations. |
| One shared library that wraps every native player invisibly | Fast happy-path API | Can hide incompatible transport/codec/background support; dependency/compiler/binary-license risk. Use only after native capability and distribution tests. |
| Navigation 3 | User-owned back stack, adaptive multiple destinations | Current docs show stable 1.1.1 and newer releases exist, but migration raises the Compose baseline and serializer/lifecycle work. Navigation 2.9.1 is the released conservative pairing [S2, S12]. Revisit deliberately. |
| Decompose/Circuit/Voyager | Explicit lifecycle/presenter architectures | More independent framework/version choices. Not needed when the app already has an app-lifetime coordinator plus JetBrains AndroidX navigation/ViewModel. |
| Room KMP | Familiar DAOs, transactions, bundled SQLite recommendation | Valid alternative across Android/iOS/JVM, but needs KSP per target and another compiler compatibility line. Current docs now demonstrate Room 3 alpha; do not copy an alpha version accidentally [S13]. SQLDelight keeps SQL/migration review explicit. |
| JSON files for all state | Very small bootstrap, portable | Poor relational queries/transactions/reordering/concurrent update recovery; use only for bounded configuration/import/export, not authoritative library/queue/progress. |
| Current latest Kotlin/Compose/AGP | New fixes and features | Current sources show Kotlin 2.4.21/Compose 1.12.2 and changed companion versions/minimum platforms. This does not prove compatibility with upstream/native player binaries. Preserve a coherent tested baseline before upgrading. |
| Server-required catalog/account | Easier server normalization/sync | Violates no-required-account/local-first operation and creates hosting/privacy obligations. Optional future sync must not take ownership away from local data. |
| YouTube-only “podcasts” | Fewer adapters | Does not produce actual RSS shows, feeds, episode GUIDs or podcast subscriptions. Rejected. |

## 10. Acceptance tests and release gates

These contracts must become automated tests where possible; checkboxes are intentionally not marked complete.

### Shared deterministic tests

1. Queue add/remove/reorder and duplicate occurrences preserve current entry identity; persisted snapshot round-trips every enum/optional field; shuffled order is stable across recreation.
2. Pause/skip is processed promptly while resolve A is delayed; stale result A cannot replace B; stale end/seek/position events do not mutate B. Duplicate ended events advance once. Repeat and sleep-at-end cannot double-advance.
3. Backward seek persists backwards; older progress tick cannot undo it; duration unknown/zero/live never divides by zero. Kill/reopen loses at most the documented checkpoint interval in ordinary playback, and restart is paused.
4. Simulated DB failure rolls back whole reorder/queue/feed update, with visible failure and previous valid state intact. Migration tests open historical databases without data loss. Concurrent follow/save is unique and idempotent.
5. Download interruption at every finalization boundary recovers to verified-complete or resumable/failed, never false-complete. Range validator mismatch restarts safely; missing length is indeterminate; stale RSS length is not a corruption verdict. Cancel/cleanup touches only job-owned files.
6. Feed fixtures include English/Arabic/RTL, UTF-8 BOM, CDATA, namespaces, relative URLs, duplicate GUIDs, missing GUIDs, multiple enclosures, ETag/304, redirects and changing signed URLs. Stable identities/follows/progress survive refresh.
7. Malicious feeds (XXE, entity expansion, excessive nesting/text/items, compressed bombs, unsafe schemes/private redirects) fail boundedly without network/file disclosure. Redacted diagnostics contain no signed URL/token/private payload.
8. Forced offline prevents **all** network mock calls and still supports library edits, queue/progress, downloaded playback and settings. Catalog failures never trigger fake production results.
9. Search latest-wins test with out-of-order success/failure; page tokens cannot cross query/provider/locale generations. Empty result and provider failure remain distinct.
10. Tests inject dispatchers/clocks and assert no direct blocking I/O in main-facing operations; bound work concurrency and verify shutdown cancels/joins owned jobs and releases clients/runtimes.

### Platform and UX integration tests

11. For **each** Android/iOS/Windows/macOS/Linux target: build, launch, DB persistent write/restart, play/pause/seek/rate/next/end from an authored audio fixture, queue restore and local offline playback. Keep per-target evidence; “common tests passed” is not this test.
12. For real remote sources: genuine RSS streaming+download+reopen, approved-scope kmpYT browse/resolve/compatible play, truthful challenge/unsupported path. Network success is environment-dependent and must be separately reported, not substituted with fixture playback.
13. Android lockscreen/notification controls, service lifecycle, audio focus, headphones/Bluetooth, screen-off; iOS remote commands, interruptions/routes/media reset, screen lock and background restrictions; desktop minimize, window reopen, keyboard/media controls and native binary loading.
14. en/ar screenshots and semantic tests at compact/medium/expanded sizes, live resize, large font scales and light/dark; restore current tab/detail/scroll without restarting player. Screen-reader focus and mixed-direction strings are manually checked, not proven by resource counts alone.
15. Build/package on the native OS runner with dependency/binary/license inventory. iOS simulator success is not device-signing/distribution evidence; unsigned macOS/Windows binaries are not signed/notarized releases.

### Filesystem and honest-reporting constraints

- Development and test commands must keep Gradle, Kotlin/Native, app test data, temporary files and tool downloads under Veyra or designated kmpYT. Use read-only preinstalled toolchains where possible; no global SDK/JDK/package install or `~/.gradle`/`~/.m2` mutation. Desktop tests require an explicit project-owned data directory, not the user's real app-data folder.
- Production code should use platform persistent app directories, not system temporary directories; test path injection prevents verification from touching real user data. A package's ordinary runtime storage location is distinct from the agent's permitted build/test workspace.
- Required model was `gpt-6-astra` at `max`. This child context has no independent deployment introspection endpoint; it cannot attest hidden runtime routing/model settings. Lead records requested settings and the limitation, not a guessed runtime verification.

## 11. Top three challenges for cross-review

1. **One queue vs native session timelines:** remote next/previous, native automatic transitions, late seek callbacks and sleep/interruption resumption can create a second owner unless the facade/tagged event contract is actually enforced. Require Android + iOS + desktop fake/real event tests before feature UI attaches.
2. **Online resolution is not codec-ready playback or redistributable runtime:** kmpYT's SABR/progressive choices, real bot challenges, desktop Node/Chromium requirements, native codec negotiation and missing upstream license are independent gates. Build a real approved-source vertical slice and genuine RSS slice first; no cosmetic app shell can remove these risks.
3. **Candidate toolchain + durable data need experimental proof:** preserved AGP 8.13.2 is outside KGP 2.2.21's fully tested matrix, SQLDelight generated-source wiring/Native linking has not been exercised here, and file+DB finalization is not atomic. First-build DB tests, crash injection and native CI must precede claims of five-platform completion.

## Sources

External sources fetched 2026-11-06; latest pages can change. Version-specific release/tag URLs carry the historical pin evidence.

- **[L1]** Local kmpYT at commit `533562f781304338f72b27d32cfe5fb7a83dc3a8`: `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `kmpyt/build.gradle.kts`, `kmpyt/src/commonMain/kotlin/dev/kmpyt/YouTubeClient.kt`; detailed source behavior cross-reviewed with the kmpYT audit agent.
- **[S1]** JetBrains, Compose compatibility/versioning: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html
- **[S2]** Compose Multiplatform **1.9.3** release/components: https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.9.3 ; tagged plugin dependencies: https://raw.githubusercontent.com/JetBrains/compose-multiplatform/v1.9.3/gradle-plugins/gradle/libs.versions.toml
- **[S3]** JetBrains KMP version compatibility matrix: https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html ; AGP 8.11 requirements: https://developer.android.com/build/releases/past-releases/agp-8-11-0-release-notes
- **[S4]** SQLDelight **2.1.0** source/release: https://github.com/sqldelight/sqldelight/releases/tag/2.1.0 ; https://raw.githubusercontent.com/sqldelight/sqldelight/2.1.0/gradle/libs.versions.toml ; later generated-source fix context: https://raw.githubusercontent.com/sqldelight/sqldelight/2.4.1/CHANGELOG.md
- **[S5]** XMLUtil **0.91.2**: https://raw.githubusercontent.com/pdvrieze/xmlutil/v0.91.2/gradle/libs.versions.toml ; common parser: https://raw.githubusercontent.com/pdvrieze/xmlutil/v0.91.2/core/base/src/commonMain/kotlin/nl/adaptivity/xmlutil/core/KtXmlReader.kt ; published metadata: https://repo.maven.apache.org/maven2/io/github/pdvrieze/xmlutil/core/0.91.2/core-0.91.2.module
- **[S6]** Official Android KMP Gradle library plugin/migration: https://developer.android.com/kotlin/multiplatform/plugin
- **[S7]** SQLDelight transaction and dispatcher-aware Flow documentation: https://sqldelight.github.io/sqldelight/2.1.0/multiplatform_sqlite/transactions/ ; https://sqldelight.github.io/sqldelight/2.1.0/multiplatform_sqlite/coroutines/
- **[S8]** Apple Search API documentation (directory terms/limits must be reviewed): https://performance-partners.apple.com/search-api
- **[S9]** Apple podcast RSS requirements: https://podcasters.apple.com/support/823-podcast-requirements
- **[S10]** Compose Navigation route/data/back-stack guidance: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation.html
- **[S11]** Compose resources strings/plurals/fonts: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html
- **[S12]** Navigation 3 KMP design and explicit non-JVM serializer requirements: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-3.html
- **[S13]** Room KMP Android/iOS/JVM setup/SQLite/coroutine context: https://developer.android.com/kotlin/multiplatform/room
- **[S14]** Published POM bases actually fetched: https://repo.maven.apache.org/maven2/org/jetbrains/compose/compose-gradle-plugin/1.9.3/compose-gradle-plugin-1.9.3.pom ; https://repo.maven.apache.org/maven2/org/jetbrains/androidx/navigation/navigation-compose/2.9.1/navigation-compose-2.9.1.pom ; https://repo.maven.apache.org/maven2/app/cash/sqldelight/gradle-plugin/2.1.0/gradle-plugin-2.1.0.pom ; https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2.pom

Platform audio, UX and CI specifics must be reconciled with their peer reports before the root implementation plan is finalized. Neither a documentation citation nor another agent's proposal is substituted for an executed test.
