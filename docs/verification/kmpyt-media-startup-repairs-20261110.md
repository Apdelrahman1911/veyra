# kmpYT media availability and audio startup repair — 2026-11-10

Local source: Veyra base `ef31659eb2600ed5f2f8c4b0dc2446555a798018`,
kmpYT base `6805d4db8077650992d342e5395b1f0d7c80e984`. The owner requested
playback, downloads and faster audio startup, with the underlying kmpYT repair
kept in its own repository. Work was performed directly, without agents.

## Result on the connected Android phone

On the Huawei AQM-LX1 running Android 10, the original failed download,
`KW2yXBRFO7c` (MATGREESH), completed through Veyra's normal download path.
The app reports **Downloaded / 3.6 MiB**, and the final private M4A is exactly
**3,725,310 bytes**. This verifies the transfer, native validation and app
publication path, rather than just an HTTP probe.

With Veyra's app-only offline mode enabled, the downloaded recording played and
its displayed position advanced from 4 to 19 seconds. On the final clean APK, the same offline check was repeated after a cold launch
and the downloaded recording advanced from 1 second to **1:02**. The original
offline setting was false and was restored to false. Phone network settings were not
changed. A separate undownloaded GHABA search result played to 33 seconds.

On the final clean APK, a fresh undownloaded CTRL result was sampled as
Preparing at 15.35 seconds after the tap, then Playing at **18.81 seconds**.
It later reached **1:31** without a crash. These are UI observation times,
including sampling overhead, full-title source search, resolution and native
preparation. They are not a claim of first audible output at an exact instant.
There is no like-for-like old-build tap-to-playing baseline. Earlier 76–212
second headless inspection probes are not comparable to foreground app startup.
Foreground audio inspection probes were approximately 8.7–10.2 seconds.

## What was repaired in kmpYT

The failing player response declared a pre-content placement with a 10-second
length and a 5,000-millisecond skip offset. Early media requests returned 403;
the same URL with the same headers later returned 200. Immediate URL refresh
could restart the declared wait. Changing user-agent, Origin or Referer did not
resolve the failure. Independent comparison found the signature/throttling
outputs matched the current upstream helper lifecycle. The media server's
internal implementation is not visible; these are the observed response and
transfer facts behind the timing repair.

`SourceAvailability` reads source-declared start placements and before-content
sequential slots, uses skip timing or declared duration, and anchors the deadline
to receipt of the actual player response. Delays are cancellable and bounded;
excessive waits and timestamp overflow leave formats unavailable. No advertising
media, credentials, challenge grants or integrity tokens are requested.

`Representation.availableAtEpochMs` carries the ephemeral deadline through
manifest expansion, concrete resolution and refresh. It is excluded from the
progressive resume selection digest, so timing changes do not change recording
identity. The common client and Android/JVM/iOS download and SABR adapters honor
the deadline. Selected manifest reads honor it as well. The new suspending
`preparePlayback` waits before exposing a native playback request; portable
`playbackPlan` remains available for callers that manage the deadline themselves.

`inspectAudio` filters unrelated video formats before cipher transformation.
Signature and throttling outputs are memoized separately within one inspection,
with cancellation checks between native transformations. Full `inspect` retains
the complete catalogue behavior. A regression first demonstrated 12 native calls
where two were expected; the repaired path reuses the successful outputs.
Veyra's YouTube source now uses `inspectAudio` and `preparePlayback`.

The earlier Android placeholder-regex, Media3 loading-state, fenced download
progress-reset and English/Arabic expired-link repairs remain included. See
[the earlier repair report](android-runtime-repairs-20261110.md).

## Verification

| Check | Result |
| --- | --- |
| Full kmpYT JVM suite | 457 discovered; 454 passed, 3 skipped; no failures/errors |
| Focused shared source/download/Room/integrity regressions | 120 passed; no failures/errors/skips |
| Final QueueSessionPlayerTest on physical Android | 2 passed |
| Final AppStringsAndroidTest on physical Android | 1 passed |
| Final app and instrumented APK assembly | Passed |
| Read back installed APK versus built APK | SHA-256 matched |
| Temporary diagnostic classes in final APKs | Absent |
| Original downloaded file after APK replacement | Retained, 3,725,310 bytes |

The three library skips were two optional FFmpeg native-decoder/combination
checks and the opt-in approved-channel live test. Chromium was supplied from an
existing installation for the JVM suite; no global tool configuration was
changed. New regressions cover audio scope/full catalogue compatibility,
restriction handling, transformation reuse, cancellation, source timing,
response receipt anchoring and resume identity. An authored native loopback
server returns 403 before its deadline and confirms exact downloaded bytes after
availability. Offline tests do not download platform media.

One separate full-transfer diagnostic reached its artificial 120-second harness
timeout at low throughput. It is not another 403 and is not a successful probe.
The later completed download in the actual app is the end-to-end evidence.

Both APKs were installed with `adb install -r`; no uninstall or data reset was
performed. The final app cold launch returned Status ok / COLD in 3,310 ms. One
immediate UI dump returned no root and was discarded; later UI samples were
collected successfully. The final running process had no AndroidRuntime fatal
exception in its captured log.

Installed app SHA-256:
`40ef7cdbdf1960deb801a72e70bc40215027e4231cb33e1e70139fdd2348db91`

Temporary instrumented probes were removed from source and both final APKs.
Only four task-owned `cache/veyra-probe-*` files were removed from the phone;
media and user data were preserved. Private source responses, signed URLs and
screenshots remain ignored local evidence.

## Source delivery and limits

The authorized upstream checkout is `.work/kmpYT`, on
`fix/android-media-transfer-startup`, with the user's kmpYT origin. Its 17 changed
source/test/documentation files match the integrated `third_party/kmpYT` copy.
Both remain uncommitted. Portable patches, a source digest manifest and commands
are saved under `.work/evidence/media-transfer-startup/`. No commit, push, PR,
upstream merge or release was performed. The original dependency pin is unchanged.

This is a tested local development result. D005's reviewed/merged upstream pin
remains a separate distribution step. The normal bootstrap intentionally refuses
a dirty submodule; the final local build used the existing scoped
`scripts/dev.sh ./gradlew` path. This report does not approve a production or
clean-checkout release of the unmerged patch. iOS adapters were updated but were
not compiled or exercised in this session; Windows, Linux and macOS native app
playback were not tested. Native position advancement is verified, while human
listening confirmation remains outside this automated session.

Evidence roots (ignored): `.work/evidence/media-transfer-startup/` and
`.work/evidence/playback-download-fix/`. Principal records include
`final-online-startup.json`, `final-device-regressions.txt`,
`final-artifact-identity.json`, `final-app-native.log`,
`full-upstream-regressions.log`, `app-source-download-regressions.log`,
`original-download-complete.png`, `offline-player-advanced.xml`,
`final-offline-player-advanced.xml`, `final-offline-restored.xml` and
`final-online-advanced.xml`.
