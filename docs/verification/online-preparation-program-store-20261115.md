# Online preparation: persistent player program and launch prewarm — 2026-11-15

## Outcome

On the owner's physical Android phone (Huawei AQM-LX1, Android 10, arm64), cold
online preparation of the approved recording `5VC9p1nzmks` dropped from
**5.6–7.4 s to 2.9 s** from tap to native playback-ready, measured with the
existing opt-in source/native probe. The same change applies to downloads, because
downloads use the same source inspection. This is a measured improvement on one
device, one network and one recording, not a universal guarantee.

## Cause

A cold process ran every step before the first media request:

1. watch page GET (~1.3 MB HTML)
2. player program GET (~3.06 MB)
3. Kotlin analysis of that program (bounded and context passes, ~2.1–2.5 s on this phone)
4. WebView JavaScript runtime initialization with the full program (~0.4–1.0 s)
5. player API request

The program URL is versioned and changes only when YouTube ships a new player.
kmpYT nevertheless kept it only in memory (`cacheEntries = 1`), so every app start
paid for steps 2–4 again.

## Change

kmpYT, applied to both `third_party/kmpYT` (consumed) and `.work/kmpYT`
(byte-identical working mirror):

- `YouTubePlayerProgramStore`: an optional interface in commonMain. It stores only
  the exact text of a response-declared player program, keyed by its URL. It never
  stores watch or player responses, media URLs, cookies or transform results.
- `YouTubePlayerProgramLoader`, `YouTubePlayerProgramCache` and `YouTubeClient`:
  - An in-memory miss now checks the store before the network.
  - A stored program is compiled and initialized exactly like a downloaded one.
  - An unusable entry falls back to the declared network source.
  - Only programs that produced usable transforms are written.
  - `YouTubeClient.prewarm()` prepares the `latest` stored program **without any
    network request**. It only accepts a stored URL that still validates as a
    public `www.youtube.com/s/player/.../base.js` program.
  - A changed player is simply downloaded during the next inspection.
- `FileYouTubePlayerProgramStore` (jvmAndroidMain):
  - private directory with entries of the form header + URL + SHA-256 + text
  - mismatched or corrupt entries are removed
  - writes use a temporary file and an atomic rename
  - a `latest` pointer, and at most 2 entries by modification time
- Android: `createAndroidYouTubeClient` uses `cacheDir/kmpyt-player-programs`. The
  OS may clear it; that only costs one later download.
- iOS: `IosYouTubePlayerProgramStore` uses the same format with CommonCrypto
  SHA-256 and POSIX rename, and `createIosYouTubeClient` uses
  `Caches/kmpyt-player-programs`.
- Desktop JVM is unchanged: its factory has no persistent store yet.

Veyra:

- `YouTubeMediaSource.prewarm()` is offline-only and returns false when closed or
  when nothing is stored.
- `AppContainer` calls it once, 1.5 s after initialization is `Ready`. It sends no
  request, so the app's offline policy is unaffected.

D005 is unchanged: every playable item still resolves through kmpYT with the full
title and the first result. No authorization, integrity or SABR boundary changes.

## Physical-device measurements

The probe is in the ignored `.work/evidence/prep-latency-20261115/kotlin/`.

- Each run is a fresh instrumented process, so the first resolution is genuinely cold.
- The store and prewarm are switched by test arguments.
- Each run includes a full complete-download measurement.
- Receipts are redacted: there are no URLs, headers or program text.

| Run | Cold source resolution | Program HTTP | JS runtime init | Native ready | **Tap → ready** | Launch prewarm (background) |
|---|---:|---:|---:|---:|---:|---:|
| baseline-a (no store) | 5,825 ms | 495 ms | 986 ms | 1,575 ms | **7,400 ms** | — |
| baseline-b (no store) | 4,964 ms | 428 ms | 385 ms | 617 ms | **5,581 ms** | — |
| store-hit | 4,732 ms | 0 | 376 ms | 735 ms | **5,467 ms** | — |
| store-hit-2 | 4,757 ms | 0 | 321 ms | 685 ms | **5,442 ms** | — |
| prewarm-a | 2,222 ms | 0 | 0 | 720 ms | **2,942 ms** | 2,545 ms |
| prewarm-b | 2,196 ms | 0 | 0 | 730 ms | **2,926 ms** | 2,443 ms |

- The disk store alone removes the program download. It only removes ~0.5 s on this
  fast Wi-Fi, but more on a slow network: one earlier run measured 9.4 s for that
  request.
- Prewarm removes analysis and runtime startup from the tap path too, leaving the
  watch page plus the player API request.
- The remaining cold cost is about the same as the previous warm resolution
  (~2 s).
- Complete download, including preparation, stayed at 2.8–4.2 s in every run.

### Invalid runs, preserved under `frozen-runs/`

**This Huawei build freezes an app process that has no visible component.** All
threads sit in kernel `D` state at 0% CPU until it is shown again.

- The first probe runs ran without Veyra on screen and recorded 190.8 s and 85.5 s
  "cold" resolutions. Their measured stages added up to only a few seconds; the rest
  was frozen time.
- Valid runs show Veyra's own activity in the probed process and re-show it on
  every 3-second check (`foregroundChecks` in each `*-exit.json`). The only
  not-in-front checks fell inside the 8-second pre-measurement settle and at test
  exit.
- One Huawei USB-mode dialog was dismissed with Back; no USB setting was chosen.
- This freezing is a device policy outside Veyra. It is relevant to any future
  background-preparation claim on such devices.

A separate analysis micro-probe on the same phone measured the bounded compiler at
516 ms (token limit) and the context analysis at 2,057 ms and then 1,869 ms. This
confirmed that the analysis itself is not pathological.

## Executed checks

- kmpYT JVM, focused, all passing:
  - `FileYouTubePlayerProgramStoreTest` 4
  - `YouTubePlayerProgramStoreTest` 6
  - `ContextYouTubePlayerPreparationTest` 5
  - `YouTubeInspectionProgramReuseTest` 7
  - `YouTubeClientTest` 8
- kmpYT iOS Simulator arm64: `IosYouTubePlayerProgramStoreTest` 4/4. The first
  attempt failed one invalid-UTF-8 deletion case; it was fixed and rerun.
- Veyra shared JVM: 71 `dev.veyra.source.youtube.*` tests, including the new
  `prewarmSendsNoRequestAndIsHarmlessWithoutAStoreOrAfterClose`;
  `PlaybackControllerTest` 31.
- Compilation:
  - composeApp desktop, Android debug, iOS simulator arm64 and iOS arm64
  - shared iOS arm64
  - kmpYT iOS simulator and arm64
- Full suites:
  - **shared JVM: 1,261 tests, 0 failures**
  - **kmpYT JVM: 504 tests, 8 failures, 3 skipped.** All 8 failures are in
    `DesktopRuntimeCleanupTest`, `DesktopRuntimeDiagnosticsTest` and
    `DesktopYouTubeJavaScriptRuntimeTest`. They stop at "Offline JavaScript execution
    requires a local Node.js and Chromium installation": this Mac has Node.js but no
    Chromium. That desktop runtime code is unchanged; this is an environment
    limitation, not a passing result.
- Final owner installation:
  - Debug APK SHA-256
    `847ef508ae6cad3e5d74df6f99c1a30413976d31d2366b6961ea0691ef3d1ff3`, installed in
    place with the library and downloads kept.
  - Cold `am start`: Status ok, 3,747 ms.
  - Process healthy (`S` state), no crash in logcat.
  - The real app's launch prewarm read the stored program at 19:45:42, about 12 s
    after the cold start, measured by the store's access timestamp.
  - In-app tap-to-Playing on that build was not timed separately from the probe.

## Limits

- One device, network and recording.
- No audible-output, iOS-device, desktop, or large network-matrix timing.
- The first preparation after a player-version change still downloads and analyzes
  the new program.
- If the OS clears the cache, the next cold start downloads it once.
- Debug local build with the reviewed, uncommitted kmpYT source, as in the earlier
  reports. Not a clean-checkout or pinned-release artifact.
- Nothing is committed and the kmpYT pin is not updated.
