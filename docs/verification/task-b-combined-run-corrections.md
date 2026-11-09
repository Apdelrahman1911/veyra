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


## Combined run 42610626351 and the macOS-inclusive successor

At exact source `c552395a895a6875839ea0fe4a6a5050ebf3d34d`, Android passed
873 tests with zero failures/errors/skips and generated debug APK, unsigned
release APK and AAB. Linux and Windows failed. All three draft lane tags,
support archive hashes and 169 total indexed entries were independently read
back by the lead; Android XML accounts for the 873 tests. The separate hosted
production readback also succeeded.

Linux's shortened Podman path passed its former failure point. The next
prerequisite failed: `Existing xdpyinfo required`. No Kotlin test XML was produced.
Independent packaging and inventory succeeded and retained the 113,069,614-byte
DEB, SHA-256 `262278365fe870d4505ec24dce57697bd14a45dd3d37cb9cd090dc01e0231f2c`.
The lead downloaded, rehashed and reopened that installer without installing it:
the contained app JAR matches `packages.json`, including all 35 legal resources,
161 components and index `a797feb7d45d6989e922dc3fb198e1acb54f959c674ba4fb75174cc083771d89`.
Two legal coverage issues remain. This proves installer byte embedding, not
installation, execution of this installer or redistribution readiness.

The display wrapper now calls the installed X11 library through a ten-second
awaited Python child instead of requiring the separate `xdpyinfo` executable.
XOpenDisplay authentication, XSync and XCloseDisplay preserve the actual
protocol/authentication check. A real local private read-only mount guard and
owned Xvfb passed correct-cookie access, empty-cookie refusal and unavailable
display refusal. No system package was installed; no cookie was logged.
Actual hosted entry and Kotlin tests remain the successor's proof step.

Windows's source-only owner-failure regression attempted to resolve its fictional
`authored-tool` after the new Windows executable guard. The test now explicitly
selects its synthetic Linux path; separate Windows path-resolution tests remain.
This stopped the targeted Room tests before Gradle, so this run offers no new
Windows WAL recovery result. Independent packaging reached FFmpeg configure and
hit its exact 600-second owned timeout, with cleanup confirmed. That is the
observed failure; the earlier command-failed hypothesis is superseded for this
attempt. Its filtered config report selected function/help definitions from
FFmpeg's initial shell `set` dump, so it did not identify the last real probe.
The reporter now excludes that dump, emits only bounded actual probe kinds and
the final three probes' credential-filtered diagnostics, and preserves the
original log. Windows configure receives a bounded 1200-second budget; other
platform budgets and the outer deadline are unchanged. This may accommodate a
slow configure, but does not diagnose or prove resolution of a stalled compiler.

Local checks on the successor diff: native bundle 35/35, FFmpeg recipe 26/26,
workflow 16/16 and tooling 17/17 (94 authored checks); Python parsing, shell syntax,
YAML parsing and diff whitespace pass. The three real display checks pass.
No Kotlin/application behavior changed in this correction, and no broad Gradle
or native rebuild was repeated for these tooling-only changes.

The owner's requested `android-desktop` scope plans exactly five production lanes:
Android, Linux x64, Windows x64, macOS arm64 and macOS x64. The desktop matrix has
`fail-fast: false`; independent package, inventory, retention and fresh readback
steps remain, including diagnostic retention on failure. iOS keeps its separate
scope. Both macOS architectures run full desktop tests; Windows in this new scope
also uses full desktop tests rather than the earlier targeted recovery subset.
No unchanged workflow retry was dispatched.

Evidence: `.work/verification/task-b-combined-42610626351/`, including original
job logs, three packets/manifests, `readback.json`, `deb-readback.json`,
`display-live.json` and the four focused test logs. These are separate from the
previous attempt's sealed evidence. No signing, publication, installation,
physical audio, native/JDK/source legal closure or final acceptance claim.
