# Media pipeline performance: sections, reuse, warm-next, hedging, countdown — 2026-11-16

Owner request: reach Snaptube-level download speed, startup and reliability where technically
feasible, without access-control circumvention, ad bypass or integrity-token work. Research
basis: `docs/research/snaptube-comparison-20261115.md` and
`docs/research/playback-download-performance-20261115.md`.

Device: owner's Huawei AQM-LX1, Android 10, the same Wi-Fi as 2026-11-15. Probes and
redacted receipts are in the ignored `.work/evidence/prep-latency-20261115/` (`p1_*`, `p1-*`,
`p6_*`) and `.work/evidence/p1/`. No URL, header, body or media was recorded. Every valid run
kept Veyra in the foreground; the only background checks fell inside the 8 s settle before
measurement and at test exit (`foregroundChecks` in each `*-exit.json`).

## What changed

### kmpYT (consumed `third_party/kmpYT`, mirrored byte-identically in `.work/kmpYT`)

1. **Sectioned, parallel progressive downloads (P1).**
   - `SectionedProgressiveTransfer` / `TransferSectioning` (jvmAndroidMain).
   - A strictly resumable progressive object is fetched as exact `Range: bytes=a-b` sections of at
     most 8 MiB. Up to 4 connections are used by default, configurable through
     `ProgressiveDownloadTuning` in `AndroidYouTubeDownloader` and `JvmYouTubeDownloader`.
   - Integrity is unchanged:
     - Only the contiguous prefix is checkpointed, after `fsync`, with a SHA-256 receipt over
       exactly those bytes.
     - Bytes ahead of the prefix are truncated whenever a transfer ends incomplete.
     - Every section must be an exact 206 with the same object size and the same strong ETag
       (`If-Range` on every section).
     - Sections retry twice with backoff.
     - A server that answers a section with 200 makes the transfer fall back to one verified
       full-extent request.
     - Non-strict transfers are never sectioned.
2. **iOS sequential sections.** `IosHttpPartTransfer` requests consecutive ≤8 MiB sections, each
   resumed from the committed, validator-checked checkpoint. A response without a strong
   validator restarts as one request.
3. **No-copy materialization.**
   - Before: a completed single-file download was copied three times before publication.
   - Now the checkpoint part is hard-linked into the processor (`FileMediaProcessor.link`:
     `Os.link` on Android, `Files.createLink` on desktop JVM, falling back to a copy).
   - The processor fetches a single resource straight into place and publishes it by rename.
   - The full sample-validation pass is kept.
4. **Hedged stalled reads (P7).**
   - `HedgedResearchTransport` duplicates an idempotent watch-page, player-API or oEmbed read
     that has not completed within 3.5 s / 2.5 s / 2.5 s.
   - The first response wins and the loser is cancelled. There are at most two attempts, and no
     hidden retry of a fast failure.
   - Media transfers never pass through it.
5. **Explicit public-client order (P6).** `YouTubeClient(publicClientOrder = …)`. The default is
   unchanged: TV first, then VisionOS.

### Veyra

1. **Sectioned playback reads (P2).**
   - `AndroidDeckDataSources` serves a googlevideo object of known length to Media3 as one
     DataSpec, while reading it from the network in exact ≤8 MiB sections.
   - A section must end exactly at its declared end before the next one opens.
   - Small explicitly bounded reads, local files and other hosts are passed through unchanged.
2. **Single-use inspection reuse (P3).** `YouTubeInspectionReuse`, D014.
3. **Warm the next music item (P4).**
   - `QueueAcquisitionCoordinator.warmMusicNext` runs once per playing music track, 40 s before
     its end.
   - It selects the next neighbour if still pending (the existing single-neighbour path) and runs
     one inspection, which the reuse cache keeps for the transition.
   - It never runs offline, for podcasts, or for a next item with a saved local file.
4. **Countdown (P8).**
   - The source publishes the declared availability deadline it is waiting for
     (`YouTubeMediaSource.playbackWait`).
   - Player status shows "Starting in N s…" / "يبدأ التشغيل خلال N ث…" with localized digits,
     instead of an open-ended "Preparing audio…".

D005 identity rules, declared availability waits, restrictions and Room-only persistence are
unchanged. No integrity token, alternate client or restriction workaround was added.

## Download throughput, 39,819,429-byte AAC item `SpkDA5qXUaw` (real kmpYT path)

| Run | Configuration | Body throughput | Result |
|---|---|---:|---|
| `p1_c0_a` | single full-extent request (previous behavior) | **0.032 MB/s** | 1.78 MB in the 60 s limit |
| `p1_c1_a` | 1 connection, 8 MiB sections | 3.03 MB/s | complete, transfer 13.1 s |
| `p1_c1_b` | 1 connection, 8 MiB sections | 1.38 MB/s | complete, transfer 28.8 s |
| `p1_c2_a` | 2 connections | 3.07 MB/s | complete, transfer 13.0 s |
| `p1_c4_a` | 4 connections (before no-copy) | 3.06 MB/s | complete, transfer 13.0 s |
| `p1_c4_b` | 4 connections | 3.12 MB/s | complete, transfer 12.8 s |
| `p1_c4_c` | 4 connections | 3.15 MB/s | complete, transfer 12.6 s |

- **About 95× faster.** The 39.8 MB file now takes ~13 s of transfer instead of the ~20 minutes
  extrapolated from 32 KB/s.
- **This link saturates near 3.1 MB/s,** so 2 or 4 connections match one healthy connection.
  Parallelism showed its value as reliability: one single-connection run fell to 1.38 MB/s, while
  three 4-connection runs stayed within 3.06–3.15 MB/s. **Default: 4 connections, 8 MiB.**
- **Post-transfer time:**
  - Download span minus transfer: 8.0 s (`c4_a`, copies) → 5.5–7.0 s (`c4_b`/`c4_c`, link and
    rename).
  - What remains is kmpYT's full sample-validation pass. It was kept deliberately and could be
    revisited only with Veyra's own decode validation as the sole check.

## Playback startup, approved recording `5VC9p1nzmks` (no declared wait)

| Run | Cold resolution | Native ready | **Cold tap → ready** | Replay: resolution | Replay: native ready |
|---|---:|---:|---:|---:|---:|
| 2026-11-15 `prewarm-a` | 2,222 ms | 720 ms | **2,942 ms** | 2,373 ms (re-inspected) | — |
| 2026-11-15 `prewarm-b` | 2,196 ms | 730 ms | **2,926 ms** | 2,018 ms (re-inspected) | — |
| `p1-prewarm-a` | 2,158 ms | 560 ms | **2,718 ms** | **40 ms** | 187 ms |
| `p1-prewarm-b` | 2,324 ms | 509 ms | **2,833 ms** | **37 ms** | 188 ms |
| `p1-prewarm-c` | 2,185 ms | 429 ms | **2,614 ms** | **32 ms** | 173 ms |

- Native ready improved by ~0.2–0.3 s with sectioned playback reads.
- A repeat play or download of a recently inspected item skips inspection: ~0.2 s total instead
  of ~2.4 s plus a new declared wait.
- For items whose response declares a wait, P4 overlaps it with the previous track for queue
  playback. Not separately timed on device.

## P6 client-order evaluation (inspection only, no media requests; `p6_order_a`)

8 library recordings × 2 rounds, alternating which order ran first:

| Order | Success | Progressive AAC | Median | Mean | Max | Declared wait observed |
|---|---:|---:|---:|---:|---:|---:|
| TV first (default) | 16/16 | 16/16 (TVHTML5) | 1,815 ms | 1,909 ms | 2,586 ms | 14/16 |
| VisionOS first | 16/16 | 16/16 (VISIONOS) | 1,642 ms | 1,665 ms | 2,073 ms | 0/16 |

**Decision: the default stays TV-first.**

- On the technical criteria the owner set (compatibility and latency), the orders differ by
  ~170 ms median; both were fully compatible here.
- Switching would mostly change whether a pre-roll wait is declared. That is exactly the effect
  the owner asked not to optimize for.
- The order is now an explicit, tested kmpYT option. Changing it is an owner decision, and any
  wait the answering profile declares would still be honored.

## Executed checks

- **kmpYT JVM, focused:**
  - `SectionedHttpTransferTest` 6
  - `HttpDownloadTransferTest`, `HttpResponseIntegrityTest`, `FileCheckpointIntegrityTest`,
    `ProgressiveHttpRangeTest`
  - `HedgedResearchTransportTest` 5
  - `YouTubeObservedProgressiveAlternativesTest` (with client order)
  - `YouTubeClientTest`
  - All pass.
- **Veyra shared JVM:**
  - `dev.veyra.source.youtube.*`: 77 tests including `YouTubeInspectionReuseTest` 5.
  - `QueueAndBatchAcquisitionTest`, with 2 new warm-next tests.
  - `PlaybackControllerTest` 31.
  - All pass.
- **Physical device:** `AndroidDeckDataSourcesTest` 6/6, including the new sectioned-read test.

### Full regression (final tree)

- **kmpYT:**
  - JVM: 516 tests, 8 failures, 3 skipped. All 8 are the known desktop Chromium-runtime tests
    (no Chromium on this Mac); unchanged code.
  - iOS simulator arm64: `IosProgressiveRangeTest` 4/4, `IosDownloadResumeTest` 15/15,
    `IosYouTubeDownloaderTest` 7/7. The range test's fixture used a 12-character media ID the
    policy rejects; it was corrected to 11 characters.
- **Veyra:**
  - Shared JVM: **1,268/1,268**.
  - `:shared:testDebugUnitTest`, `:composeApp:testDebugUnitTest` and `:composeApp:lintDebug`:
    pass.
  - iOS Kotlin compile (simulator arm64 and device arm64) and the Xcode device build: pass.
- **Fixed during the full run:**
  - The hedged transport hung when a transport itself threw `CancellationException`; it now
    forwards that as the attempt's result (new test).
  - Warm-next competed with crossfade staging for the single neighbour-prefetch slot; it now
    stands aside when crossfade is active (`AcquisitionAdmissionStageCasTest` passes).
- **Not caused by this work:** `:composeApp:desktopTest` 493 tests, 17 failures. They are missing
  or duplicated UI nodes on library, settings, podcast and provenance screens this session did not
  change, e.g. `play-ui-fixture-second`, `settings-licenses`, duplicated "Resume at". They fail
  identically with warm-next disabled, and the traced test makes no network call in isolation.
  They belong to the uncommitted redesign work of earlier sessions and remain open.

### Installs

- **Android (Huawei):** debug APK `09d4a601cd40a90d…` (SHA-256 prefix) installed in place with
  data kept; cold `am start` OK, 3,963 ms.
- **iPhone ("abdelrahman's iPhone", iPhone 17):**
  - Debug device build with Xcode.app; the pinned Xcode_26.0.1 is not installed.
  - Development-signed with the existing identity and profile.
  - Installed in place with `devicectl` and launched. No on-device timing yet.
- **kmpYT pin:** `7b14f6859b65cc7bc8ff9b2d21a0a61e7b19bf69` (branch
  `fix/android-media-transfer-startup`); `scripts/bootstrap.sh` passes.

## Limits

- One device, one Wi-Fi network, one large item and one startup recording. There was no
  mobile-network, iOS-device or desktop timing.
- iOS sections are covered by MockEngine tests only. AVPlayer streaming is unchanged.
- The P4 trigger in `PlaybackController` is covered through the coordinator tests and
  compilation, not by an end-to-end queue-transition timing.
- The P8 countdown is compiled for desktop and Android and was not visually verified on device.
- P5 (removing the watch page) was not implemented. Every later request is built from the WEB
  client contract the watch page supplies (visitor data, signature timestamp, player URL).
  Replacing it requires creating an undocumented anonymous client context
  (`/youtubei/v1/visitor_id`). P7 hedging addresses its stall outliers instead.

## iPhone playback repair: declared-wait videos (same day, physical iPhone 17)

Owner report: `_VMJ4YSPx3A` played on Android but failed on iOS with
`YOUTUBE_STREAM_ACCESS_REQUIRED`. The cause was traced with a redacted, debug-only console
(names, counts, statuses and byte ranges only; no URLs).

1. **The wait was missing.**
   - iOS prefers SABR, whose track comes from the WEB watch response. That response declared
     no wait, while the TV response for the same visitor declared a 5 s start ad.
   - **Fix (kmpYT `9ee319a`):** every track of one inspection gets the longest declared
     deadline. A wait can only lengthen.
2. **SABR was still refused** (`SABR_RESOURCE_NOT_AUTHORIZED`) after the 5 s wait was honored.
3. **Direct progressive in AVPlayer fails** with -11800 / -12190 (open-ended request refused;
   2026-11-11 record).
4. **Fix (kmpYT `7f4a6dd`, Veyra):**
   - The iOS owned loopback now serves a single progressive audio object as exact ≤1 MiB range
     windows, with caller authorization and download policy on every request and redirect.
   - iOS uses it whenever the source declares a pre-roll wait; SABR remains first otherwise.
5. **Result:** the video played to the end on the iPhone, with 22 exact range responses, all
   HTTP 206.

**Clearer errors.** `RECOVERY_REQUIRED` playback-selection details no longer show "Saved data
needs recovery":
- `acquisition_selection_unplayable`: YouTube's first result for the title is not a playable
  video (D005), for example "This video is hilarious - Apollo Qeshta".
- Collection first results and transient selection or native states each get their own message.
- Data and download integrity cases keep the recovery text.

**Tests:** shared JVM 1,269/1,269; iOS simulator `IosProgressiveLoopbackTest` 2/2 and
`IosProgressiveRangeTest` 4/4; `AppStringsTest`; Android and iOS compile.

**Known gap:** a response that declares a start ad without a duration computes no wait
(`waitMs=0`). Such an item keeps the SABR preference and may still be refused on the first
attempt.

## iPhone titles and first-result reliability (same day, owner-confirmed)

The owner reported English titles on iOS and intermittent "first result isn't playable" errors.

- **Cause 1: translated titles.** iOS requests carried the device language. YouTube then
  returned creator-supplied English titles, and Veyra searched with them.
  - kmpYT now sends `Accept-Language: *` on every service (`cc283d2`; an earlier wiring left
    the parameter shadowed).
  - The iOS session keeps no cookies (`cf9cf11`).
  - Debug log afterwards: every search `hl=ar`.
- **Cause 2: a leading suggestion shelf.** Some searches start with a `gridShelfViewModel`
  shelf. D015 (owner decision) skips such shelves; collection shelves, ads and unknown cards
  stay strict.
- **Cause 3: SABR refusals.** SABR was refused for responses that declare a pre-roll without a
  duration. iOS now prefers the progressive loopback, with SABR as fallback.
- **Owner confirmation:** "works now". The log shows no playback failures and 82 loopback
  requests (54 × 206, 28 × 302 followed with re-validation).

**Remaining limits:**
- Library items saved earlier keep their translated titles.
- About 10 s from tap to native preparation on iOS, of which 5 s is the declared wait.
