# Android/Linux/Windows combined-run corrections — 2026-11-09

Run `42605023132` completed at `29f6477a4ecaacf32d805fe0a8831ee4e3516e1d`.
Android passed; Linux and Windows failed. The independent production retention
readback passed. The PR's empty check rollup is separate from these manual jobs.

## Actual failures and corrections

- Android: 873 tests / 75 suites / zero failures, errors or skips; compile,
  lint, debug APK, unsigned release APK and AAB succeeded. The support archive,
  every indexed entry, exact tag target and XML counts were independently checked.
  Artifact hashes are in the retained manifest; the independent workflow readback
  verified the package assets. No device, installation or signing claim.
- Linux: Podman rejected the runroot above its 50-character limit, before GUI
  Gradle tests. The Actions API step conclusion reflected continue-on-error,
  so the earlier statement that Linux tests passed was incorrect. Shortened
  owned state to `.work/ns`, with an explicit path-length check. No uncontained
  fallback, policy changes or container/image operation was introduced.
- Linux: `packageDeb` created the installer, then legal verification failed
  because its persistent application image did not exist in a fresh checkout.
  `package.sh` now explicitly requests `createDistributable` in the same Gradle
  invocation as the installer task. Actual JAR byte verification remains required.
  The failed lane retained diagnostics only; no verified DEB was uploaded.
- Windows: two forced-process recovery tests again returned SQLite 1546 during
  validation. The exact bundled DLL identifies SQLite 3.50.1. SQLite's upstream
  October 5, 2026 report identifies a matching Windows WAL/-shm mapping regression
  from 3.50.0 and documents bounded reopen as a workaround. This runner did not
  retain the underlying Windows errno, so exact causal attribution remains open.
  Validation now retries only that exact extended error, only on Windows, only
  when WAL and SHM exist, for up to 30 waits / a three-second retry budget. Every
  failed connection is closed; the lease stays held; persistent errors, future
  schemas, unrelated I/O errors and cancellation still propagate. No file removal,
  transaction retry, database replacement or validation bypass was added.
- Windows native preparation again failed at configure; the bounded inner
  report contained no compiler lines and no config.log report. The source
  launched bare `bash`, which Windows CreateProcess can resolve from System32
  before PATH. FFmpeg commands now use the executable already resolved from the
  owned child PATH, including Git Bash, with no fallback when absent. Authored
  tests cover both exact argv preservation and failure before launch. Actual
  Windows execution remains a hosted gate; this is not an attested old root cause.

## Local validation

- `local-corrections-1`: 204 data/Room tests, 23 suites, zero failures/errors/skips;
  actual JVM production/test and Android shared compilation succeeded. New six
  retry-policy tests cover recovery, exhaustion, slow failure, unrelated/non-Windows
  errors, future-schema/cancellation and interruption. Input manifests match.
- Authored suites: FFmpeg 26, tooling 17, desktop legal 7, workflow 15,
  native bundle 33, package manifest 19, bootstrap 5: 122 passing tests.
  Final workflow YAML parses and shell syntax checks pass.
- `fresh-image-1`: source-pinned FFmpeg build, authored remux check, native staging,
  fresh Linux application image and exact generated/embedded legal comparison
  succeeded. Launcher passed prepare/play/pause/seekForward/rate/seekBack/stop,
  TLS verification and owned shutdown with silent null audio (mpv 0.41.0,
  FFmpeg 8.0.1). Both local gates exited zero with matching before/after inputs.
  The source-pinned native output was rebuilt because its recipe hash changed;
  no existing receipt was rewritten to bypass identity checking.

Evidence: `.work/verification/task-b-combined-42605023132/`.
The previous logs, manifests, support archives and red results remain unchanged.
Two selective cleanups removed 880 completed authored download-fixture directories
(about 1.38 GB) after zero process-reference checks, preserving the newest twenty.
The previous native output and app JAR were preserved; the generated application
image was removed after reference inspection to ensure fresh-image validation.

## Limits

Hosted Podman entry, Windows WAL recovery and Windows native build still need the
next actual run. Local fakeroot is absent, so current local verification covers
an application image rather than a DEB. Installer extraction, physical audio,
Android device/emulator, signing, publication and Task A/Task B release acceptance
remain separate gates. Existing notice/native/JDK/source closure issues remain.

Primary references:
- https://sqlite.org/forum/info/ba7afbd0e81501118563617430fe06d0aca052e55ece2b0d457f8e16fcf85bba
- https://sqlite.org/src/info/7c103a0b7d
- https://docs.python.org/3/library/subprocess.html
- https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw
