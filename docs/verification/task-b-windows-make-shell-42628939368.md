# Windows FFmpeg dependency parsing correction — 2026-11-09

The completed Windows lane of run `42628939368` at `7b91522` passed all 1,215
shared tests and discovered 484 desktop tests with zero failures/errors and one
platform skip. The skip is the Linux-only real D-Bus/MPRIS test. FFmpeg configure
completed; the subsequent Make invocation failed at
`ffbuild/common.mak:81: libavdevice/alldevices.o` with an AWK syntax error.
No Windows installer was produced. The original console and structured native
negative are retained; no build success is inferred from the successful tests.

macOS arm64 also completed successfully: 1,215 shared tests, 484 desktop tests
with the same single Linux-only skip, and a DMG built and uploaded to its
unpublished draft release. Android's successful lane remains unchanged. Linux's
nine EGL load failures are addressed by `08bc006` with local proof documented in
`task-b-linux-native-closure-42628939368.md`. macOS x64 remained in progress when
this correction was prepared.

## Source finding and change

The recipe resolved an existing Bash for configure but left Make's recipe shell
implicit. GNU Make's manual documents different Windows shell selection and
requires an exact shell path when supplied through the command line. The pinned
FFmpeg MSVC dependency command uses single-quoted AWK with backslash substitution
and Make dollar escaping. The retained diagnostic included the syntax-error caret
but omitted the actual AWK program, so the precise earlier parser input and shell
selection are **not proven**. Shell selection is the correction being tested,
not an established retrospective attribution to cmd.exe.

- Windows Make now receives `SHELL=<exact scoped Bash>` and `.SHELLFLAGS=-c`.
  The Bash must exist and match PATH resolution of the already trusted scoped
  executable. No tool installation, global environment or PATH change occurs.
- Inherited `SHELL`/`MAKESHELL` are removed from the native build child environment.
  Linux/macOS Make arguments otherwise retain their existing behavior.
- Before the lengthy Windows configure, a 30-second Make/Bash/AWK preflight runs
  the exact pinned upstream dependency filter on authored MSVC-style include
  lines. It must normalize backslashes and exclude paths containing spaces,
  producing exactly the expected dependency line. Failure stops the build early.
- Bounded native diagnostics now retain `awk: cmd. line:<number>:` program lines,
  preserving the existing credential filtering, size limits, logs and failure.

## Validation and evidence

- `native/ffmpeg/test_build.py`: **30 tests, zero failures/errors/skips**, including
  actual local Make + Bash + AWK execution of the new probe, exact scoped-shell
  refusal and incorrect-output refusal.
- `native/test_bundle.py`: **36 tests, zero failures/errors/skips**, including AWK
  program retention and sensitive-context suppression.
- The probe's AWK fragment was compared literally with `configure` in the retained
  hash-pinned official `FFmpeg-n8.0.1.tar.gz`; it matches.
- Linux, Windows and macOS arm64 support ZIPs, all **615 indexed entries**, and
  exact draft tag targets independently verify. Sanitized XML confirms the
  counts above. This verifies support packets; it is not installer execution or
  a claim of independently reopening the macOS DMG.

Evidence: `.work/verification/task-b-combined-42628939368/`, including
`windows.log`, `macos-arm64.log`, `windows-build-diagnostic.json`,
`desktop-readback-receipt.json`, `windows-make-tests.log`,
`windows-diagnostic-tests.log` and `windows-make-receipt.json`. The official Make
manual and 4.4.1 shell-selection source are retained as
`make-choosing-shell.html` / `make-main.c`; the hosted Make version was not captured.

The tests ran on Linux against the applied working-tree changes, not a Windows
host or a clean committed candidate. Actual Windows shell/compilation/package
success still requires the next coordinated hosted run. No Kotlin, pin, schema,
workflow, native media source or Android behavior was changed. Keep the existing
macOS x64 run so any observed failure can join the same combined retry.
