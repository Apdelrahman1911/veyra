# kmpYT feasibility and integration audit

**Audit date:** 2026-11-06 UTC. **Scope:** public repository, checked-in implementation/tests, read-only GitHub metadata, and cross-team public-page probe evidence. No kmpYT source edits, dependency installation, local library test execution, or native playback were performed for this audit. Code examples below are integration sketches, not execution evidence.

## Decision in brief

- **Suitable as a YouTube source adapter, not as Veyra's player, podcast provider, music catalogue, persistent library, or download scheduler.** Its actual public API covers discovery, inspection, source/transfer plans, captions, and platform file transfers.
- **Do the real discovery → inspected audio source → native play/seek → download → process-restart/offline-play spike before promising YouTube playback.** Public discovery works from this environment; an approved video's watch response currently requires sign-in/bot verification and supplies no formats. A working browse page or green fixture suite does not establish a playable source.
- Prefer an **actually offered progressive AAC-in-MP4 audio track**, validated by the target native player. Do not treat SABR endpoints as media URLs, invent an itag, or relabel WebM as M4A. Other offered codecs can be supported only through a tested decoder path.
- Desktop source extraction requires separately supplied **Node.js 22 + Chromium/Chrome/Edge**; FFmpeg is additionally needed for combining/segmented output, **not for the simple progressive audio-only path**. Upstream supplies neither a clean-machine runtime bundle nor an application installer.
- **Public redistribution licensing is unresolved:** there is no root source license or POM license declaration. The owner has authorized work on their own upstream repository; that is not a license statement that downstream users can rely on. Obtain an explicit owner-approved license/permission before shipping public binaries/source copies; do not invent one.
- Preserve failures honestly. This audit did not establish a working live YouTube extraction/playback/download path, and no environmental authorization failure has been demonstrated to be a library defect.

## 1. Exact upstream baseline and evidence

| Item | Observed value |
|---|---|
| Repository | `https://github.com/Apdelrahman1911/kmpYT` |
| Default branch / audited commit | `main` / **`533562f781304338f72b27d32cfe5fb7a83dc3a8`** |
| Commit date / subject | 2026-11-05T16:20:25Z / `Execute every manually requested live check without test cache reuse` |
| Source version / coordinates | `0.1.0` / `io.github.apdelrahman1911.kmpyt:kmpyt:0.1.0` |
| Published distribution | README says not on Maven Central; public releases API returned `[]`; local clone has no tags |
| Build versions | Kotlin/serialization plugin 2.2.21; AGP 8.13.2; Gradle 8.13; JVM target 17 |
| Dependencies | Coroutines 1.10.2; serialization-json 1.9.0; Ktor 3.2.3 (Android/CIO/Darwin engines) |
| Targets | Android (`minSdk=23`, `compileSdk=36`); JVM; `iosArm64`, `iosSimulatorArm64`, `iosX64`; static `KmpYT.xcframework` |
| Unsupported targets | No Kotlin/JS, Wasm, browser app, native Linux, or native Windows target; desktop is JVM |
| Source size, not application size | 151 tracked files / 1,499,511 bytes; runtime source/resource files 650,209 bytes (measured from the pinned checkout) |

Sources: [commit](https://github.com/Apdelrahman1911/kmpYT/commit/533562f781304338f72b27d32cfe5fb7a83dc3a8), [pinned README](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/README.md), [module build](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/build.gradle.kts), [root build](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/build.gradle.kts), [wrapper checksum](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/gradle/wrapper/gradle-wrapper.properties), [GitHub metadata](https://api.github.com/repos/Apdelrahman1911/kmpYT), [releases](https://api.github.com/repos/Apdelrahman1911/kmpYT/releases).

All local source references below are relative to `.work/kmpYT/` at this commit. Read its `AGENTS.md`: live tests are opt-in and restricted to the approved channel in `ci/approved-channel.json`; offline tests must not contact YouTube. Library functionality itself is not hardcoded to that channel.

## 2. Public API actually present

Factories:

```kotlin
dev.kmpyt.jvm.createJvmYouTubeClient(ffmpegPath: String? = System.getenv("KMPYT_FFMPEG"))
dev.kmpyt.android.createAndroidYouTubeClient(context: android.content.Context)
dev.kmpyt.createIosYouTubeClient()
```

They return `dev.kmpyt.YouTubeClient`, own anonymous HTTP clients, and must be closed. The public constructor also permits explicit `ResearchTransport`, runtime, and downloader injection. It is not necessary to fork extraction for normal application integration.

| API (`YouTubeClient.kt` line) | Actual contract |
|---|---|
| `suspend inspect(url): ExtractionReport` (54) | Fresh watch/source metadata; no media bytes; report includes diagnostics, inspection time, media, and real representation identities |
| `suspend search(query): YouTubeBrowsePage` (59) | Public YouTube search, not YouTube Music search; trimmed 1–256-character query |
| `suspend home()`, `open(url)`, `more(page)` (61–65) | Public home/browse; `more` returns an **accumulated** page, not just the next page's items |
| `suspend channel(urlOrHandle)` (69) | Accepts `@handle` or public channel URL; defaults to `/videos`; actual `channelId` comes from response metadata |
| `suspend playlist(url)` (80) | Public playlist URL; ordered entries and duplicate occurrences preserved |
| `suspend expandFormats(report, mediaId=...)` (94) | Fetches up to eight HLS/DASH catalogue manifests; at most 1,024 resulting formats; metadata only |
| `select(report, maxHeight=1080, audioOnly=false, mediaId=...)` (101) | Nullable `YouTubeFormatSelection(primaryId, audioId?)`; IDs must come from this report |
| `playbackPlan(report, selection, mediaId=...)` (110) | `SourcePlaybackRequest`, **not playback**; only progressive/static-SABR contracts |
| `suspend plan(report, selection, mediaId=...)` (121) | `PlannedDownload`; resolves only selected manifests; does not transfer media |
| `suspend captions(report, sourceIndex, translationLanguage?, mediaId=...)` (127) | Caption document/cues; `captionState` also exposed |
| `suspend download(plan, destinationPath, onProgress)` (134) | Explicit save; progress has byte count, nullable total, speed; result has destination, bytes written, MIME |
| `suspend refresh(plan): PlannedDownload` (145) | Explicit fresh inspection of the **same selected track(s)**; rejects missing/ambiguous/changed identity |
| `suspend close()` (169) | Waits for active operations, releases cached player programs/runtimes and owned transport; cannot be called from an active progress callback |

Source: [YouTubeClient.kt](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/src/commonMain/kotlin/dev/kmpyt/YouTubeClient.kt), `YouTubeDownloader.kt`, platform factory files under `jvmMain`, `androidMain`, and `iosMain`.

### Discovery and catalogue boundaries

`PublicYouTubeBrowseService.search("oPiiLz")` performs an anonymous GET of `https://www.youtube.com/results?search_query=oPiiLz`, parsing `ytInitialData`. There is no developer API-key/account setup. Pagination uses only the actual page-declared WEB context/endpoint; it does not invent continuation tokens. Supported renderers include legacy video/channel/playlist renderers, shorts, and modern `lockupViewModel`.

`YouTubeBrowseItem` contains `id`, `title`, canonical `url`, `kind` (`VIDEO`, `CHANNEL`, `PLAYLIST`), optional thumbnail/subtitle/duration text. There are **no typed albums, songs, artists, podcast shows/episodes, charts, saved subscriptions, recommendations service, or RSS models**. `ExtractedMedia.artist` is sourced from uploader/author metadata, not proof of a musical artist credit. `YouTubeIncomingLinks` accepts some `music.youtube.com` links by canonicalizing them to normal YouTube; this does not implement YouTube Music's catalogue/API.

Browse limits: 8,000,000-character page, 500 items per parsed page, 5,000 accumulated items, 100 pages, cycle detection. Non-playlist pages deduplicate by kind/id. Consent, login, network, rate-limit, or unreadable-result outcomes often become `page.notice` plus empty/retained items rather than throwing. Veyra must display notices, avoid treating a blocked result as a successful empty catalogue, debounce search, preserve playlist duplicates, and stop requesting continuations when absent.

Sources: `youtube/YouTubeBrowseService.kt`; `youtube/PublicYouTubeBrowseService.kt:16–149,391–490`; `extraction/YouTubeInspectionResolver.kt:75–104`; `youtube/YouTubeIncomingLinks.kt`.

## 3. Audio, playback, expiration, and renewal

### Audio format selection

`Representation` records actual `mimeType`, `codecs`, `bitrate`, `sizeBytes?`, `durationMs?`, `expiresAtEpochMs?`, audio sample rate/channels/language/default flag/track ID/display name, DRC/loudness, transport and availability. It does not promise MP3, a fixed bitrate, an audio-only format, or any usable format for every video.

`select(audioOnly=true)` considers audio-role representations; sorting prefers explicit preferred audio ID (via lower-level selector), default audio, non-DRC, progressive transport, then bitrate. **It checks download compatibility, not device decoder support.** A directly copied WebM/Opus file can pass this selection on iOS/JVM but fail native playback. The application should select a compatible *offered* representation or expose a truthful unsupported-source state. Do not extract fake audio URLs or assume that `select` always yields AAC.

`YouTubeFormatCapabilities.playbackProblem` deliberately does not validate native codecs. Android/iOS combining rejects known WebM/incompatible MP4 codec combinations; no transcoding occurs. JVM combining uses FFmpeg stream-copy to MP4; that is not a universal codec conversion path either.

Sources: `extraction/ExtractionModels.kt:9–62`; `youtube/YouTubeFormatCapabilities.kt:33–61,95–118`; `jvm/JvmMediaProcessor.kt:47–69`.

### Playback contracts are not a player

`SourcePlaybackRequest.plan` contains selected representations and canonical inspection identity. `requireSupportedSource()` rejects arbitrary request headers, credentials, external SABR checkpoints, segments/init resources, and mixed transports. Plans are all progressive or all static SABR. The normal `playbackPlan` creates **empty headers/no credentials**, so current public progressive plans do not require an undocumented iOS custom-header API.

- Progressive audio: pass the actual expiring source URL to a native decoder/player adapter; the app owns prepare/play/pause/seek, queue, focus/interruptions, background audio, media sessions, lock-screen metadata, and error recovery.
- HLS/DASH: library parsers/download plans exist, but portable `playbackPlan` rejects them. Native HLS support would be an additional tested app adapter, not an already delivered kmpYT capability.
- SABR: endpoint is a protocol POST endpoint, **not an MP4/M4A URL**. `YouTubeSabrStreamingSession` needs caller-owned random-access caches and an explicit `SourcePlaybackAuthorization`; absent authorization fails with `SABR_PLAYBACK_AUTHORIZATION_REQUIRED`. It downloads whole static tracks, exposes verified byte coverage/read/awaitRange, and owns cancellation. Its seek waits for ingestion of an offset; it does **not** implement server-time seek or live rolling windows. A Media3/AVPlayer/desktop data-source bridge is substantial additional work.

Source: [YouTubeSabrStreamingSession.kt](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/src/commonMain/kotlin/dev/kmpyt/extraction/YouTubeSabrStreamingSession.kt), especially 16–46, 62–79, 96–107, 122–135, 163–178, 213–249.

### Expiry and refusal

The provider reads `expire` from resource URLs or `streamingData.expiresInSeconds`; expired-at-inspection formats become unavailable. Null expiry remains unknown, not infinite. Only a bounded player-program cache is reused (client sets one cached program entry); metadata/signed resource URLs are refreshed on inspection.

`refresh(plan)` re-inspects and preserves representation identity, transport, codecs, dimensions, language/track, sample rate/channels, DRC/HDR and related characteristics. It does not silently select a different quality/language/transport. There is no automatic player URL-recovery loop. Persist canonical content identity + chosen format identity/characteristics + position, **not signed media URLs or raw plan/report objects**, then resolve again when needed.

JVM/Android download code maps HTTP 401/403/410 to an **internal** `ExpiredResourceException`; this does not prove expiry caused the response and is not a public typed recovery API. Other public errors are largely exceptions/messages or extraction diagnostics. Veyra should use its own typed source errors, at most a bounded explicit same-track refresh when appropriate, and never retry a reported account/age denial through alternate identities. If login/bot/rate/region rejection is returned, show it and retain an external canonical watch-page option instead of claiming playback.

Sources: `extraction/YouTubeProvider.kt:87–119,197–214,339–340`; `YouTubeClient.kt:145–165`; `extraction/YouTubeRepresentationRefresh.kt`; `internal/HttpFileTransfer.kt:18–21,110–118`; `extraction/YouTubeResponseExtractor.kt:58–61,153`; `youtube/YouTubePlaybackRecovery.kt`.

## 4. Downloads, integrity, resume, and offline state

### What the library provides

- Explicit, cancellable suspend download into a caller-owned path; final destination must not already exist. Same-destination calls are excluded/locked. The application owns jobs, database state, user actions, notifications, retries across app lifetime, and background scheduling.
- Private checkpoint/staging files; completed output is published only after transfer/materialization success. JVM/Android uses sibling `.<destination-name>.kmpyt`; iOS uses an owned checkpoint directory.
- HTTP status, content encoding/media-type, Content-Length, Content-Range/offset/size, EOF, validator, and overflow checks; local HTTP is opt-in for tests. HTTP redirects are revalidated, including HTTPS downgrade prevention on JVM/Android.
- Same-plan interruption resume using validated ranges/validators; changed or unverifiable resources restart safely. SABR has durable byte coverage/segment receipts and resumes a fresh protocol session, not a reused stale session.
- Nullable known-size reporting; unknown does not become zero. No fixed 512 MiB file cap: whole-file totals use `Long`. This does **not** mean unbounded support: planning has a 3,000-part cap, HLS/DASH parsers 4,096 segments, HLS duration bounds, and Android native processing has sample limits. Free-space failures remain possible.

### Important platform asymmetries and limitations

1. **Signed-URL renewal does not retain JVM/Android partials at this revision.** `FileDownloadCoordinator.kt:46–52` hashes the serialized *entire* plan (except SABR checkpoint/diagnostics), including URLs and expiry. A refreshed plan changes the digest and clears prior partials. Normal same-plan resume works; do not advertise refresh-preserving cross-restart resume on these platforms. iOS intentionally strips URLs/expiry from selection identity and validates resource scope plus strong response validators (`IosDownloadResumeStore.kt:39–90`), with a signed-URL-refresh test. This qualifies README's broad wording.
2. **HTTP integrity is not proof of decodable audio.** JVM progressive single-resource processing copies bytes without decoder/container probing (`JvmMediaProcessor.kt:47–49`); iOS's equivalent path publishes nonempty verified transfer bytes without AVFoundation mux/probe (`IosYouTubeDownloader.kt:103–119`). Android performs MediaExtractor/sample validation. Veyra must probe/prepare the final file and check actual audio/duration before showing it as verified offline media. Wrongly labelled server bytes must not receive a successful playable-download claim.
3. Native muxers only accept compatible inputs. iOS segmented downloads require static fragmented MP4; MPEG-TS/WebM segments are unsupported. Live/encrypted/nested multivariant downloads are unsupported. A fixed `.m4a` filename cannot make another container AAC/MP4.
4. Staging/materialization can hold multiple full-track copies; peak disk usage is materially above final output size. Budget and measure it. Do not remove checkpoint files owned by an active job, and do not mistake a partial/cache file for a ready download.
5. Checkpoints are transfer receipts, **not Veyra's persistent library**. The app still needs stable media/download IDs, selected track identity, durable state transitions, safe app-private paths, final byte count/digest/container metadata, missing-file reconciliation, rename/delete behavior, storage quota, and offline restart restoration. Library defaults do not prove cryptographic integrity of a previously completed local file after later corruption.
6. Raw serializable representations/plans contain signed URLs and potentially opaque source state. Do not log/publish them; redact diagnostic evidence and store only the minimal application metadata. iOS checkpoint code explicitly hashes rather than stores signed URLs/headers/session context.

Sources: `internal/FileDownloadCoordinator.kt:26–85,94–159`; `internal/HttpFileTransfer.kt`; `internal/RangedFileTransfer.kt`; `downloads/DownloadProtocol.kt`; `extraction/TransferPlan.kt`; `IosDownloadResumeStore.kt`; `IosDownloadResumeTest.kt:19–44`; `IosYouTubeDownloader.kt:29–35,103–119`; `android/AndroidMediaProcessor.kt:99–139,220–271`.

## 5. Desktop runtimes, clean machines, licensing, and reproducibility

The JVM factory explicitly says no executables are bundled. `DesktopYouTubeJavaScriptRuntime` locates Node and a browser using `KMPYT_NODE`, `KMPYT_CHROMIUM`, or a short hardcoded path list—not general PATH discovery. Its Node helper uses built-in modules/native WebSocket, not an npm/Puppeteer dependency tree. Node 22 is the documented requirement. A fresh isolated Chromium profile executes player programs offline; requests are blocked, no user's browser profile is imported, and owned processes/files are cleaned up on close. Cold preparation may allow 60 seconds; ordinary transforms are bounded.

**Clean-machine plan:** either ship vetted per-OS/per-architecture Node/Chromium artifacts with licenses/notices and verified checksums, or make source unavailability an explicit diagnosed dependency/setup requirement. Merely having Chrome/Node on this development host is not packaging. A self-contained JVM app also needs an appropriate Java runtime and its chosen audio decoder/runtime; those are separate from kmpYT.

For repository-only execution, align **both** JVM `-Djava.io.tmpdir=<Veyra>/.work/tmp` and `TMPDIR` (plus `TMP`/`TEMP` where relevant) to that same real directory. Java creates the helper/profile; Node's `ownedProfile()` requires its parent to equal `os.tmpdir()`. Mismatching them causes `OFFLINE_PROFILE_INVALID`. Scope Gradle/Kotlin Native caches, temporary files, app data, and any browser HOME/XDG state under `.work`. Do not alter global SDK files, PATH, browser installs, kernel settings, or sandbox policy.

The helper launches Chromium with its sandbox, without `--no-sandbox`. A root-only/container/unsupported-user-namespace launch can fail before extraction; record that as a runtime/environment problem instead of patching away sandboxing or labeling it a YouTube parser failure. Upstream Linux CI changes a kernel AppArmor setting on its disposable runner; that is **not** a safe deployment prerequisite to copy onto a user's machine.

FFmpeg is caller-supplied through the factory/path or `KMPYT_FFMPEG`, and used only on verified local files for stream-copy combining. Simple progressive audio-only download avoids it. Distribution requirements depend on the exact FFmpeg build, enabled components, and chosen media runtime; retain applicable LGPL/GPL notices/source obligations rather than assuming all FFmpeg binaries have one license. Chromium/Node/JRE and all transitive dependencies also need a collected license/SBOM inventory.

No release/application size was established. The small source checkout excludes Node, browser, FFmpeg, decoder, JRE, Compose, and other dependencies. **Do not quote repository size as installer size.** Report compressed artifact bytes, installed bytes, runtime memory/startup, and exact bundled versions/checksums per target after a clean-machine package test.

Recommended integration: pin this immutable Git SHA and validate it in a bootstrap/verification script; use Gradle `includeBuild` with explicit substitution for the coordinates, or a versioned checksummed internal artifact. Avoid mutable `main`, opportunistic `mavenLocal()` artifacts, or a system-wide preinstalled checkout. Keep upstream unmodified for the first spike; submit only evidence-driven, separately reviewed fixes later. Exact plugin/dependency versions and wrapper SHA exist, but upstream has no dependency lockfiles/verification metadata and CI actions use moving major tags; reproducible application release inputs need tightening separately.

License finding: GitHub `license=null`; complete tree has no root `LICENSE`; Maven POM omits license metadata. The **CC0 fixture license only covers authored test media**, not the library. The README's ClearMedia extraction/provenance statement and yt-dlp profile attribution are not a redistribution license grant. `YouTubePublicPlayerClients.kt` pins profile facts to yt-dlp 2026.10.26; no yt-dlp executable is bundled. Resolve the owner's intended library license and third-party provenance before public distribution.

Sources: `jvm/JvmYouTubeClient.kt:15–37`; `jvm/DesktopYouTubeJavaScriptRuntime.kt:19–67,110–141`; `jvmMain/resources/youtube/chromium-runtime.cjs:1–63`; `jvm/JvmMediaProcessor.kt`; `.github/workflows/ci.yml`; `extraction/YouTubePublicPlayerClients.kt:6–42`; `jvmTest/resources/research/LICENSE.txt`.

## 6. Validation evidence: controlled versus live

### Independently checked public CI metadata

The latest pinned-commit [Cross-platform CI run 42063400390](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42063400390) completed **success**. [Jobs API](https://api.github.com/repos/Apdelrahman1911/kmpYT/actions/runs/42063400390/jobs?per_page=100) reports six successful jobs: JVM Linux/Windows/macOS, Android API 29/35, iOS simulator/XCFramework. This is upstream CI evidence, not a local Veyra build or a real-media playback assertion.

The checkout contains 340 `@Test` annotations in common tests, 51 JVM, 48 Android instrumented, and 25 iOS. These are **source annotation counts, not an executed-test total**; some live checks are opt-in, common tests repeat across targets, and hosted Swift XCTest cases are separate. Tests cover browse parsing/continuation, inspection freshness, player program/cache lifecycle, cancellation, manifest expansion, source identity, SABR framing/coverage/read/seek waits, range/EOF/integrity, checkpoints/resume, and native media processing with authored fixtures/local servers.

### Live evidence must stay separate

- README records [approved live run 41980615183](https://github.com/Apdelrahman1911/kmpYT/actions/runs/41980615183) on 2026-11-05 at older commit `19d92663795a3400e60a7892ce487b45be3aeb2a`: channel reached, video extraction yielded `AUTHORIZATION_REQUIRED`, zero formats, no media download. API confirms run failure. Subsequent changes do not turn this into a successful live run.
- README records local Android 15 `TRANSPORT_DNS_FAILURE`, before media transfer. It explicitly says emulator live playback remains unverified.
- Veyra integration-feasibility agent's **2026-11-06T05:05:49Z** public-page probe: search `oPiiLz` HTTP 200/`ytInitialData`; channel `/@oPiiLz/videos` HTTP 200 and actual `UCHVDgaiLbpj5Leazw8vsAmg`; selected public search video `J_UH5zb-kf4` watch HTTP 200 but `playabilityStatus=LOGIN_REQUIRED`, reason `Sign in to confirm you're not a bot`, no `videoDetails`, no progressive/adaptive/HLS/DASH formats. No media, cookies, profile variation, or bypass was attempted. This is a cross-team direct-page probe, **not yet the kmpYT JVM API execution**; consult that agent's evidence/report for the raw redacted record.

A watch page returning HTTP 200 can still deny playback. The provider maps `LOGIN_REQUIRED`/`AGE_CHECK_REQUIRED` to `AUTHORIZATION_REQUIRED`; the extractor stops alternate-client/transform attempts on that denial. The library's use of pinned public profiles in other permitted cases does not authorize bypassing explicit refusal. Do not replace a denied YouTube result with a sample MP3/podcast and claim the YouTube pipeline passed.

## 7. Earliest real vertical spike and acceptance gates

Run these gates serially on the first available real target; maintain target-specific evidence instead of assuming KMP compilation proves native behavior.

1. **Resolve/build:** verify pinned SHA; compile the actual library and Veyra adapter with scoped caches. Run the offline library tests. Capture exact environment/runtime versions and redacted results. Missing browser/runtime and transport refusal must be distinct.
2. **Real discovery:** call `youtube.search("oPiiLz")`, show actual results and notices. Call `youtube.channel("@oPiiLz")`; use the actual channel ID from its response. Choose a result from that approved channel, not an invented/sample item. Test `more` where offered; do not append its already-accumulated items twice.
3. **Real source:** call `inspect(result.url)`; check diagnostic codes and actual uploader ID before any media transfer. If an authorization/bot/rate/DNS error occurs, record an honest blocked source outcome and stop that path. If permitted, find a real progressive AAC/MP4 audio candidate by role/MIME/codec; do not assume itag 140 or manipulate signatures. If only unsupported transport/codecs exist, report the exact unsupported capability, not general library failure.
4. **Play then seek:** obtain `playbackPlan`; native prepare the actual source, decode/output audio, prove position advances, pause/resume, and seek forward/backward. Verify read position/decoded audio beyond the seek target, not just a moved UI slider. Repeat with rapid item switching/cancellation. Do not call a plan or downloaded byte range playback evidence.
5. **Download:** `plan(report, selection)` then `download(plan, appPrivateDestination)` with visible progress and cancellation. Probe the completed container/audio track and duration before marking verified ready, including native prepare/read/seek near the beginning, middle and end. Verify exact length when source supplies it; compute a local digest. For the first live smoke use an explicit approved small resource budget—this is test policy, not a hidden product size cap.
6. **Restart truly offline:** persist content/selection metadata, local artifact identity, queue and position; close client/player; terminate/restart the app; deny network; open the saved artifact, decode audio and seek; ensure no reinspection or remote artwork fetch is needed for playback. Network denial must cover the native player's own networking, not only a mocked/denying Ktor transport. Assert the resolved local file/cache handle and independently check egress. A surviving in-memory player/cache does not pass.
7. **Recovery:** authored servers separately exercise missing/weak/changed validators, ignored/wrong ranges, truncated bodies, mislabeled media, interrupted transfer, same-path exclusion, storage full, app kill, missing/corrupt final file, and expiry refresh. Confirm JVM/Android renewed-signature restart versus iOS validated continuation, rather than promising platform parity not implemented.
8. **Clean-machine package:** install and run on a machine without development Node/Chrome/FFmpeg/JDK; verify bundled dependencies or explicit diagnosed unavailable features, license notices, data paths, uninstall behavior and target architecture. On Apple/Android run actual background/lock-screen/interruption tests; desktop build success cannot substitute.

Minimal **nonexecuted** audio selection sketch (use platform-specific factory and a real discovered approved URL):

```kotlin
val report = youtube.inspect(discoveredItem.url)
check(report.diagnostics.none { it.code == "AUTHORIZATION_REQUIRED" })
val media = report.media.single()
check(media.uploaderChannelId == actualApprovedChannelId)
val audio = media.representations
    .filter { it.selectable && it.role == TrackRole.AUDIO &&
        it.transport == TransportKind.PROGRESSIVE &&
        it.mimeType.substringBefore(';').equals("audio/mp4", ignoreCase = true) &&
        it.codecs.orEmpty().split(',').any { codec -> codec.trim().equals("mp4a.40.2", ignoreCase = true) } }
    .sortedWith(compareByDescending<Representation> { it.audioIsDefault == true }
        .thenBy { it.isDrc == true }.thenByDescending { it.bitrate ?: 0L })
    .firstOrNull() ?: error("No compatible progressive audio source offered")
val selection = YouTubeFormatSelection(primaryId = audio.id)
val playback = youtube.playbackPlan(report, selection)
// Veyra's native adapter must now prepare/play/seek playback.plan.primary.url.
// Only after actual playback evidence: plan/download/probe/persist/restart offline.
```

Imports for the sketch: `dev.kmpyt.extraction.Representation`, `TrackRole`, `TransportKind`, and `dev.kmpyt.youtube.YouTubeFormatSelection`. This conservative first spike selects declared **AAC-LC (`mp4a.40.2`)**; merely matching the `mp4a` prefix is not an AAC-LC/decoder guarantee. Other AAC profiles/codecs require their own tested capability path, and native preparation remains decisive even for AAC-LC. For production replace generic `check/error` with typed, user-visible capability/refusal states. Background lifetime, coroutine ownership, audio focus, file permissions and persistence remain Veyra responsibilities.

## 8. Proposed narrow upstream follow-up: refreshed HTTP checkpoint identity

**Status: design only; no library edit or executed regression.** The lead is awaiting the M0 research freeze and the owner's license decision. Existing pinned CI is green; this does not justify toolchain drift or edits intended to bypass source refusal.

The concrete issue is an unnecessarily lost resume opportunity, **not demonstrated byte corruption**: `FileDownloadCoordinator.kt:46–52` digests full `PlannedDownload` JSON and discards the checkpoint if any signed URL/expiry changes. `YouTubeClient.refresh()` legitimately changes those values while preserving selected track identity. The iOS implementation/tests already separate selected media identity from refreshed resource addressing. Current JVM/Android fallback is safe but potentially downloads a large interrupted file again.

Suggested first PR scope: preserve validated **single-resource progressive HTTP** partials across equivalent signed-URL refresh, covering the shared JVM/Android file coordinator; keep current conservative behavior for SABR/segmented cases unless independently proven. Do not broadly remove URL fields from the existing digest and call it fixed.

Required design:

1. Introduce a versioned checkpoint schema with a stable **selection digest** plus a per-part **resource-scope digest**, retaining exact role/format/source video, codec/language/DRC, transport, declared byte size and range identity. Changes to tracks/content scope must still invalidate old bytes. Prefer explicit reviewed identity fields over accidental dependence on every future serialized property.
2. Permit only documented ephemeral signing changes within the same resource scope, and require a strong matching response validator before appending any refreshed response bytes. Non-YouTube origins/path/query changes remain conservative. Treat cross-CDN/host equivalence explicitly; identical ETags across unrelated origins alone do not prove identity. Existing `iosSelectionDigest`, `iosResourceScope` and signed-refresh test are useful references, not an instruction to copy them without review.
3. For a previously complete part under a refreshed resource, require safe response revalidation before reusing it; do not skip the network solely because local length equals old length. Reject/clear malformed, duplicate-key, out-of-range or incompatible checkpoints; truncate uncommitted tails to proven receipts. Keep raw signed URLs/tokens out of the new persisted schema/logs.
4. Migrate legacy checkpoints conservatively: reuse only when the original full-plan digest matches; otherwise restart rather than inventing resource identity the old schema did not preserve. Do not relax SABR generation binding (`YouTubeSabrTransfer.kt:588–594`) or playability authorization stop conditions.

Regression sequence using only authored media and loopback `MockWebServer`:

- First request returns a throttled real authored AAC/MP4 fixture with stable strong ETag. Cancel after a committed nonzero prefix; assert destination absent and private partial present.
- Create a **new** downloader/coordinator instance and a copied plan with only `sig`/`expire` URL parameters and `expiresAtEpochMs` refreshed. Server answers only the exact remaining range with `206`, matching strong ETag and exact `Content-Range`/size. Assert request `Range: bytes=<committed-prefix>-`, no full-file refetch, byte-exact final fixture, valid decoded audio and successful cleanup.
- **Expected old-code failure:** changed digest discards prefix, so second request lacks Range/starts at zero. This is a deterministic local failure independent of YouTube availability. Implement this failing test before the fix.
- Negative controls: changed/missing/weak validator, same ETag but changed media/codec/language/range/content scope, expiry change with identity change, HTTP 200 despite Range, changed object total, short/overlong response, corrupt/full-but-uncommitted part, cancellation while checkpointing, and concurrent same-destination call. Every case must safely restart/reject, never concatenate different generations.
- Keep existing `FileCheckpointIntegrityTest`, `HttpDownloadTransferTest`, `HttpResponseIntegrityTest`, `RangedFileTransferTest` and all common/JVM tests green; mirror the regression on Android instrumented tests because production file code is shared. Report executed commands/results separately. No live YouTube request is needed to prove this fix.

## 9. Cross-review actions

The integration-feasibility agent accepted these three challenges to the podcast/source architecture:

1. Episode identity is `(canonical feed identity, GUID)` with feed alias handling; enclosure/download **generation** is separate. RSS length, mirror URL, or reused ETag alone cannot certify byte equivalence. Test replaced enclosures and rotating mirrors.
2. Convert safe fetch/XML policy into tests: bounded parsing, DTD/external entities disabled, per-hop URL/credential/scheme validation, private/link-local/loopback IPv4/IPv6/DNS protections, and no credential leakage across origins. A HEAD response is not proof about later GET bytes.
3. Offline denial must cover native player networking; a denied application HTTP client alone is insufficient. Terminate the process, resolve an actual local artifact, deny/check native egress, and verify playback/seek on restart.

Mobile-audio review additionally confirmed that generic kmpYT suspend transfer is not an OS-durable background executor, that Media3 cache spans and native HLS packages require opaque offline handles rather than fabricated `.m4a` paths, and that `mp4a.40.2` is a narrower AAC-LC selection than a generic `mp4a` prefix. Those constraints are now reflected in the spike above; their platform implementation belongs to the app rather than this source-audit PR.

Architecture review sent three implementation challenges: (a) if DataV1 uses atomic JSON snapshots, one serialized read-modify-durable-write owner must prevent progress/library/queue/download updates from overwriting each other; rename alone is not a transaction, and the final architecture document must resolve its earlier SQLDelight proposal; (b) freeze one durable download specification/owner across kmpYT plans versus Media3/background URLSession—native executors do not automatically inherit kmpYT's identity/validator protections; (c) preserve typed local file/cache/package handles and prove native-player offline behavior independently of application HTTP mocks. These are acceptance requirements, not claims that implementation already satisfies them.

## 10. Model/configuration attestation boundary

The user requested `gpt-6-astra` with `max` reasoning for research. This audit agent cannot independently inspect/verify the backend model actually serving inference. Any harness-reported spawn/model configuration should be recorded by the lead as configuration evidence, not claimed as cryptographic/runtime attestation. No additional subagents were spawned by this audit agent.
