# Completed five-platform run 42615340845

The manual run tested `c4a3f1ec669655de1af63ea95adf05b6bfdceba0` with
`scope=android-desktop`, packaging and draft retention enabled. It failed overall.
The empty PR check rollup is separate from this actual workflow result.

| Lane | Retained test results | Package result |
| --- | --- | --- |
| Android | 873 tests, zero failures/errors/skips; lint passed | Debug APK, unsigned release APK and AAB retained |
| Linux x64 | Shared 1,211 passed; desktop 484 executed, 10 failures | DEB and exact inventory retained |
| Windows x64 | Shared 1,211 executed, 2 failures; desktop 484, zero failures, one skip | FFmpeg configure completed; Make failed before installer generation |
| macOS arm64 | Shared 1,211 executed, 2 failures; desktop 484, zero failures, one skip | Native preparation completed; Gradle composite packaging graph stalled |
| macOS x64 | Shared 1,211 passed; desktop 484, zero failures, one skip | DMG and exact inventory retained |

Independent hosted production readback passed. The lead also downloaded and
verified all five support archives against their manifests, every indexed entry
size/hash, exact tag targets and unpublished draft/prerelease state. This verifies
retained evidence, not installation, signing, physical audio or release readiness.
Local receipts and original logs are under
`.work/verification/task-b-combined-42615340845/`.

## Observed failures and corrections

### Linux audio dependency and shutdown

The authenticated Xlib display check passed, and actual Kotlin tests ran. Nine
native audio tests failed because the pinned MPV runtime needs the host's
`libpulse.so.0`, absent on this runner. No system package installation is added.
`scripts/linux-audio-test-deps.py` uses a committed exact Ubuntu 24.04/amd64 package
metadata snapshot to download exact hash/size-verified Ubuntu audio client packages into
`.work`, verifies package control identities, extracts there and performs a real
loader check. Only the Linux test subprocess receives its library path. These
packages are not added to Veyra distribution resources. Other host/runtime and
package dependency closure remains a release concern.

Local proof exercised ten real Ubuntu noble packages: official HTTPS metadata,
real downloads, hash/control checks, extraction and a successful loader child.
The final helper run uses the committed package metadata directly; only distro
detection is supplied through a seam;
this is not a hosted Ubuntu execution or an independent signature verification.
Six authored metadata/download/refusal tests also pass.

The remaining Linux integration failure was an uncaught
`ClosedSendChannelException` during app cleanup. Playback's actor closed producer
channels before cancelling the parent. Shutdown now cancels producers first and
closes channels with a cancellation cause, preserving buffered-command disposal
and making a concurrently executing late producer observe cancellation. The
integration fixture now reports complete exception stacks on failure.

### Windows FFmpeg paths and database lease

The original retained diagnostic proves Make rejected the Git Bash `/d/...`
absolute source include. Configure no longer timed out. Windows now builds inside
its private extracted source tree, an upstream-supported configuration with
`SRC_PATH=.`; the pinned archive is unchanged. Linux/macOS retain out-of-tree
builds. An authored real-Make discriminator passes; Windows compilation still
requires the successor hosted run.

One crash-recovery test now fails at the external database lease boundary, before
SQLite open. The prior blanket lease wrapper hid its underlying exception. A
Windows-only bounded retry allows up to three seconds for an unavailable kernel
lease or transient access denial; a live holder is never bypassed. Same-JVM
registry rejection remains immediate, other I/O and interruption propagate, and
the original exception is retained as the safe public failure's cause. Four
clock-controlled regressions cover eventual acquisition, permanent holders,
access denial, other I/O and interruption. The exact prior OS-level cause is not
proven by the retained wrapper-only stack; this remains a bounded recovery
measure awaiting actual Windows execution.

### Cleanup scheduling and foreground expiry

Windows and macOS arm64 both timed out in the recovery test after its single fake
clock advance. The competing scheduler can discover the held path just after that
advance and set a later backoff that the fixed clock never reaches. The test now
follows the actual local wake deadline only while there is no admitted lease;
it preserves its real Room/file/ownership and no-transfer assertions.

The ARM expiration test also exposed a public removal race: an old foreground
epoch can refuse its ACK after a new healthy foreground epoch resumes. An active
Remove caller now waits for authoritative durable removal through the existing
bounded observer in that specific healthy-host case. Suspended/closed/failed
hosts still refuse, and success still requires no asset, staging, finalization,
reservation, maintenance lease or active attempt. A failed old drain alone is
never deletion evidence.

### macOS packaging graph

The ARM packaging log records `Unable to make progress running work`, one waiting
worker and unresolved included-build JAR work; that JAR then appears completed
while the root build fails. Native preparation had completed. The package graph
now has two bounded Gradle workers and stack traces. This changes the admission
condition implicated by the log; it does not establish the exact Gradle internals
cause or prove macOS packaging success. Kotlin compilation remains in-process and
root-project parallel execution remains disabled.

## Verification and limits

The first local focused run passed 32 shared tests and 49 desktop tests, including
the recovery/removal cases, integration shutdown and native audio. The final run
passed 36 shared tests in five suites and 49 desktop tests in three suites, with
zero failures/errors/skips, and executed `:shared:compileDebugKotlinAndroid`. It
includes all four new Windows lease policy tests and the final cancellation
channel cause. The exact changed Kotlin files were stable during that run; the
checkout was dirty, and script/docs edits continued independently. This is not a
clean-commit execution claim. `local-final-receipt.json` records the file hashes.

Cheap checks pass: FFmpeg recipe 27, tooling 17, workflow 16, desktop package legal
7, audio dependency helper 6; shell and Python syntax and whitespace checks pass.
No full native rebuild is repeated on Linux for a Windows-only build layout
change. No iOS lane, device, live-source, signing, distribution publication or
Task B acceptance follows from this work. Existing legal/native/JDK coverage and
physical audio holds remain.

Final Android/audio followup passed on the same unchanged Kotlin source: 713
shared Android unit tests + 160 Compose Android unit tests (873 total), Android
lint and debug APK assembly, plus 10 silent native audio/crossfade tests with the
extracted Ubuntu library path. An independent loader child loaded the pinned
MPV library and verified `/proc/self/maps` places both PulseAudio libraries in the
owned Ubuntu sysroot. `local-android-audio-receipt.json` binds counts and the APK
hash; `ubuntu-mpv-loader.json` records the actual mappings. No physical device,
GUI install, audible output or hosted successor success is claimed.
