# Linux native test dependency correction — 2026-11-09

Run `42628939368` tests source `7b91522ec5a233caf11ff497f517f6dfc0c1d1cc`.
Android has completed successfully. Linux completed with 1,215 shared tests passing
and 484 desktop tests, nine failing. Its installer and inventory steps succeeded;
the DEB and support packet were retained in an unpublished draft release.
Windows and both macOS lanes were unfinished when this correction was prepared.

## Observed cause and correction

All nine Linux negatives are `DesktopAudioEngineTest` / `DesktopCrossfadeTest`
native loads reporting `libEGL.so.1: cannot open shared object file`.
The earlier PulseAudio extraction succeeded but checked only `libpulse.so.0`.
A VPS native pass therefore did not establish the hosted runner's graphics-library
closure. No application assertion is disabled or skipped by this correction.

The scoped helper now checks all 35 external library names identified by the exact
MPV bundle's resolved ELF dependency chain. If any is absent, it extracts the
complete corresponding set of 35 pinned Ubuntu noble amd64 packages (5,075,264
archive bytes) under `.work/linux-audio-tests`. All package bytes, sizes, control
identities and architectures are checked before use; a fresh child must load every
name before the helper returns a library path. Existing glibc/compiler runtimes
remain host prerequisites. Only Linux test subprocesses receive the path; this
does not add libraries to application installers or establish their host closure.
The committed metadata is from the official HTTPS Ubuntu noble main index, with
its exact index hash recorded; no independent index-signature check is claimed.

## Verification actually performed

- All 35 real official packages downloaded, hash/control-checked and extracted;
  the child library preflight passed. Only the initial host-library probe was
  forced to fail so the fallback would actually execute on this provisioned VPS.
- A separate child entered a private root containing only those packages, the
  existing pinned MPV bundle and four existing core ABI libraries plus the ELF
  loader. With the loader cache disabled, MPV resolved 76 dependency output lines.
  It had no access to the VPS's other installed graphics/audio libraries. Removing
  the private EGL soname reproduced the missing-library negative; it was restored.
- A real MPV load's `/proc/self/maps` confirms EGL, PulseAudio and Vulkan mapped
  from the extracted sysroot rather than the VPS system library directories.
- Through the existing project admission lock and bounded Gradle configuration,
  `DesktopAudioEngineTest` and `DesktopCrossfadeTest`: **10 tests, zero failures,
  errors or skips**, exit 0. This is silent native execution, not audible output.
- Authored dependency helper checks: **7/7**, including the regression where
  PulseAudio loads successfully but EGL is absent. Shell syntax and diff checks pass.
- The Linux support archive and all **205 indexed entries** were independently
  hash/size checked against its retained manifest. Its XML confirms the 1,215/484
  hosted counts above; the original nine negatives remain preserved.

Evidence is under `.work/verification/task-b-combined-42628939368/`, principally
`native-dependency-receipt.json`, `minimal-native-loader.log`,
`minimal-native-negative.log`, `local-audio.log`, `local-audio-test/`,
`dependency-tests.log` and `linux-readback-receipt.json`. The original hosted log
is `.work/verification/task-b-combined-42615340845/successor-linux.log`.
The initial local evidence harness stopped after successful extraction because
its sysroot path calculation was wrong; `local-dependency-verification.log`
preserves that harness negative. The corrected minimal-root check reused the
verified extraction and completed successfully without another download.

Local tests ran in the existing working tree with this helper correction applied;
this is not a clean committed-source run or a hosted successor pass. No Kotlin,
pin, schema, workflow, native bundle, Android or package-resource source changed.
Do not dispatch another production matrix until the ongoing Windows/macOS lanes
have supplied their results, so any additional concrete fixes can be batched.
Native audible output, installation, legal/native/JDK closure, signing and release
acceptance remain open.
