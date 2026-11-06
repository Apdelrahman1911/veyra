# Veyra execution plan

Status: **implementation plan accepted after research and cross-review, 2026-11-06**. Product name **Veyra / ڤيرا**. Repository **public**. No paid services, Veyra account, or app-store publication required.

## Required release scope
- Android, iOS, Windows, macOS and Linux; Kotlin Multiplatform shared logic and Compose Multiplatform interface with native audio integration.
- English and Arabic across complete core journeys, full RTL, adaptive phone/desktop layouts, light/dark themes, accessible labels/focus/text scaling/contrast and desktop keyboard navigation.
- Real supported discovery/search and playable audio; local library, favorites, user playlists and editable persistent queue; mini/full players; play/pause/resume/seek/next/previous, music shuffle/repeat.
- Genuine podcast shows/episodes, following, per-episode progress, resume, played/unplayed, speed, configurable appropriate skips, sleep timer.
- Reliable queued downloads with progress/cancel/retry and resumption where supported; validated offline files, metadata/artwork, storage management, safe missing/corrupt file handling and restart/offline playback.
- Single session/player/queue ownership; platform media controls/background behavior and interruption/route handling to the extent platforms permit.
- Pinned, reproducible kmpYT integration; meaningful tests; clean-checkout instructions; per-target artifacts and honest platform/signing verification matrix.

Local audio import is included as an account-free, copy-into-owned-storage capability; it cannot substitute for streaming. Optional only after essentials: recommendation personalization, extensive EQ/audio effects, cloud sync/accounts, desktop Linux ARM64, large-scale catalog indexing, automatic feed downloads, gapless/crossfade, transcripts, and elaborate animation.

## Final product and architecture direction
- Four destinations: **Listen, Search, Podcasts, Library**. Listen is cross-media continuation plus clearly attributed discovery; Search handles explicit catalog/local scopes; Podcasts owns Continue/New/Following/show details/Add RSS; Library owns saved items, playlists, favorites and downloads. Settings is a secondary destination. See the research reports for screen concepts and source-linked reasoning.
- Original Quiet Currents design: petrol/sea-glass/sand, paired light/dark themes, bundled IBM Plex Sans Arabic with OFL notices, 48dp targets, content-sized rows and adaptive bottom bar/rail/sidebar. Mirror navigation, not media transport/time. Localized errors are part of the shared resource contract.
- One app-lifetime coordinator/player/session with **two persistent queue contexts** (Music/Podcast). Viewing a context never activates audio. Restore paused. Position samples come from the native engine; generation/seek tokens reject stale callbacks. Explicit collection replacement preserves recoverability; manual queue reorder disables shuffle visibly without dropping occurrences.
- Podcast completion uses confirmed natural end or an explicit mark action, independent of saved progress/downloads. Default speed 1x; initial verified range 0.5–2x; configurable back15/forward30. Duration sleep timers follow the active session in wall-clock elapsed time and remain visible across contexts; end-episode timers bind to the selected episode and stop before queue advance.
- `:shared` owns domain/data/source/download/playback contracts and implementations; `:composeApp` owns shared Compose UI and platform engines/hosts; `iosApp` is the Xcode host. Constructor injection and a single AppContainer, not per-screen clients or players.
- Kotlin2.2.21/Compose1.9.3/AGP8.13.2/Gradle8.13/JDK21, JVM17 bytecode; pin upstream as a Git submodule. AGP is outside KGP's published fully tested range, so actual build gates are mandatory. Use versioned atomic JSON snapshots with serialized writes, last-known-good recovery, bounded parsing and coalesced progress; migrate deliberately to SQLite if measured scale demands it. No silent in-memory fallback on storage failure.
- Native transports: Android Media3; iOS AVPlayer; desktop raw libmpv C API through JNA using pinned per-host native runtime artifacts. Native session commands route back to the same coordinator. All media-file work streams; no whole-audio in-memory buffering.
- kmpYT is the real YouTube adapter; progressive native-compatible audio first, exact source diagnostics, bounded identity-preserving refresh. Genuine podcasts use publisher RSS with optional no-key Apple directory and direct-feed entry. Explicitly labeled Open music may use Commons with source-declared compatible derivatives and per-recording provenance. Never silently substitute a podcast/sample/open track for denied YouTube media.
- Downloads use private staging, validator-aware resumability, actual HTTP size/range checks (RSS length is advisory), format validation, local hash, final rename, durable Ready commit and crash reconciliation. Offline resolution validates local files before any remote resolution; offline mode blocks artwork/catalog/feed/source traffic too.
- Free standard public GitHub Actions runners only; no larger runners, purchases or artifact-storage assumptions. Use logs/job summaries and public Release assets for outputs, with software redistribution/license gates satisfied first. Target Android API23+, iOS16+, Windows10 x64, macOS13+ (arm64/x64), Linux Ubuntu24.04+ x64; architecture variants outside these are optional, not silently claimed.

## Implementation ownership (no overlapping edits)
| Owner | Files/area |
|---|---|
| Lead | Root Gradle/version/submodule setup; shared downloads; composition root/UI shell; integration review/fixes after owner coordination; plan/state/decision records |
| architecture_research | shared `dev.veyra.model`, `data`, `playback`, and source contracts; portable tests of those areas |
| music_ux_research | composeApp `dev.veyra.ui.music` (Listen/Search/Library/playlists/player/queue); widget tests |
| podcast_ux_research | composeApp `dev.veyra.ui.podcasts` (hub/show/episode/progress actions); screen tests |
| design_a11y_research | composeApp `dev.veyra.ui.design`, common localized/font/icon resources, resource/contrast tests |
| kmpyt_audit | YouTube source adapter and platform factories; scoped upstream resume fix + regression PR (no license edit without approval) |
| mobile_audio_research | composeApp Android/iOS audio/session/platform entrypoints/manifests; iosApp Swift host; native tests |
| desktop_audio_research | composeApp desktop audio/native system media/runtime integration, desktop entrypoint; native source/build scripts and tests |
| verification_ci_research | .github/workflows, tooling/boundary/bootstrap/packaging scripts, checked-in Xcode project wiring, verification records; coordinate manifests with mobile owner |
| integration_feasibility | shared podcast/RSS/directory/Open-music networking adapters and fixtures/tests; real-source/offline smoke harness |

## Reviewable acceptance groups
1. **Real integration:** run actual source search and select genuine returned content; inspect compatible source; actual app engine advances/pause/resumes/seeks; download final validated file; terminate/restart with native network egress denied and play/seek locally. Record YouTube refusals separately. Repeat supported real path on every executable target; missing essential evidence remains a gap.
2. **Local data/queue:** favorite/save/download/playlist independent; restart retains queue/positions/settings/follows; stale callbacks cannot replace current audio; repeat/shuffle/next/add/move/remove/undo and two-context switches pass deterministic tests.
3. **Podcasts:** real Arabic/English feeds; stable GUID identity; no destructive refresh; followed shows and unfinished episodes remain offline; confirmed seek/progress persists; explicit played flags, speed/skips and both timer policies pass fake-clock/native tests.
4. **Reliability/security:** malformed/oversized/hostile feeds, unsafe URLs/redirects, cancellation/ranges/changed validators/HTTP errors/storage failure/corrupt or missing files have truthful recovery; never Ready on partial/undecodable bytes; no signed URLs/secrets in persisted snapshots/logs.
5. **UX/platform:** every core state in en/ar/light/dark; phone/desktop screenshots reviewed at narrow/wide/large text; keyboard/back/focus/labels tested; singleton audio/session and background/interruption/routes tested where hardware/environment supports, explicitly matrixed otherwise.
6. **Delivery:** final-revision tests/builds, clean checkout/setup, bundled runtime closure and source notices, per-target installable or unsigned/signing-limited artifacts and exact links. No essential feature is reclassified as optional to finish.

## Milestones and gates
| Milestone | Concrete output | Dependencies | Verification |
|---|---|---|---|
| M0 Research and decisions | Source-linked UX studies, original design direction, journeys, cross-review, product/architecture/acceptance spec | None | Lead checks evidence and resolves challenges; finite endpoint before code |
| M1 Feasibility + kmpYT | Exact API/runtime/license audit, live network/source classification, supported podcast source, audio engine experiments | M0 discovery starts in parallel | Actual API calls and source/decoder checks; no mock presented as live |
| M2 Repository/foundations | Public GitHub repo, versioned KMP structure, pinned dependency integration, isolated tooling, CI scaffolding | M0 final decisions | Clean compilation and initial portable tests |
| M3 Real listening proof | Real search→select→source→play→seek→download→restart→offline test harness | M1/M2 | Persist evidence and classify each target independently; remains a release gate |
| M4 Connected core journeys | Local persistence, libraries/playlists/queue, podcast semantics, download lifecycle, complete phone/desktop UI | M2; M3 constraints | Unit/integration/UI tests with faults, state restoration and real-source smoke |
| M5 Platform integration/refinement | Native audio/session/control behavior, accessibility/RTL/themes, visually reviewed responsive screens | M3/M4 | Screenshots plus accessibility/keyboard, rapid actions, background/routes/network tests |
| M6 Tests/packaging/clean-checkout | Free-standard-runner matrix, signed/unsigned artifacts as available, setup/build verification | M4/M5 | Final-revision checks and clean-machine install/start tests, explicit gaps |
| M7 Delivery review | Repository/revision/artifact links, instructions, upstream PRs/pin, exact verification matrix and remaining external distribution requirements | All required gates | User-perspective acceptance review; Goal complete only when required scope actually satisfied |

## Completion contract
A passing compilation is not playback. A source URL is not playback. A downloaded byte count is not validated media. A simulator run is not a physical-device test. A screenshot is not a working journey. A green subagent report is not lead verification. External restrictions do not erase essential requirements; continue independent work, preserve resumable state, and leave the Goal incomplete if a required gate cannot be met.
