# Mobile audio research, implementation and evidence

Updated **2026-11-06 UTC**. The original documentation research preceded implementation. Android/iOS native engines, hosts, controls, validators and execution gates are now **authored**, not presumed working. The first Android production compile is in progress (`.work/android-main-first-compile.log`); iOS has not compiled here. No emulator, simulator, physical audio, interruption, or end-to-end mobile download/restart test has executed. Keep those gates separate.

## Authoritative architecture and superseded proposals

Decisions D005/D006/D008/D009 replace the earlier progressive-only, optional-crossfade, independent-native-downloader proposal in this report:

- **All playable acquisition and downloads use central kmpYT.** Discover full metadata, search the complete title, accept the first ordered result, and preserve requested/resolved provenance. RSS, podcast directories and Commons are metadata-only. No later-result filtering, independent enclosure playback/downloading, authorization bypass, or fixture substitution for a refused source.
- **Room Multiplatform is the persistence owner.** Normalized queues, progress, settings, downloads, attempts, finalization/recovery and local references are not JSON snapshots. Native services are execution/lifetime adapters, not additional databases or schedulers.
- **One application-lifetime controller/session with two managed decks.** Android uses two Media3 ExoPlayers; iOS uses two AVPlayers. Persistent Music/Podcast queue contexts belong to the common controller. Browsing, screen recreation and service reattachment never create another queue owner.
- **Native music crossfade is required**, with persisted enable/duration settings and physical audible verification. Podcasts never overlap and retain unity gain. Missing physical evidence leaves the acceptance gate unmet.
- **kmpYT owns transfer/read execution.** Android WorkManager and iOS bounded UIKit tasks host the common coordinator; there is no competing Media3 DownloadManager or URLSession byte-transfer implementation in Veyra. Native background APIs may be extended upstream when their central execution contract is proven; they are not an implicit fallback.

## Implemented boundary and native ownership

The current public contracts are in `shared/.../playback/AudioEngine.kt` and `PlaybackModels.kt`; this document does not define a second interface. Native sources are under `composeApp/src/{androidMain,iosMain}/kotlin/dev/veyra/platform/`.

| Concern | Authored behavior and limit |
|---|---|
| Queue/session | `PlaybackController` serializes commands and owns occurrences, active context, next selection, repeat/shuffle, progress and timer policy. Native repeat/autonomous queues are disabled. |
| Identity | Prepared/item/seek/end/error callbacks retain originating generation and native item identity. Seek completion carries a sequence. Duplicate media IDs are distinct queue occurrences. |
| State | Actual native position, rate, buffering/playing and seekability are published; a successful source URL or command enqueue is not audible playback. Position snapshots may be conflated; terminal/control events use a separate channel. |
| Lifetime | Android Application owns engine, session and AppContainer; MediaSessionService owns only the OS foreground/notification attachment. iOS singleton owns AppContainer, AVAudioSession and remote targets. Views own neither transport. |
| Startup/recovery | Hosts expose a constructed container before initialization completes so common Loading/Failed/explicit backup-recovery UI remains mounted. Native Play/Resume fails immediately while uninitialized/failed, rather than replaying an old command after recovery. Android workers wait for the current initialization attempt, not a permanently poisoned deferred. |
| Threading | Native transport/session work is Main-confined; source, Room, hash and sampled file validation run off Main. A suspended resource close must be able to return to Main without a blocking join. |
| Restart | Restore paused, never autoplay from boot, process restart or recovered metadata. Successful explicit library recovery may resume permitted persisted downloads, not audio. |
| Disposal | Remove owned listener/notification/remote-target tokens once; source cleanup is idempotent and is a deletion barrier. A persistent cleanup-failure latch forbids later duplicate Stop from falsely authorizing deletion. |

### Two-deck crossfade and durable handover

Both engines implement `CrossfadeAudioEngine` with prepare/preload/start/cancel/confirm:

1. Preload the selected incoming MUSIC recording at zero gain. Require actual readiness, known duration and outgoing playback; unknown/too-short/stalled or failed candidates do not become fake successful fades. The shared scheduler limits overlap to at most half of either short recording and uses actual rate-adjusted remaining time.
2. Use monotonic equal-power envelopes: `out = G*cos(pi*t/2)`, `in = G*sin(pi*t/2)`, where `G = 0.6683439` (fixed −3.5 dB music gain). **The same gain applies outside fades**, avoiding a last-second attenuation jump. Worst-case coherent sum at midpoint is below 0.946; this is headroom, not a native limiter or a proof about intersample/hardware clipping.
3. Start the envelope only after the incoming decoder is actually playing. Native 20 ms envelope updates and a 250 ms state sampler are authored; their audible smoothness and timing remain device-test questions.
4. Ordinary cancellation ramps incoming down and outgoing back to its fixed gain over 60 ms. Pause/Stop/interruption silences immediately, rather than preserving a cosmetic ramp. Podcasts use gain 1 and never enter automatic overlap.
5. **`CrossfadeCompleted` is a proposal, not a committed queue switch.** Outgoing retains logical ownership/resources until the controller commits its Room transaction and invokes `confirmCrossfade(nextGeneration, generation)`. Deferred EOF/failure/interruption is mapped across that specific acknowledged handover; stale ACK cannot resurrect cancelled output.
6. Explicit/restored seek-to-end is not natural episode completion. Seek-back clears terminal suppression. Native failure, cancellation, route loss and manual context changes must not mark a podcast played or silently advance the wrong occurrence.

### PlaybackResource and physical deletion

Ownership transfers on **invocation** of native prepare/preload, even when invocation is cancelled before Main dispatch or rejected as stale/invalid. The controller disposes plans that were never handed off. `PlaybackResource.close()` must be idempotent, Main-safe, and promptly cancel readers.

- **Android:** each deck wraps its Media3 DataSource factory. Retirement rejects late opens, tracks actual open handles, and waits until real delegate `close()` returns. Stop/clear plus an ordered playback-thread PlayerMessage is not alone proof that an asynchronous Media3 Loader closed its descriptor. The additional loader-close barrier is therefore required. Timeout/failure reports `RECOVERY_REQUIRED`, never deletion approval. Failed partial opens and close failures have dedicated tests. [A10]
- **iOS:** pause/detach AVPlayerItem and observers before central-resource close; Stop/cancelNext drains tracked cleanup. **AVPlayer exposes no public actual file-descriptor-close acknowledgement.** Item removal is not represented as synchronous FD proof. Physical local-file deletion/reopen remains an explicit native gate.
- `PlaybackController.releaseForAssetRemoval(mediaId)` awaits relevant stop/cancelNext before shared deletion. Concurrent duplicate Stop/close must wait existing cleanup. File/database journals must remain recoverable if native cleanup cannot be acknowledged.

## Android platform integration

### Foreground playback and focus

The manifest declares mediaPlayback foreground service/permission, internet/network, wake lock and dataSync service permissions. No boot receiver or sticky autoplay is registered. Activity recreation only reconnects Compose.

`PlaybackForegroundGate` waits for the service's **actual startForeground ACK**, not merely startForegroundService, before `AndroidAudioFocus` requests focus. A bounded five-second startup notification prevents a paused initial session from prematurely dropping the foreground allowance. Android target-35+ eligibility and start restrictions still require real OS tests. [A1–A3, A6–A7]

The application uses one explicit focus lease rather than two players independently requesting automatic focus. Both music and speech conservatively pause on duck/transient loss. Only a true gain callback reports transient-end eligibility; the common intent/timer token decides whether to resume. Permanent loss, output removal and service loss stay paused. Native service-loss handling is identity-checked, immediately silences both decks, rejects already-queued output, and emits the originating-generation interruption rather than an unscoped queued Pause.

Media-session notifications are exempt from POST_NOTIFICATIONS permission, but a foreground service must still supply a notification. Download notification permission/denial, foreground start failures, task removal, OS quotas and force-stop are distinct scenarios—not blanket promises of background survival. [A3, A6, A9]

### Session/notification projection

`QueueSessionPlayer` is a read-only SimpleBasePlayer projection of the full canonical occurrence queue, not a wrapper around one decoder. Trusted/same-UID controllers receive a safe command superset; arbitrary URI/media-list mutation, unrelated pitch and cross-item explicit-offset seeks are not silently accepted. Common Previous may restart the current music item, so the distinct PREVIOUS_MEDIA_ITEM command is not advertised.

Reviewing **Media3 1.8.0 source** found its default legacy notification ignores player-command preferences and can issue PREVIOUS_MEDIA_ITEM even when only common Previous is offered. The authored `PlaybackNotificationProvider` overrides button projection only: music Previous/Play-Pause/Next, podcasts SkipBack/Play-Pause/SkipForward. Session and notification labels follow Veyra's selected English/Arabic preference and refresh even if queue context stays unchanged; channel renaming preserves user channel settings. This does not claim verified System UI behavior. [A10]

Session command futures signal completion timing. Media3 SimpleBasePlayer does **not** inspect failed futures as proof of an external error response; rejected operations leave the authoritative state unchanged, but client-visible failure propagation is not yet verified. iOS synchronous remote-command success similarly acknowledges enqueueing, not successful playback.

### WorkManager download/maintenance execution

- A durable unique APPEND_OR_REPLACE worker chain avoids losing work that arrives as an old worker exits. WorkManager setForeground(dataSync) must succeed **before** a lease becomes ready.
- The worker calls the one shared coordinator, never resolves or transfers media itself. Native lease count plus common pending work protects finalization/cleanup from idle retirement.
- Gate expiration records callbacks atomically, completes readiness failures, and drains previous checkpoint/cancellation before a new worker/foreground resume can admit work. Shared claim/epoch guards are still essential: a captured stale expiration must not cancel a later attempt.
- Failed start, OS cancellation/timeout and unavailable background allowance map to typed `BACKGROUND_RESTRICTED`/NeedsForeground behavior. Explicit user pause is not resumed by a worker. Cold persisted work and disk-only reconciliation must obtain the same execution allowance.
- Acquisition cancellation at the Main-dispatch return edge releases an allocated lease; the native fake-worker tests cover this lifecycle boundary, not a real WorkManager/OS run.

## iOS platform integration

### Audio/session and controls

The Swift AppDelegate/SwiftUI host forwards application lifecycle, and Compose controller recreation reuses the singleton. AVAudioSession is `.playback`, `.default` for music and `.spokenAudio` for episodes; activation occurs on real Play, not launch/browse. `Info.plist` background modes contain **audio only**. Background audio is not an entitlement to unrelated indefinite computation, silent keepalive or force-quit persistence. [I1–I2, I6]

Both AVPlayers use explicit item ownership, pause-at-end and time-domain pitch processing. Ready status, duration/seekability, actual timeControlStatus/rate and item-end/failure are sampled/observed. Speeds 0.5–2× are candidate capabilities; sub-1× still checks item canPlaySlowForward. Real route/device codec/rate evidence is missing. [I1, I15, I17]

One Now Playing adapter publishes title/creator, duration, actual elapsed/rate and cached local artwork, refreshes confirmed seeks/drift, and lets iOS extrapolate normal progress. It retains/removes exactly its remote-command targets; music uses next/previous, podcasts configured skip intervals. No artwork network request originates from the native session. The Apple-provided system control labels remain OS-owned. [I3–I4, I14]

Interruption-end honors Apple's shouldResume plus common current intent. Headphone/Bluetooth oldDeviceUnavailable stays paused. Media-services reset rebuilds native objects and requires explicit user restart, preserving common metadata/positions. Callback identity/handover mapping is authored, not an incoming-call/AirPlay/Bluetooth verification result. [I5, I7–I8]

### Bounded central execution and storage

`IosDownloadExecutionGate` uses UIKit begin/endBackgroundTask around the **central kmpYT coordinator**, not background URLSession. Each allowance has identity; stale callbacks cannot expire its successor. Foreground resume waits outstanding callbacks. An inline expiration before begin returns is handled; acquisition cancellation releases newly allocated work. [I18]

When backgroundTimeRemaining is finite and at most six seconds, a watchdog asks the shared owner to checkpoint/cancel. A proactive guard ends the task within two seconds even if checkpointing blocks; the final OS expiration ends immediately. Expiry/denial stays exhausted until foreground and presents NeedsForeground. The estimate is not a promised execution window. There is no assertion that transfers continue after suspension, system termination or user force-quit.

Background URLSession/AVAssetDownloadURLSession are documented alternatives, **not implemented Veyra transfer paths**; they have delegate/relaunch/redirect/content-package semantics that a future central contract must handle. Their existence does not authorize bypassing kmpYT. [I9–I11]

Application Support's OS-provided base is canonicalized (for example `/var` → `/private/var`), but app-owned `Veyra`, `media` and `artwork` symlinks are refused before/after creation. Retained media/artwork are excluded from backup; Room metadata root is not. Protection is CompleteUntilFirstUserAuthentication. Native first-unlock/locked playback and exclusion flags still require Apple verification. Android similarly resolves trusted filesDir base aliases without weakening owned-descendant checks. [I16]

## Native capability, source and local-file checks

| Candidate representation | Android authored capability | iOS authored capability | Gate |
|---|---|---|---|
| Progressive MP3/AAC-LC MP4 | Media3 extractor/device decoder | AVFoundation | Actual prepare, progress and seek; MIME alone is insufficient |
| WebM/Ogg Opus/Vorbis | Candidate supported | Not advertised | Never choose a later kmpYT result to hide incompatibility |
| WAV/FLAC/ALAC | Limited declared codec/container set | Limited declared codec/container set | Authored local fixtures are not import support; imports disabled |
| HLS/DASH/live | Not advertised by current engine contract | Not advertised | Native framework support does not equal central/app implementation |
| Static SABR | Central owned streaming bridge | Central owned streaming bridge, uncompiled | Not an arbitrary direct URL; upstream adapter and native integration must both pass |

The owned bridge is a typed `PlaybackResource` capability for exactly its `http://127.0.0.1:port/nonempty-route`. It remains `isLocal=false`, so offline-only policy still rejects it. Ordinary unowned loopback is rejected. Android network-security and Apple ATS allow only the exact numeric loopback exception; arbitrary cleartext is disabled. These settings are not a DNS-pinning guarantee. All other native source URLs pass public URL policy. iOS refuses arbitrary request headers rather than use undocumented AVURLAssetHTTPHeaderFieldsKey. [A4, I12–I13]

Shared transfer receipts, scoped validators/ranges, expected body lengths and streaming SHA-256 complement native file-only validators. Android MediaExtractor/MediaCodec and iOS AVAssetReader decode bounded beginning/near-end samples without starting another audible session. Samples help detect invalid media; **they do not prove complete-track decoding**, and a self-computed hash is integrity/restart evidence, not source authenticity. Reject HTML/JSON, partial or missing files, invalid size/type and unsupported packages; do not use metadata/publisher enclosure sizes as authoritative response lengths.

Atomic staging/finalization and Room recovery must gate Ready. Local restart resolution verifies owned paths/files before any remote resolution. Deletion drains active/native readers and transfer/maintenance leases. The release acceptance test must deny **native-player network egress**, terminate/restart the app, and play/seek locally; setting only a shared HTTP offline flag is insufficient evidence.

## Seek, progress and sleep policy

Confirmed native content position—not a UI timer—is durable progress. Generation and seek sequence reject old completions; unknown duration is not zero. Rate changes publish actual/effective rate and never turn a user pause into Play. Common episode completion is natural EOF or explicit user marking, independent of downloaded/saved/progress state.

Duration sleep timers use monotonic elapsed time while alive and remain visible across queue contexts, including paused/buffering/interrupted time. Entry-bound end-of-episode timers stop before queue advance. Timers, explicit Pause, manual context changes and permanent interruption invalidate automatic resumption. No exact-alarm permission or silent audio is used to manufacture background timer guarantees.

## Verification ledger and remaining release gates

| Evidence | Current status |
|---|---|
| Android/Apple documentation and pinned Media3 API source | Read; cached public API notes/source under `.work/mobile-api/` |
| Android XML and iOS plist/static network policy | Scoped parsing passed previously; rerun required after final source edits |
| Android production Kotlin/native API compile | First run in progress, `.work/android-main-first-compile.log`; not a pass claim |
| Android instrumented-source compile | Not yet run |
| Android emulator/device tests | Not run; read-only inventory found zero transports/USB products |
| iOS Kotlin/Swift compilation, simulator/XCTest | Not run; this Linux host has no Xcode |
| Physical audible music crossfade/podcast non-overlap | **Not verified on either platform; mandatory gate remains unmet** |
| Real central title→first-result→native playback→download→restart offline | Not verified on either mobile target; source authorization refusal is separate from app correctness |

Authored native suites cover real local decoder progression/pause/seek, music envelopes/handover ACK, stale resources, podcasts at unity/no overlap, duplicate stop/blocked cleanup, ordinary cancellation, restore-at-end, loader close barriers, trusted-base versus owned symlinks, command/notification projections, startup access, and synthetic background/service/interruption races. **Authored does not mean compiled or executed.** Fake task deadlines and synthetic notifications test logic only. Fixtures are original eight-second 440 Hz PCM tones, not a compliant live-source substitute.

Still required: actual service/session/Now Playing commands; app-selected language on real System UI; stalled/short/unknown sources; physical routes/calls/lock screen and ≥10-minute background tests; Android API/FGS quota/permission/task-swipe/force-stop matrix; iOS first-unlock/media-reset/task-expiration/termination; native deletion/reopen; crossfade timing/clipping listening; shared Room migration/recovery on each target; real central online→download→cold offline restart; bilingual light/dark/large-text UI checks.

An adversarial concurrent-call audit also identified a prepare/stop generation-ordering edge while native prepare suspends in smooth cancellation. The common engine-operation queue is serialized; arbitrary concurrent direct calls are not used by production. This does not excuse the race or replace a regression; keep it visible for final native review.

A green shared test, compilation, null sink, emulator or synthetic callback cannot establish physical audible output. Build, automated/native execution, physical hardware and signing/distribution must remain independently recorded. Model configuration was accepted as `gpt-6-astra`/`max` by the parent spawn request; this agent has no independent effective-runtime attestation.

## Primary sources and audited upstream

Accessed 2026-11-06. Apple pages were read using their official DocC JSON endpoint when the visible page is JavaScript-rendered.

- **A1:** Android, [Background playback with a MediaSessionService](https://developer.android.com/media/media3/session/background-playback): service ownership/lifecycle, manifest, controllers, notification, resumption, trusted controllers.
- **A2:** Android, [Manage audio focus](https://developer.android.com/media/optimize/audio-focus): ExoPlayer automatic focus, speech behavior, target-35 foreground restriction.
- **A3:** Android, [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission): media-session exemption, FGS notice requirements.
- **A4:** Android, [Supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats): containers, DASH/HLS, decoder-dependent support, seeking limitations.
- **A5:** Android, [Downloading media](https://developer.android.com/media/media3/exoplayer/downloading-media): service/index/cache architecture, durable cache, requirements, adaptive download selection.
- **A6:** Android, [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): permissions/types and boot restrictions.
- **A7:** Android, [ExoPlayer API reference](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer): `setHandleAudioBecomingNoisy`, `setWakeMode` and `WAKE_LOCK`.
- **A8:** Android, [ExoPlayer customization](https://developer.android.com/media/media3/exoplayer/customization): operation interception with `ForwardingSimpleBasePlayer`.
- **A9:** Android, [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout): data-sync time budgets and timely shutdown.
- **I1:** Apple, [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer): single reusable item, local/remote file media, HLS, state/time observations.
- **I2:** Apple, [Configuring your app for media playback](https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback): `.playback`, activation timing and background capability.
- **I3:** Apple, [MPNowPlayingInfoCenter](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter): metadata and lock-screen/Control Center publication.
- **I4:** Apple, [MPRemoteCommandCenter](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter): shared remote-control commands.
- **I5:** Apple, [Handling audio interruptions](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions): AVPlayer behavior, established interruptions and newer availability-specific life-cycle APIs.
- **I6:** Apple, [AVAudioSession.Mode.spokenAudio](https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/spokenaudio): podcast/speech pause-rather-than-duck semantics.
- **I7:** Apple, [Responding to audio route changes](https://developer.apple.com/documentation/avfaudio/responding-to-audio-route-changes): headset privacy behavior and AVPlayer handling.
- **I8:** Apple, [mediaServicesWereResetNotification](https://developer.apple.com/documentation/avfaudio/avaudiosession/mediaserviceswereresetnotification): rebuild and require user-initiated restart.
- **I9:** Apple, [Downloading files in the background](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background) and [background(withIdentifier:)](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:)): scheduling/delegates/reconnection/redirect constraints and force-quit behavior.
- **I10:** Apple, [didFinishDownloadingTo](https://developer.apple.com/documentation/foundation/urlsessiondownloaddelegate/urlsession(_:downloadtask:didfinishdownloadingto:)): temporary file must be retained/moved before callback returns.
- **I11:** Apple, [AVAssetDownloadURLSession](https://developer.apple.com/documentation/avfoundation/avassetdownloadurlsession): native background asset downloads, relaunch, user force-quit.
- **I12:** Apple, [isPlayableExtendedMIMEType](https://developer.apple.com/documentation/avfoundation/avurlasset/isplayableextendedmimetype(_:)) and [HLS authoring specification](https://developer.apple.com/streaming/hls-authoring-specification-for-apple-devices.html): codec/container query and Apple-compatible HLS audio requirements.
- **I13:** Apple, [AVURLAssetHTTPCookiesKey](https://developer.apple.com/documentation/avfoundation/avurlassethttpcookieskey) and [AVURLAssetHTTPUserAgentKey](https://developer.apple.com/documentation/avfoundation/avurlassethttpuseragentkey): documented request customization and cookie scoping.
- **I14:** Apple, [Elapsed playback time](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfopropertyelapsedplaybacktime) and [Playback rate](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfopropertyplaybackrate): system time extrapolation.
- **I15:** Apple, [AVPlayer seek with tolerance/completion](https://developer.apple.com/documentation/avfoundation/avplayer/seek(to:tolerancebefore:toleranceafter:completionhandler:)) and [seekableTimeRanges](https://developer.apple.com/documentation/avfoundation/avplayeritem/seekabletimeranges): cancellation, precision and discontinuous ranges.
- **I16:** Apple, [isExcludedFromBackupKey](https://developer.apple.com/documentation/foundation/urlresourcekey/isexcludedfrombackupkey): exclude redownloadable application-support data from backups. Storage/data-protection choices above are proposed implementation policy requiring device verification, not a claim that this flag controls data protection.
- **I17:** Apple, [canPlayFastForward](https://developer.apple.com/documentation/avfoundation/avplayeritem/canplayfastforward), [canPlaySlowForward](https://developer.apple.com/documentation/avfoundation/avplayeritem/canplayslowforward), [defaultRate](https://developer.apple.com/documentation/avfoundation/avplayer/defaultrate), [audioTimePitchAlgorithm](https://developer.apple.com/documentation/avfoundation/avplayeritem/audiotimepitchalgorithm): item-specific rate behavior and pitch processing.

- **A10:** AndroidX Media3 **1.8.0** public source: [DataSource](https://github.com/androidx/media/blob/1.8.0/libraries/datasource/src/main/java/androidx/media3/datasource/DataSource.java), [SimpleBasePlayer](https://github.com/androidx/media/blob/1.8.0/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java), [DefaultMediaNotificationProvider](https://github.com/androidx/media/blob/1.8.0/libraries/session/src/main/java/androidx/media3/session/DefaultMediaNotificationProvider.java), [CommandButton](https://github.com/androidx/media/blob/1.8.0/libraries/session/src/main/java/androidx/media3/session/CommandButton.java). API source review is not a compiled integration result.
- **I18:** Apple, [Extending your app's background execution time](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time) and [beginBackgroundTask](https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:)): bounded allowance, expiration and balanced end calls.
- **K1 (historic audit):** Baseline kmpYT `533562f781304338f72b27d32cfe5fb7a83dc3a8` exposed progressive/static-SABR constraints and format-selection limitations. Upstream has since changed; use `gradle/kmpyt.commit`, `docs/research/kmpyt-audit.md` and upstream test evidence for the current adapter/resume state. No historic restriction or JVM pass is claimed as proof of current Apple support.
