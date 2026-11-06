# Mobile audio feasibility and contract

Research date: **2026-11-06 UTC**. Primary Android and Apple documentation was fetched successfully during this research. This is a design/research result, **not** evidence that Veyra has compiled, played audio, survived backgrounding, or downloaded a usable file on either platform. No implementation or device test was performed in this workstream.

## Recommendation and decision boundaries

- **Android:** one service-lifetime Media3 `ExoPlayer`, exposed through `MediaSessionService` and a session-facing player facade. Compose is a controller, not the player owner. Use `MediaLibraryService` instead only when an actual external browsable catalog/Android Auto library is implemented. [A1]
- **iOS:** one app-lifetime `AVPlayer`, `AVAudioSession`, Now Playing publisher and remote-command adapter. A view disappearing does not stop or dispose playback. [I1–I5]
- **Common Kotlin:** one serialized `PlaybackCoordinator` owns the queue, active queue-entry identity, transport intent, source-resolution generation, repeat/shuffle, episode progress and sleep policy. Platform adapters own decoding and OS integration, not independent music/podcast queues.
- **Interoperable first source:** progressive MP3 or AAC-LC in MP4/M4A, selected from real reported representations and successfully prepared by the native player. Native HLS is a separate capability. ExoPlayer support does not establish AVPlayer support. Never relabel WebM bytes `.m4a`, assume a `video/*` source is audio-only, or treat SABR as an ordinary media URL. [A4, I1, I12, K1]
- **Downloads:** native background executors with durable records; validate the actual asset before marking it offline-ready. Use opaque asset handles because Media3 cache spans, an iOS HLS package, and a standalone MP3 are different storage objects. [A5, I9–I10]
- **Honest capability gates:** source availability, OS support, and tested support are distinct. Missing compatible media is an explicit unavailable/error state, not a fabricated playable item or fake download.

Current Android documentation recommends `ForwardingSimpleBasePlayer` over error-prone direct `ForwardingPlayer` overrides for intercepted player operations. Verify its exact API against the project's eventual pinned Media3 version before implementation. [A8] Do not import newer Apple APIs merely because a live documentation page shows them; compile/deployment SDK availability controls their use.

For the current progressive-first source scope, a **validated standalone progressive file** is the simplest initial offline product. Media3 cache/HLS package support is a separate optional implementation path, not a reason to pretend those objects are files. If the executor uses native Media3/URLSession instead of `kmpYT.download`, it must explicitly implement the planned-resource identity, range/validator, expiry/refresh and completion checks: choosing a library-produced URL does not inherit that library's downloader safeguards.

## Single-owner state and platform interfaces

The following is a proposed boundary, not a second implementation of the architecture team's public model. Names can be unified with that model; invariants must remain.

```kotlin
data class PlaybackToken(val queueEntryId: String, val generation: Long)
data class SeekToken(val playback: PlaybackToken, val sequence: Long)

interface PlatformAudioEngine {
    val capabilities: AudioCapabilities
    val events: Flow<AudioEvent>
    suspend fun replace(source: PlayableSource, token: PlaybackToken, positionMs: Long)
    suspend fun setPlaying(token: PlaybackToken, playing: Boolean)
    suspend fun seek(token: SeekToken, positionMs: Long)
    suspend fun setRate(token: PlaybackToken, rate: Float)
    suspend fun stop()
    suspend fun close()
}

interface PlatformMediaSession {
    val commands: Flow<PlaybackCommand>
    suspend fun publish(snapshot: PlaybackSnapshot)
    suspend fun close()
}

interface PlatformDownloadExecutor {
    val events: Flow<DownloadEvent>
    suspend fun reconcile(): List<NativeTransferRecord>
    suspend fun enqueue(request: ValidatedDownloadRequest): NativeTransferId
    suspend fun cancel(id: NativeTransferId)
    suspend fun retry(id: NativeTransferId)
}

interface PlatformAssetValidator {
    suspend fun inspect(handle: LocalAssetHandle): AssetValidationResult
}
```

### Required contract semantics

| Concern | Required representation/policy |
|---|---|
| Command completion | An accepted request is not playback success. `Prepared`, native playback state and errors arrive as events. Async completions must carry their token; adapters reject operations for a replaced token. |
| Source | Stable source/representation identity, transport (`Progressive`, `Hls`, etc.), container, codec, URL/opaque local handle, expiry, safe request requirements, original source for re-resolution. Secrets/signed URLs must not appear in `toString`, UI errors or logs. |
| Capabilities | Transport/container/codec candidates, supported request headers/cookies, local handle kinds, media-session availability, native background-download kinds. Do not represent everything as a single `supported: Boolean`. |
| Per-item capabilities | Actual seekable ranges, known/unknown duration, live/VOD, speed support, decoder/preparation outcome. Re-evaluate after ready/format/route changes. |
| State | `Resolving / Preparing / Ready / Buffering / Ended / Failed`, `desiredPlaying`, **actual** `isPlaying`, suppression reason, position, duration nullable, buffered ranges, effective rate, active token. Do not animate actual progress from a mock timer. |
| Events | State, discontinuity/seek completion, ended, failure, route change, interruption/suppression, media-services reset. Terminal/control events must not be dropped; only position snapshots may be conflated. Position is sampled from the native engine; a 250–500 ms UI feed is sufficient. |
| Identity | Queue-entry ID is distinct from media ID: the same episode/track can occur twice. Queue revision, load generation and seek sequence solve different races. |
| Threading | Serialize public intents in the common coordinator. Touch ExoPlayer on its application looper and Apple media/session objects on the main/native serial execution context. Never block those threads while resolving, downloading, hashing or parsing media. |
| Lifetime | Android: coordinator/engine/session in playback service; Activity controls through a service/controller boundary. iOS: application root owns them, not a composable or screen. |
| Disposal | Remove listeners, periodic time observers, item observers, route/interruption observers, remote-command targets and service resources exactly once. Repeated controller connections must not add duplicate handlers. |

Start with **one loaded native item**. A common `Ended(activeToken)` event advances the shared queue exactly once. Native `AVQueuePlayer`, ExoPlayer repeat/shuffle, and autonomous native next-item decisions must not become competing queue owners. This simple design does not promise gapless transitions. A later preloaded/native queue is a projection of the common queue and needs transition/revision tests before enabling it.

Android's session-facing player must expose the canonical navigation capabilities and send play/pause/next/previous/seek operations into the same coordinator. The engine's internal player calls must not loop through that facade. A facade over a single underlying item must explicitly map canonical next/previous and available commands; merely overriding `seekToNext()` is insufficient because Media3 normalizes operations and may otherwise see no next item. Cover the facade with session-controller tests. [A1, A8]

### Races, resume and persistence

1. A new selection increments the generation, cancels old resolution and pending seeks, and stops the old item before publishing the new item's playback state. Check generation again after every async prepare/resolution/artwork callback. Capture the originating item's token/identity when registering or issuing native work; tagging an old callback with the latest generation on receipt would defeat this protection. Player-wide callbacks must verify current native item identity and sample current state.
2. UI, Bluetooth, lock screen and notification commands enter the same serialized command path. One click produces one transition. Navigation commands do not have separate queue copies in adapters.
3. Store **intent separately from actuality**. Buffering or a transient OS interruption is not a user pause. Native auto-pause/suppression must still reach common state, so UI/lock screen do not show false playback.
4. A resumption token captures item generation, user-intent revision and timer state. Resume after a transient interruption only if all remain valid and the OS permits it. An explicit pause/stop, route disconnect, timer expiry or item change invalidates it. Permanent focus loss does not silently resume.
5. Persist stable queue entries, ordering/shuffle order, repeat, current identity, per-episode position, speed preference and metadata—not an expiring URL as the permanent source. Use periodic checkpoints plus pause/seek/item change/background/finalization checkpoints; a process can die without a final callback.
6. Restart restores a **paused** queue and last safe position. Re-resolve expired online media, or verify a local asset. Do not automatically begin sound on boot/app launch. Platform user-initiated resumption is a separate command.
7. On an expired/403 source, allow at most a bounded re-resolution attempt that preserves content/track/language identity and current position. Classify authentication/restriction/unsupported-media/network failure; do not retry forever or select an unrelated recording.

## Android implementation requirements

### Service, session, background and notification

- Construct/release the player and session in service lifecycle; `onGetSession` returns the owned session. `MediaSessionService` automatically publishes a media notification and maintains the playback service lifecycle. Default recent-tasks dismissal leaves active playback running and stops an idle service. This is not immunity from process death or user force-stop. [A1]
- Manifest: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and a service declared `foregroundServiceType="mediaPlayback"`. If using network wake mode, declare `WAKE_LOCK`; have ExoPlayer manage locks rather than keeping an independent permanent wake lock. [A1, A7]
- Let ExoPlayer handle focus via media `AudioAttributes` and `handleAudioFocus=true`; **do not install a second competing focus manager**. Use media/music content type for music and speech content type for podcasts. Enable `setHandleAudioBecomingNoisy(true)`. Reflect suppression/auto-pause in the shared snapshot and apply the common resume policy. [A2, A7]
- Android 15+ target apps cannot request focus unless topmost or running a foreground service. Media playback FGS must not be launched from `BOOT_COMPLETED` for target 35+. Begin from genuine user action/session support and obey foreground-start restrictions. [A2, A6]
- Supply title, artist/show, artwork, duration, position and available commands in session state. Android 13+ system controls derive primarily from the session; a custom notification alone cannot fix inaccurate metadata/state. [A1]
- `POST_NOTIFICATIONS` is **not a prerequisite for media-session playback**: media-session notifications are exempt. Request it contextually for non-exempt notifications, such as ordinary download completion/progress. An FGS must still supply its required notification even when permission is denied. [A3]
- Use next/previous for music and appropriate seek-back/forward for podcasts, with reachable queue navigation. Availability reflects actual common capabilities. Exposed session custom commands require connection/command authorization; never let another app inject unrestricted arbitrary URIs into the trusted playback path. [A1]
- Restore locally available metadata quickly for resumption. If registering Media3's `MediaButtonReceiver`, implement `onPlaybackResumption`; Android System UI resumption discovery has additional `MediaLibraryService` requirements. Do not advertise unimplemented library/Auto support. [A1]

### Background download options

- For standalone progressive files, use a durable native service/worker executor rather than an Activity coroutine. It may host the kmpYT transfer path to preserve its documented guards; app job identity, service policy, retries, notifications and native file validation still remain Veyra responsibilities. Do not call this a Media3-cache download when it actually writes an ordinary file.
- If selecting the **Media3-cache alternative**, use one Media3 `DownloadManager`/persistent `DownloadIndex`, hosted in a `DownloadService`; configure connectivity/Wi-Fi requirements and a bounded concurrent count. Use app-owned `SimpleCache` with `NoOpCacheEvictor` for retained downloads, separately from an evictable streaming cache. Configure the playback data source to use that same download store. [A5]
- Download service is `dataSync`, not `mediaPlayback`; declare its FGS permission/type separately. Android 15 data-sync FGS time is limited (six hours per 24-hour budget while background, with documented reset behavior). Stop/checkpoint on timeout, report queued/retryable state, and do not keep silent playback alive to evade it. Scheduler/OS quotas mean completion time is not guaranteed. [A5–A6, A9]
- A completed Media3 cache download is **not a standalone MP3/M4A file**. Validate/reopen through its matching cache-backed data source and download identity. If the product requires an exported standalone file, implement an explicit progressive-file transfer/materialization path and validate that result. Do not point native file decoders at a directory of cache spans.
- Offline mode must use a no-network local/cache-only data source. A fallback HTTP source can hide missing cache data during testing and is therefore not offline proof.
- Adaptive downloads require track-aware `DownloadHelper` selection/stream keys; a downloaded manifest alone is not downloaded audio. This platform capability does not make current kmpYT playback plans HLS/DASH-compatible. [A5, K1]

## iOS implementation requirements

### Audio session, background and external controls

- Configure `AVAudioSession.Category.playback`, normally `.default` mode for music and `.spokenAudio` for continuous podcast speech. The latter expresses pause-rather-than-duck behavior for another app's spoken prompt. Activate on a genuine play request, not merely launch/browse; catch activation failure and publish suppressed/error state. Do not enable mixing/ducking of other apps unless explicitly chosen as a product policy. [I2, I6]
- Enable the Xcode **Audio, AirPlay, and Picture in Picture** background mode (`UIBackgroundModes` includes `audio`). Category plus this capability enables playback with silent switch/lock screen/background. It is not permission for indefinite arbitrary background computation, keeping a silent player alive, or continuing after force-quit. [I2]
- Reuse one `AVPlayer` with `replaceCurrentItem`. Observe item status, `timeControlStatus`, effective rate, item failure/end, seekable/buffered ranges and periodic position. A successful URL resolution or creating `AVPlayerItem` is not `readyToPlay`. [I1]
- Publish `MPNowPlayingInfoCenter.nowPlayingInfo`: title, artist/show, album/artwork where present, duration when known, elapsed seconds, actual playback rate (zero when paused), and default rate. Update on load/play/pause/seek/rate change; system extrapolates elapsed time, so high-frequency dictionary rewrites are unnecessary. Async artwork must match the current token. [I3, I14]
- Register and retain one set of `MPRemoteCommandCenter` targets for play, pause/toggle, next/previous, skip forward/backward, change position and supported rates. Commands feed the common coordinator; enable only commands that can work and remove the same targets at disposal. Return meaningful command status when unavailable. [I4]
- Observe interruption notifications and honor interruption-end resumption advice plus the common user-intent token. AVPlayer already monitors the audio session; reconcile its automatic pause with the app's state instead of assuming every pause is a user command. For supported deployment versions use the established `interruptionNotification` path. The live Apple article also documents newer iOS 27 life-cycle APIs; adopting those requires availability/SDK checks, not an unconditional baseline change. [I5]
- On `oldDeviceUnavailable`, respect the user's privacy by staying paused after headphone/Bluetooth disconnection; reconnect alone never resumes. Connecting headphones while already playing normally continues. AVPlayer handles its own route behavior; observe/publish it and invalidate app resumption tokens. Re-query current routes rather than inferring output from an old label. [I7]
- On media-services reset, recreate audio objects/configuration and retain common queue/progress, but **wait for explicit user action to resume**, per Apple's guidance. AirPlay/Bluetooth routes may affect speed/seek capabilities. [I8]
- A Swift/iOS application delegate bridge is needed for background URL-session callbacks even when the visible UI is entirely Compose. Background tasks must reconnect independently of whether a Compose screen has been instantiated.

### Background downloads and native storage

- Progressive files: background `URLSessionConfiguration.background(withIdentifier:)` plus download tasks/delegate, durable task-to-download IDs, reconnect with the same session identifier at launch, and `getAllTasks`/database reconciliation. `sessionSendsLaunchEvents` supports system relaunch; reserve non-discretionary transfers for user-started downloads. [I9]
- In `didFinishDownloadingTo`, move the temporary file to an app-owned staging location **before returning**. Validate there, then publish atomically; a temporary URL is not a durable download. Store the app-delegate background completion handler and call it on the main queue after all session events are handled. [I9, I10]
- HLS VOD: use `AVAssetDownloadURLSession` and its native asset-download package/selection APIs. Reopen the package through AVFoundation; do not move/repackage its internals as a fake `.m4a`. DRM/offline authorization, when relevant, is a distinct unsupported capability until actually implemented. [I11]
- System termination may allow transfers to continue/reconnect; **user force-quit cancels background URL-session transfers and disables automatic relaunch until the user opens the app**. Background transfers are OS-scheduled, not guaranteed immediate. [I9, I11]
- Background URL sessions only support HTTP(S), require delegates and follow redirects without the ordinary redirect-delegate callback. This matters for untrusted feed/enclosure URLs: strict per-hop redirect allowlisting cannot be promised with this API alone. Validate source/trust policy before scheduling, send no unrelated secrets, and validate response/content. A stricter policy needs a different transport design. [I9]
- Keep retained downloads in app-owned nonpurgeable storage, exclude redownloadable files from backup using the documented resource flag, and choose data protection that permits lock-screen playback after first unlock without abandoning device protection. Test the actual first-unlock/locked behavior. Caches may be purged and cannot be the only evidence of retained downloads. [I16]

## Source and file validation

### Native capability matrix (not a claim of Veyra test coverage)

| Representation | Android Media3 | iOS AVPlayer | Veyra policy |
|---|---|---|---|
| Progressive MP3 | Supported extractor; some files only approximately seekable | Documented file media | Baseline candidate; validate duration/actual seekability |
| AAC-LC in MP4/M4A | Supported container; decoder must support codec | Interoperable Apple media candidate | Preferred extracted audio candidate; native prepare still required |
| HLS AAC, compatible fMP4/TS | Supported HLS module and documented codecs | Native HLS | Supported source category only if resolver provides a valid authorized manifest; separate offline package path |
| DASH | Supported DASH module and documented codecs | Not a baseline native AVPlayer transport | Return iOS-incompatible unless a separately proven adapter exists |
| WebM/Ogg Opus/Vorbis | Supported containers with decoder/device conditions | Do not promise general AVPlayer parity | Android-only candidate after actual support check; seek a real compatible representation on iOS |
| WAV/FLAC/ALAC and other local formats | Container/decoder/device-dependent | Asset/device-dependent | Import/probe, not extension-based promises |
| Static SABR from kmpYT | Requires upstream/custom read/cache adapter | Requires upstream/custom read/cache adapter | Not a native direct URL; explicit capability gap until integrated/tested |
| HTML/JSON error body with `.mp3` URL or 200 status | Not audio | Not audio | Reject as validation/source failure |

Android separates container support from **device decoder** support. [A4] Apple provides `AVURLAsset.isPlayableExtendedMIMEType` for a codec/container preliminary query, but successful real-item loading/preparation remains required. Rate, range requests and duration are also content/server-dependent. [I12]

**kmpYT audited restriction:** the pinned checkout's `SourcePlaybackRequest.requireSupportedSource` rejects nonempty request headers/credentials, live manifests/segments, and mixed transport; it permits progressive or static SABR. `audioOnly` selection does not enforce AVPlayer-compatible codecs. SABR reading has whole-track ingestion/seek limitations, so treating its endpoint as progressive media would be incorrect. An equivalent progressive fallback must come from the inspected report, not URL invention. [K1]

Android can send supported request headers using its HTTP data source. On iOS use documented asset options (including scoped cookies and user agent where available); **do not rely on the undocumented `AVURLAssetHTTPHeaderFieldsKey`**. If a new source requires arbitrary headers for streaming, declare that unsupported or build and test a proper resource-loader path. Generic background `URLSession` download requests can carry headers; that does not imply AVPlayer streaming has the same header contract. [I13]

### Completion/validation pipeline

1. **Before transfer:** verify allowed URI scheme/source, content/representation identity, transport, downloadability, expected codec and safe headers. Do not trust feed titles/extensions as filesystem names. Use app-generated IDs, bounded size/concurrency and a storage check. HEAD may be unsupported or inaccurate; it is advisory and its failure must not block a permitted GET. Feed enclosure length is also advisory, not an authoritative transfer invariant: the integration probe found stale feed lengths and HEAD denial alongside successful ranged GET.
2. **Transfer:** use a staging object, keep incomplete data invisible to the offline library, persist native ID/checkpoint, and update byte progress honestly (indeterminate when total is unknown). Validate HTTP success and range behavior. Resume only when response/range/content validators show the same representation; never concatenate a new full `200` response to old partial bytes.
3. **Byte/container check:** reject empty/truncated/error HTML/JSON; compare validated HTTP range/body lengths and any trustworthy expected representation size, inspect container signatures and parse media metadata. Do not reject solely because an advisory feed enclosure length differs. MIME/extension alone is insufficient, and a signature alone does not prove a playable complete asset.
4. **Native media check:** confirm at least one intended audio track and native load/preparation success, nonzero plausible duration for finite VOD where available, and recorded container/codec. For package/cache formats use their own complete-resource check and corresponding playback data source, not a raw-file validator. A parser open test cannot prove full end-to-end decoding; automated fixtures and sampled/final playback tests add evidence.
5. **Commit:** move/rename a validated progressive staging file atomically on the same filesystem, then transactionally mark the asset ready; record storage kind, relative handle, size, validated type, timestamp, optional hash and validator version. Handle crash gaps via reconciliation. An internally computed hash detects later corruption but does **not** prove authenticity without a trusted expected hash.
6. **Use/restart:** check handle existence/size and stored identity; recover file/DB mismatches. Surface missing/corrupt status with delete/retry, never silently mark it ready or try network when offline mode was requested. Recheck hashes when corruption is suspected without blocking every tap on a full-file scan.
7. **Remove:** coordinate deletion with active playback/transfer references and database updates. Do not evict explicitly downloaded media through the streaming cache policy.

## Seek, speed, progress and sleep behavior

- **Seeking:** use actual native seekable ranges; duration `null` is not zero. Clamp valid finite seek requests, disable unavailable scrub controls for nonseekable/live sources, and allow documented tolerances. Coalesce rapid scrub requests; token both load and seek sequence. AVPlayer may complete an older seek with `finished=false`; never apply that to a newer seek's UI. Sample-accurate zero-tolerance seeking can add latency and is not needed for every drag update. [A4, I15]
- **Podcast skips:** common configurable skip-back/forward intervals (product-selected defaults), same behavior on screen and OS controls. Seek within current episode; queue next is a separate action. Whether music previous restarts after a threshold belongs to common policy, not each backend.
- **Speed:** preserve pitch by using appropriate native time-pitch processing; publish the **effective** rate. Start with tested presets inside 1.0–2.0, then enable 0.5/0.75 only when slow-forward is supported and >2 only when the native item supports it. On iOS `canPlayFastForward=false` does **not** reject 1.0–2.0: ready items support that range. Modern `defaultRate` can preserve the chosen rate across `play()`, availability-gated; otherwise explicitly reapply the intended rate. [I17]
- **Progress:** native content time, not elapsed wall time, is saved. At 2× it advances roughly twice as fast. Persist per episode and maintain played/unplayed independently from transient player state. Do not mark an episode played merely because a source fails or the user switches away.
- **Duration sleep timer:** use monotonic elapsed time while the process is alive, independent of media position/rate, with an absolute deadline for restoration checks. Define whether pause time counts (recommend wall elapsed including pause). A sleeping/suspended app cannot promise an exact timer callback; check expiry before every playback/resume/source-ready action and pause at the first permitted callback. During background audio use native/app-lifetime scheduling, not a composable timer. Never resume after an expired timer.
- **End-of-item timer:** bind it to the current queue-entry token and pause on that item's completion before repeat/auto-next. Manual skip or replacing the entry must follow a documented policy (recommend cancel with visible state). End-of-item is not the same as duration.
- Do not request exact-alarm permission or run silent audio just to make a sleep timer survive suspension. Playback stops with process death; restore the queue paused, reconcile/cancel an expired timer, and wait for user action.

## Verification matrix and release gates

Run common deterministic tests first, Android instrumented/session tests next, then Apple XCTest/simulator and real-device checks. Fixtures must be authored/redistributable. Live source checks are separate and must redact signed URLs, credentials and listening data.

| Test | Required assertions |
|---|---|
| 100 rapid A→B selections with delayed/cancel-ignoring resolver | At most one native output; latest requested entry wins; no stale prepared/end/seek/artwork callbacks change it; duplicate media IDs in queue still work |
| Play/pause/next from UI + external controller concurrently | One ordered command stream; no double next or handler duplication; canonical queue, session, UI agree |
| Continuous music→podcast→music | Same engine/session; correct title/artwork, content type, skip controls and speed; no leaked observers/audio sessions |
| Seek/rate stress while buffering | Last seek wins; bounded position; unknown duration safe; cancelled seek ignored; rate displayed is actual; no accidental auto-play after pause |
| Network loss midstream + return | Buffered content may continue, then honest buffering/error; bounded recovery; same item/position; user pause during outage prevents later auto-resume |
| Expiring/403 URL while playing/download-resuming | One controlled identity-preserving re-resolution; no permanent retry loop, corrupted concatenation or unrelated fallback |
| Incoming call/assistant/other app focus | Correct pause/duck/suppression; transient resumption only if still eligible; permanent loss and intervening pause remain paused |
| Wired unplug / Bluetooth disconnect / reconnect | Pause without speaker leak; UI/session update; reconnect does not auto-resume; connecting during existing playback keeps intended behavior |
| Lock screen and app background ≥10 minutes | Sound/seek/controls work while permitted; duration/rate/elapsed and artwork agree; activity/view recreation does not restart player |
| Android recent-task swipe, FGS limits, notification denied, force-stop | Correct distinct behaviors; no uncaught focus/FGS exception; no missing required foreground notification; no claim of force-stop persistence |
| iOS background, OS termination, user force-quit | Distinguish playback and transfer behavior; reconnect sessions after OS termination; force-quit cancellation is represented truthfully |
| Sleep at 2×, buffering, paused, interrupted and end-of-item | Timer follows its defined wall-time/entry policy; no replay from late source/focus/seek callbacks; end timer prevents automatic next/repeat |
| Download pause/cancel/retry; disk full; process death at rename/DB commit | Accurate persistent states; no partial offline-ready asset; resource cleanup; reconciliation repairs either side of commit |
| Wrong MIME, HTML 200, truncated MP4/ID3, invalid ranges, missing cache span | Correct validation failure; no ready/offline claim based only on byte count/extension/HEAD |
| Restart with network disabled, valid completed local asset | Play **and seek** beginning/middle/near-end through local-only source; metadata/progress remain; absence of network proven rather than assumed |
| Offline missing/deleted/corrupt asset | Safe actionable state and retry/delete; never hidden HTTP fallback or application crash |
| iOS media-services reset | Recreate native objects once, preserve queue/progress, require user play and no observer duplication |
| Accessibility/Arabic external controls | RTL UI does not reverse chronological seek meaning; accessible localized labels; meaningful bilingual metadata; OS layout need not match custom UI |

**Evidence levels must remain separate:** common tests ≠ native compilation; native build ≠ simulator playback; simulator playback ≠ physical interruptions/routes/lock-screen reliability. Android API/target-level matrix must include focus/FGS restrictions; iOS includes the minimum supported version and current supported OS. Actual Bluetooth/headphone, device lock and interruption checks require physical-device evidence where simulation is insufficient. No current Veyra mobile gate is marked passed by this report.

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
- **K1:** Owner-authorized kmpYT checkout, revision `533562f781304338f72b27d32cfe5fb7a83dc3a8`; `kmpyt/src/commonMain/kotlin/dev/kmpyt/extraction/YouTubeSabrStreamingSession.kt` lines 16–46 (plan constraints), 62–79 (report-derived fallback), 122–127 and 213–249 (ingestion/read limitations); `kmpyt/src/commonMain/kotlin/dev/kmpyt/youtube/YouTubeFormatCapabilities.kt` lines 54–61 and 106–118 (playability and audio-only selection). See the separate `kmpyt-audit.md` report for the complete upstream audit and live-source status. Upstream implementation constraints are not platform documentation.

## Cross-review outcomes and pending architecture decisions

The integration-feasibility and kmpYT-audit agents reviewed these boundaries. They agreed with the capability/source distinction and the actual offline-restart gate; their agreement is review evidence, not runtime evidence. Architecture must settle final interface names, DB transaction ownership and the specific native download executor before implementation.

| Challenge | Evidence and resolution | Required regression |
|---|---|---|
| Common queue versus default native session mutation | A1/A8: controllers normally act directly on the player. Keep one coordinator; service/root lifetime; canonical session facade; token originating native work rather than relabeling late events. | Rapid A/B including duplicate media IDs and concurrent UI/OS next; old A end cannot advance B. |
| Generic URL/audio-only selection versus real native capability | A4/I12/K1: container, codec and transport are separate; static SABR is not progressive, and generic `mp4a` is not specifically AAC-LC (`mp4a.40.2`). Select an actually offered supported representation then native prepare. No live playable YouTube candidate was established by the reviewed probes. | WebM-only/SABR-only/denied source is explicitly unavailable; real MP3/AAC proof includes prepare, progressing native playback and seek. |
| Transfer-complete versus durable, valid, offline-playable asset | A5/I9–I11/K1: cache/package/file differ; native executor does not inherit kmpYT transfer checks; iOS background redirects lack per-hop delegate control. Prefer progressive files initially, preserve transfer identity/integrity in the chosen executor, and choose an explicit trusted-background versus strict-foreground source policy. | Complete same-item download, terminate app, deny native network, restart, prepare and seek beginning/middle/near-end; HTML200/truncation/missing spans fail. HEAD denial/stale feed lengths do not reject valid GET. |

Verification also needs native/controller tests plus physical route/background gates, keeping unsupported and unverified target status visible.

Model evidence: parent reports explicit `gpt-6-astra` / `max` spawn configuration was accepted. This agent has no independent effective-runtime model/reasoning attestation and makes no stronger claim.
