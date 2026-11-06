# Desktop audio and distributable runtime

Research date: **2026-11-06**. This is a decision/evidence document, not an assertion that the application or its installers already work.

## Decision

Use a **small JVM/JNA adapter to libmpv's C client API**, behind Veyra's common audio-engine contract. Package the **per-platform `org.openani.mediamp:mediamp-mpv-runtime-…:0.5.3` artifact** as the native runtime; do **not** depend on MediaMP's Kotlin/Compose player or use an installed `mpv`, `vlc`, `ffmpeg`, or JavaFX runtime.

Why this choice:

- Actual published native artifacts exist for Windows x64/arm64, macOS x64/arm64 and Linux x64. They include libmpv, FFmpeg and their portable dependency closure. This is not an instruction for users to install a media player.
- The downloaded Linux binary identifies itself as **mpv v0.41.0 / FFmpeg 8.0.1**. Actual native decoding, HTTP input, pause, seek, rate and EOF were exercised here.
- Audio-only use avoids video surfaces, Compose interop and rendering requirements at the API level. Some platform graphics libraries remain ELF dependencies of this particular build.
- Using the native runtime only avoids MediaMP 0.5.3's Kotlin **2.3.20** / Compose **1.12.0** wrapper dependency. Veyra controls its own playback lifecycle and platform media-session integration.

**Release gates, not footnotes:** the Linux runtime is published as **GPLv3**, unlike the LGPL Windows/macOS builds. Veyra has adopted GPL-3.0-or-later; the owner authorized MIT adoption for `kmpYT` (D007). Complete dependency/contribution audit and corresponding source for redistributed native binaries remain required before public binary release. Missing media runtime or incompatible OS is an explicit startup/capability error, never silent fallback to a developer-installed executable.

## Exact artifacts and platform boundaries

All coordinates below have group `org.openani.mediamp`, version `0.5.3`, and artifact prefix `mediamp-mpv-runtime-`.

| Suffix | Maven JAR bytes | SHA-256 | Boundary |
|---|---:|---|---|
| `linux-x64` | 29,657,424 | `d1169c752a587f8d96311e773cda057ca8563fc5c8cb5088f299d61c95d0a15a` | Upstream Ubuntu 24.04 / glibc 2.39 baseline; GPLv3 |
| `windows-x64` | 22,180,917 | `228642a9a2f22ab4e8bf8096d935e1bbc2493310d8a47a4a321aef9378cb6c26` | Separate x64 DLL set; LGPL2.1+ native build |
| `windows-arm64` | 17,867,716 | `bc6222e61b13b890665c3a019d9058a10cfb7f7a815a20905536b13b6b340229` | Separate ARM64 DLL set; target Compose/JDK support must also be checked |
| `macos-x64` | 25,402,220 | `7c9bda187618d851cc258378126d1afdb48202561b476e9aaaab45947406edf0` | Build flags target macOS 12.0; LGPL2.1+ |
| `macos-arm64` | 21,753,403 | `1ed2def130d751d5c14add0995726670de6426bc4cf868353e963a5c363a656f` | Build flags target macOS 12.0; LGPL2.1+ |

All five original JARs were downloaded and their complete SHA-256 values independently recomputed locally against the pins above. The non-Linux manifest inventory is `.work/desktop-audio/artifacts/nonlinux-manifests.json`: Windows uses `libmpv-2.dll`; both macOS archives contain `libmpv.2.dylib` and `libmpv.dylib`. This verifies archive layout/integrity, **not** Windows/macOS linking or execution. A digest fetched from the same server is reproducibility/integrity evidence, not independent publisher authentication.

The accepted desktop release matrix (PLAN.md) is **Windows 10+ x64, macOS 13+ x64 and arm64, Ubuntu 24.04+ x64 desktop**. These are supported-target requirements, not all verified systems. Windows ARM64 is a native-runtime capability, not a promise that the selected Compose/Skiko/JDK/installer stack supports it; keep it gated. No Linux ARM64 artifact is available in this runtime family. Do not advertise generic Linux or Ubuntu 22.04/Debian 12 compatibility.

The Linux archive expands to **76,228,251 bytes** and contains duplicate soname/version filenames. Preserve the tested closure first; reducing duplicates must retain correct sonames and pass a fresh load test. It includes `libavcodec.so.62`, `libavformat.so.62`, `libavutil.so.60`, `libmpv.so.2`, libass, libplacebo, OpenSSL, font shaping libraries and selected X11 extensions. `$ORIGIN` RUNPATH resolves sibling bundled libraries.

### Linux platform dependencies are still real

This is self-contained **media decoding**, not a statically linked entire operating system. The actual `ldd` result still needs host libc/libm, libstdc++/libgcc, base X11/xcb, GL/EGL/Vulkan loader, ALSA, PulseAudio, fontconfig, D-Bus and associated system libraries. The relevant Ubuntu package roots include `libasound2t64`, `libpulse0`, `libx11-6`, `libgl1`, `libegl1`, `libvulkan1`, `libfontconfig1`, `libstdc++6` and `libc6`; obtain the complete package dependency closure from the final app image, including Compose/JDK requirements. A normal desktop supplies audio/graphics services; a minimal server may not.

- Declare distribution dependencies explicitly in a `.deb`; a clean package installation may resolve declared OS dependencies through the user's package manager. **Veyra must not run privileged installers or attempt system repair itself.**
- CI/workspace restrictions still apply: consume preinstalled runner platform libraries read-only; missing requirements fail a named gate rather than running system `apt` installs.
- For a portable archive, document/check these OS prerequisites. Do not claim that archive is suitable for every distribution.
- The highest GLIBC symbol version found in the downloaded closure was **2.38**; upstream intentionally builds/checks against **2.39**. The observed maximum does not establish compatibility below the documented build baseline.

## Alternatives evaluated

| Engine | Strengths | Why not the chosen baseline |
|---|---|---|
| **JavaFX Media** (JavaFX 25 API examined) | Public player API, play/pause/seek/rate, platform Maven binaries, easy JVM use | Official encoding/container tables omit **Opus, WebM, Ogg and FLAC**. AAC/M4A and MP3 alone are not general YouTube/podcast format coverage. HTTP headers/output-device control are also weaker than libmpv. Do not claim WebM support or assume every extraction has an AAC alternative. |
| **vlcj 4.12.1 + VLC 3.0.24** | Mature libVLC API, broad codecs, time/rate/device APIs, audio-only player possible | vlcj is GPL3+ or commercially licensed; `vlcj-natives` supplies JNA bindings, **not libVLC binaries**. Windows/macOS VLC archives exist, but Linux/plugin/dependency packaging still needs an audited bundle. Copying the whole VLC install also brings plugin licenses. No licensing shortcut over this libmpv bundle. |
| **vlcj 5.0.0-M6** | New libVLC 4 APIs | Maven's `latest` is a milestone, not stable; this line requires development VLC 4, unlike vlcj 4's VLC 3 baseline. Do not accidentally resolve it dynamically. |
| **Raw libmpv + JNA** | C client API, actual known classifier artifacts, codecs/HTTP/seek/pitch-corrected rate/device APIs | Chosen, with adapter lifecycle/security and GPL/Linux-floor gates made explicit. |
| **mpv child process / JSON IPC** | Crash isolation, mature CLI, small JVM integration | Needs separate trusted OS-specific executables; upstream does not provide one official all-platform binary source. Adds process/IPC lifecycle and executable licensing/distribution work. Not needed when a pinned C runtime is already available. |
| **Full MediaMP 0.5.3 wrapper** | Audio-only operation, prepared state machine and common APIs | Pre-1.0 API; pulls Kotlin 2.3.20/CMP 1.12.0 plus UI dependencies into the host. Useful reference, not necessary for this audio-only adapter. Platform media sessions are not provided by the chosen build. |
| **JavaCPP FFmpeg + JavaSound/OpenAL** | Possible LGPL native bundle, explicit decode/filter pipeline | FFmpeg is not a full player: output clock, interruption, buffered transport, seek flush, speed, device handoff and resource ownership become application code. A credible fallback/research option, not a lower-risk finished engine. |
| **Audio-only LGPL libmpv rebuild** | Can disable GPL-only X11/video paths with `-Dgpl=false`; retain LGPL FFmpeg and native audio | Credible future redistribution option, **not an existing verified artifact in this work**. Must build/publish/check exact Windows/macOS/Linux dependency closures and corresponding source. Do not declare the current Linux JAR LGPL by changing metadata. |

Maven metadata observed JNA **5.19.1** and dbus-java-core **5.2.2** as current releases. These are availability observations; final dependency pins still need JVM compilation and native startup tests. Avoid dynamic versions. JavaFX Maven metadata includes EA releases; this research relies on the explicitly versioned JavaFX 25 support table, not on the newest EA label.

## Evidence from this VPS

Read-only inventory: Ubuntu **24.04.4**, Linux x86_64, OpenJDK/Javac/jpackage **21.0.12**. System FFmpeg/ffprobe **6.1.1** exist, but **mpv, VLC and libVLC do not**. Xvfb, PulseAudio 16.1 and dbus utilities exist; no display, session bus or audio server was configured in the process environment. No system packages/settings were installed/changed.

Research assets, downloaded binaries, temporary home/cache/config and probe outputs are contained in **`.work/desktop-audio/`**, not global caches. Synthetic fixtures were generated with the existing system FFmpeg solely as test inputs. **The player used the downloaded libmpv and bundled FFmpeg 8.0.1**, not that executable.

Native test command and outputs:

- Probe: `.work/desktop-audio/probes/probe_mpv.py`
- Results: `.work/desktop-audio/probe-results.json`
- Environment: project-owned `HOME`, `TMPDIR`, `XDG_CONFIG_HOME`, `XDG_CACHE_HOME`, `XDG_DATA_HOME`; native options `config=no`, `vo=null`, `vid=no`, `ao=null`, `tls-verify=yes`.

| Probe | Observed result |
|---|---|
| Pinned libmpv native initialization | Success; reports mpv v0.41.0 / FFmpeg 8.0.1 |
| AAC in M4A, Opus in WebM, MP3, Opus in Ogg, FLAC local files | Opened, decoded with null audio sink, position advanced, pause worked, seek to six seconds took effect, rate 1.75 was accepted, playback continued and normal EOF occurred |
| AAC/M4A and Opus/WebM from controlled localhost HTTP | Extensionless URL + query + required request header + HTTP Range accepted; native decode and seek/play/EOF exercised |
| WAV PCM fixture | Rejected as unrecognized format; this particular FFmpeg build lacks WAV demuxer despite PCM decoder support |
| HTML named `.mp3` | Rejected as unrecognized format |
| Half-truncated WebM | **Opened successfully and ended with normal EOF**, while emitting an incomplete-file warning; reported original duration still looked plausible |
| Real public HPR4764 podcast, native HTTPS | Ahuka's CC-BY-SA-4.0 episode opened through its HTTPS redirector with `tls-verify=yes` and the host CA file; MP3 decoded, position advanced, seek to 60 seconds then back to five seconds continued playing, speed 1.5 was applied |
| Same podcast after full download, fresh network-isolated process | 3,284,906 bytes matched HTTP Content-Length, SHA-256 `3bd3d328bca98a2af68dfe8daf7eaa503f9e117d5d4b2932af23d6663d24f093`; fresh libmpv opened the final **local path** inside `unshare --net`, advanced/seeked both ways at speed 1.5; an explicit egress control returned `ENETUNREACH` |

Real-podcast probe: `.work/desktop-audio/probes/probe_real_podcast.py`, with `podcast-{remote,download,offline}-results.json` and `offline-network.strace`. `strace -f -e trace=network` recorded only the deliberate denied AF_INET egress control, no other AF_INET/AF_INET6 activity. The native-reported duration was **410.344229 seconds**, illustrating that native duration should not be silently replaced with a differing directory estimate. Public enclosure: `https://hub.hackerpublicradio.org/ccdn.php?filename=/eps/hpr4764/hpr4764.mp3`; [episode/license page](https://hackerpublicradio.org/eps/hpr4764/index.html). Media remains a local ignored test fixture, not a shipped asset.

The speed setting and media progression are verified, but short-window measurements around asynchronous seeks are not precise tempo conformance measurements. Pitch correction was reported enabled; no spectral/audible pitch proof has been performed.

**These are native-engine transport/codec/control and cold local-file tests, not audible-listening evidence, clean-machine install evidence, Veyra downloader/database evidence, Compose UI evidence, real Bluetooth routing evidence, or successful YouTube extraction.** The HTTPS probe used the host CA file, not an already packaged CA solution. Windows/macOS cannot be executed on this Linux VPS. Null-sink progression must never stand in for physical/audio-server verification.

## Common contract and adapter responsibilities

The common coordinator, not a composable or the native decoder, owns queue advancement, current item, sleep timer and restore policy. Align desktop/mobile around:

- `PlaybackToken(entryId, generation)` and a monotonically increasing seek sequence. Every asynchronous ready/error/ended event carries the token; ignore stale source/seek completions.
- An input model distinguishing **transport, container, codec and identity**, with an HTTP(S) URL plus request headers/expiry or an opaque local file handle. Keep `stableMediaId` separate from the expiring URL. A URL string alone is not enough.
- `prepare`, desired play/pause, seek, rate, release; actual native phase, desired `playWhenReady`, buffering/suppression, position, duration/seekable ranges, buffered progress, selected capability-supported rate, terminal error.
- Unknown duration/live/unseekable are real states. Clamp seeks to known seekable ranges and expose capabilities rather than inventing duration/rate support.
- Output device selection and application gain are optional desktop capabilities. **System device volume is not the same as app gain**, and should not be required by the mobile common contract.
- Wall-clock sleep timer deadlines, independent of media position/rate. Route disconnect pauses; reconnection does not auto-play without valid user intent.

Desktop implementation details:

1. Verify/select native bundle by actual OS **and JVM architecture**; reject unknown combinations instead of mapping everything unknown to x64/Linux.
2. Load only the application-controlled absolute native path, with bundled sibling lookup. Disable system discovery and environment-driven binary fallback.
3. Create one active playback handle and one owner executor/coroutine context. Never block Swing/Compose UI on native loading, transport, native commands or destruction.
4. Configure before initialization: `config=no`, no video, no terminal/default keyboard input, finite network timeout, **TLS verification enabled**. The chosen build has Lua/JavaScript disabled and no `ytdl` option; do not spawn an extractor from mpv.
5. Use the C command/property APIs (not a shell command string); pass per-resource headers safely as an array/node or correctly escaped option list. Reject header CR/LF and avoid logging headers, signed URLs, cookies or tokens. Reset all source-specific headers/referrer/user-agent on each preparation so one item cannot inherit another's credentials.
6. Consume `mpv_wait_event` from one event owner, translate `FILE_LOADED`, buffering/property changes, `SEEK`/`PLAYBACK_RESTART`, `END_FILE` and errors into tokenized common state. Bind the token to the **originating native load/playlist-entry ID**, not whatever item happens to be current when a callback arrives; delayed item A EOF must not advance item B. Command acceptance is not seek completion. Check actual native position and continued playback.
7. Own all native allocations/callback lifetimes. Free strings/nodes with matching libmpv APIs; retain JNA callbacks while native can call them. Cancel/remove observers before teardown. Repeated play/seek/close must not leak handles or crash during an old callback.
8. Keep streaming URL refresh in the source adapter: on a classified expired-URL/auth response, re-resolve the same stable item and restore last confirmed position/desired intent with a bounded retry budget. Do not retry every decode error as URL expiry.

### TLS is an explicit blocker to naive integration

The actual native default was **`tls-verify=no`**. Media playback succeeding under that default is not acceptable HTTPS evidence.

- Set `tls-verify=yes` explicitly and test the resulting value and connection behavior.
- On OpenSSL-backed builds, provide an application-owned, provenance-recorded CA bundle and set `tls-ca-file` as needed; do not assume the JVM truststore configures FFmpeg's TLS backend. Schannel/Apple-backed builds may use platform trust differently: verify each actual build.
- Gate packages on one trusted HTTPS resource succeeding and expired/self-signed/untrusted certificate fixtures failing. Never work around CA problems by disabling verification.
- A Ktor-verified loopback range relay is an alternative only if designed/tested intentionally; it adds authorization, cancellation, range and proxy-lifecycle responsibilities. It is not necessary merely because native HTTP exists.

## Download and offline-file correctness

Playback-ready is **not** download-complete. The truncated WebM experiment establishes this directly.

An offline asset is committed only after:

1. Download to an app-owned `.part` file; resolve only approved HTTP(S) sources; check HTTP status, redirect policy, MIME plus media sniffing and ensure the response is not HTML/login/rate-limit data.
2. Track successful transfer termination and reconcile actual bytes with trustworthy `Content-Length`/`Content-Range` total when available. A resumed response must have the expected range and strong validator/resource identity. HTTP 200 to a resume request means restart, not append. A changed source format/itag starts a new file.
3. Treat missing total size as unknown, not zero or complete from duration. Whole-body transfer EOF is necessary; demux/read validation adds confidence but does not magically recover missing completeness information.
4. Validate the staged asset with a native media/demux probe; require an audio stream, supported codec/container and plausible metadata. A local digest protects later file integrity, **not remote authenticity or original completeness** unless an authoritative expected digest exists.
5. Flush/close; atomically rename within the same filesystem; transactionally publish the final path, byte count, local digest, format and completion state. Reconcile crash leftovers instead of marking all existing files complete.
6. Reopen the **final local path** in a fresh player/process with network disabled; seek forward/back and finish playback. Cached remote data or a resolver URL must not satisfy an offline test. Missing/corrupt files demote the DB state visibly.

The packaged libmpv runtime does **not** include `ffprobe` (`--disable-ffprobe` was observed in FFmpeg's build configuration). Do not add a production `ffprobe` subprocess dependency just because this VPS happens to have it. The native libraries may be used for a targeted validation adapter, or a separate audited helper must be explicitly bundled. A seek-to-tail/readiness check alone cannot reject all truncation cases.

## Media keys, system UI, and output changes

Codec engines are not platform media-session integrations. MediaMP's actual build explicitly disables `win32-smtc` and `macos-media-player`.

### Linux

- Export the MPRIS2 session-bus service `org.mpris.MediaPlayer2.Veyra` (with a valid instance suffix if needed), `/org/mpris/MediaPlayer2`, root/player interfaces.
- Implement Play/Pause/PlayPause/Next/Previous/Stop/Seek/SetPosition against the same coordinator, not a second queue. Use stable object-path track IDs, microsecond position, accurate CanSeek/rate bounds and metadata. `SetPosition` with a stale track ID must do nothing.
- Emit `PropertiesChanged` and `Seeked` correctly. Position is not broadcast as a high-frequency property update. No session bus is a supported unavailable capability, not an app crash.
- Candidate dbus-java transport must be packaged and tested with JDK modules/Unix socket support; D-Bus native dependency should not be assumed simply from a core JAR name.

### Windows

- Use **SystemMediaTransportControls** for a Win32 desktop HWND via **`ISystemMediaTransportControlsInterop::GetForWindow`**, rather than UWP-only assumptions about a current view.
- Provide a small bundled JNI/C ABI C++/WinRT bridge for metadata, playback status/timeline and button/position callbacks. Build the bridge for every supported native architecture; marshal callbacks back to the single coordinator and unsubscribe on release.
- A global keyboard hook is not a replacement for native lock-screen/media overlay integration. Lack of a built/smoke-tested bridge means report the feature unavailable, not “supported by mpv”.

### macOS

- Use `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`; Apple documents macOS **10.12.2+** for these APIs, below the proposed app floor.
- A small Objective-C++ JNI/C ABI bridge updates title/artist/artwork/duration/elapsed/rate and routes supported commands. Retain/remove handler tokens, update on appropriate native queues, and sign the bridge as part of the app.
- Do not ask for Accessibility permission just to capture media keys. Platform MediaPlayer APIs are the intended integration; actual command availability must be verified on packaged signed/unsigned test builds as appropriate.

### Output routing

libmpv exposes `audio-device-list`, `audio-device` and audio reconfiguration events. Default device should normally follow the OS. Persist explicit selection by opaque stable ID, not list index; recover visibly when the selected device disappears. Pause on headphone/device loss according to common policy and do not auto-resume on reconnect. WASAPI/CoreAudio/PulseAudio behavior, hotplug and actual output need on-platform tests; querying a device list on this VPS is not route-change proof.

## Packaging and release proof

1. Use Compose Desktop's jpackage integration to create an **app image with a bundled JDK runtime**; JDK 17+ is required by the plugin, while the local test toolchain is JDK 21. Do not ship only a fat JAR and require Java from the user.
2. Build on matching OS/architecture: jpackage/Compose native distribution tasks are not cross-platform installer builders. Linux VPS success cannot produce evidence for a Windows installer or notarized macOS app.
3. Select one native classifier per package, plus JNA and required runtime Java modules. Do not include the all-platform aggregator in every installer. Native extraction must have a strict allowlist/manifest, digest validation, atomic/versioned private directory, process lock and path-traversal protection. Avoid executable bits/paths influenced by media input.
4. Prefer immutable pre-extracted native libraries inside the app image, with application-selected absolute load paths. If extraction at first run is required, use a Veyra-owned versioned directory, never uncontrolled shared `/tmp`, `~/.cache/JNA` or a writable working directory. Set JNA temp/extraction properties **before** initializing JNA; its own JNI dispatcher also needs this policy.
5. Windows bundles all non-OS DLLs and uses secure sibling DLL lookup. macOS dylib install names must be relative (`@loader_path`); sign nested libraries before the app, then notarize/staple with owner-provided credentials only. Extracting unsigned libraries after signing can invalidate hardened-runtime/library-validation assumptions. No signing identity is available/assumed here.
6. Linux package metadata/checks must match the actual ELF/Compose/JDK closure and supported baseline. List required OS libraries; fail cleanly on unsupported glibc. Do not bundle the host's libc/graphics driver stack to fake portability.
7. Run **the packaged executable and bundled JVM**, with external Java/mpv/VLC/ffmpeg hidden from PATH and a clean Veyra data directory. Exercise real source open, playback/seek/speed/error, download commit and a network-denied cold offline restart. Scan dynamic loads to prove the packaged decoder was used.
8. Run OS media controls and output tests on Windows/macOS/Linux desktop sessions. A hardware/device listening gate is separate from CI null-sink decoding. CPU-only CI can prove load/demux/control/lifecycle, not physical audibility.

### Redistribution/licensing checklist

- Record **every** bundled native filename, architecture, version, origin, digest and license; include notices and source/build provenance in the distribution.
- MediaMP source reference **v0.5.3** pins `mpv` commit `41f6a645068483470267271e1d09966ca3b9f413`, FFmpeg `449453a9713aa1af31a12e148fb0caba80ed02d7`, dav1d `54706fc6bc0cdecab7e9593974a4039cc038fca7`. Those three revisions alone are not the full corresponding-source inventory: platform patches and all copied dependency versions/build instructions must be captured too.
- The Linux runtime JAR observed here contains libraries and a native manifest, **not a complete license/source compliance package**. Publishing a Maven link or a GPL text alone is insufficient for the corresponding-source obligation. Gate public release until this is assembled and reviewed.
- Upstream config sets `-Dgpl=false` for Windows/macOS; Linux enables GPL-only X11 paths and is published GPLv3. FFmpeg reports LGPL2.1+ locally. Do not infer the whole bundle license from FFmpeg alone or from MediaMP's top-level Apache license.
- This is engineering compliance scoping, not legal advice. Owner decisions on application/upstream licensing and signing credentials remain explicit gates.

## Cross-review outcomes / top three challenges

1. **Source/adapter integration:** real podcast/YouTube discovery is not a playable format, and successful ffmpeg/HTTP probes are not the Veyra native player. Require same-item source identity, verified TLS/headers, post-seek convergence and clear unavailable/auth errors. kmpYT Node/browser/FFmpeg requirements must be separately packaged or eliminated with source-adapter evidence; libmpv does not solve them. The source audit identifies progressive URLs versus static SABR: **a SABR endpoint is not a native audio URL** and must pass through its proven whole-track/segment reconstruction adapter. Do not “test streaming” by handing the SABR URL directly to mpv. FFmpeg is not required merely to play an already progressive AAC audio-only file.
2. **Offline assurance:** half-truncated WebM returned normal native EOF. Transfer completeness, safe resume, commit ordering and fresh network-denied final-path playback are mandatory; Ready/Ended or file existence alone are false proofs.
3. **Release assurance:** packaged bundled-JDK executable on each supported OS/architecture, dependency closure, controlled JNA/native extraction, real output/media-session gates and **full GPL/LGPL corresponding source** are separate from Linux ctypes success. No system package installs to conceal gaps, no unsupported architectures or signing claims.

Review received from `verification_ci_research` and `integration_feasibility` concurs on those separation-of-proof and licensing gates. See also [verification/CI](verification-ci.md), [source integration](integration-feasibility.md) and [mobile audio](mobile-audio.md).

## Implementation checkpoint — 2026-11-06

**Verification status:** the first real `:composeApp:compileKotlinDesktop` invocation
passed shared JVM/Room compilation but failed on two root UI receiver errors and one
desktop cross-module nullable-hash smart cast. The latter is fixed in source; another
build is required. Evidence: `.work/desktop-first-compile.log`. Desktop authored tests
and Windows/macOS C++/Objective-C++ have **not yet been executed** at this checkpoint.

- `MpvAudioDeck` owns one native handle/thread. `DesktopAudioEngine` is the public
  coroutine-actor dual-deck adapter: muted/paused preload, native-clock equal-power
  overlap, lossless proposal/ACK, generation attribution, prompt native wakeup and
  idempotent ephemeral-source ownership. Outgoing ownership stays cancellable until
  the shared coordinator durably commits and ACKs; incoming ordinary events remain
  private before ACK.
- MUSIC uses fixed −3.5 dB master headroom (`CrossfadeMix.MUSIC_GAIN`, approximately
  0.66834), including crossfade-disabled playback. EPISODE uses unity gain and never
  stages automatic overlap. Correlated peak sum is bounded to approximately 0.9454;
  ordinary cancellation uses a coordinated 60 ms convex return, while pause/stop
  silence immediately. These are implementation/math claims, **not a measured
  native limiter or physical-output claim**.
- `stop` destroys/joins the outgoing handle before returning the asset-removal
  barrier, then recreates idle; `cancelNext` similarly destroys the staged handle.
  Mere acceptance of an mpv stop command is not a Windows file-release guarantee.
- Native asset validation uses a separate full-decoding NULL handle, size/hash checks,
  corrupt/incomplete warnings and a typed finite validation timeout. Bundled Mozilla
  CA roots are pinned; positive bundled-CA HTTPS and negative TLS behavior tests still
  need execution. The old direct HPR probe is decoder research, **not D005 proof**.
- `DesktopProcessContext` acquires a private data-directory lock before Room/audio.
  Cross-review reproduced a POSIX hazard: closing a second same-process descriptor
  after `OverlappingFileLockException` releases the owner's kernel lock. A canonical
  process registry now rejects duplicates **before open**, remains reserved through
  close, and makes repeated close harmless. An authored fresh-JVM regression checks
  the kernel lock before/after rejected probes and after release; awaiting execution.
- Main now hosts `AppContainer`/`VeyraApp`, tray/quit behavior, private CLI data/result
  paths, real native self-test, frame-render UI smoke and ordered cleanup. A missing
  system session is degraded capability, not a reason to construct another player.
  MPRIS has a private-bus regression; Windows SMTC HWND and macOS MediaPlayer bridges
  are source-only. Linux default-route monitoring remains to be implemented.
- `/proc/asound/cards` reports **no soundcards**, and `/dev/snd` has only seq/timer.
  Physical desktop listening/crossfade remains an unmet requirement. NULL or virtual
  PulseAudio output must not be relabeled as physical evidence.

### Clean-machine source helpers — in progress, not provisioned yet

The upstream runtime-options commit `84afce07b7f996a316847212e094a507711f9141`
accepts vetted absolute Node/Chromium paths and scopes the child temporary directory
without changing global environment variables. Root added cancellable
`PlatformServices.prepareMediaRuntime` and setup status. Acquisition invokes the
suspend gate; synchronous `YouTubeJavaScriptRuntime.prepare` must **not** download
helpers. Offline library/startup and local playback do not depend on helpers.
Upstream source review confirms search/home/channel/playlist/more use only the
public browse transport; `details()` calls inspect and may require the gate too.

Official Node22 **v22.23.4** archives and complete checksums/licenses were fetched
under `.work/desktop-audio/helper-research/`. Linux x64, Windows x64, macOS x64/arm64
digests all match official SHASUMS. Node/npm are not installed globally or executed.

Official Chromium snapshots were fetched and inventoried, not executed:

| Platform | Revision | Archive bytes | SHA-256 |
|---|---:|---:|---|
| Linux x64 | 1737260 | 261,911,787 | `a8b703d4d92fecda979b944dae5c2b8bad4ebd1968c6134d16f09b249a39dc9b` |
| Windows x64 | 1737213 | 382,475,232 | `746fcbb8b65ed4d0e6a7f0dac07ad24f7cb5c835af57bc0296c547f92be1cf98` |
| macOS x64 | 1737266 | 205,961,875 | `840c1cca12dbf1b7c29990da762efbf14105799443c9987faef2b8e05540b443` |
| macOS arm64 | 1737272 | 185,964,695 | `a18cd36657ae51a308c56e6ce8902ec4e7fd041328b50a6bc6a7b54bd6177d86` |

Their immutable Git revision receipts, full path/mode/size manifests and URLs are
in that research directory. Each macOS archive has five contained framework
symlinks. No standalone license/credits file exists in these ZIPs; Chromium's BSD
root license has been fetched, but complete bundled-third-party notices still need
resolution. No Chrome sandbox-disabling flag is permitted. Root execution sandbox
refusal must be reported truthfully.

Commons Compress1.28.0 is approved for bounded safe archive extraction (Apache2;
dependency closure audit pending). Planned installer uses private staging, whole
archive pins, bounded streaming, contained links only, executable permissions,
atomic commit, cancellation and retryable visible setup failure. It must never
execute an unverified archive or use a developer's PATH tool as a silent fallback.

For HLS/DASH remux downloads, the accepted direction is a minimal CLI built from
pinned official FFmpeg source on matching hosts, bundled next to native helpers.
Static SABR single-audio materialization does not itself require FFmpeg. Exact
feature closure/source pin/build tests and packaged execution are still pending;
this direction is not a claim that FFmpeg is already bundled or usable.

## Primary sources

- [MediaMP runtime Maven directory](https://repo.maven.apache.org/maven2/org/openani/mediamp/mediamp-mpv-runtime-linux-x64/0.5.3/) and [platform artifacts index](https://repo.maven.apache.org/maven2/org/openani/mediamp/).
- [MediaMP v0.5.3 native targets and licensing switches](https://github.com/open-ani/mediamp/blob/v0.5.3/buildSrc/src/main/kotlin/mpv/MpvTargets.kt), [dependency closure implementation](https://github.com/open-ani/mediamp/blob/v0.5.3/buildSrc/src/main/kotlin/mpv/MpvTaskTypes.kt), [Linux baseline/dependency notes](https://github.com/open-ani/mediamp/blob/v0.5.3/mediamp-mpv/dependency.md), [runtime publishing](https://github.com/open-ani/mediamp/blob/v0.5.3/buildSrc/src/main/kotlin/mpv/MpvRuntimePublishing.kt).
- [mpv C client API](https://github.com/mpv-player/mpv/blob/v0.41.0/include/mpv/client.h), [options](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst), [commands/properties](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/input.rst), [licensing](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright).
- [JavaFX 25 supported media types](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/package-summary.html).
- [vlcj 4.12.1 README/license/minimum VLC](https://github.com/caprica/vlcj/blob/vlcj-4.12.1/README.md), [vlcj Maven metadata](https://repo.maven.apache.org/maven2/uk/co/caprica/vlcj/maven-metadata.xml), [VLC 3.0.24 archives](https://download.videolan.org/pub/videolan/vlc/3.0.24/), [VideoLAN licensing](https://www.videolan.org/legal.html).
- [Compose native distributions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-native-distribution.html).
- [MPRIS 2 specification](https://specifications.freedesktop.org/mpris-spec/latest/), [player interface](https://specifications.freedesktop.org/mpris-spec/latest/Player_Interface.html).
- [Windows SMTC interop/GetForWindow](https://learn.microsoft.com/en-us/windows/win32/api/systemmediatransportcontrolsinterop/nf-systemmediatransportcontrolsinterop-isystemmediatransportcontrolsinterop-getforwindow), [SMTC integration](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/system-media-transport-controls).
- [Apple MPRemoteCommandCenter](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter), [MPNowPlayingInfoCenter](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter).

Fetched reference copies and experimental binaries are intentionally in the ignored project work area; no third-party binary or podcast media is proposed for Git source control.
