# Desktop media-session helper

Linux uses the bundled dbus-java MPRIS implementation. Windows uses the real
Compose HWND with `ISystemMediaTransportControlsInterop::GetForWindow` (not UWP
`GetForCurrentView`). macOS uses MediaPlayer remote commands/Now Playing. Output
device notifications only ask the same app audio owner to pause; they never
start or resume an independent player.

Build on the matching host with an existing CMake 3.24+ and Windows SDK/MSVC or
Xcode command-line tools. Configure/build output must stay inside this repo:

```
cmake -S native -B .work/native-session -DCMAKE_BUILD_TYPE=Release
cmake --build .work/native-session --config Release
```

Output is `.work/native-session/output/veyra-session.dll` (Windows) or
`libveyra-session.dylib` (macOS), including multi-configuration generators. Set
`CMAKE_OSX_ARCHITECTURES` to the target's `arm64` or `x86_64`; do not infer the
target from a packaging filename. Windows uses the static MSVC CRT.

The build integration must put the matching binary at classpath resource:
`dev/veyra/desktop/native/{windows-x64,macos-x64,macos-arm64}/<filename>`. No
host/PATH helper fallback exists. The app checks its extracted copy against
the trusted packaged resource on every start. On macOS sign this Mach-O before
embedding it, then sign the app's nested code and bundle; unsigned developer
builds are not notarized releases. Do not enable disabled library validation
as a substitute for correct signing.

`include/veyra_session.h` is the versioned C/JNA contract. All callback
registrations are removed/drained before close returns. UTF-8 metadata is
copied; signed audio URLs, request headers, and remote artwork are not passed
to the session. These sources require real Windows/macOS compilation and
native command/route tests; their existence is not verification.

The libmpv runtime and source redistribution/license gates are separate; see
`docs/research/desktop-runtime.md` and `native/licenses/`.
