# Trial installer workflow

The owner requested installable Android, Windows, macOS and Linux builds to try
Veyra. The manual **Veyra trial installers (APK, EXE, DMG, DEB)** entry point is
`.github/workflows/installers.yml`. It calls the existing verification workflow at
the same commit with `scope=installers`, desktop packaging and retention enabled.
It deliberately skips the full test suite; retention records `tests=skipped`,
and successful lanes still require the actual package and runtime inventory.

| Target | Trial output |
| --- | --- |
| Android | `composeApp-debug.apk`, debug signed and installable |
| Windows x64 | `Veyra-1.0.0.exe` installer |
| macOS Apple Silicon | DMG in the `desktop-macos-arm64` lane |
| macOS Intel | DMG in the `desktop-macos-x64` lane |
| Linux x64 | DEB installer for Debian/Ubuntu |

The four desktop builds may run in parallel; Android has its own job. There is
no iOS lane in this entry point. Android builds the debug APK only, without the
unused unsigned release APK/AAB. Desktop packaging uses the same native builds,
embedded legal checks, fresh package manifest and exact-source provenance as the
verified path. Windows requests `packageExe`; legacy MSI identification is kept,
and a fresh MSI cannot satisfy an EXE request.

Each lane retains packages in an unpublished draft prerelease using a unique
run/platform tag. Open the workflow run summary, choose **Download this platform
build**, sign in as the repository owner and download the APK/EXE/DMG/DEB asset.
The manifest and support ZIP are verification material. A fresh runner downloads
and checks every expected lane's identity, size and hashes. Build steps have no
`GH_TOKEN`; only retention/readback receive it. Existing standard public runners
are used, without Actions artifact/cache storage or release publication.

GitHub requires a new manually dispatched workflow to be on the default branch
before it appears as a standalone manual entry. While PR27 is pending, the same
installer path can be started through the already registered workflow:

```sh
gh workflow run verify.yml --repo Apdelrahman1911/veyra \
  --ref t3/task-a-ui-recovery-integration \
  -f scope=installers -f package_desktop=true \
  -f retain_builds=true -f retention_canary=false
```

## Timing and verification

Successful Windows run `42677661597` at
`b658073eff08fc791371675ff03776d312bc1e83` passed 1,216 shared and 484 desktop
tests (one Linux-only skip), built `Veyra-1.0.0.msi` (137,762,179 bytes) and passed
independent retention readback. Its MSI SHA-256 is
`81c6e8ea474a756cab5e986a3d4aae12f513775dc674a1bd707f45f0d0d01ada`.
It spent about 15 minutes testing and 38 minutes packaging. The session helper
finished quickly; FFmpeg's source build and remaining packaging accounted for the
long quiet period. The native telemetry fix also passed short probe `42676263500`
at that same commit before the full run.

This trial path removes the repeated full tests and desktop queueing between
platforms, but does not remove cold dependency downloads, Kotlin compilation,
FFmpeg compilation or installer assembly. A cold native package can still take
tens of minutes. Existing timeouts, source hashes and process-drain checks remain.

Local changes were checked with 22 workflow tests, 20 package-manifest tests,
eight desktop legal tests, 18 build-retention tests and 17 tooling tests: **85
passing authored tests**. Both YAML files parse with duplicate-key rejection;
all 36 embedded shell scripts and changed shell entry points pass `bash -n`.
The actual offline Gradle `:composeApp:tasks --all` configuration passed in 29
seconds through the shared `vps-build` lock. The installed Compose 1.9.3 plugin
also exposes `TargetFormat.Exe`. Evidence is in
`.work/verification/trial-installers-20261109/`.

The new five-lane build is still the proof step for these trial outputs. These
are trial builds; installation, physical audio, signing/notarization, native/JDK
legal closure and overall Task A/Task B release acceptance remain separate.
