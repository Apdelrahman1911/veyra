# Playback preparation and download performance investigation — 2026-11-15

Owner question: Snaptube prepares and downloads the same content much faster on the
same phone and network. What exactly prevents Veyra from matching it? No product code
was changed for this investigation. Device: Huawei AQM-LX1, Android 10, Wi-Fi. Probes
and redacted receipts: ignored `.work/evidence/prep-latency-20261115/`. No signed URL,
header, response body or media was stored.

Evidence labels used below:

- **Verified:** measured on the phone today, or read in current source.
- **Prior record:** documented by the 2026-11-10/11 investigations; not re-tested today.
- **Inference:** a reasoned explanation that cannot be directly observed.
- **Unverifiable:** would require Snaptube's current source or internal behavior.

## 1. Root cause of KB/s downloads — verified

**googlevideo paces a single progressive request whose `Range` is larger than about
10–20 MiB to about 32 KB/s (~2× the audio bitrate). kmpYT always requests the entire
remaining file in one range.**

`HttpFileTransfer.boundedProgressiveRangeEnd` (jvmAndroidMain) sets the range end to the
declared object length. On 2026-11-11 this replaced an unbounded GET and fixed small files
(a 1.2 MB file went from 32 KB/s to 2.6 MB/s). For large files the full-length range
still falls into the paced regime.

### Same authorized URL and headers

Only the range end changes; 1 MiB is read per request. Item `SpkDA5qXUaw`:
39,819,429 bytes, AAC 129 kbps, progressive, TVHTML5 client.

| Range end | Runs | Body throughput |
|---|---:|---:|
| 1 MiB | 2 | 2,796 / 2,788 KB/s |
| 4 MiB | 1 | 2,139 KB/s |
| 10 MiB | 2 | 2,330 / 2,235 KB/s |
| 20 MiB | 1 | 32 KB/s |
| full file | 2 | 32 / 32 KB/s |

### Real kmpYT download path, same code and client

| Item | Size | Body throughput |
|---|---:|---:|
| `KW2yXBRFO7c` | 3.7 MB | ≈3.0 MB/s (complete in ~1.4 s of transfer) |
| `AM5jgcj1TJc` | 3.5 MB | MB/s; complete |
| `SpkDA5qXUaw` | 39.8 MB | 28–34 KB/s, steady for the 60 s limit (1.68 MB) |

### The app's own history

The Room `download_attempts` table agrees:

- Today's 39.8 MB attempt moved 49,152 bytes in 21.4 s (≈2.3 KB/s), then was paused
  and cancelled.
- The 2026-11-10 attempts (old build, before the 11-11 range fix) averaged 22–123 KB/s.

### Ruled out

- **Format.** AAC itag-140-class progressive is chosen; it is not SABR and not a
  low-efficiency container.
- **Storage I/O.** The same writer, buffer and periodic fsync sustained ≈3 MB/s.
- **Room/UI progress writes and Ktor.** The range probe bypasses them and reproduces the
  32 KB/s.
- **Network.** The same URL is fast with a smaller range.

The exact threshold (somewhere in (10, 20] MiB) is an observation, not a published
YouTube contract.

yt-dlp's default `http_chunk_size` of 10,485,760 bytes is the standard public practice for
this. It uses ordinary HTTP Range requests, which the server supports by design
(HTTP 206); it is not a restriction bypass.

## 2. The ~5 s preparation wait

### Verified: the wait is exact and per-response

In 15 of 16 cold resolutions, playback was released 5,004–5,012 ms after receipt of the
**public-client (TVHTML5) player response**. One resolution of `-RRHSQcMqTg` had no wait,
but a later one did, so ads are decided per request, not per video. Downloads wait the same
way: the first byte arrived ~5 s after the player response.

### Source of the wait

- **Verified (code):** `SourceAvailability` reads the skip time from the response.
- **Prior record:** the 2026-11-10 capture of the same kind of response contained
  `AD_PLACEMENT_KIND_START` with `instreamVideoAdRenderer.skipOffsetMilliseconds = 5000`
  and `length_seconds = 10`. Earlier requests returned HTTP 403, and the same URL later
  returned 200.
- **Not done today:** reading today's TV responses directly. A standalone script imitating
  the client from the Mac was refused by the session's permission policy and was not
  worked around.

### Why this response

- **Verified:** the browser watch page (WEB) declares only SABR audio, with no plain URL.
  So kmpYT requests progressive alternatives from the pinned public profiles. The TV
  profile answers first and is selected; its response is the one that declares the ad.
- **Verified (2 of 4 songs fetched from the Mac):** the WEB page declared a start
  placement with a `clientForecastingAdRenderer`, which kmpYT does not treat as a wait.

### Snaptube

**Unverifiable.** There is no evidence that Snaptube bypasses anything. Possible honest
explanations, none confirmed:

- It uses different client profiles whose responses declare no pre-roll.
- It starts resolution earlier, for example from list or detail views, so the wait has
  already elapsed when the user taps.
- It reuses still-valid URLs.
- It uses integrity tokens that Veyra does not have.

Do not copy profile choices *because* they avoid ads without an explicit owner decision.

## 3. Pipeline comparison

kmpYT's relationship to Snaptube is narrower than "based on Snaptube code":

- kmpYT's README states that no code, binaries or assets from the SnapTube APK are
  included.
- Client profiles are pinned to yt-dlp 2026.10.26.
- Only the SABR/UMP protocol state machine cites observed behavior of "the original APK"
  (for example `rjb`, `UmpRequestHelper`).
- No Snaptube-derived source exists in this repository or elsewhere in the owner's
  Projects folder.

A line-by-line comparison with Snaptube is therefore **impossible here**. What can be
compared is Veyra against the documented public practice that Snaptube-class tools
follow:

| Aspect | Veyra / kmpYT today | Common practice (yt-dlp documented) | Impact |
|---|---|---|---|
| Progressive download | one full-length Range | ≤10 MiB Range chunks | **KB/s on files > ~10–20 MiB** |
| Concurrency | one connection per track | optional parallel chunk fragments | further speedup possible |
| Playback (Android Media3) | `AndroidDeckDataSources` bounds the DataSpec to the full remaining length | chunked ranges | long tracks stream at the paced rate (still ≥ real-time); first byte ~0.35 s later (474–527 ms vs 51–182 ms) |
| Clients | WEB page, then TV (VISIONOS fallback), anonymous | similar anonymous profiles | TV response carries the pre-roll declaration |
| Watch page | full 1.3 MB HTML per resolution | often only the player API, with reused visitor/STS context | ~1.2–1.6 s per cold play, stall outliers (5.1 s, 28.5 s seen) |
| Player program | now stored and prewarmed (2026-11-15) | cached | fixed |
| Media URL reuse | never; each play/download inspects again and gets a new wait | often reused within `expiresInSeconds` | a download right after playing pays another ~5 s |

## 4. Prioritized fixes (supporting evidence; none implemented yet)

1. **Chunked progressive downloads (≤10 MiB ranges).**
   - Change: in `HttpFileTransfer`/`RangedFileTransfer` (both kmpYT checkouts), request
     successive ranges ≤10 MiB within one transfer.
   - Keep: the existing resume validators, prefix digest, exact-size and 206 checks.
   - Expected: the 39.8 MB file in ≈15–20 s instead of ≈20 min.
   - Evidence: the range table, 3 runs per shape, repeatable.
   - Optional later: 2–4 parallel chunks.
2. **Chunked playback reads on Android.**
   - Change: a DataSource that serves a long progressive object as consecutive ≤10 MiB
     requests transparently. Do not simply shorten the DataSpec: the extractor would treat
     an early end as end of media.
   - Benefit: faster buffering and seeking on long tracks, ~0.3 s earlier first byte.
   - iOS AVPlayer issues its own ranges; check separately.
3. **Reuse the already-cleared media URL** for download-after-play and repeat plays
   within the source-declared validity.
   - Removes a full extra inspection and the ~5 s wait. The wait is still honored once
     per response.
   - This reverses kmpYT's "never cache media URLs" design, so it needs an owner decision.
4. **Overlap the wait.** Resolve the next queued item early, and optionally the item whose
   detail screen is open. The wait elapses in the background. Costs extra unused requests.
5. **Retry a stalled watch request.** If it hasn't completed within a few seconds, issue
   the same request in parallel and take the first result. Targets the >12 s cases.
6. **Show the wait.** Countdown UI ("Starting in 5 s") instead of an open-ended
   "Preparing audio". Requires exposing the deadline from `preparePlayback`.
7. **Investigate skipping the watch page** when a recent visitor/STS context exists, using
   only the player API.
   - Unproven: affects visitor and context behavior.
   - Must keep D005 identity rules.

## 5. What is genuinely imposed by YouTube

- **Verified:** the pacing of large single ranges (server behavior; the client chooses
  smaller ranges).
- **Prior record and verified:** the declared pre-roll timing, enforced with 403 before
  the skip offset.
- **Inference:** the server-side reason for either behavior.

Neither is a reason for KB/s downloads. Fix 1 addresses the downloads with standard HTTP;
the pre-roll wait can be hidden or avoided only through fixes 3–4 or a client-choice
decision.
