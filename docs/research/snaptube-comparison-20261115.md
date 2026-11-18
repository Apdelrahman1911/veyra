# Snaptube vs kmpYT: preparation, pre-roll wait and downloads — 2026-11-15

## Method and scope

The owner supplied `snaptube.apk` at the repository root:

- SHA-256 `cb617d41…f8a6a`, 30.9 MB.
- **Untracked. It must not be committed.** Add it to `.gitignore` or move it under `.work/`.

How it was analyzed:

- Statically only. The APK was unpacked into ignored `.work/snaptube-apk/` and its six
  dex files were disassembled with the Android SDK's `dexdump`. String tables and code
  around them were read.
- Nothing from the APK was executed. No decompiler was downloaded. No Snaptube code is
  copied into Veyra or kmpYT; this note records behavior and identifiers only.
- Class names quoted below are as they appear in the binary. Single-letter `o/*` names
  are obfuscated.

Evidence labels:

- **APK-verified:** read in the disassembly.
- **Device-verified:** measured on the owner's Huawei today; see
  `playback-download-performance-20261115.md`.
- **Inference:** a reasoned explanation not proven by the bytecode.
- **Unverified:** not established.

Static analysis shows what code *can* do, not which branches run for a given user or remote
configuration. Snaptube reads many decisions from remote config (`PageContext`, `k55`).

## 1. Downloads: why Snaptube gets MB/s

**APK-verified.** Snaptube's YouTube downloads go through `com.wandoujia.mtdownload`.
`o/v2b` extends `mtdownload.d` (a section `Worker`), and `mtdownload.Dispatcher` is the
scheduler.

- **Connections:** `Dispatcher.y()` reads `download_threads_per_task`, **default 4**,
  clamped to 1–10.
- **Adaptive:** `Dispatcher.A()` logs "schedule increase/reduce thread, softLimit" and
  re-plans sections while running.
- **Section size:** remaining bytes ÷ thread count, with a minimum of 0x8000 (32 KB). Each
  `Worker` fetches its own `start..end`.
- **How a range is requested:** the googlevideo worker (`o/v2b.I`) writes a `range=a-b`
  **URL query parameter** (default `0-`) and keeps an `&rn=` request counter. The same
  class also handles `Range: bytes=`, `Content-Range` checks, `If-Match`/`ETag` resume
  and POST.
- **Older engine:** `com.wandoujia.download` (`DownloadConfigInfo.a()` returns 3 or 1
  blocks) has `SingleThreadBlockDownloadTask` blocks with `Range: bytes=start-end`.
- **Setting:** `setting_enable_multi_thread_download` is a user setting.

**Device-verified consequence.** For the owner's 39.8 MB song, 4 workers means ~9.95 MB per
section. That is below the ~10–20 MiB size above which googlevideo paces a single range to
~32 KB/s (measured: ≤10 MiB ranges ran at 2.1–2.8 MB/s; 20 MiB and full-file ranges at
32 KB/s). kmpYT requests the whole file in one range. **This fully explains KB/s vs MB/s.**
Snaptube's speed comes from ordinary, smaller, parallel ranges, not from a bypass.

## 2. The pre-roll wait: Snaptube waits too

**APK-verified.**

- `o/z2d` ("YtbVideoAdDetector") reads `adPlacements` (`AD_PLACEMENT_KIND_START` +
  `instreamVideoAdRenderer`) and `adSlots` (`SLOT_TRIGGER_EVENT_BEFORE_CONTENT`,
  sequential layouts). It takes `skipOffsetMilliseconds` and the ad's `length_seconds`, uses
  the skippable time rounded up to whole seconds, and sums it. This is essentially kmpYT's
  `SourceAvailability` rule.
- `Parser.parsePlayerResponse` stores that value. `sites/Youtube.e0` sets
  `Format.setAvailableAt(elapsedRealtime + seconds·1000)` on **every** format. The seconds
  value is a **remote-config default (`qu4.n()`) unless ads are declared**.
- The player (`playerv2/ExoPlayerImpl.k0`, "checkDelay time") and the video download task
  (`o/yyb.D2`, "download wait start") both delay by the remaining time.

So Snaptube honors the same server-declared wait, and is configurable to wait even without
an ad. The claim "Snaptube doesn't have the 5 s wait" is not supported. What differs is
*when* and *where* the wait is spent (§3).

## 3. Preparation pipeline differences (APK-verified unless marked)

| Aspect | Snaptube | kmpYT/Veyra today |
|---|---|---|
| First request | player API directly. The HTML watch page (`YOUTUBEWEB_HTML_MOBILE`) is a late fallback | always GET the 1.3 MB watch HTML first (1.2–1.7 s; stalls of 5.1 s and 28.5 s seen on device) |
| Visitor context | `/youtubei/v1/visitor_id` JSON request (`YoutubeParsingHelper`), plus a consent cookie `SOCS=CAE=` | read from the watch HTML |
| Default client order (not logged in) | `VISIONOS`, `ONESIE_MWEB`, `WEB_EMBEDDED`, `WEB_MUSIC_API`, `YOUTUBEAPI` (ANDROID, conditional), `YOUTUBEWEB_HTML_MOBILE`, `MWEB`, `REEL_API_ANDROID`; `TV_DOWNGRADED` only when logged in; remote `extractor` can force one client | watch page (WEB), then pinned **TV first**, VISIONOS second |
| Client catalogue | ~20 profiles (`ClientConfig`), including `ANDROID_VR`, `IOS_MUSIC`, `ANDROID_TESTSUITE`, `MEDIACONNECT` and four `ONESIE_*` | 2 pinned public profiles |
| Onesie | `ONESIE_*` clients and `onesieHotConfig` from a `tv_config` response. Onesie returns the player response together with initial media | none |
| Integrity | `potoken/BotGuardPoTokenProvider` + `PoTokenWebView` mint **PO tokens** by running BotGuard in a WebView, with a failure circuit breaker. The `youtube/getpo_precoded` asset is likely related (inference) | none, by design |
| SABR | `SabrFormatStrategy` = `DISABLED` / `STABLE_FALLBACK_SABR` / `FORCE_SABR` | SABR when no progressive URL exists |
| Decipher code | `YouTubeSignCodeServer$YoutubeSignCode` model (`code`, `function`, `needUrlDecode`, `setSignEncrypted`). **Unverified** whether it is delivered from a server | player script downloaded, stored (new) and analyzed on device |
| Pre-resolution | the playback logger records `is_url_preresolved` and `has_buffered_target_size` for `AUTO_PLAY` / `SCROLL_PLAY` feeds: URLs are resolved and buffered **before** the tap. The in-app browser injects `assets/js/loader.js` to intercept YouTube page playback | resolution starts on tap |

**Inference: why Snaptube *feels* faster.**

1. The wait elapses during pre-resolution or browsing, before the tap.
2. It skips the watch HTML request.
3. With VISIONOS first, it may receive fewer pre-roll declarations than kmpYT's TV-first
   order. Unverified: today 15/16 pre-roll declarations came from kmpYT's TV responses,
   and VISIONOS responses were not sampled.
4. Downloads use smaller, parallel ranges.

## 4. Re-evaluated and prioritized plan

None of this is implemented yet. kmpYT changes go to both `third_party/kmpYT` and the
private `Apdelrahman1911/kmpYT`, then the pin is updated.

**P1 — Segmented, parallel progressive downloads (kmpYT `HttpFileTransfer`).** Confirmed
as the top fix and refined by Snaptube's design.

- Split each file into sections no larger than 8 MiB (below the measured threshold, with
  margin).
- Run 4 workers by default, at most 8, each sending a bounded `Range: bytes=a-b`.
- Write each section at its offset; keep per-section resume validators (`If-Range`/ETag),
  exact 206/Content-Range checks, the final size and hash, and the existing atomic
  finalization.
- Fall back to sequential sections on any 200/ignored range.
- Acceptance: the 39.8 MB item in under ~30 s on this phone (currently ~20 min), plus
  regression tests for resume, interruption and mismatch.
- The `range=` query form is an alternative; keep the header form unless a test shows a
  difference.

**P2 — Android playback reads in sections (`AndroidDeckDataSources`).** A DataSource that
transparently serves the object as consecutive ≤8 MiB requests, so long tracks buffer and
seek at full speed. About 0.3 s earlier first byte was measured. iOS AVPlayer: measure
separately.

**P3 — Reuse a resolved, already-cleared URL** within its source-declared validity, for
repeat plays and download-after-play. This removes a full re-inspection and a new ~5 s wait.
Snaptube's preload and extract-store architecture does the equivalent. **Owner decision:**
it reverses kmpYT's "never cache media URLs" rule; keep the URLs in memory only, never in
Room.

**P4 — Pre-resolve before the tap** (Snaptube-verified pattern). Resolve the next queue item
and the item on an open detail screen, and optionally the top visible search result. The
wait elapses in the background. Bound the concurrency and cancel stale work.

**P5 — Drop the watch HTML from the hot path.**

- Use `/youtubei/v1/visitor_id` for visitor context (cache it per app session).
- Use the stored player program's signature timestamp, so the first request is the player
  API.
- Keep the watch page as a fallback for metadata and failure cases.
- Saves ~1.2–1.7 s and removes the stall outliers.
- D005 is unaffected.

**P6 — Client-order experiment (owner decision).**

- Measure on the device how often VISIONOS-first vs TV-first responses declare pre-roll and
  provide usable progressive audio.
- Any declared wait is still honored. Changing order "to avoid ads" is close to the
  restriction line, so adopt it only with your explicit decision and only if the profile is
  otherwise equivalent in correctness.

**P7 — Stalled-request hedge.** Duplicate a public GET or POST that hasn't produced headers
within a few seconds, and take whichever response finishes first.

**P8 — Wait countdown UI.** Expose the remaining deadline from `preparePlayback`.

**Not recommended without a separate owner decision:**

- **PO-token minting via BotGuard** (Snaptube does this). It executes Google's attestation
  challenge to obtain integrity tokens. It contradicts the current recorded decision
  (D005/D007: no integrity tokens) and carries policy and maintenance risk.
- **Onesie** — an undocumented protocol with significant implementation and maintenance
  cost.
- **Server-supplied decipher code** — it would add a Veyra-operated or third-party service
  to the trust chain.

## 5. Capability audit after implementation (2026-11-16)

Status labels: **done** (implemented and verified as recorded in
`verification/media-pipeline-performance-20261116.md`), **partial**, **not adopted** (with the
reason).

| Snaptube capability (APK-verified) | Veyra/kmpYT now | Status |
|---|---|---|
| Multi-connection sectioned downloads (`mtdownload`, 4 default, 1–10) | 4 connections, ≤8 MiB exact ranges, contiguous hashed prefix, fallback to one request (kmpYT JVM/Android); sequential sections on iOS | done; adaptive thread scaling not adopted (no measured benefit on a link saturated by one connection) |
| Ranged googlevideo reads during playback | `AndroidDeckDataSources` ≤8 MiB sections under one Media3 DataSpec | done (Android); iOS AVPlayer unchanged |
| Honors declared pre-roll wait for play and download | unchanged: kmpYT `SourceAvailability` | same behavior |
| Extract store / pre-resolved URLs (`is_url_preresolved`) | single-use in-memory inspection reuse (D014); detail views leave their inspection for the next play | done |
| Preload of upcoming items | warm the next music queue item 40 s before the end | done (resolution only; no media pre-buffer outside crossfade staging) |
| Player API without the watch HTML; `visitor_id` endpoint | watch page kept; stalled reads hedged | not adopted: creating an undocumented anonymous client context; hedging covers stalls |
| VISIONOS-first client order | configurable `publicClientOrder`, default TV-first | owner decision (see D014) |
| ~20 client profiles, `ONESIE_*`, `onesieHotConfig` | 2 pinned public profiles | not adopted: undocumented protocol, maintenance cost |
| BotGuard PO-token minting (`BotGuardPoTokenProvider`, WebView) | none | not adopted: integrity-token work, excluded by D005/D007 and the owner |
| Server-supplied signature code (`YouTubeSignCodeServer`) | player program downloaded, stored and analyzed on device | not adopted: adds an external service to the trust chain |
| SABR strategy (`STABLE_FALLBACK_SABR` etc.) | SABR used when no progressive URL exists | equivalent |
| Wait countdown | "Starting in N s" during a declared wait | done (compiled; not visually verified on device) |
