# Media transfer and preparation investigation — 2026-11-11

## Outcome and scope

Tested the real shared YouTube source, Android JavaScript runtime, Media3 engine,
and native kmpYT downloader on the preserved API 34 arm64 emulator. The initial
native-player UI work is complete for its scoped implementation and emulator
check; the full redesign remains open. Work was performed directly, without
agents or workers.

The download slowdown is reproducibly caused by the CDN pacing unbounded media
requests. The Android native player also issued open-ended requests. Both now
use an exact bounded byte range when the source declares a usable progressive
object length. The signed media URL is unchanged.

Cold source resolution is a separate remaining delay: a watch-page request,
player-program fetch, program analysis, native JavaScript initialization and
player API request precede native playback. This change does not optimize that
cold setup or promise constant throughput across videos/networks.

## Evidence isolating the transfer cause

The opt-in diagnostic used approved-channel recording `5VC9p1nzmks`, progressive
AAC/MP4, and the same authorized media URL within each A/B/A experiment. The URL's
`n` value was already transformed. There was no `ratebypass` parameter, and no
query parameters were added or changed. The diagnostic recorded statuses, byte
counts and timings, never signed URLs, headers, JavaScript payloads or media.

Each request read at most the first 256 KiB in memory:

| Request on the same URL | HTTP | Elapsed |
| --- | --- | ---: |
| Unbounded GET, first control | 200 | 9.102 s |
| `Range: bytes=0-262143` | 206 | 0.648 s |
| Unbounded GET, repeated control | 200 | 8.176 s |

A second control requested the entire declared object extent, rather than a
small prefix:

| Request on the same URL | HTTP | Time to read 256 KiB |
| --- | --- | ---: |
| Unbounded GET, first control | 200 | 8.898 s |
| `Range: bytes=0-1247701` | 206 | 0.827 s |
| Unbounded GET, repeated control | 200 | 9.000 s |

The actual unbounded native download reproduced approximately 32,592 bytes/s
without Room writes or UI rendering in its path. Changing only the range header
in the controls changed response pacing. This isolates request shape as the
cause for this source, rather than a UI progress-display cap.

## Changes

In both matching local kmpYT checkouts (`third_party/kmpYT`, consumed by Veyra,
and `.work/kmpYT`), the JVM/Android and iOS transfer implementations request the
exact remaining extent for Googlevideo progressive objects with a valid known
length. Unknown lengths retain the existing behavior. Resume validators,
redirect/header rules, full response/range checks, cancellation, and final byte
integrity checks remain in place. An initial full 200 response is accepted when
a server ignores this range optimization; an incomplete prefix is rejected.

Shared ephemeral playback metadata now forwards an optional declared
`contentLength`. It is populated only for a direct progressive object without a
remuxed resource, initialization part or segment list. Android's owned DataSource
converts an unbounded initial read or seek into the corresponding bounded
Media3 DataSpec. Local files, unknown lengths, other origins, and already bounded
reads retain their original specs. Deck retirement and native-handle ownership
are unchanged. There is no database or schema change.

## Complete-download measurements

All runs completed the same 1,247,702-byte recording through the real native
downloader, including fresh source preparation, private checkpoint creation and
completion. Temporary media/checkpoints were deleted by the diagnostic.

| Measurement | Before changes | Download fix | Final Android build |
| --- | ---: | ---: | ---: |
| Download including preparation | 40.529 s | 2.519 s | 3.892 s |
| Body sample throughput | 32.6 kB/s | 2.57 MB/s | 2.67 MB/s |
| Completed byte count | 1,247,702 | 1,247,702 | 1,247,702 |

The final run used approximately 9.6% of the baseline elapsed time (a 10.4×
speedup); the download-only run was approximately 16.1 times faster. These are
sequential single-recording measurements, not a universal speed guarantee.
The final run incurred another player-program fetch, so the preparation costs
differed between runs. Body sample rates exclude source setup and finalization.

## Playback preparation measurements

| Stage | Before changes | Download fix only | Final Android build |
| --- | ---: | ---: | ---: |
| Cold source resolution | 10.253 s | 8.037 s | 5.621 s |
| Warm source resolution | 1.241 s | 1.761 s | 1.197 s |
| Native ready after cold resolution | 3.594 s | 2.889 s | 1.354 s |
| Native ready after warm resolution | 1.609 s | 1.599 s | 0.057 s |

The final warm source-plus-native preparation took 1.254 s. The final cold path
took 6.975 s. These native-ready observations show improvement in this run;
network, connection reuse, program caching and emulator runtime vary. No
source-resolution improvement is attributed to the download or DataSpec change.

In the baseline cold resolution, watch HTTP took 3.512 s, player-program HTTP
0.983 s for 3,040,919 characters, and native JavaScript preparation 0.775 s.
The gap from completed program HTTP to native preparation was approximately
2.227 s, encompassing analysis/setup; it was not CPU-profiled, so it must not all
be labeled parsing CPU time. The player API request took 0.863 s. Actual `n`
transform calls took only 2–4 ms each. Other gaps include response processing and
plan construction. Warm resolution skipped the program fetch and VM preparation.

The final cold run still fetched a roughly 3 MB program and initialized its VM;
its transform calls took 1–3 ms. kmpYT keeps one versioned program in memory per
client, rather than persisting ready VMs across process restarts. This explains
why a fresh process or a different player program can still show a long preparing
state after the transfer fix. Further cold-program optimization remains open.

## Normal app flow

A second approved-channel recording (5:16) was selected through the normal
search screen. Its full player showed Playing with advancing elapsed time;
playback was paused before downloading. The player menu Download action
completed, search showed Ready offline, and the Downloads screen showed
Downloaded. A process stop/relaunch retained both this new download and the
existing download after startup validation settled. No retry, reset, deletion or
replacement of the existing download was performed.

The initial restart snapshot briefly showed an unavailable label for the older
entry while the new entry was verifying. A later settled snapshot showed both
Downloaded again. Preserve this transient startup presentation as an open UX
observation; it did not establish a missing file. This test does not claim
network-denied offline acceptance. After the restart, the new download was
played using its Downloads-screen action; the full player reached Playing with
advancing elapsed time and was paused afterward.

## Executed checks and artifacts

- 40 kmpYT JVM protocol/transfer tests passed: DownloadProtocol 12,
  HttpDownloadTransfer 16, HttpResponseIntegrity 9, ProgressiveHttpRange 3.
- 48 selected shared JVM tests passed: NativePlaybackSource 14,
  YouTubeMediaPurpose 7, YouTubeMediaSource 18, YouTubePreparedDownloadGateway 9.
- 11 Android instrumentation tests passed on the emulator: AndroidDeckDataSources
  5, AppStringsAndroid 1, QueueSessionPlayer 2, AndroidDownloadOwnerDrain 3.
- The final live source/native/playback/download probe passed, as did the earlier
  complete-download and successful range-control probes.
- kmpYT iOS arm64 main compilation and Simulator test-source compilation passed.
  The new iOS native tests were compiled, not executed.
- Veyra iOS arm64 compilation and Android app/test APK builds passed. The earlier
  native-player change also passed 30 focused Desktop UI tests.

Latest installed app APK SHA256:
`ad0cccf780c010dfae078d147346e2037a3ae560094b16580e0e07c20e327689`.
This supersedes the APKs in the earlier seek and download-only measurements.
It was installed in place with existing emulator app data retained. The physical
iPhone was not accessed or updated. These are local diagnostic builds including
the reviewed dirty kmpYT source, not clean-checkout/release artifact claims.

Evidence is under `.work/evidence/redesign/media-performance-20261111/`:
`baseline-receipt.json`, `range-comparison-retry-receipt.json`,
`full-range-comparison-receipt.json`, `bounded-transfer-post-receipt.json`,
`streaming-post-receipt.json`, `final-performance-summary.json`,
`shared-jvm-junit/`, `upstream-jvm-junit/`, `android-regressions.log`,
installation receipts, `app-ui-acceptance.json`, source hashes and
`runtime-cleanup.json`. The task-owned emulator and private ADB listener were
stopped with emulator data retained. The emulator console shutdown returned
success but did not exit within 20 seconds; SIGTERM to the verified task-owned
process completed shutdown. No shared ADB or physical device was touched.
Preserve previous failures alongside
successful runs. A range-control attempt failed with a transient DNS error and
was retried after read-only host/emulator DNS checks; no network setting changed.
Offline uncached task/dependency and incorrect task-name attempts are build setup
failures, not executed failing product tests. The notification helper specified
in AGENTS.md is absent on this host; ordinary thread updates were used.

## Remaining limits

No physical audible-output, physical iOS playback/download, native iOS test
execution, large-file/network matrix, or production distribution acceptance is
claimed. Cold program setup remains measurable. The full app redesign and its
broader accessibility/platform acceptance gates remain incomplete.
