# Desktop media-session helper

Linux uses the bundled dbus-java MPRIS implementation, not this C bridge.
Windows uses the real Compose HWND with
`ISystemMediaTransportControlsInterop::GetForWindow` (not UWP
`GetForCurrentView`). macOS uses MediaPlayer remote commands/Now Playing.
Commands and output-device changes route to the app's existing audio owner;
these helpers never create or resume an independent player.

## ABI 2: ownership is not readiness

[`include/veyra_session.h`](include/veyra_session.h) is the authoritative C/JNA
contract. The host must serialize calls using one handle.

| Call/result | Host obligation |
|---|---|
| `veyra_session_abi_version()` | Require **exactly 2 before create**. ABI 1 had a void close and is incompatible. |
| `create` returns null | No native registrations were acquired; report the unavailable capability. |
| `create` returns a handle | Take ownership **before the first update**. This can be a partially initialized, inactive session, not proof of readiness. Every non-null handle needs close. |
| `update` returns 1 | Projection accepted. On Windows it is queued, not confirmation that Windows processed or displayed it; asynchronous failure is reported by a later update. |
| `update` returns 0 for a valid handle | Callback admission is permanently retired. Do not revive/retry that session; close it and preserve any incomplete-cleanup outcome. |
| `close` returns 1 | Required cleanup and JNA-callback drain succeeded; the handle is **consumed**. Never call it again. The host may release its JNA callback. Null close also returns 1. |
| `close` returns 0 | Cleanup is incomplete and the handle is **not consumed**. Retain the handle, callback and library. Future callback admission is disabled, but callback/native cleanup is not proven drained. This failure is sticky, **not a retry API**. |

Keep the native library loaded for the **entire process lifetime**, even after
close returns 1: already-enqueued platform wrappers can finish native-only
work after JNA admission is retired. A successful close is not a claim that
every platform wrapper has returned or that all process-lifetime memory was
freed.

Callbacks must only enqueue host work. Do not close inline from a callback,
or synchronously wait for another operation that can close the session.
Direct reentrant close returns 0; cross-thread callback/wait dependency cycles
cannot be detected. JNA callback admission begins inactive and cannot reopen
after retirement.

UTF-8 metadata is copied during update. Signed audio URLs, request
headers/cookies and remote artwork must not be passed to this API; artwork is
an optional local file path. Seek callback values are milliseconds; rate values
are playback multipliers. See the header for command/capability values.

### Windows output-notification lifetime

`IMMNotificationClient` callbacks only try to post to a preallocated bounded
mailbox (16 entries, IDs up to 1024 wide characters). They do not wait for its
mutex or call JNA. Contention, saturation or an unrepresentable ID coalesces
into a conservative pause request; route interpretation and JNA delivery run
on the owned MTA worker. Its 50ms timed wake is a missed-notification fallback,
**not a guaranteed route-response latency**.

After successful unregister, an inert observer and mailbox are deliberately
retained until process exit because IMM provides no separate queued-callback
drain guarantee. This is a bounded retention **per created session**, not a
leak-free or globally bounded repeated-session claim. The retained observer
has no Session or JNA pointer. Incomplete native cleanup instead retains the
failed session owner under the close-0 contract.

## Scoped matching-host build

Use existing Python 3.11+, CMake 3.24+ and Windows SDK/MSVC or Xcode
command-line tools. Do not install tools or change global configuration.
The repository preparation entry point keeps homes/caches/temp/output local
and uses the owned, awaited process runner. For example, **on arm64 macOS**:

```sh
bash scripts/dev.sh python3 -B native/bundle.py --target macos-arm64
```

Choose the actual matching host: `windows-x64`, `macos-x64`, `macos-arm64`, or
`linux-x64`. Do not cross-label an artifact. This command also prepares the
source-built FFmpeg helper; it is not a session-only build or a native test.
Honor the repository's serialized build slot. On cleanup failure retain the
owned work/logs for inspection; do not delete them or silently start a retry.

Windows builds the session helper before the cold FFmpeg stage. Its scoped
MSBuild children disable node reuse so they exit within the owned process tree.
CMake configuration and compilation keep separate `configure.log` / `build.log`
files under `.work/native-session/<target>/`, with explicit phase markers.
The configured deadlines and complete descendant-drain requirements still apply.

Fat/uber-JAR distributions are unsupported: Compose rewrites the whole-hash-pinned
native-runtime archive. Both uber-JAR tasks fail before task execution, including
with `--dry-run`. Use `createDistributable` or native installers instead. A plain
`desktopJar` is not a standalone distribution.

Session output is `.work/native-session/<target>/output/veyra-session.dll`
(Windows) or `libveyra-session.dylib` (macOS), including multi-configuration
generators. The helper sets the macOS architecture explicitly to `arm64` or
`x86_64` with deployment target 13.0. Windows uses the static MSVC CRT.

Staged resources belong under `.work/desktop-native/resources/<target>/` at
classpath path
`dev/veyra/desktop/native/{windows-x64,macos-x64,macos-arm64}/<filename>`.
There is no host/PATH session-helper fallback. The app bounds the packaged
helper to 16MiB and checks its extracted copy against the packaged bytes before
loading. An inventory/hash check is not a signature or clean-machine proof.

On macOS sign the Mach-O before embedding it, then sign the app's nested code
and bundle; generate the final inventory from the actual shipped bytes.
Unsigned developer builds are not notarized releases. Do not disable library
validation as a substitute for correct signing.

## Verification remains separate

This ABI-2 revision is source-reviewed, **not yet verified by matching-host
Windows/macOS compilation or native command/route/cleanup execution**. Linux
process fixtures and fake Windows Job-policy tests cannot establish those
results. Packaging, signing and clean-machine startup remain separate gates.

The libmpv/FFmpeg runtime, corresponding sources/notices, real central-source
playback and mandatory physical audible crossfade gates are also separate;
see [`docs/research/desktop-runtime.md`](../docs/research/desktop-runtime.md),
[`native/licenses/`](licenses/) and [`native/ffmpeg/README.md`](ffmpeg/README.md).
